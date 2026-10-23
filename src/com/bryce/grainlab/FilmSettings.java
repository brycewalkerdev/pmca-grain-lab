package com.bryce.grainlab;

/** Ordered native parameter contract; camera-free so ranges/navigation can be tested. */
final class FilmSettings {
    static final int GRAIN = 0, SIZE = 1, SEED = 2, HALATION = 3, HAL_RADIUS = 4, THRESHOLD = 5,
        BLOOM = 6, BLOOM_RADIUS = 7, DIFFUSION = 8, VIGNETTE = 9, LEAK = 10, DUST = 11,
        SCRATCH = 12, ABERRATION = 13, DISTORTION = 14, EDGE_SOFTNESS = 15, OUTPUT = 16, COUNT = 17;
    static final int VIEW=COUNT,PRESET=COUNT+1,STORE=COUNT+2,TIMINGS=COUNT+3,SONY_TEST=COUNT+4,SAVE=COUNT+5,ROWS=COUNT+6;
    static final int[] DEFAULT = {35, 2, 1, 0, 30, 75, 0, 40, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    static final int[] MIN = {0, 1, Integer.MIN_VALUE, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, -100, 0, 0};
    static final int[] MAX = {100, 8, Integer.MAX_VALUE, 100, 100, 95, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 1};
    static int[] defaults() { return DEFAULT.clone(); }
    static int[] off() { int[] v = defaults(); v[GRAIN] = 0; return v; }
    static boolean valid(int[] values) {
        if (values == null || values.length != COUNT) return false;
        for (int i = 0; i < COUNT; i++) if (values[i] < MIN[i] || values[i] > MAX[i]) return false;
        return true;
    }
    static void adjust(int[] values, int row, int direction) {
        int step = row == SIZE || row == SEED || row == OUTPUT || row == THRESHOLD ? 1 : 5;
        if (row == SEED) { values[row] += direction; return; }
        values[row] = Math.max(MIN[row], Math.min(MAX[row], values[row] + direction * step));
    }
    static int gridStart(int selected) { return selected / 6 * 6; }
    static int gridMove(int selected, int direction, int count) {
        return count == 0 ? 0 : (selected + direction % count + count) % count;
    }
}
