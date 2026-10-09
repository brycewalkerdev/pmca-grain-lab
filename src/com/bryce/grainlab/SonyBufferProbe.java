package com.bryce.grainlab;

import java.util.Arrays;

/** Public SDK scratch-memory transfer and read-only image descriptors. */
final class SonyBufferProbe {
    private static final String[] KEYS={"memory-size","image-data-offset","image-canvas-width","image-canvas-height","image-target-width","image-target-height","image-target-x-offset","image-target-y-offset"};
    private static void mark(StringBuilder report,String text,SonyAcceleration.Observer observer){report.append(text).append('\n');if(observer!=null)observer.progress(report.toString());}
    private static Object call(Object object,String name)throws Exception{return object.getClass().getMethod(name).invoke(object);}
    private static void release(Object object){if(object!=null)try{call(object,"release");}catch(Exception e){}catch(LinkageError e){}}
    static void run(Object original,StringBuilder report,SonyAcceleration.Observer observer){
        run(original,report,observer,null);
    }
    static void run(Object original,StringBuilder report,SonyAcceleration.Observer observer,java.io.File card){
        Object processor=null,buffer=null,image=null;
        try{
            Class<?> dsp=Class.forName("com.sony.scalar.hardware.DSP");
            Class<?> memory=Class.forName("com.sony.scalar.hardware.DeviceMemory");
            String device=(String)Class.forName("com.sony.scalar.hardware.DSP$DeviceType").getField("SONY_DI_DSP").get(null);
            mark(report,"Buffer probe: creating "+device,observer);
            processor=dsp.getMethod("createProcessor",String.class).invoke(null,device);
            if(processor==null)throw new IllegalStateException("DSP returned no processor");
            mark(report,"Buffer probe: allocating 4096-byte scratch buffer",observer);
            buffer=dsp.getMethod("createBuffer",int.class).invoke(processor,4096);
            if(buffer==null||!Boolean.TRUE.equals(call(buffer,"isValid")))throw new IllegalStateException("No valid scratch buffer");
            int capacity=((Integer)call(buffer,"getSize")).intValue();
            if(capacity<4096)throw new IllegalStateException("Scratch capacity too small: "+capacity);
            byte[] pattern=new byte[4096],received=new byte[4096];
            for(int i=0;i<pattern.length;i++)pattern[i]=(byte)((i*37+19)^ (i>>>3));
            mark(report,"Buffer probe: writing scratch pattern",observer);
            int written=((Integer)buffer.getClass().getMethod("write",byte[].class).invoke(buffer,(Object)pattern)).intValue();
            mark(report,"Buffer probe: reading scratch pattern",observer);
            int read=((Integer)buffer.getClass().getMethod("read",byte[].class).invoke(buffer,(Object)received)).intValue();
            if(written!=4096||read!=4096||!Arrays.equals(pattern,received))throw new IllegalStateException("Scratch transfer mismatch; write="+written+", read="+read);
            mark(report,"Scratch round trip: PASS (4096 bytes)",observer);
            byte[] replacement=new byte[96],slice=new byte[80];
            for(int i=0;i<replacement.length;i++)replacement[i]=(byte)(255-i);
            mark(report,"Buffer probe: testing bounded offset transfer",observer);
            written=((Integer)buffer.getClass().getMethod("write",byte[].class,int.class,int.class,int.class).invoke(buffer,replacement,16,64,128)).intValue();
            read=((Integer)buffer.getClass().getMethod("read",byte[].class,int.class,int.class,int.class).invoke(buffer,slice,8,64,128)).intValue();
            if(written!=64||read!=64)throw new IllegalStateException("Offset transfer length mismatch");
            for(int i=0;i<64;i++)if(slice[8+i]!=replacement[16+i])throw new IllegalStateException("Offset transfer byte mismatch");
            for(int i=0;i<slice.length;i++)if((i<8||i>=72)&&slice[i]!=0)throw new IllegalStateException("Offset read changed bytes outside target");
            mark(report,"Offset round trip: PASS (64 bytes, scratch offset 128)",observer);
            mark(report,"Buffer probe: allocating separate 64x48 image",observer);
            image=dsp.getMethod("createImage",int.class,int.class).invoke(processor,64,48);
            if(image==null||!Boolean.TRUE.equals(call(image,"isValid")))throw new IllegalStateException("No valid allocated image");
            if(image==original)throw new IllegalStateException("Allocated image aliases original");
            describe(processor,image,"Allocated image",memory,report,observer);
            describe(processor,original,"Original image (read-only)",memory,report,observer);
            System.arraycopy(replacement,16,pattern,128,64);
            SonyNativeSamples.run(processor,buffer,original,pattern,memory,report,observer);
            if(card!=null){
                final Object[] owned={image,buffer,processor};
                Runnable releaseProbe=new Runnable(){public void run(){for(int i=0;i<owned.length;i++){Object object=owned[i];owned[i]=null;release(object);}}};
                try{SonyWriteProbe.run(processor,buffer,original,memory,card,report,observer,releaseProbe);}
                finally{releaseProbe.run();image=null;buffer=null;processor=null;}
            }
            mark(report,"Selected original pixels not written; writes limited to owned test buffers",observer);
        }catch(Exception e){mark(report,"Buffer probe FAILED: "+SonyGallery.error(e),observer);}
         catch(LinkageError e){mark(report,"Buffer probe unavailable: "+e,observer);}
         catch(OutOfMemoryError e){mark(report,"Buffer probe ran out of memory",observer);}
        finally{
            mark(report,"Buffer probe: releasing allocated image",observer);release(image);
            mark(report,"Buffer probe: releasing scratch buffer",observer);release(buffer);
            mark(report,"Buffer probe: releasing processor",observer);release(processor);
        }
    }
    private static void describe(Object processor,Object image,String label,Class<?> memory,StringBuilder report,SonyAcceleration.Observer observer)throws Exception{
        mark(report,label+" class: "+image.getClass().getName(),observer);
        try{mark(report,label+" SDK format tag: "+call(image,"getImageFormat"),observer);}catch(NoSuchMethodException e){mark(report,label+" has no public image-format accessor",observer);}
        for(String key:KEYS){
            mark(report,"Query "+label+": "+key,observer);
            Object value=processor.getClass().getMethod("getProperty",memory,String.class).invoke(processor,image,key);
            mark(report,label+" "+key+" = "+value,observer);
        }
        boolean readable=false,writable=false;
        for(java.lang.reflect.Method method:image.getClass().getMethods()){
            if(method.getName().equals("read")&&Arrays.equals(method.getParameterTypes(),new Class<?>[]{byte[].class}))readable=true;
            if(method.getName().equals("write")&&Arrays.equals(method.getParameterTypes(),new Class<?>[]{byte[].class}))writable=true;
        }
        mark(report,label+" public byte[] access: read="+readable+", write="+writable,observer);
    }
}
