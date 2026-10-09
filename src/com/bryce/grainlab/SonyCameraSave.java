package com.bryce.grainlab;

import android.content.ContentResolver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Export through Sony's camera saver; a database refresh cannot import arbitrary files. */
final class SonyCameraSave {
    private static boolean active;
    static synchronized boolean busy(){return active;}
    private static synchronized boolean acquire(){if(active)return false;active=true;return true;}
    private static synchronized void done(){active=false;}
    private static List<File> allImages(File card)throws java.io.IOException{
        List<File> files=new ArrayList<File>(GrainFiles.list(card,false));
        files.addAll(GrainFiles.list(card,true));return files;
    }
    static String report(File processed,String text){
        // Use the same proven-writable folder as the processed JPEG, not a new root
        // directory. Flush before USB attachment; report failures in Save timings.
        File file=new File(processed.getParentFile(),"GLSAVE.TXT");
        return writeReport(file,text);
    }
    static String writeReport(File file,String text){
        try{
            FileOutputStream stream=new FileOutputStream(file);
            try{stream.write(text.getBytes("UTF-8"));stream.getFD().sync();}
            finally{stream.close();}
            return "Report: "+file.getPath();
        }catch(Exception e){return "Diagnostic write failed: "+SonyGallery.error(e);}
    }
    private static void release(Object object){
        if(object!=null)try{object.getClass().getMethod("release").invoke(object);}catch(Exception e){}catch(LinkageError e){}
    }
    static SonyGallery.Result save(ContentResolver resolver,File card,File source,File processed,String build){return save(resolver,card,source,processed,build,SonyImageUpload.NATIVE);}
    static SonyGallery.Result save(final ContentResolver resolver,final File card,final File source,final File processed,final String build,final SonyImageUpload.Pixels pixels){
        final long start=SystemClock.elapsedRealtime();
        if(!acquire())return new SonyGallery.Result(false,"Previous Sony export still running; do not retry yet",0);
        final CountDownLatch finished=new CountDownLatch(1);
        final SonyGallery.Result[] result=new SonyGallery.Result[1];
        final HandlerThread thread=new HandlerThread("grain-camera-export");
        thread.start();
        // Sony constructs its callback Handler on the calling Looper. Keep that Looper and
        // native resources alive after a timeout until the actual export callback arrives.
        final class Export implements Runnable,SonyImageUpload.Progress {
            Object image,exporter;SonyImageUpload.Frame frame;
            final Set<String> before=new HashSet<String>();
            String media;
            boolean completed;
            boolean cameraSaved;
            int verificationAttempts;
            final StringBuilder trace=new StringBuilder("Build: ").append(build).append("\nCard: ").append(card).append("\nProcessed: ").append(processed).append('\n');
            public void stage(String detail){trace.append(detail).append('\n');report(processed,trace.toString());}
            void finish(boolean success,String detail){
                if(completed)return;completed=true;
                release(exporter);if(frame!=null)frame.release();
                trace.append("Sony saved: ").append(cameraSaved||success).append("\nCatalog confirmed: ").append(success).append('\n').append(detail).append("\nElapsed: ").append(SystemClock.elapsedRealtime()-start).append(" ms\n");
                String diagnostic=report(processed,trace.toString());
                result[0]=new SonyGallery.Result(success,cameraSaved||success,detail+"; "+diagnostic,SystemClock.elapsedRealtime()-start);
                done();finished.countDown();thread.quit();
            }
            void exported(int code){
                cameraSaved=code==0;
                stage("Export callback: "+code);
                if(code!=0){finish(false,"Sony export callback "+code+" (save failed); grain backup retained");return;}
                try{
                    File found=null;int count=0;
                    SonyGallery gallery=new SonyGallery();
                    StringBuilder candidates=new StringBuilder();
                    for(File candidate:allImages(card)){
                        if(!before.contains(candidate.getCanonicalPath()))candidates.append(GrainFiles.relative(card,candidate)).append(';');
                        if(!before.contains(candidate.getCanonicalPath())&&new SonyGallery().contains(resolver,media,candidate)){
                            found=candidate;count++;
                        }
                    }
                    if(count==0&&verificationAttempts++<5){
                        stage("Waiting briefly for native file/catalog publication");
                        new Handler(thread.getLooper()).postDelayed(new Runnable(){public void run(){exported(0);}},400);return;
                    }
                    String orientation="";
                    if(count==1){stage("Reconciling native playback orientation");orientation="; "+SonyOrientation.sync(resolver,found,processed);stage(orientation);}
                    finish(count==1,count==1?"Sony export callback "+code+"; AV index confirmed: "+GrainFiles.relative(card,found)+orientation+"; grain backup retained":
                        "Sony export callback "+code+(cameraSaved?" (save succeeded)":" (save failed)")+"; catalog readback not confirmed ("+count+"). New files: "+candidates+"; grain backup retained");
                }catch(Exception e){finish(false,"Sony export callback "+code+"; verification failed: "+SonyGallery.error(e));}
                 catch(LinkageError e){finish(false,e.toString());}
                 catch(OutOfMemoryError e){finish(false,"Sony export callback "+code+"; catalog verification ran out of memory");}
            }
            public void run(){
                try{
                    stage("Discovering Sony external media");
                    String[] ids=(String[])Class.forName("com.sony.scalar.provider.AvindexStore").getMethod("getExternalMediaIds").invoke(null);
                    stage("External media IDs: "+java.util.Arrays.toString(ids));
                    if(ids==null||ids.length!=1)throw new IllegalStateException("Expected one mounted external camera medium");
                    media=ids[0];Integer.parseInt(media,16);
                    for(File file:allImages(card))before.add(file.getCanonicalPath());
                    stage("Source: "+source);
                    frame=SonyImageUpload.prepare(resolver,source,processed,pixels,this);
                    image=frame.image;
                    Class<?> type=Class.forName("com.sony.scalar.graphics.JpegExporter"),opts=Class.forName("com.sony.scalar.graphics.JpegExporter$Options");
                    Class<?> listener=Class.forName("com.sony.scalar.graphics.JpegExporter$onExportEventListener");
                    Object settings=opts.newInstance();opts.getField("quality").setInt(settings,opts.getField("FINE").getInt(null));
                    Object callback=Proxy.newProxyInstance(listener.getClassLoader(),new Class<?>[]{listener},new InvocationHandler(){
                        public Object invoke(Object proxy,Method method,Object[] args){
                            if(method.getName().equals("onExported")){exported(((Integer)args[0]).intValue());return null;}
                            if(method.getName().equals("hashCode"))return Integer.valueOf(System.identityHashCode(proxy));
                            if(method.getName().equals("equals"))return Boolean.valueOf(proxy==args[0]);
                            return "Grain Lab export callback";
                        }
                    });
                    exporter=type.newInstance();
                    stage("Submitting Sony JPEG export to media "+media);
                    type.getMethod("encode",Class.forName("com.sony.scalar.graphics.OptimizedImage"),String.class,opts,listener).invoke(exporter,image,media,settings,callback);
                }catch(Exception e){finish(false,"Sony camera save failed: "+SonyGallery.error(e));}
                 catch(LinkageError e){finish(false,e.toString());}
                 catch(OutOfMemoryError e){finish(false,"Sony MAIN decode/export ran out of memory; grain backup retained");}
            }
        }
        new Handler(thread.getLooper()).post(new Export());
        try{
            if(finished.await(90,TimeUnit.SECONDS))return result[0];
            SonyGallery.Result timedOut=new SonyGallery.Result(false,"Sony export callback timed out; may still finish. Grain backup retained; do not retry yet",SystemClock.elapsedRealtime()-start);
            report(processed,"Build: "+build+"\nProcessed: "+processed+"\n"+timedOut.detail+"\n");return timedOut;
        }catch(InterruptedException e){Thread.currentThread().interrupt();return new SonyGallery.Result(false,"Sony export wait interrupted; may still finish",SystemClock.elapsedRealtime()-start);}
    }
}
