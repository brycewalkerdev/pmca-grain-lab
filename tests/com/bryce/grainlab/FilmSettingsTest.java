package com.bryce.grainlab;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
public final class FilmSettingsTest {
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        int[] v = FilmSettings.defaults(); check(FilmSettings.valid(v), "valid defaults");
        for (int i = 0; i < FilmSettings.COUNT; i++) {
            if (i == FilmSettings.SEED) continue;
            v = FilmSettings.defaults(); for (int n = 0; n < 100; n++) FilmSettings.adjust(v, i, -1);
            check(v[i] == FilmSettings.MIN[i], "lower bound " + i);
            for (int n = 0; n < 200; n++) FilmSettings.adjust(v, i, 1);
            check(v[i] == FilmSettings.MAX[i], "upper bound " + i);
        }
        check(FilmSettings.gridStart(7) == 6, "six-photo pages");
        check(FilmSettings.gridMove(0, -1, 7) == 6 && FilmSettings.gridMove(6, 2, 7) == 1, "grid wrapping");
        for (int p = 0; p < 4; p++) check(FilmSettings.valid(FilmPresets.builtIn(p)), "built-in preset " + p);
        File dir = Files.createTempDirectory("film-preset-").toFile();
        try {
            v = FilmPresets.builtIn(2); FilmPresets.save(dir, v); check(Arrays.equals(v, FilmPresets.load(dir)), "preset roundtrip");
            v[FilmSettings.VIGNETTE] = 75; FilmPresets.save(dir, v); check(Arrays.equals(v, FilmPresets.load(dir)), "preset replacement");
            StringBuilder legacy=new StringBuilder("format=1\n");
            for(int i=0;i<16;i++)legacy.append("effect.").append(i).append("=").append(v[i]).append("\n");
            Files.write(new File(dir,"effects.properties").toPath(),legacy.toString().getBytes("UTF-8"));
            int[] imported=FilmPresets.load(dir);check(imported[FilmSettings.OUTPUT]==0&&imported[FilmSettings.VIGNETTE]==75,"old presets retain effects and default to full resolution");
            Files.write(new File(dir, "effects.properties").toPath(), "format=999".getBytes("UTF-8"));
            boolean rejected = false; try { FilmPresets.load(dir); } catch (java.io.IOException e) { rejected = true; }
            check(rejected, "unknown preset version rejected");
        } finally { for (File f : dir.listFiles()) f.delete(); dir.delete(); }
        System.out.println("PASS: effect ranges, grid navigation, built-in presets, user preset save/load/replacement");
    }
}
