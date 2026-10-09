package com.bryce.grainlab;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.SystemClock;
import java.io.File;
import java.lang.reflect.Method;
import java.util.Timer;
import java.util.TimerTask;

/** Sony's actual AV index, not Android MediaStore. No arbitrary inserts or raw DB writes. */
final class SonyGallery {
    static final class Result {
        final boolean indexed, saved; final String detail; final long millis;
        Result(boolean indexed,String detail,long millis){this(indexed,indexed,detail,millis);}
        Result(boolean indexed,boolean saved,String detail,long millis){this.indexed=indexed;this.saved=saved;this.detail=detail;this.millis=millis;}
    }
    private volatile String activeMedia;
    private volatile Class<?> imagesClass;
    Result refresh(ContentResolver resolver,File file){
        long start=SystemClock.elapsedRealtime();
        final ContentResolver cr=resolver;
        Timer timeout=new Timer("grain-index-timeout",true);
        try{
            Class<?> store=Class.forName("com.sony.scalar.provider.AvindexStore");
            String[] ids=(String[])store.getMethod("getExternalMediaIds").invoke(null);
            if(ids==null||ids.length!=1)return new Result(false,"Expected one mounted external camera medium",SystemClock.elapsedRealtime()-start);
            imagesClass=Class.forName("com.sony.scalar.provider.AvindexStore$Images");activeMedia=ids[0];
            timeout.schedule(new TimerTask(){public void run(){SonyGallery.this.cancel(cr);}},20000);
            boolean updated=Boolean.TRUE.equals(imagesClass.getMethod("waitAndUpdateDatabase",ContentResolver.class,String.class).invoke(null,resolver,activeMedia));
            boolean found=updated&&contains(resolver,activeMedia,file);
            return new Result(found,found?"Sony AV index confirmed":"Sony refresh returned "+updated+"; image not confirmed",SystemClock.elapsedRealtime()-start);
        }catch(Exception e){return new Result(false,error(e),SystemClock.elapsedRealtime()-start);}
         catch(LinkageError e){return new Result(false,e.toString(),SystemClock.elapsedRealtime()-start);}
        finally{activeMedia=null;timeout.cancel();}
    }
    boolean contains(ContentResolver resolver,String media,File file)throws Exception{
        int folder=GrainFiles.folderNumber(file),number=GrainFiles.fileNumber(file);
        if(folder<0||number<0)return false;
        Class<?> type=Class.forName("com.sony.scalar.provider.AvindexStore$Images$Media");
        Uri uri=(Uri)type.getMethod("getContentUri",String.class).invoke(null,media);
        Cursor cursor=resolver.query(uri,new String[]{"dcf_folder_number","dcf_file_number","exist_jpeg"},null,null,null);
        if(cursor==null)return false;
        try{
            int f=cursor.getColumnIndex("dcf_folder_number"),n=cursor.getColumnIndex("dcf_file_number"),j=cursor.getColumnIndex("exist_jpeg");
            if(f<0||n<0||j<0)return false;
            while(cursor.moveToNext())if(cursor.getInt(f)==folder&&cursor.getInt(n)==number&&cursor.getInt(j)!=0)return true;
            return false;
        }finally{cursor.close();}
    }
    void cancel(ContentResolver resolver){
        String media=activeMedia;Class<?> type=imagesClass;if(media==null||type==null)return;
        try{Method m=type.getMethod("cancelWaitAndUpdateDatabase",ContentResolver.class,String.class);m.invoke(null,resolver,media);}catch(Exception e){}catch(LinkageError e){}
    }
    static String imageId(ContentResolver resolver,File file)throws Exception{
        String[] ids=(String[])Class.forName("com.sony.scalar.provider.AvindexStore").getMethod("getExternalMediaIds").invoke(null);
        if(ids==null||ids.length!=1)throw new IllegalStateException("Expected one mounted external camera medium");
        Uri uri=(Uri)Class.forName("com.sony.scalar.provider.AvindexStore$Images$Media").getMethod("getContentUri",String.class).invoke(null,ids[0]);
        Cursor cursor=resolver.query(uri,new String[]{"_data","dcf_folder_number","dcf_file_number","exist_jpeg"},null,null,null);
        if(cursor==null)return null;
        try{
            int data=cursor.getColumnIndex("_data"),folder=cursor.getColumnIndex("dcf_folder_number"),number=cursor.getColumnIndex("dcf_file_number"),jpeg=cursor.getColumnIndex("exist_jpeg");
            if(data<0||folder<0||number<0||jpeg<0)throw new IllegalStateException("Sony catalog columns unavailable");
            while(cursor.moveToNext())if(cursor.getInt(folder)==GrainFiles.folderNumber(file)&&cursor.getInt(number)==GrainFiles.fileNumber(file)&&cursor.getInt(jpeg)!=0){
                String id=cursor.getString(data);return id!=null&&id.startsWith("avindex://")?id:null;
            }
            return null;
        }finally{cursor.close();}
    }
    static String error(Throwable e){
        if(e instanceof java.lang.reflect.InvocationTargetException&&e.getCause()!=null)e=e.getCause();return e.toString();
    }
}
