package com.sony.scalar.hardware;
import com.sony.scalar.graphics.OptimizedImage;
public class DSP {
    public static int allocations,releases;
    public static class DeviceType {public static final String SONY_DI_DSP="sony-di-dsp";}
    public static DSP createProcessor(String type){if(!type.equals(DeviceType.SONY_DI_DSP))throw new AssertionError("Processor type");allocations++;return new DSP();}
    public DeviceBuffer createBuffer(int size){return new DeviceBuffer(size);}
    public DeviceMemory createImage(int w,int h){return new OptimizedImage(w,h);}
    public String getProperty(DeviceMemory image,String key){if(!image.isValid())throw new AssertionError("Invalid image");OptimizedImage im=(OptimizedImage)image;int w=im.getWidth(),h=im.getHeight();int cw=(w+127)/128*128,ch=(h+31)/32*32;
        if(key.equals("memory-size"))return Integer.toString(cw*ch*2);
        if(key.equals("memory-address"))return Integer.toString((im.identity%16)*67108864);
        if(key.equals("image-target-width"))return Integer.toString(w);
        if(key.equals("image-target-height"))return Integer.toString(h);
        if(key.equals("image-canvas-width"))return Integer.toString(cw);
        if(key.equals("image-canvas-height"))return Integer.toString(ch);
        return "0";}
    public void release(){releases++;}
}
