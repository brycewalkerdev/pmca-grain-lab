package com.bryce.grainlab;

import android.content.Context;

/** Every on-screen label uses Android's locale-specific string resources. */
final class Lang {
    static String t(Context context, int id, Object... args) {
        return context.getString(id, args);
    }
}
