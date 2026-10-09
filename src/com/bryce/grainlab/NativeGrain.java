package com.bryce.grainlab;

final class NativeGrain {
    static final boolean AVAILABLE;
    static {
        boolean available;
        try { System.loadLibrary("grainlab"); available = true; }
        catch (UnsatisfiedLinkError e) { available = false; }
        AVAILABLE = available;
    }
    static native int[] preview(String source, int[] settings, boolean detail);
    static native void process(String source, String destination, int[] settings);
    static native void reset();
    static native void cancel();
    static native int progress();
    static native int stage();
    static native double[] timings();
    static native String backend();
    static native byte[] sonyRead(Object memory,int offset,int count);
    static native int[] sonyJpegSize(String path);
    static native void sonyUpload(Object target,String path,int[] geometry);
    static native int sonyWrite(Object memory,int offset,byte[] data);
}
