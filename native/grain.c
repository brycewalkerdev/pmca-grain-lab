#include "grain.h"
#include "film.h"
#include <math.h>
#include <time.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <setjmp.h>
#ifdef __ANDROID__
#include <unistd.h>
#endif
#include "jpeglib.h"

static volatile int cancelled, progress, stage;
static double timings[6];
static double now_ms(void) {
#ifdef __ANDROID__
    struct timespec t; clock_gettime(CLOCK_MONOTONIC,&t); return t.tv_sec*1000.0+t.tv_nsec/1000000.0;
#else
    return (double)clock()*1000.0/CLOCKS_PER_SEC;
#endif
}
void film_timings(double values[6]) { memcpy(values,timings,sizeof(timings)); }
int film_stage(void) { return __sync_fetch_and_add(&stage,0); }
const char *film_backend(void) {
#ifdef LIBJPEG_TURBO_VERSION
#if defined(__ARM_ARCH) && __ARM_ARCH >= 7
#define PROCESSOR_DESCRIPTION "ARMv7 hardware FP"
#elif defined(__arm__)
#define PROCESSOR_DESCRIPTION "generic ARM"
#else
#define PROCESSOR_DESCRIPTION "host"
#endif
#ifdef WITH_SIMD
    extern int jsimd_can_rgb_ycc(void);
    return jsimd_can_rgb_ycc()?"libjpeg-turbo / NEON / " PROCESSOR_DESCRIPTION:"libjpeg-turbo / scalar / " PROCESSOR_DESCRIPTION;
#else
    return "libjpeg-turbo / scalar / " PROCESSOR_DESCRIPTION;
#endif
#else
    return "IJG JPEG 9f";
#endif
}
void grain_reset(void) { __sync_lock_test_and_set(&cancelled, 0); __sync_lock_test_and_set(&progress, 0); __sync_lock_test_and_set(&stage,1); memset(timings,0,sizeof(timings)); }
void grain_cancel(void) { __sync_lock_test_and_set(&cancelled, 1); }
int grain_progress(void) { return __sync_fetch_and_add(&progress, 0); }
static int is_cancelled(void) { return __sync_fetch_and_add(&cancelled, 0); }

static uint32_t mix(uint32_t n) {
    n ^= n >> 16; n *= 0x7feb352dU; n ^= n >> 15; n *= 0x846ca68bU; return n ^ (n >> 16);
}
static int noise(int x, int y, uint32_t seed) {
    uint32_t h = mix((uint32_t)x * 0x9e3779b9U ^ (uint32_t)y * 0x85ebca6bU ^ seed);
    return (int)(h & 255) + (int)((h >> 8) & 255) + (int)((h >> 16) & 255) - 382;
}
int grain_delta(int x, int y, int luminance, int strength, int size, uint32_t seed) {
    int gx = x / size, gy = y / size, fx = x % size, fy = y % size;
    int top = noise(gx, gy, seed) * (size - fx) + noise(gx + 1, gy, seed) * fx;
    int bottom = noise(gx, gy + 1, seed) * (size - fx) + noise(gx + 1, gy + 1, seed) * fx;
    int correlated = (top * (size - fy) + bottom * fy) / (size * size);
    int texture = (3 * correlated + noise(x, y, seed ^ 0xd1b54a35U)) / 4;
    /* Strongest in midtones; gently suppress noise in deep blacks and highlights. */
    int weight = 64 + 191 * (255 - abs(2 * luminance - 255)) / 255;
    return texture * strength * weight / (100 * 255 * 5);
}

typedef struct {
    struct jpeg_error_mgr pub;
    jmp_buf jump;
    char message[JMSG_LENGTH_MAX];
    int warning;
} Error;
static void fail(j_common_ptr c) {
    Error *e = (Error *)c->err;
    (*c->err->format_message)(c, e->message);
    longjmp(e->jump, 1);
}
static void warning(j_common_ptr c, int level) {
    Error *e = (Error *)c->err;
    if (level < 0) { e->warning = 1; (*c->err->format_message)(c, e->message); }
}
typedef struct {
    struct jpeg_decompress_struct read;
    struct jpeg_decompress_struct map;
    struct jpeg_compress_struct write;
    Error error;
    FILE *input, *output, *map_input;
    unsigned char *row, *ring;
    FilmContext *film;
    uint32_t *pixels;
    int *global_x;
    int read_created, write_created, map_created;
} Job;

