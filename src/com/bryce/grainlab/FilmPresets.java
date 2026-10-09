package com.bryce.grainlab;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

/** One user slot, written atomically in app-private storage, with a versioned format. */
final class FilmPresets {
    static void save(File directory, int[] values) throws IOException {
        if (!FilmSettings.valid(values)) throw new IOException("Invalid effect settings");
        File temporary = new File(directory, "effects.tmp"), file = new File(directory, "effects.properties");
        Properties p = new Properties(); p.setProperty("format", "2");
        for (int i = 0; i < values.length; i++) p.setProperty("effect." + i, String.valueOf(values[i]));
        FileOutputStream out = new FileOutputStream(temporary);
        try { p.store(out, "Grain Lab effect preset"); out.getFD().sync(); } finally { out.close(); }
        if (!temporary.renameTo(file)) {
            File backup = new File(directory, "effects.backup");
            if (!file.exists() || !file.renameTo(backup)) { temporary.delete(); throw new IOException("Cannot store preset"); }
            if (!temporary.renameTo(file)) {
                backup.renameTo(file); temporary.delete(); throw new IOException("Cannot store preset");
            }
            backup.delete();
        }
    }
    static int[] load(File directory) throws IOException {
        File file = new File(directory, "effects.properties");
        if (!file.exists()) file = new File(directory, "effects.backup");
        Properties p = new Properties(); FileInputStream in = new FileInputStream(file);
        try { p.load(in); } finally { in.close(); }
        boolean legacy = "1".equals(p.getProperty("format"));
        if (!legacy && !"2".equals(p.getProperty("format"))) throw new IOException("Unsupported preset format");
        int[] v = FilmSettings.defaults();
        try { for (int i = 0; i < (legacy ? 16 : v.length); i++) v[i] = Integer.parseInt(p.getProperty("effect." + i)); }
        catch (RuntimeException e) { throw new IOException("Invalid preset"); }
        if (!FilmSettings.valid(v)) throw new IOException("Invalid effect settings");
        return v;
    }
    static int[] builtIn(int number) {
        int[] v = FilmSettings.defaults();
        if (number == 1) { v[0] = 25; v[3] = 20; v[6] = 15; v[8] = 15; v[9] = 15; }
        if (number == 2) { v[0] = 45; v[3] = 55; v[6] = 25; v[9] = 20; }
        if (number == 3) { v[0] = 60; v[1] = 3; v[8] = 20; v[9] = 40; v[10] = 20; v[11] = 15; v[12] = 10; v[13] = 25; v[14] = 20; v[15] = 25; }
        return v;
    }
}
