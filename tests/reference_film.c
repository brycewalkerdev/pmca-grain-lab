/* Frozen pre-optimization implementation for pixel-equivalence tests. */
#define FilmContext ReferenceContext
#define film_valid reference_valid
#define film_needs_map reference_needs_map
#define film_create reference_create
#define film_feed reference_feed
#define film_finish_map reference_finish_map
#define film_free reference_free
#define film_coordinates reference_coordinates
#define film_pixel reference_pixel
#include "film.h"
#include "grain.h"
#include <math.h>
#include <stdlib.h>
#include <string.h>

struct FilmContext {
    FilmSettings s;
    int width, height, mw, mh;
    uint16_t *base, *soft, *glow, *halo;
    float linear[256];
    unsigned char encoded[65536];
};
static float bound(float x, float lo, float hi) { return x < lo ? lo : x > hi ? hi : x; }
static uint32_t hash(uint32_t n) { n ^= n >> 16; n *= 0x7feb352dU; n ^= n >> 15; n *= 0x846ca68bU; return n ^ (n >> 16); }
static float random01(uint32_t n) { return (hash(n) & 65535) / 65535.f; }
int film_valid(const FilmSettings *s) {
    if (!s) return 0;
    for (int i = 0; i < F_COUNT; i++) {
        if (i == F_SEED) continue;
        int lo = i == F_SIZE || i == F_HAL_RADIUS || i == F_BLOOM_RADIUS ? 1 : i == F_THRESHOLD ? 40 : i == F_DISTORTION ? -100 : 0;
        int hi = i == F_SIZE ? 8 : i == F_THRESHOLD ? 95 : 100;
        if (s->v[i] < lo || s->v[i] > hi) return 0;
    }
    return 1;
}
int film_needs_map(const FilmSettings *s) {
    return s->v[F_HALATION] || s->v[F_BLOOM] || s->v[F_DIFFUSION] || s->v[F_EDGE_SOFTNESS];
}
FilmContext *film_create(const FilmSettings *s, int width, int height, int mw, int mh) {
    if (!film_valid(s) || width < 1 || height < 1 || mw < 0 || mh < 0 || mw > 640 || mh > 640) return NULL;
    FilmContext *f = (FilmContext *)calloc(1, sizeof(*f)); if (!f) return NULL;
    f->s = *s; f->width = width; f->height = height; f->mw = mw; f->mh = mh;
    for (int n = 0; n < 256; n++) {
        float x = n / 255.f;
        f->linear[n] = x <= .04045f ? x / 12.92f : powf((x + .055f) / 1.055f, 2.4f);
    }
    for (int n = 0; n < 65536; n++) {
        float x = n / 65535.f;
        float encoded = x <= .0031308f ? 12.92f * x : 1.055f * powf(x, 1.f / 2.4f) - .055f;
        f->encoded[n] = (unsigned char)(bound(encoded, 0, 1) * 255 + .5f);
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
static float sample(const uint16_t *data, int w, int h, int channels, float x, float y, int channel) {
    x = bound(x, 0, w - 1); y = bound(y, 0, h - 1);
    int x0 = (int)x, y0 = (int)y, x1 = x0 + 1 < w ? x0 + 1 : x0, y1 = y0 + 1 < h ? y0 + 1 : y0;
    float fx = x - x0, fy = y - y0;
    float a = data[(y0 * w + x0) * channels + channel] * (1 - fx) + data[(y0 * w + x1) * channels + channel] * fx;
    float b = data[(y1 * w + x0) * channels + channel] * (1 - fx) + data[(y1 * w + x1) * channels + channel] * fx;
    return (a * (1 - fy) + b * fy) / 65535.f;
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
void film_pixel(FilmContext *f, int x, int y, unsigned char rgb[3]) {
    const int *s = f->s.v;
    float p[3] = {f->linear[rgb[0]], f->linear[rgb[1]], f->linear[rgb[2]]};
    float nx = f->width > 1 ? 2.f * x / (f->width - 1) - 1 : 0;
    float ny = f->height > 1 ? 2.f * y / (f->height - 1) - 1 : 0;
    float radial = (nx * nx + ny * ny) / 2;
    if (f->base) {
        float sx, sy, rx, ry, bx, by;
        film_coordinates(&f->s, x, y, f->width, f->height, &sx, &sy, &rx, &ry, &bx, &by);
        float mx = sx * (f->mw - 1) / (f->width > 1 ? f->width - 1 : 1);
        float my = sy * (f->mh - 1) / (f->height > 1 ? f->height - 1 : 1);
        float softness = bound((s[F_DIFFUSION] * .0035f + s[F_EDGE_SOFTNESS] * .006f * radial), 0, .9f);
        float halo = s[F_HALATION] ? sample(f->halo, f->mw, f->mh, 1, mx, my, 0) * s[F_HALATION] / 100.f : 0;
        float source_luma = .2126f * p[0] + .7152f * p[1] + .0722f * p[2];
        /* Suppress tint inside bright sources; the warm fringe lives outside the source. */
        halo *= (1 - bound(source_luma, 0, 1)) * .6f;
        for (int c = 0; c < 3; c++) {
            if (softness) p[c] += softness * (sample(f->soft, f->mw, f->mh, 3, mx, my, c) - p[c]);
            if (s[F_BLOOM]) p[c] += sample(f->glow, f->mw, f->mh, 3, mx, my, c) * s[F_BLOOM] * .005f;
            p[c] += halo * (c == 0 ? 1 : c == 1 ? .22f : .035f);
        }
    }
    float vignette = 1 - s[F_VIGNETTE] / 100.f * .85f * radial * radial;
    uint32_t seed = (uint32_t)s[F_SEED];
    float leak = 0;
    if (s[F_LEAK]) {
        int side = hash(seed ^ 0x51edU) & 1;
        float edge = side ? (1 - nx) / 2 : (1 + nx) / 2;
        float centre = random01(seed ^ 0x9271U) * 1.5f - .75f;
        leak = s[F_LEAK] / 100.f * .8f * expf(-edge * edge * 25.f - (ny - centre) * (ny - centre) * 1.8f);
    }
    float defect = 0;
    if (s[F_DUST]) {
        int cx = (int)((nx + 1) * 16), cy = (int)((ny + 1) * 12);
        uint32_t key = hash(seed ^ (uint32_t)cx * 0x9e3779b9U ^ (uint32_t)cy * 0x85ebca6bU);
        if ((int)(key & 255) < s[F_DUST] * 2) {
            float px = (cx + .12f + .76f * random01(key ^ 17)) / 32 * (f->width - 1);
            float py = (cy + .12f + .76f * random01(key ^ 23)) / 24 * (f->height - 1);
            float radius = (1 + 2 * random01(key ^ 31)) * (f->width > f->height ? f->width : f->height) / 6000.f;
            if (radius < 1) radius = 1;
            float dx = (x - px) / radius, dy = (y - py) / (radius * 1.6f);
            defect = bound(1 - dx * dx - dy * dy, 0, 1) * .8f;
        }
    }
    if (s[F_SCRATCH]) {
        int cell = x * 32 / f->width;
        uint32_t key = hash(seed ^ (uint32_t)cell * 0xa24baed5U);
        if ((int)(key & 255) < s[F_SCRATCH]) {
            float px = (cell + random01(key ^ 17)) / 32 * f->width;
            float line = bound(1 - fabsf(x - px) / (1 + f->width / 6000.f), 0, 1);
            float broken = .3f + .7f * random01(key ^ (uint32_t)(y * 32 / f->height));
            defect = bound(defect + line * broken * .55f, 0, 1);
        }
    }
    for (int c = 0; c < 3; c++) {
        p[c] = p[c] * vignette + leak * (c == 0 ? 1 : c == 1 ? .25f : .04f);
        p[c] *= 1 - defect;
        rgb[c] = f->encoded[(int)(bound(p[c], 0, 1) * 65535.f + .5f)];
    }
    int long_edge = f->width > f->height ? f->width : f->height;
    int grain_size = (s[F_SIZE] * long_edge + 3000) / 6000; if (grain_size < 1) grain_size = 1;
    int luma = (77 * rgb[0] + 150 * rgb[1] + 29 * rgb[2]) >> 8;
    int delta = grain_delta(x, y, luma, s[F_GRAIN], grain_size, seed);
    for (int c = 0; c < 3; c++) rgb[c] = (unsigned char)bound(rgb[c] + delta, 0, 255);
}
void film_free(FilmContext *f) {
    if (!f) return;
    free(f->base); free(f->soft); free(f->glow); free(f->halo); free(f);
}
