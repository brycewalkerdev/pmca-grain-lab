package com.sony.scalar.graphics;
public final class OptimizedImageFactory {
    public static boolean fail,nativeOnly;public static String observed;
    public static class Options {public static final int MAIN=3;public int imageType=2;}
    public static OptimizedImage decodeImage(String uri,Options options){
        if(options.imageType!=3||(!uri.startsWith("file://")&&!uri.startsWith("avindex://")))throw new AssertionError("MAIN image URI contract");observed=uri;
        if(fail||(nativeOnly&&!uri.startsWith("file:///android/mnt/sdcard/")))return null;return new OptimizedImage(6000,4000);
    }
}
