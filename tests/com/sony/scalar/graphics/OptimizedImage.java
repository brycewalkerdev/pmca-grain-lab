package com.sony.scalar.graphics;
public class OptimizedImage extends com.sony.scalar.hardware.DeviceMemory {
    public boolean uploaded;public static int allocations,releases;public final int identity;private boolean live=true;private final int w,h;
    public OptimizedImage(int w,int h){this.w=w;this.h=h;allocations++;identity=allocations;}
    public boolean isValid(){return live;}public int getWidth(){return w;}public int getHeight(){return h;}
    public int getImageFormat(){return 0;}
    public void release(){if(!live)throw new AssertionError("Double image release");live=false;releases++;}
}