/* Keep the original EXIF/ICC/XMP. Remove the IFD1 pointer to the stale JPEG thumbnail.
 * Image dimensions/orientation and all IFD0/Exif/MakerNote offsets remain unchanged. */
static uint32_t u32(const unsigned char *p, int le) {
    return le ? (uint32_t)p[0] | (uint32_t)p[1]<<8 | (uint32_t)p[2]<<16 | (uint32_t)p[3]<<24
              : (uint32_t)p[3] | (uint32_t)p[2]<<8 | (uint32_t)p[1]<<16 | (uint32_t)p[0]<<24;
}
static void detach_thumbnail(unsigned char *p, unsigned int length) {
    if (length < 20 || memcmp(p, "Exif\0\0", 6)) return;
    unsigned char *tiff = p + 6;
    int le = tiff[0] == 'I' && tiff[1] == 'I';
    if (!le && !(tiff[0] == 'M' && tiff[1] == 'M')) return;
    uint32_t offset = u32(tiff + 4, le);
    if (offset > length - 8) return;
    unsigned char *ifd = tiff + offset;
    unsigned int count = le ? ifd[0] | ifd[1]<<8 : ifd[1] | ifd[0]<<8;
    uint32_t next = 6 + offset + 2 + count * 12U;
    if (next <= length - 4) memset(p + next, 0, 4);
}
static void set32(unsigned char *p,uint32_t v,int le){for(int n=0;n<4;n++)p[le?n:3-n]=(unsigned char)(v>>(n*8));}
static uint32_t resize_ifd(unsigned char *p,unsigned int length,uint32_t offset,int le,int width,int height){
    if(length<8||offset>length-8)return 0;
    unsigned char *ifd=p+6+offset;unsigned int count=le?ifd[0]|ifd[1]<<8:ifd[1]|ifd[0]<<8;
    if(count>(length-6-offset-2)/12)return 0;
    uint32_t child=0;
    for(unsigned int i=0;i<count;i++){
        unsigned char *entry=ifd+2+i*12;
        unsigned int tag=le?entry[0]|entry[1]<<8:entry[1]|entry[0]<<8;
        unsigned int type=le?entry[2]|entry[3]<<8:entry[3]|entry[2]<<8;
        if(u32(entry+4,le)!=1)continue;
        if(tag==0x8769&&type==4)child=u32(entry+8,le);
        int value=tag==0x100||tag==0xa002?width:tag==0x101||tag==0xa003?height:0;
        if(value&&type==4)set32(entry+8,(uint32_t)value,le);
        else if(value&&type==3){entry[le?8:9]=(unsigned char)value;entry[le?9:8]=(unsigned char)(value>>8);}
    }return child;
}
static void resize_exif(unsigned char *p,unsigned int length,int width,int height){
    if(length<20||memcmp(p,"Exif\0\0",6))return;
    int le=p[6]=='I'&&p[7]=='I';if(!le&&!(p[6]=='M'&&p[7]=='M'))return;
    uint32_t child=resize_ifd(p,length,u32(p+10,le),le,width,height);
    if(child)resize_ifd(p,length,child,le,width,height);
}

