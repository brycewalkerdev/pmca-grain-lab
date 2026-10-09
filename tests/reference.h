#ifndef FILM_REFERENCE_H
#define FILM_REFERENCE_H
#include "film.h"
typedef struct ReferenceContext ReferenceContext;
ReferenceContext *reference_create(const FilmSettings *, int, int, int, int);
void reference_feed(ReferenceContext *, const unsigned char *, int, int, int);
int reference_finish_map(ReferenceContext *);
void reference_pixel(ReferenceContext *, int, int, unsigned char[3]);
void reference_free(ReferenceContext *);
#endif
