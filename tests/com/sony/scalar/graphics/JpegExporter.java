package com.sony.scalar.graphics;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
public final class JpegExporter {
    interface onEventListener {
        void onEvent(int code,InputStream stream);void waitEvent();InputStream getInputStream();
        Object getListener();void setListener(Object listener);
    }
    private static void EncodeImage(OptimizedImage image,Options opts,boolean needStream,int media,onEventListener listener){
        if(!image.isValid()||!needStream||media!=0)throw new AssertionError("Native stream callback contract");
        listener.onEvent(code,code==0?new ByteArrayInputStream(new byte[100]):null);
    }
    public static int streamsClosed,releases,code;public static boolean didSave;
    public static java.io.File card;
    public interface onExportEventListener {void onExported(int code);}
    public void encode(OptimizedImage image,String media,Options opts,onExportEventListener listener)throws java.io.IOException {
        if(!image.isValid()||!image.uploaded||!media.equals("abcd")||opts.quality!=2)throw new AssertionError("Sony camera export contract");
        if(code==0){java.io.File folder=new java.io.File(card,"DCIM/100MSDCF");folder.mkdirs();java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(folder,"DSC00002.JPG"));out.write(1);out.close();didSave=true;}
        listener.onExported(code);
    }
    public static class Options {public static final int FINE=2;public int quality=1;}
    public InputStream encode(OptimizedImage image,Options options){
        if(!image.isValid()||options.quality!=2)throw new AssertionError("JPEG stream contract");
        return new ByteArrayInputStream(new byte[4096]){public void close(){streamsClosed++;}};
    }
    public void release(){releases++;}
}
