package com.sony.scalar.graphics.imagefilter;
import com.sony.scalar.graphics.OptimizedImage;
public class SoftFocusImageFilter extends ScaleImageFilter {
    public static boolean fail;
    public void setEffectLevel(int value){if(value!=1)throw new AssertionError("Mild probe level");}
    public boolean execute(){if(fail)throw new IllegalStateException("Mock execute failure");output=new OptimizedImage(source.getWidth(),source.getHeight());return true;}
}
