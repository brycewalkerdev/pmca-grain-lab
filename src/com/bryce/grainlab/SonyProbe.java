package com.bryce.grainlab;

import android.content.ContentResolver;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Bounds the UI wait, without pretending Java can cancel Sony's synchronous native calls. */
final class SonyProbe {
    private static boolean active;
    static synchronized boolean busy(){return active;}
    private static synchronized boolean acquire(){if(active)return false;active=true;return true;}
    private static synchronized void complete(){active=false;}
    static String run(ContentResolver resolver,File source,File card,String header){return run(resolver,source,card,header,30000);}
    static String run(final ContentResolver resolver,final File source,final File card,final String header,long timeout){
        if(!acquire())return "Previous Sony test is still running. Restart the camera before retrying.";
        final CountDownLatch finished=new CountDownLatch(1);
        final String[] result=new String[1];
        final class Trace implements SonyAcceleration.Observer {
            volatile String latest="Starting Sony test",writeStatus="Report not written yet";
            volatile boolean timedOut,ended;
            public void progress(String snapshot){
                latest=header+"\nSelected image: "+source.getPath()+"\n\n"+snapshot;
                writeStatus=SonyCameraSave.writeReport(new File(card,"GLTEST.TXT"),latest+(timedOut?"\nUI wait timed out; native test continued.\n":ended?"":"\nTest in progress.\n"));
            }
        }
        final Trace trace=new Trace();
        Thread thread=new Thread(new Runnable(){public void run(){
            try{
                trace.progress("Starting Sony test\n");
                String report=SonyAcceleration.run(resolver,source,trace,card);
                trace.ended=true;
                trace.progress(report+"\nTest finished.\n");
                result[0]=trace.latest+"\n"+trace.writeStatus;
            }catch(Exception e){result[0]=trace.latest+"\n"+SonyGallery.error(e);}
             catch(LinkageError e){result[0]=trace.latest+"\n"+e;}
             catch(OutOfMemoryError e){result[0]=trace.latest+"\nSony test ran out of memory";}
            finally{complete();finished.countDown();}
        }},"grain-sony-probe");
        thread.setDaemon(true);thread.start();
        try{
            if(finished.await(timeout,TimeUnit.MILLISECONDS))return result[0];
        }catch(InterruptedException e){Thread.currentThread().interrupt();}
        trace.timedOut=true;
        return trace.latest+"\n"+trace.writeStatus+"\nWait timed out. Sony call may still be running. Restart the camera before another Sony test or save.";
    }
}
