package com.sony.scalar.graphics.imagefilter;
import com.sony.scalar.graphics.OptimizedImage;
public class ScaleImageFilter {
    public static int releases;public static boolean supported=true,fail;protected OptimizedImage source,output;private int w,h;
    public boolean isSupported(){return supported;}
    public void setSource(OptimizedImage image,boolean releaseSource){if(releaseSource)throw new AssertionError("Borrow source");source=image;}
    public void setDestSize(int width,int height){w=width;h=height;}
    public boolean execute(){if(fail)throw new IllegalStateException("Mock scale execute failure");output=new OptimizedImage(w,h);return true;}
    public OptimizedImage getOutput(){return output;}
    public void release(){releases++;}
}
