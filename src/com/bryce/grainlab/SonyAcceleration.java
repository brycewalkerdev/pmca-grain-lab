package com.bryce.grainlab;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;

/** On-camera benchmark of firmware paths. Does not save images or change capture settings. */
final class SonyAcceleration {
    interface Observer {void progress(String report);}
    private static void mark(StringBuilder report,String stage,Observer observer){report.append(stage).append('\n');if(observer!=null)observer.progress(report.toString());}
    private static Object call(Object object,String method)throws Exception{return object.getClass().getMethod(method).invoke(object);}
    private static void release(Object object){if(object==null)return;try{call(object,"release");}catch(Exception e){}catch(LinkageError e){}}
    static String run(File source){
        return run(null,source);
    }
    static String run(android.content.ContentResolver resolver,File source){
        return run(resolver,source,null);
    }
    static String run(android.content.ContentResolver resolver,File source,Observer observer){
        return run(resolver,source,observer,null);
    }
    static String run(android.content.ContentResolver resolver,File source,Observer observer,File card){
        StringBuilder report=new StringBuilder();Object image=null;
        try{
            long started=System.nanoTime();
            if(resolver!=null){
                mark(report,"Reading Sony catalog identifier",observer);
                String id=SonyGallery.imageId(resolver,source);
                if(id==null)throw new IllegalStateException("Selected JPEG has no Sony AV-index identifier; select an original camera photo for this test");
                mark(report,"Calling Sony MAIN decoder: "+id,observer);
                image=SonyImageReader.decodeUri(id,report);
                if(image==null)throw new IllegalStateException("Sony MAIN decode failed using catalog identifier");
            }else image=SonyImageReader.decodeMain(source.getAbsolutePath(),report);
            report.append("MAIN decode: ").append((System.nanoTime()-started)/1000000).append(" ms\n");
            report.append("Dimensions: ").append(call(image,"getWidth")).append('x').append(call(image,"getHeight")).append('\n');
            testFilter(image,"ScaleImageFilter",true,report,observer);
            mark(report,"SoftFocusImageFilter: skipped (execute hung in A5000 camera test)",observer);
            SonyBufferProbe.run(image,report,observer,card);
        }catch(Exception e){report.append(SonyGallery.error(e)).append('\n');}
         catch(LinkageError e){report.append(e).append('\n');}
         catch(OutOfMemoryError e){report.append("Sony decoder ran out of memory\n");}
        finally{mark(report,"Releasing MAIN image",observer);release(image);}
        return report.toString();
    }
    private static void testFilter(Object image,String name,boolean scale,StringBuilder report,Observer observer){
        Object filter=null,output=null,exporter=null;InputStream stream=null;
        try{
            mark(report,"Creating "+name,observer);
            Class<?> type=Class.forName("com.sony.scalar.graphics.imagefilter."+name);filter=type.newInstance();
            mark(report,"Checking support for "+name,observer);
            if(!Boolean.TRUE.equals(call(filter,"isSupported"))){report.append(name).append(": unsupported\n");return;}
            Class<?> optimized=Class.forName("com.sony.scalar.graphics.OptimizedImage");
            mark(report,"Configuring "+name,observer);
            type.getMethod("setSource",optimized,boolean.class).invoke(filter,image,false);
            if(scale){int w=((Integer)call(image,"getWidth")).intValue(),h=((Integer)call(image,"getHeight")).intValue();type.getMethod("setDestSize",int.class,int.class).invoke(filter,Math.max(1,w/2),Math.max(1,h/2));}
            else type.getMethod("setEffectLevel",int.class).invoke(filter,1);
            long started=System.nanoTime();
            mark(report,"Executing "+name,observer);
            if(!Boolean.TRUE.equals(call(filter,"execute")))throw new IllegalStateException("Filter execute returned false");
            output=call(filter,"getOutput");
            if(output==null||!Boolean.TRUE.equals(call(output,"isValid")))throw new IllegalStateException("No valid filter output");
            report.append(name).append(": ").append((System.nanoTime()-started)/1000000).append(" ms, ").append(call(output,"getWidth")).append('x').append(call(output,"getHeight")).append('\n');
            Class<?> exportType=Class.forName("com.sony.scalar.graphics.JpegExporter"),opts=Class.forName("com.sony.scalar.graphics.JpegExporter$Options");
            mark(report,"Creating JPEG exporter for "+name,observer);
            exporter=exportType.newInstance();Object settings=opts.newInstance();opts.getField("quality").setInt(settings,opts.getField("FINE").getInt(null));
            mark(report,"Calling JPEG stream encoder for "+name,observer);
            started=System.nanoTime();stream=(InputStream)exportType.getMethod("encode",optimized,opts).invoke(exporter,output,settings);
            if(stream==null)throw new IllegalStateException("No JPEG stream");
            mark(report,"Reading JPEG stream for "+name,observer);
            long bytes=0;byte[] buffer=new byte[32768];int count;while((count=stream.read(buffer))!=-1)bytes+=count;
            report.append("JPEG stream: ").append((System.nanoTime()-started)/1000000).append(" ms, ").append(bytes).append(" bytes\n");
        }catch(Exception e){report.append(name).append(": ").append(SonyGallery.error(e)).append('\n');}
         catch(LinkageError e){report.append(name).append(": ").append(e).append('\n');}
         catch(OutOfMemoryError e){report.append(name).append(": out of memory\n");}
        finally{mark(report,"Releasing "+name+" resources",observer);if(stream!=null)try{stream.close();}catch(Exception e){}release(exporter);release(filter);if(output!=image)release(output);}
    }
}
