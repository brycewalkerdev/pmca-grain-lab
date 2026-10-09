/* Host-only verification tool; uses exactly the same engine as the camera app. */
#include <stdio.h>
#include <stdlib.h>
#include "film.h"
#include "grain.h"
int main(int argc, char **argv) {
    if (argc != 3 && argc != F_COUNT + 3 && argc != F_COUNT + 2) {
        fprintf(stderr, "Usage: grain-process input.jpg output.jpg [16 effect values] [0=full/1=half]\n"); return 2;
    }
    FilmSettings s = {{35,2,1,25,30,75,15,40,10,15,0,0,0,0,0,0}};
    if (argc > 3) for (int i = 0; i < argc-3; i++) s.v[i] = atoi(argv[i + 3]);
    grain_reset(); char message[256];
    int result = film_process(argv[1], argv[2], &s, message, sizeof(message));
    if (result) { fprintf(stderr, "%s\n", message); return 1; }
    double times[6];film_timings(times);
    printf("prepare=%.1f decode=%.1f effects=%.1f encode_write=%.1f flush=%.1f total=%.1f ms\n",times[0],times[1],times[2],times[3],times[4],times[5]);
    puts(film_backend());puts("Processed successfully"); return 0;
}
