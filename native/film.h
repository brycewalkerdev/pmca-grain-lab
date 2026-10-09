#ifndef FILM_EFFECTS_H
#define FILM_EFFECTS_H
#include <stdint.h>
enum { F_GRAIN, F_SIZE, F_SEED, F_HALATION, F_HAL_RADIUS, F_THRESHOLD, F_BLOOM,
    F_BLOOM_RADIUS, F_DIFFUSION, F_VIGNETTE, F_LEAK, F_DUST, F_SCRATCH,
    F_ABERRATION, F_DISTORTION, F_EDGE_SOFTNESS, F_OUTPUT, F_COUNT };
typedef struct { int v[F_COUNT]; } FilmSettings;
typedef struct FilmContext FilmContext;
int film_valid(const FilmSettings *s);
int film_needs_map(const FilmSettings *s);
int film_no_effects(const FilmSettings *s);
FilmContext *film_create(const FilmSettings *s, int width, int height, int map_width, int map_height);
void film_feed(FilmContext *f, const unsigned char *row, int y, int width, int height);
int film_finish_map(FilmContext *f);
void film_free(FilmContext *f);
void film_coordinates(const FilmSettings *s, float x, float y, int width, int height,
                      float *sx, float *sy, float *rx, float *ry, float *bx, float *by);
void film_pixel(FilmContext *f, int x, int y, unsigned char rgb[3]);
void film_pixel_at(FilmContext *f, int x, int y, float source_x, float source_y, unsigned char rgb[3]);
void film_begin_row(FilmContext *f, int y);
/* Milliseconds: preparation, decode/read, effects, encode/write, final flush, total. */
void film_timings(double values[6]);
int film_stage(void);
const char *film_backend(void);
int film_process(const char *source, const char *destination, const FilmSettings *s,
                 char *message, unsigned int capacity);
/* Native JPEG preview goes through the same engine as export. Packed RGB, max 960 px long edge. */
int film_preview(const char *source, const FilmSettings *s, int detail,
                 uint32_t **pixels, int *width, int *height, char *message, unsigned int capacity);
#endif
