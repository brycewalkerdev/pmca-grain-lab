package com.bryce.grainlab;

import android.content.ContentResolver;
import java.io.File;

/** Repair only the newly saved Sony copy through the firmware's own rotation path. */
final class SonyOrientation {
    interface Tags {int read(File file)throws Exception;}
    interface Rotator {boolean rotate(String id,int relativeAngle)throws Exception;}
    static final Tags EXIF=new Tags(){public int read(File file)throws Exception{
        Class<?> type=Class.forName("android.media.ExifInterface");Object exif=type.getConstructor(String.class).newInstance(file.getPath());
        return ((Integer)type.getMethod("getAttributeInt",String.class,int.class).invoke(exif,"Orientation",1)).intValue();
    }};
    static final Rotator SONY=new Rotator(){public boolean rotate(String id,int angle)throws Exception{
        Class<?> type=Class.forName("com.sony.scalar.database.avindex.wrapper.InfraScalarMprWrapper");
        return Boolean.TRUE.equals(type.getMethod("rotateImage",String.class,int.class).invoke(null,id,angle));
    }};
    private static int degrees(int tag){switch(tag){case 1:return 0;case 6:return 90;case 3:return 180;case 8:return 270;default:return -1;}}
    static int relativeAngle(int current,int wanted){
        int a=degrees(current),b=degrees(wanted);if(a<0||b<0)return -2;
        switch((b-a+360)%360){case 0:return -1;case 90:return 0;case 180:return 1;default:return 3;}
    }
    static String sync(ContentResolver resolver,File saved,File backup){
        try{
            int wanted=EXIF.read(backup),current=EXIF.read(saved);
            int relative=relativeAngle(current,wanted);
            if(relative==-1)return "Orientation already matches EXIF "+wanted;
            if(relative==-2)return "Orientation not changed: mirrored/unknown EXIF orientation "+current+" -> "+wanted;
            String id=SonyGallery.imageId(resolver,saved);if(id==null)return "Orientation not changed: new Sony catalog ID unavailable";
            return apply(saved,id,current,wanted,EXIF,SONY);
        }catch(Exception e){return "Orientation repair failed: "+SonyGallery.error(e);}
         catch(LinkageError e){return "Orientation API unavailable: "+e;}
    }
    static String apply(File saved,String id,int current,int wanted,Tags tags,Rotator rotator)throws Exception{
        int angle=relativeAngle(current,wanted);
        if(angle==-1)return "Orientation already matches EXIF "+wanted;
        if(angle==-2)return "Orientation not changed: unsupported EXIF transform";
        if(!rotator.rotate(id,angle))return "Sony rotation failed: EXIF "+current+" -> "+wanted;
        int after=tags.read(saved);
        return after==wanted?"Sony orientation confirmed: EXIF "+current+" -> "+after:
            "Sony rotation accepted; EXIF readback "+after+" differs from expected "+wanted+"; playback needs verification";
    }
}
