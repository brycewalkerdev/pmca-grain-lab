package com.bryce.grainlab;

import java.util.Arrays;

/** Calibrate against the public buffer API before reading opaque image samples. */
final class SonyNativeSamples {
    interface Reader {byte[] read(Object memory,int offset,int count)throws Exception;}
    private static void mark(StringBuilder report,String text,SonyAcceleration.Observer observer){report.append(text).append('\n');if(observer!=null)observer.progress(report.toString());}
    static int[] offsets(int size,int canvasWidth,int canvasHeight,int width,int height,int dataOffset){
        // This is a sampling hypothesis, not a pixel-format declaration.
        if(size<=0||canvasWidth<=0||canvasHeight<=0||width<32||height<1||width>canvasWidth||height>canvasHeight||dataOffset!=0||2L*canvasWidth*canvasHeight!=size)throw new IllegalArgumentException("Image descriptor does not match two-byte canvas sampling hypothesis");
        long center=2L*((height/2)* (long)canvasWidth+((width/2)&~1));
        long bottom=2L*(height-1)*canvasWidth;
        if(center+64>size||bottom+64>size)throw new IllegalArgumentException("Sample exceeds image bounds");
        return new int[]{0,(int)center,(int)bottom};
    }
    static void capture(Reader reader,Object buffer,Object image,byte[] expected,int[] offsets,StringBuilder report,SonyAcceleration.Observer observer)throws Exception{
        mark(report,"Native bridge: calibrating scratch offset 0",observer);
        if(!Arrays.equals(Arrays.copyOfRange(expected,0,64),reader.read(buffer,0,64)))throw new IllegalStateException("Native scratch read mismatch; image reads refused");
        mark(report,"Native bridge: calibrating scratch offset 128",observer);
        if(!Arrays.equals(Arrays.copyOfRange(expected,128,192),reader.read(buffer,128,64)))throw new IllegalStateException("Native offset read mismatch; image reads refused");
        mark(report,"Native/public scratch agreement: PASS",observer);
        for(int offset:offsets){
            mark(report,"Native image read: offset="+offset+", bytes=64 (two-byte canvas hypothesis)",observer);
            byte[] data=reader.read(image,offset,64);
            if(data==null||data.length!=64)throw new IllegalStateException("Native image sample incomplete");
            StringBuilder hex=new StringBuilder();for(byte value:data){int v=value&255;hex.append("0123456789abcdef".charAt(v>>>4)).append("0123456789abcdef".charAt(v&15));}
            mark(report,"Image sample "+offset+": "+hex,observer);
        }
        mark(report,"Native read-only image sampling: PASS; no pixels written",observer);
    }
    static void run(Object processor,Object buffer,Object image,byte[] expected,Class<?> memory,StringBuilder report,SonyAcceleration.Observer observer){
        try{
            if(!NativeGrain.AVAILABLE){mark(report,"Native sample bridge unavailable",observer);return;}
            int[] values=new int[6];String[] keys={"memory-size","image-canvas-width","image-canvas-height","image-target-width","image-target-height","image-data-offset"};
            for(int i=0;i<keys.length;i++){
                mark(report,"Native sample descriptor: "+keys[i],observer);
                String value=(String)processor.getClass().getMethod("getProperty",memory,String.class).invoke(processor,image,keys[i]);
                values[i]=Integer.parseInt(value);
            }
            int[] sampleOffsets=offsets(values[0],values[1],values[2],values[3],values[4],values[5]);
            capture(new Reader(){public byte[] read(Object object,int offset,int count){return NativeGrain.sonyRead(object,offset,count);}},buffer,image,expected,sampleOffsets,report,observer);
        }catch(Exception e){mark(report,"Native sample FAILED: "+SonyGallery.error(e),observer);}
         catch(LinkageError e){mark(report,"Native sample bridge unavailable: "+e,observer);}
    }
}