static unsigned char row_sample(Job *j, int rows, int width, int height, float x, float y, int channel) {
    if (x < 0) x = 0;
    if (x > width - 1) x = width - 1;
    if (y < 0) y = 0;
    if (y > height - 1) y = height - 1;
    int x0 = x, y0 = y, x1 = x0 + 1 < width ? x0 + 1 : x0, y1 = y0 + 1 < height ? y0 + 1 : y0;
    float fx = x - x0, fy = y - y0;
    unsigned char *a = j->ring + (y0 % rows) * width * 3;
    unsigned char *b = j->ring + (y1 % rows) * width * 3;
    return (unsigned char)((1 - fy) * ((1 - fx) * a[x0 * 3 + channel] + fx * a[x1 * 3 + channel]) +
        fy * ((1 - fx) * b[x0 * 3 + channel] + fx * b[x1 * 3 + channel]) + .5f);
}
static int render(const char *source, const char *destination, const FilmSettings *settings, int preview, int detail,
                  uint32_t **pixels, int *pw, int *ph, char *message, size_t capacity) {
    Job *j = (Job *)calloc(1, sizeof(Job));
    double total_start=now_ms(), prepare_start=total_start;
    volatile int result = -1;
    if (!j) { snprintf(message, capacity, "Out of memory"); return -1; }
    if (!film_valid(settings) || (!preview && !strcmp(source, destination))) {
        snprintf(message, capacity, "Invalid processing request"); goto cleanup;
    }
    j->read.err = j->write.err = jpeg_std_error(&j->error.pub);
    j->map.err = &j->error.pub;
    j->error.pub.error_exit = fail; j->error.pub.emit_message = warning;
    if (setjmp(j->error.jump)) { snprintf(message, capacity, "%s", j->error.message); goto cleanup; }
    j->input = fopen(source, "rb");
    if (!j->input) { snprintf(message, capacity, "Cannot read source JPEG"); goto cleanup; }
    jpeg_create_decompress(&j->read); j->read_created = 1;
    jpeg_stdio_src(&j->read, j->input);
    for (int i = 0; i < 16; i++) jpeg_save_markers(&j->read, JPEG_APP0 + i, 65535);
    jpeg_save_markers(&j->read, JPEG_COM, 65535);
    jpeg_read_header(&j->read, TRUE);
    if (j->read.progressive_mode || j->read.comps_in_scan != j->read.num_components ||
        j->read.arith_code || j->read.data_precision != 8 ||
        (j->read.jpeg_color_space != JCS_YCbCr && j->read.jpeg_color_space != JCS_GRAYSCALE) ||
        j->read.image_width > 20000 || j->read.image_height > 20000 ||
        j->read.image_width * (uint64_t)j->read.image_height > 150000000ULL) {
        snprintf(message, capacity, "Use a baseline 8-bit camera JPEG (not progressive or CMYK)"); goto cleanup;
    }
    int width = j->read.image_width, height = j->read.image_height;
    /* Full-resolution Off is a byte-for-byte copy, including valid auxiliary images and thumbnails. */
    if(!preview&&!settings->v[F_OUTPUT]&&film_no_effects(settings)){
        jpeg_destroy_decompress(&j->read);j->read_created=0;
        if(fseek(j->input,0,SEEK_END)){snprintf(message,capacity,"Cannot seek JPEG");goto cleanup;}
        long length=ftell(j->input);if(length<1||fseek(j->input,0,SEEK_SET)){snprintf(message,capacity,"Cannot seek JPEG");goto cleanup;}
        j->output=fopen(destination,"wb");j->row=(unsigned char *)malloc(65536);
        if(!j->output||!j->row){snprintf(message,capacity,"Cannot create copy");goto cleanup;}
        timings[0]=now_ms()-prepare_start;__sync_lock_test_and_set(&stage,2);
        long copied=0;size_t amount;
        double copy_start=now_ms();
        while((amount=fread(j->row,1,65536,j->input))>0){
            if(is_cancelled()){result=1;goto cleanup;}
            if(fwrite(j->row,1,amount,j->output)!=amount){snprintf(message,capacity,"Card write failed");goto cleanup;}
            copied+=(long)amount;__sync_lock_test_and_set(&progress,(int)(99.0*copied/length));
        }
        timings[3]=now_ms()-copy_start;
        if(ferror(j->input)){snprintf(message,capacity,"JPEG read failed");goto cleanup;}
        goto flush;
    }
    /* The first pass builds only small linear-light maps. No full-resolution bitmap. */
    if (film_needs_map(settings)) {
        j->map_input = fopen(source, "rb");
        if (!j->map_input) { snprintf(message, capacity, "Cannot read highlight map"); goto cleanup; }
        jpeg_create_decompress(&j->map); j->map_created = 1; jpeg_stdio_src(&j->map, j->map_input);
        jpeg_read_header(&j->map, TRUE); j->map.scale_num = 1; j->map.scale_denom = 8;
        j->map.out_color_space = JCS_RGB; jpeg_start_decompress(&j->map);
        int edge = j->map.output_width > j->map.output_height ? j->map.output_width : j->map.output_height;
        int limit = edge > 640 ? 640 : edge;
        int mw = j->map.output_width * limit / edge, mh = j->map.output_height * limit / edge;
        if (mw < 1) mw = 1;
        if (mh < 1) mh = 1;
        j->film = film_create(settings, width, height, mw, mh);
        j->row = (unsigned char *)malloc(j->map.output_width * 3);
        if (!j->film || !j->row) { snprintf(message, capacity, "Out of memory for effect maps"); goto cleanup; }
        while (j->map.output_scanline < j->map.output_height) {
            if (is_cancelled()) { result = 1; goto cleanup; }
            int y = j->map.output_scanline; JSAMPROW r = j->row;
            jpeg_read_scanlines(&j->map, &r, 1);
            film_feed(j->film, r, y, j->map.output_width, j->map.output_height);
            __sync_lock_test_and_set(&progress, 20 * j->map.output_scanline / j->map.output_height);
        }
        jpeg_finish_decompress(&j->map);
        if (film_finish_map(j->film)) { snprintf(message, capacity, "Out of memory for glow blur"); goto cleanup; }
        free(j->row); j->row = NULL;
        jpeg_destroy_decompress(&j->map); j->map_created = 0; fclose(j->map_input); j->map_input = NULL;
    } else j->film = film_create(settings, width, height, 0, 0);
    if (!j->film) { snprintf(message, capacity, "Out of memory for effects"); goto cleanup; }
    timings[0]=now_ms()-prepare_start;
    if (preview && !detail) {
        jpeg_abort_decompress(&j->read);
        jpeg_destroy_decompress(&j->read); j->read_created = 0;
        fclose(j->input); j->input = fopen(source, "rb");
        if (!j->input) { snprintf(message, capacity, "Cannot reopen JPEG"); goto cleanup; }
        jpeg_create_decompress(&j->read); j->read_created = 1; jpeg_stdio_src(&j->read, j->input);
        jpeg_read_header(&j->read, TRUE); j->read.scale_num = 1; j->read.scale_denom = 1;
        while ((width / j->read.scale_denom > 960 || height / j->read.scale_denom > 640) && j->read.scale_denom < 16) j->read.scale_denom *= 2;
    } else {
        jpeg_abort_decompress(&j->read);
        jpeg_destroy_decompress(&j->read); j->read_created = 0;
        fclose(j->input); j->input = fopen(source, "rb");
        if (!j->input) { snprintf(message, capacity, "Cannot reopen JPEG"); goto cleanup; }
        jpeg_create_decompress(&j->read); j->read_created = 1; jpeg_stdio_src(&j->read, j->input);
        if (!preview) {
            for (int i = 0; i < 16; i++) jpeg_save_markers(&j->read, JPEG_APP0 + i, 65535);
            jpeg_save_markers(&j->read, JPEG_COM, 65535);
        }
        jpeg_read_header(&j->read, TRUE);
        if(!preview&&settings->v[F_OUTPUT]){j->read.scale_num=1;j->read.scale_denom=2;}
    }
    j->read.out_color_space = JCS_RGB;
    jpeg_start_decompress(&j->read);
    int rw = j->read.output_width, rh = j->read.output_height;
    int stride=1;
    while(preview&&!detail&&(rw/stride>960||rh/stride>640))stride*=2;
    volatile int ow=preview&&detail&&rw>640?640:(rw+stride-1)/stride;
    volatile int oh=preview&&detail&&rh>300?300:(rh+stride-1)/stride;
    int ox=preview&&detail?(rw-ow)/2:0,oy=preview&&detail?(rh-oh)/2:0;
    if (preview) {
        j->pixels = (uint32_t *)malloc((size_t)ow * oh * sizeof(uint32_t));
        if (!j->pixels) { snprintf(message, capacity, "Out of memory for preview"); goto cleanup; }
    } else {
    j->output = fopen(destination, "wb");
    if (!j->output) { snprintf(message, capacity, "Cannot write effect copy"); goto cleanup; }
    jpeg_create_compress(&j->write); j->write_created = 1; jpeg_stdio_dest(&j->write, j->output);
    j->write.image_width = ow; j->write.image_height = oh;
    j->write.input_components = 3; j->write.in_color_space = JCS_RGB;
    jpeg_set_defaults(&j->write); jpeg_set_quality(&j->write, 95, TRUE);
    j->write.optimize_coding = FALSE; /* A second Huffman pass would require full-image coefficient storage. */
    j->write.write_JFIF_header = FALSE;
    for (int i = 0; i < j->write.num_components && j->read.num_components == 3; i++) {
        j->write.comp_info[i].h_samp_factor = j->read.comp_info[i].h_samp_factor;
        j->write.comp_info[i].v_samp_factor = j->read.comp_info[i].v_samp_factor;
    }
    jpeg_start_compress(&j->write, TRUE);
    for (jpeg_saved_marker_ptr m = j->read.marker_list; m; m = m->next) {
        /* MPF offsets refer to the old codestream/auxiliary images, which are not re-encoded. */
        if (m->marker == JPEG_APP0 + 2 && m->data_length >= 4 && !memcmp(m->data, "MPF\0", 4)) continue;
        if (m->marker == JPEG_APP0 + 1) {
            detach_thumbnail(m->data, m->data_length);
            if(settings->v[F_OUTPUT])resize_exif(m->data,m->data_length,ow,oh);
        }
        jpeg_write_marker(&j->write, m->marker, m->data, m->data_length);
    }
    }
    int warped = settings->v[F_DISTORTION] || settings->v[F_ABERRATION];
    int rows = warped ? (int)ceilf(rh * .09f) + 8 : 3;
    size_t ring_bytes = (size_t)rows * rw * 3;
    if (ring_bytes > 16 * 1024 * 1024) { snprintf(message, capacity, "Lens effects exceed the 16 MB row-buffer limit"); goto cleanup; }
    j->ring = (unsigned char *)malloc(ring_bytes);
    j->row = (unsigned char *)malloc(ow * 3);
    j->global_x=(int *)malloc(ow*sizeof(int));
    if (!j->row || !j->ring || !j->global_x) { snprintf(message, capacity, "Out of memory for JPEG rows"); goto cleanup; }
    float xs=(rw-1.f)/(width>1?width-1:1),ys=(rh-1.f)/(height>1?height-1:1);
    for(int x=0;x<ow;x++)j->global_x[x]=(int)((x*stride+ox)*(width-1.f)/(rw>1?rw-1:1)+.5f);
    __sync_lock_test_and_set(&stage,2);
    int map_progress = film_needs_map(settings) ? 20 : 0;
    for (int y = 0; y < oh; y++) {
        if (is_cancelled()) { result = 1; goto cleanup; }
        int source_y=y*stride+oy;
        float global_y=source_y*(height-1.f)/(rh>1?rh-1:1);
        float min_y=source_y,max_y=source_y;
        if(warped){min_y=rh-1;max_y=0;
        for (int edge = 0; edge < 3; edge++) {
            float sx, sy, rx, ry, bx, by;
            film_coordinates(settings, edge * (width - 1.f) / 2, global_y, width, height, &sx, &sy, &rx, &ry, &bx, &by);
            float points[3] = {sy, ry, by};
            for (int i = 0; i < 3; i++) {
                float py = points[i] * (rh - 1.f) / (height > 1 ? height - 1 : 1);
                if (py < min_y) min_y = py;
                if (py > max_y) max_y = py;
            }
        }
        }
        int until = (int)floorf(max_y) + 1; if (until >= rh) until = rh - 1;
        if (until - (int)floorf(min_y) >= rows) { snprintf(message, capacity, "Lens row window exceeded"); goto cleanup; }
        while ((int)j->read.output_scanline <= until) {
            if (is_cancelled()) { result = 1; goto cleanup; }
            JSAMPROW row = j->ring + (j->read.output_scanline % rows) * rw * 3;
            double decode_start=now_ms();jpeg_read_scanlines(&j->read,&row,1);timings[1]+=now_ms()-decode_start;
        }
        double effects_start=now_ms();
        int global_y_int=(int)(global_y+.5f);film_begin_row(j->film,global_y_int);
        if(!warped&&stride==1)memcpy(j->row,j->ring+(source_y%rows)*rw*3+ox*3,ow*3);
        for (int x = 0; x < ow; x++) {
            unsigned char *p=j->row+x*3;
            if(warped){
            float global_x=(x*stride+ox)*(width-1.f)/(rw>1?rw-1:1);
            float sx, sy, rx, ry, bx, by;
            film_coordinates(settings, global_x, global_y, width, height, &sx, &sy, &rx, &ry, &bx, &by);
            p[0] = row_sample(j, rows, rw, rh, rx * xs, ry * ys, 0);
            p[1] = row_sample(j, rows, rw, rh, sx * xs, sy * ys, 1);
            p[2] = row_sample(j, rows, rw, rh, bx * xs, by * ys, 2);
            film_pixel_at(j->film,j->global_x[x],global_y_int,sx,sy,p);
            }else {
                if(stride>1)memcpy(p,j->ring+(source_y%rows)*rw*3+(x*stride+ox)*3,3);
                film_pixel(j->film,j->global_x[x],global_y_int,p);
            }
            if (preview) j->pixels[y * ow + x] = 0xff000000U | (uint32_t)p[0]<<16 | (uint32_t)p[1]<<8 | p[2];
        }
        timings[2]+=now_ms()-effects_start;
        if (!preview) { JSAMPROW row=j->row;double encode_start=now_ms();jpeg_write_scanlines(&j->write,&row,1);timings[3]+=now_ms()-encode_start; }
        __sync_lock_test_and_set(&progress,map_progress+(99-map_progress)*(y+1)/oh);
    }
    while (j->read.output_scanline < j->read.output_height) {
        if (is_cancelled()) { result = 1; goto cleanup; }
        JSAMPROW row=j->ring;double decode_start=now_ms();jpeg_read_scanlines(&j->read,&row,1);timings[1]+=now_ms()-decode_start;
    }
    jpeg_finish_decompress(&j->read);
    if (!preview) {double encode_start=now_ms();jpeg_finish_compress(&j->write);timings[3]+=now_ms()-encode_start;}
    if (j->error.warning) { snprintf(message, capacity, "%s", j->error.message); goto cleanup; }
flush:
    __sync_lock_test_and_set(&stage,3);
    double flush_start=now_ms();
#ifdef __ANDROID__
    if (!preview && (fflush(j->output) || fsync(fileno(j->output)))) {
        snprintf(message, capacity, "Card write failed"); goto cleanup;
    }
#endif
    if (!preview && fclose(j->output)) { j->output = NULL; snprintf(message, capacity, "Card write failed"); goto cleanup; }
    j->output = NULL;
    timings[4]=now_ms()-flush_start;
    result = is_cancelled() ? 1 : 0;
    if(!result){__sync_lock_test_and_set(&progress,100);__sync_lock_test_and_set(&stage,4);}
    if (!result && preview) { *pixels = j->pixels; j->pixels = NULL; *pw = ow; *ph = oh; }
cleanup:
    if (j->read_created) jpeg_destroy_decompress(&j->read);
    if (j->write_created) jpeg_destroy_compress(&j->write);
    if (j->map_created) jpeg_destroy_decompress(&j->map);
    if (j->input) fclose(j->input);
    if (j->output) fclose(j->output);
    if (j->map_input) fclose(j->map_input);
    film_free(j->film);free(j->global_x); free(j->ring); free(j->pixels); free(j->row); free(j);
    timings[5]=now_ms()-total_start;
    /* Caller writes a reserved temporary file and deletes it on failure. */
    return result;
}
int film_process(const char *source, const char *destination, const FilmSettings *s, char *message, unsigned int capacity) {
    return render(source, destination, s, 0, 0, NULL, NULL, NULL, message, capacity);
}
int film_preview(const char *source, const FilmSettings *s, int detail, uint32_t **pixels, int *width, int *height, char *message, unsigned int capacity) {
    return render(source, NULL, s, 1, detail, pixels, width, height, message, capacity);
}
int grain_process(const char *source, const char *destination, int strength, int size, uint32_t seed, char *message, size_t capacity) {
    FilmSettings s = {{strength, size, (int)seed, 0, 30, 75, 0, 40, 0, 0, 0, 0, 0, 0, 0, 0}};
    return film_process(source, destination, &s, message, capacity);
}
