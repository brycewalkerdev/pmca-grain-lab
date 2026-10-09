package com.bryce.grainlab;

import android.content.ContentResolver;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Original provides the encodable context; uploaded pixels come from the effect JPEG. */
final class SonyImageUpload {
    interface Pixels {int[] dimensions(File file)throws Exception;void upload(Object image,File file,int[] geometry)throws Exception;}
    static final Pixels NATIVE=new Pixels(){
        public int[] dimensions(File file){return NativeGrain.sonyJpegSize(file.getPath());}
        public void upload(Object image,File file,int[] geometry){NativeGrain.sonyUpload(image,file.getPath(),geometry);}
    };
    interface Progress {void stage(String message);}
    static final class Frame {
        Object image;int[] geometry;
        final List<Object> images=new ArrayList<Object>(),filters=new ArrayList<Object>();
        void release(){for(Object filter:filters)SonyImageUpload.release(filter);filters.clear();for(int i=images.size()-1;i>=0;i--)SonyImageUpload.release(images.get(i));images.clear();}
    }
    private static Object call(Object object,String name)throws Exception{return object.getClass().getMethod(name).invoke(object);}
    private static void release(Object object){if(object!=null)try{call(object,"release");}catch(Exception e){}catch(LinkageError e){}}
    private static Object scale(Frame frame,Object source,int w,int h)throws Exception{
        Class<?> type=Class.forName("com.sony.scalar.graphics.imagefilter.ScaleImageFilter"),image=Class.forName("com.sony.scalar.graphics.OptimizedImage");
        Object filter=type.newInstance();frame.filters.add(filter);
        if(!Boolean.TRUE.equals(call(filter,"isSupported")))throw new IllegalStateException("Sony scaler unsupported");
        type.getMethod("setSource",image,boolean.class).invoke(filter,source,false);type.getMethod("setDestSize",int.class,int.class).invoke(filter,w,h);
        if(!Boolean.TRUE.equals(call(filter,"execute")))throw new IllegalStateException("Sony template scaling failed");
        Object output=call(filter,"getOutput");if(output==null||output==source||!Boolean.TRUE.equals(call(output,"isValid")))throw new IllegalStateException("No separate Sony image template");
        frame.images.add(output);return output;
    }
    static Frame prepare(ContentResolver resolver,File source,File processed,Pixels pixels,Progress progress)throws Exception{
        Frame frame=new Frame();Object dsp=null;boolean ready=false;
        try{
            int[] size=pixels.dimensions(processed);if(size==null||size.length!=2||size[0]<2||size[1]<2||(size[0]&1)!=0)throw new IllegalStateException("Unsupported processed JPEG dimensions");
            String id=SonyGallery.imageId(resolver,source);if(id==null)throw new IllegalStateException("Source photo has no Sony catalog identifier");
            progress.stage("Decoding original catalog image: "+id);
            Object original=SonyImageReader.decodeUri(id,new StringBuilder());if(original==null)throw new IllegalStateException("Original Sony MAIN decode failed");frame.images.add(original);
            int width=((Integer)call(original,"getWidth")).intValue(),height=((Integer)call(original,"getHeight")).intValue();
            if(size[0]>width||size[1]>height)throw new IllegalStateException("Processed image exceeds original dimensions");
            Object input=original;
            if(size[0]==width&&size[1]==height){progress.stage("Creating full-resolution copy via intermediate scaler image");input=scale(frame,original,width/2,height/2);}
            progress.stage("Creating encoder template "+size[0]+"x"+size[1]);frame.image=scale(frame,input,size[0],size[1]);
            Class<?> type=Class.forName("com.sony.scalar.hardware.DSP"),memory=Class.forName("com.sony.scalar.hardware.DeviceMemory");
            String device=(String)Class.forName("com.sony.scalar.hardware.DSP$DeviceType").getField("SONY_DI_DSP").get(null);dsp=type.getMethod("createProcessor",String.class).invoke(null,device);
            String[] keys={"image-target-width","image-target-height","image-canvas-width","image-canvas-height","image-data-offset","image-target-x-offset","image-target-y-offset","memory-size"};
            frame.geometry=new int[8];for(int i=0;i<8;i++)frame.geometry[i]=Integer.parseInt((String)type.getMethod("getProperty",memory,String.class).invoke(dsp,frame.image,keys[i]));
            String sourceAddress=(String)type.getMethod("getProperty",memory,String.class).invoke(dsp,original,"memory-address"),targetAddress=(String)type.getMethod("getProperty",memory,String.class).invoke(dsp,frame.image,"memory-address");
            int sourceSize=Integer.parseInt((String)type.getMethod("getProperty",memory,String.class).invoke(dsp,original,"memory-size"));
            if(!SonyWriteProbe.separate(sourceAddress,sourceSize,targetAddress,frame.geometry[7]))throw new IllegalStateException("Sony template overlaps original memory; upload refused");
            release(dsp);dsp=null;
            // The intermediate is no longer required once the final scaler completes.
            if(input!=original){release(input);frame.images.remove(input);}
            progress.stage("Uploading processed JPEG pixels to padded Sony canvas "+frame.geometry[2]+"x"+frame.geometry[3]);
            pixels.upload(frame.image,processed,frame.geometry);progress.stage("Processed-pixel upload completed");ready=true;return frame;
        }finally{release(dsp);if(!ready)frame.release();}
    }
}
