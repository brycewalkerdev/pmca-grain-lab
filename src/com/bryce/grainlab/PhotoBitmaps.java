package com.bryce.grainlab;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import java.io.File;
import java.io.IOException;

/** Small thumbnail-first decodes; every returned bitmap is owned by its caller. */
final class PhotoBitmaps {
    static Bitmap thumbnail(File source, int limit) throws IOException {
        ExifInterface exif = null;
        try { exif = new ExifInterface(source.getPath()); } catch (IOException e) {}
        byte[] embedded = exif == null ? null : exif.getThumbnail(); Bitmap image = null;
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        if (embedded != null) BitmapFactory.decodeByteArray(embedded, 0, embedded.length, bounds);
        if (bounds.outWidth > 0 && bounds.outHeight > 0 && Math.max(bounds.outWidth, bounds.outHeight) >= limit * 3 / 4) {
            image = BitmapFactory.decodeByteArray(embedded, 0, embedded.length, options(bounds, limit));
        }
        if (image == null) {
            bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(source.getPath(), bounds);
            if (bounds.outWidth < 1 || bounds.outHeight < 1) throw new IOException("JPEG thumbnail unavailable");
            image = BitmapFactory.decodeFile(source.getPath(), options(bounds, limit));
        }
        if (image == null) throw new IOException("JPEG thumbnail unavailable");
        return orient(image, exif == null ? 1 : exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1));
    }
    private static BitmapFactory.Options options(BitmapFactory.Options bounds, int limit) {
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inSampleSize = 1;
        while (bounds.outWidth / options.inSampleSize > limit || bounds.outHeight / options.inSampleSize > limit) options.inSampleSize *= 2;
        return options;
    }
    static Bitmap orient(Bitmap image, int orientation) {
        Matrix m = new Matrix();
        switch (orientation) {
            case 2: m.setScale(-1, 1); break;
            case 3: m.setRotate(180); break;
            case 4: m.setScale(1, -1); break;
            case 5: m.setRotate(90); m.postScale(-1, 1); break;
            case 6: m.setRotate(90); break;
            case 7: m.setRotate(-90); m.postScale(-1, 1); break;
            case 8: m.setRotate(-90); break;
        }
        if (m.isIdentity()) return image;
        Bitmap result = Bitmap.createBitmap(image, 0, 0, image.getWidth(), image.getHeight(), m, true);
        if (result != image) image.recycle(); return result;
    }
    static int orientation(File source) {
        try { return new ExifInterface(source.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1); }
        catch (IOException e) { return 1; }
    }
}
