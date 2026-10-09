#include "film.h"
#include "grain.h"
#include <math.h>
#include <stdlib.h>
#include <string.h>

typedef struct { float x, y, radius; int active; } DustSpot;
static float linear_table[256];
static unsigned char encoded_table[65536];
static int tables_ready; /* The application serializes native jobs on one worker. */
struct FilmContext {
    FilmSettings s;
    int width, height, mw, mh;
    uint16_t *base, *soft, *glow, *halo;
    const float *linear;
    const unsigned char *encoded;
    float *nx, *mapx, *leakx, *leaky, *scratchline;
    int *dustcell, *scratchcell, *grain_top, *grain_bottom;
    DustSpot dust[33 * 25];
    float scratch_breaks[32][32];
    int needs_linear, grain_size, grain_width, grain_y, row_y, dust_y, scratch_y;
    float ny, map_y, diffusion, edge_softness, vignette, halation, bloom;
};
static float bound(float x, float lo, float hi) { return x < lo ? lo : x > hi ? hi : x; }
static uint32_t hash(uint32_t n) { n ^= n >> 16; n *= 0x7feb352dU; n ^= n >> 15; n *= 0x846ca68bU; return n ^ (n >> 16); }
static float random01(uint32_t n) { return (hash(n) & 65535) / 65535.f; }
int film_valid(const FilmSettings *s) {
    if (!s) return 0;
    for (int i = 0; i < F_COUNT; i++) {
        if (i == F_SEED) continue;
        int lo = i == F_SIZE || i == F_HAL_RADIUS || i == F_BLOOM_RADIUS ? 1 : i == F_THRESHOLD ? 40 : i == F_DISTORTION ? -100 : 0;
        int hi = i == F_SIZE ? 8 : i == F_THRESHOLD ? 95 : i == F_OUTPUT ? 1 : 100;
        if (s->v[i] < lo || s->v[i] > hi) return 0;
    }
    return 1;
}
int film_no_effects(const FilmSettings *s) {
    static const int amounts[] = {F_GRAIN,F_HALATION,F_BLOOM,F_DIFFUSION,F_VIGNETTE,F_LEAK,F_DUST,F_SCRATCH,F_ABERRATION,F_DISTORTION,F_EDGE_SOFTNESS};
    for (unsigned int i=0;i<sizeof(amounts)/sizeof(amounts[0]);i++) if(s->v[amounts[i]])return 0;
    return 1;
}
static int texture_noise(int x, int y, uint32_t seed) {
    uint32_t h = hash((uint32_t)x * 0x9e3779b9U ^ (uint32_t)y * 0x85ebca6bU ^ seed);
    return (int)(h & 255) + (int)((h >> 8) & 255) + (int)((h >> 16) & 255) - 382;
}
int film_needs_map(const FilmSettings *s) {
    return s->v[F_HALATION] || s->v[F_BLOOM] || s->v[F_DIFFUSION] || s->v[F_EDGE_SOFTNESS];
}
FilmContext *film_create(const FilmSettings *s, int width, int height, int mw, int mh) {
    if (!film_valid(s) || width < 1 || height < 1 || mw < 0 || mh < 0 || mw > 640 || mh > 640) return NULL;
    FilmContext *f = (FilmContext *)calloc(1, sizeof(*f)); if (!f) return NULL;
    f->s = *s; f->width = width; f->height = height; f->mw = mw; f->mh = mh;
    const int *v = s->v;
    f->needs_linear = film_needs_map(s) || v[F_VIGNETTE] || v[F_LEAK] || v[F_DUST] || v[F_SCRATCH];
    f->row_y = -1; f->grain_y = -1;
    int long_edge = width > height ? width : height;
    f->grain_size = (v[F_SIZE] * long_edge + 3000) / 6000;
    if (f->grain_size < 1) f->grain_size = 1;
    f->diffusion = v[F_DIFFUSION] * .0035f; f->edge_softness = v[F_EDGE_SOFTNESS] * .006f;
    f->vignette = v[F_VIGNETTE] / 100.f * .85f;
    f->halation = v[F_HALATION] / 100.f; f->bloom = v[F_BLOOM] * .005f;
    if (f->needs_linear && !tables_ready) {
    for (int n = 0; n < 256; n++) {
        float x = n / 255.f;
        linear_table[n] = x <= .04045f ? x / 12.92f : powf((x + .055f) / 1.055f, 2.4f);
    }
    for (int n = 0; n < 65536; n++) {
        float x = n / 65535.f;
        float encoded = x <= .0031308f ? 12.92f * x : 1.055f * powf(x, 1.f / 2.4f) - .055f;
        encoded_table[n] = (unsigned char)(bound(encoded, 0, 1) * 255 + .5f);
    }
    tables_ready = 1;
    }
    f->linear = linear_table; f->encoded = encoded_table;
    if (v[F_GRAIN]) {
        f->grain_width = (width - 1) / f->grain_size + 2;
        f->grain_top = (int *)malloc(f->grain_width * sizeof(int));
        f->grain_bottom = (int *)malloc(f->grain_width * sizeof(int));
        if (!f->grain_top || !f->grain_bottom) { film_free(f); return NULL; }
    }
    if (f->needs_linear) {
        f->nx = (float *)malloc(width * sizeof(float));
        if (!f->nx) { film_free(f); return NULL; }
        for (int x=0;x<width;x++) f->nx[x] = width > 1 ? 2.f * x / (width - 1) - 1 : 0;
    }
    if (mw && mh && !v[F_DISTORTION]) {
        f->mapx = (float *)malloc(width * sizeof(float));
        if (!f->mapx) { film_free(f); return NULL; }
        for (int x=0;x<width;x++) f->mapx[x] = (f->nx[x] + 1) * (width - 1) / 2 * (mw - 1) / (width > 1 ? width - 1 : 1);
    }
    if (v[F_LEAK]) {
        f->leakx=(float *)malloc(width*sizeof(float)); f->leaky=(float *)malloc(height*sizeof(float));
        if (!f->leakx || !f->leaky) { film_free(f); return NULL; }
        int side=hash((uint32_t)v[F_SEED]^0x51edU)&1;
        float centre=random01((uint32_t)v[F_SEED]^0x9271U)*1.5f-.75f;
        float amount=v[F_LEAK]/100.f*.8f;
        for(int x=0;x<width;x++){float edge=side?(1-f->nx[x])/2:(1+f->nx[x])/2;f->leakx[x]=expf(-edge*edge*25.f);}
        for(int y=0;y<height;y++){float ny=height>1?2.f*y/(height-1)-1:0;f->leaky[y]=amount*expf(-(ny-centre)*(ny-centre)*1.8f);}
    }
    if (v[F_DUST]) {
        f->dustcell=(int *)malloc(width*sizeof(int)); if(!f->dustcell){film_free(f);return NULL;}
        for(int x=0;x<width;x++)f->dustcell[x]=(int)((f->nx[x]+1)*16);
        for(int cy=0;cy<25;cy++)for(int cx=0;cx<33;cx++){
            DustSpot *d=&f->dust[cy*33+cx];uint32_t key=hash((uint32_t)v[F_SEED]^(uint32_t)cx*0x9e3779b9U^(uint32_t)cy*0x85ebca6bU);
            d->active=(int)(key&255)<v[F_DUST]*2;
            d->x=(cx+.12f+.76f*random01(key^17))/32*(width-1);d->y=(cy+.12f+.76f*random01(key^23))/24*(height-1);
            d->radius=(1+2*random01(key^31))*long_edge/6000.f;if(d->radius<1)d->radius=1;
        }
    }
    if(v[F_SCRATCH]){
        f->scratchline=(float *)calloc(width,sizeof(float)); f->scratchcell=(int *)malloc(width*sizeof(int));
        if(!f->scratchline||!f->scratchcell){film_free(f);return NULL;}
        float radius=1+width/6000.f;
        for(int cell=0;cell<32;cell++){
            uint32_t key=hash((uint32_t)v[F_SEED]^(uint32_t)cell*0xa24baed5U);
            for(int y=0;y<32;y++)f->scratch_breaks[cell][y]=.3f+.7f*random01(key^(uint32_t)y);
            float px=(cell+random01(key^17))/32*width;
            for(int x=cell*width/32;x<width;x++){
                int actual=x*32/width;if(actual<cell)continue;if(actual>cell)break;
                f->scratchcell[x]=cell;
                if((int)(key&255)<v[F_SCRATCH])f->scratchline[x]=bound(1-fabsf(x-px)/radius,0,1)*.55f;
            }
        }
    }
    if (mw && mh) {
        size_t count = (size_t)mw * mh;
        f->base = (uint16_t *)calloc(count * 3, sizeof(uint16_t));
        f->soft = (uint16_t *)calloc(count * 3, sizeof(uint16_t));
        f->glow = (uint16_t *)calloc(count * 3, sizeof(uint16_t));
        f->halo = (uint16_t *)calloc(count, sizeof(uint16_t));
        if (!f->base || !f->soft || !f->glow || !f->halo) { film_free(f); return NULL; }
    }
    return f;
}
/* Feed nearest map rows/columns from an IDCT-downsampled JPEG; at most 640x640 RGB. */
void film_feed(FilmContext *f, const unsigned char *row, int y, int width, int height) {
    if (!f->base) return;
    for (int my = 0; my < f->mh; my++) {
        if (my * height / f->mh != y) continue;
        for (int mx = 0; mx < f->mw; mx++) {
            int x = mx * width / f->mw;
            for (int c = 0; c < 3; c++) f->base[(my * f->mw + mx) * 3 + c] = (uint16_t)(f->linear[row[x * 3 + c]] * 65535.f + .5f);
        }
    }
}
/* Sliding box sums: fixed work per pixel regardless of glow radius, reflected by clamped borders. */
static int blur(uint16_t *data, int w, int h, int channels, float radius) {
    size_t count = (size_t)w * h * channels;
    uint16_t *temporary = (uint16_t *)malloc(count * sizeof(uint16_t));
    if (!temporary) return -1;
    int r = (int)ceilf(radius / 1.5f); if (r < 1) r = 1;
    for (int pass = 0; pass < 3; pass++) {
        for (int y = 0; y < h; y++) for (int c = 0; c < channels; c++) {
            uint64_t sum = 0;
            for (int k = -r; k <= r; k++) sum += data[(y * w + (k < 0 ? 0 : k >= w ? w - 1 : k)) * channels + c];
            for (int x = 0; x < w; x++) {
                temporary[(y * w + x) * channels + c] = (uint16_t)(sum / (2 * r + 1));
                int before = x - r, after = x + r + 1;
                if (before < 0) before = 0;
                if (after >= w) after = w - 1;
                sum += data[(y * w + after) * channels + c]; sum -= data[(y * w + before) * channels + c];
            }
        }
        for (int x = 0; x < w; x++) for (int c = 0; c < channels; c++) {
            uint64_t sum = 0;
            for (int k = -r; k <= r; k++) sum += temporary[((k < 0 ? 0 : k >= h ? h - 1 : k) * w + x) * channels + c];
            for (int y = 0; y < h; y++) {
                data[(y * w + x) * channels + c] = (uint16_t)(sum / (2 * r + 1));
                int before = y - r, after = y + r + 1;
                if (before < 0) before = 0;
                if (after >= h) after = h - 1;
                sum += temporary[(after * w + x) * channels + c]; sum -= temporary[(before * w + x) * channels + c];
            }
        }
    }
    free(temporary); return 0;
}
int film_finish_map(FilmContext *f) {
    if (!f->base) return 0;
    int count = f->mw * f->mh;
    memcpy(f->soft, f->base, count * 3 * sizeof(uint16_t));
    float threshold = f->linear[f->s.v[F_THRESHOLD] * 255 / 100];
    for (int n = 0; n < count; n++) {
        float l = (.2126f * f->base[n * 3] + .7152f * f->base[n * 3 + 1] + .0722f * f->base[n * 3 + 2]) / 65535.f;
        float mask = bound((l - threshold) / (1 - threshold), 0, 1);
        mask *= mask;
        f->halo[n] = (uint16_t)(mask * 65535.f);
        for (int c = 0; c < 3; c++) f->glow[n * 3 + c] = (uint16_t)(f->base[n * 3 + c] * mask);
    }
    float edge = f->mw > f->mh ? f->mw : f->mh;
    if ((f->s.v[F_DIFFUSION] || f->s.v[F_EDGE_SOFTNESS]) && blur(f->soft, f->mw, f->mh, 3, edge * .003f)) return -1;
    if (f->s.v[F_BLOOM] && blur(f->glow, f->mw, f->mh, 3, edge * f->s.v[F_BLOOM_RADIUS] / 5000.f)) return -1;
    if (f->s.v[F_HALATION] && blur(f->halo, f->mw, f->mh, 1, edge * f->s.v[F_HAL_RADIUS] / 10000.f)) return -1;
    return 0;
}
typedef struct { int a,b,c,d; float fx,fy; } MapPoint;
static MapPoint map_point(int w, int h, float x, float y) {
    x = bound(x, 0, w - 1); y = bound(y, 0, h - 1);
    int x0 = (int)x, y0 = (int)y, x1 = x0 + 1 < w ? x0 + 1 : x0, y1 = y0 + 1 < h ? y0 + 1 : y0;
    float fx = x - x0, fy = y - y0;
    MapPoint p={y0*w+x0,y0*w+x1,y1*w+x0,y1*w+x1,fx,fy};return p;
}
static float sample(const uint16_t *data, MapPoint p, int channels, int channel) {
    float a=data[p.a*channels+channel]*(1-p.fx)+data[p.b*channels+channel]*p.fx;
    float b=data[p.c*channels+channel]*(1-p.fx)+data[p.d*channels+channel]*p.fx;
    return (a*(1-p.fy)+b*p.fy)*(1.f/65535.f);
}
void film_coordinates(const FilmSettings *s, float x, float y, int w, int h,
                      float *sx, float *sy, float *rx, float *ry, float *bx, float *by) {
    float nx = w > 1 ? 2 * x / (w - 1) - 1 : 0, ny = h > 1 ? 2 * y / (h - 1) - 1 : 0;
    float radial = nx * nx + ny * ny;
    float warp = 1 + s->v[F_DISTORTION] * .0004f * radial;
    float ca = s->v[F_ABERRATION] * .00002f * radial;
    *sx = bound((nx * warp + 1) * (w - 1) / 2, 0, w - 1);
    *sy = bound((ny * warp + 1) * (h - 1) / 2, 0, h - 1);
    *rx = bound((nx * (warp + ca) + 1) * (w - 1) / 2, 0, w - 1);
    *ry = bound((ny * (warp + ca) + 1) * (h - 1) / 2, 0, h - 1);
    *bx = bound((nx * (warp - ca) + 1) * (w - 1) / 2, 0, w - 1);
    *by = bound((ny * (warp - ca) + 1) * (h - 1) / 2, 0, h - 1);
}
void film_begin_row(FilmContext *f, int y) {
    if(f->row_y==y)return;
    f->row_y=y;
    f->ny=f->height>1?2.f*y/(f->height-1)-1:0;
    f->map_y=(f->ny+1)*(f->height-1)/2*(f->mh-1)/(f->height>1?f->height-1:1);
    f->dust_y=(int)((f->ny+1)*12); f->scratch_y=y*32/f->height;
    if(f->s.v[F_GRAIN]){
        int gy=y/f->grain_size;
        if(gy!=f->grain_y){
            int adjacent=gy==f->grain_y+1&&f->grain_y>=0;
            if(adjacent){int *p=f->grain_top;f->grain_top=f->grain_bottom;f->grain_bottom=p;}
            for(int gx=0;gx<f->grain_width;gx++){
                if(!adjacent)f->grain_top[gx]=texture_noise(gx,gy,(uint32_t)f->s.v[F_SEED]);
                f->grain_bottom[gx]=texture_noise(gx,gy+1,(uint32_t)f->s.v[F_SEED]);
            }f->grain_y=gy;
        }
    }
}
static void add_grain(FilmContext *f,int x,int y,unsigned char rgb[3]){
    if(!f->s.v[F_GRAIN])return;
    int size=f->grain_size,gx=x/size,fx=x%size,fy=y%size;
    int top=f->grain_top[gx]*(size-fx)+f->grain_top[gx+1]*fx;
    int bottom=f->grain_bottom[gx]*(size-fx)+f->grain_bottom[gx+1]*fx;
    int correlated=(top*(size-fy)+bottom*fy)/(size*size);
    int texture=(3*correlated+texture_noise(x,y,(uint32_t)f->s.v[F_SEED]^0xd1b54a35U))/4;
    int luma=(77*rgb[0]+150*rgb[1]+29*rgb[2])>>8;
    int weight=64+191*(255-abs(2*luma-255))/255;
    int delta=texture*f->s.v[F_GRAIN]*weight/(100*255*5);
    for(int c=0;c<3;c++)rgb[c]=(unsigned char)bound(rgb[c]+delta,0,255);
}
void film_pixel_at(FilmContext *f, int x, int y, float source_x,float source_y,unsigned char rgb[3]) {
    const int *s = f->s.v;
    film_begin_row(f,y);
    if(!f->needs_linear){add_grain(f,x,y,rgb);return;}
    float p[3] = {f->linear[rgb[0]], f->linear[rgb[1]], f->linear[rgb[2]]};
    float nx=f->nx[x],ny=f->ny;
    float radial = (nx * nx + ny * ny) / 2;
    if (f->base) {
        float mx=f->mapx?f->mapx[x]:source_x*(f->mw-1)/(f->width>1?f->width-1:1);
        float my=f->mapx?f->map_y:source_y*(f->mh-1)/(f->height>1?f->height-1:1);
        MapPoint point=map_point(f->mw,f->mh,mx,my);
        float softness=bound(f->diffusion+f->edge_softness*radial,0,.9f);
        float halo=s[F_HALATION]?sample(f->halo,point,1,0)*f->halation:0;
        float source_luma = .2126f * p[0] + .7152f * p[1] + .0722f * p[2];
        /* Suppress tint inside bright sources; the warm fringe lives outside the source. */
        halo *= (1 - bound(source_luma, 0, 1)) * .6f;
        for (int c = 0; c < 3; c++) {
            if (softness) p[c] += softness * (sample(f->soft,point,3,c)-p[c]);
            if (s[F_BLOOM]) p[c] += sample(f->glow,point,3,c)*f->bloom;
            p[c] += halo * (c == 0 ? 1 : c == 1 ? .22f : .035f);
        }
    }
    float vignette=1-f->vignette*radial*radial;
    float leak=f->leakx?f->leakx[x]*f->leaky[y]:0;
    float defect = 0;
    if (s[F_DUST]) {
        DustSpot *d=&f->dust[f->dust_y*33+f->dustcell[x]];
        if(d->active&&fabsf(x-d->x)<d->radius&&fabsf(y-d->y)<d->radius*1.6f){
            float dx=(x-d->x)/d->radius,dy=(y-d->y)/(d->radius*1.6f);
            defect = bound(1 - dx * dx - dy * dy, 0, 1) * .8f;
        }
    }
    if (s[F_SCRATCH]) {
        defect=bound(defect+f->scratchline[x]*f->scratch_breaks[f->scratchcell[x]][f->scratch_y],0,1);
    }
    for (int c = 0; c < 3; c++) {
        p[c] = p[c] * vignette + leak * (c == 0 ? 1 : c == 1 ? .25f : .04f);
        p[c] *= 1 - defect;
        rgb[c] = f->encoded[(int)(bound(p[c], 0, 1) * 65535.f + .5f)];
    }
    add_grain(f,x,y,rgb);
}
void film_pixel(FilmContext *f,int x,int y,unsigned char rgb[3]){
    float sx=x,sy=y,rx,ry,bx,by;
    if(f->s.v[F_DISTORTION])film_coordinates(&f->s,x,y,f->width,f->height,&sx,&sy,&rx,&ry,&bx,&by);
    film_pixel_at(f,x,y,sx,sy,rgb);
}
void film_free(FilmContext *f) {
    if (!f) return;
    free(f->base); free(f->soft); free(f->glow); free(f->halo);
    free(f->nx); free(f->mapx); free(f->leakx); free(f->leaky); free(f->scratchline);
    free(f->dustcell); free(f->scratchcell); free(f->grain_top); free(f->grain_bottom); free(f);
}
