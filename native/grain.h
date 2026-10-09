#ifndef GRAIN_LAB_H
#define GRAIN_LAB_H
#include <stddef.h>
#include <stdint.h>
int grain_delta(int x, int y, int luminance, int strength, int size, uint32_t seed);
int grain_process(const char *source, const char *destination, int strength, int size,
                  uint32_t seed, char *message, size_t capacity);
void grain_reset(void);
void grain_cancel(void);
int grain_progress(void);
#endif
