package com.bryce.grainlab;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Arrays;

/** Writes only a separately scaled test image; original pixels are not written. */
final class SonyWriteProbe {
    interface Transfer {
        int write(Object memory,int offset,byte[] data)throws Exception;
        byte[] read(Object memory,int offset,int count)throws Exception;
        byte[] publicRead(Object scratch,int offset,int count)throws Exception;
    }
    private static void mark(StringBuilder report,String text,SonyAcceleration.Observer observer){report.append(text).append('\n');if(observer!=null)observer.progress(report.toString());}
    private static int clamp(long value){return (int)Math.max(0,Math.min(255,value));}
    static boolean separate(String source,int sourceSize,String target,int targetSize){
        if(source==null||target==null||sourceSize<=0||targetSize<=0)return false;
        long a=Long.parseLong(source)&0x3fffffffL,b=Long.parseLong(target)&0x3fffffffL;
        return a+sourceSize<=0x40000000L&&b+targetSize<=0x40000000L&&(a+sourceSize<=b||b+targetSize<=a);
    }
    static byte[] bars(int canvasWidth){
        if(canvasWidth<64||canvasWidth>128||(canvasWidth&1)!=0)throw new IllegalArgumentException("Unsupported test canvas");
        int[][] colors={{255,255,255},{255,255,0},{0,255,255},{0,255,0},{255,0,255},{255,0,0},{0,0,255},{0,0,0}};
        byte[] row=new byte[canvasWidth*2];
        for(int x=0;x<canvasWidth;x+=2){
            int[] c=colors[x<64?x/8:7];int r=c[0],g=c[1],b=c[2];
            int y=clamp(Math.round(.299*r+.587*g+.114*b)),u=clamp(Math.round(128-.168736*r-.331264*g+.5*b)),v=clamp(Math.round(128+.5*r-.418688*g-.081312*b));
            row[2*x]=(byte)u;row[2*x+1]=(byte)y;row[2*x+2]=(byte)v;row[2*x+3]=(byte)y;
        }return row;
    }
    static void fill(Transfer io,Object scratch,Object allocated,byte[] row,StringBuilder report,SonyAcceleration.Observer observer)throws Exception{
        mark(report,"Native write probe: calibrating owned scratch buffer",observer);
        byte[] pattern=new byte[64];for(int i=0;i<64;i++)pattern[i]=(byte)(i^0xa5);
        if(io.write(scratch,256,pattern)!=64||!Arrays.equals(pattern,io.publicRead(scratch,256,64)))throw new IllegalStateException("Native/public write mismatch; image writes refused");
        mark(report,"Native/public scratch write agreement: PASS",observer);
        mark(report,"Writing UYVY color bars to separate 64x48 image",observer);
        for(int y=0;y<48;y++){
            if(y==0||y==24||y==47)mark(report,"Writing allocated test row "+y,observer);
            if(io.write(allocated,y*row.length,row)!=row.length)throw new IllegalStateException("Image row write incomplete");
        }
        for(int y:new int[]{0,24,47})if(!Arrays.equals(row,io.read(allocated,y*row.length,row.length)))throw new IllegalStateException("Image write/readback mismatch");
        mark(report,"Allocated image row readback: PASS",observer);
    }
    static int[] patch(Transfer io,Object scratch,Object target,int[] v,StringBuilder report,SonyAcceleration.Observer observer)throws Exception{
        calibrate(io,scratch,report,observer);
        return patchCalibrated(io,target,v,report,observer);
    }
    private static void calibrate(Transfer io,Object scratch,StringBuilder report,SonyAcceleration.Observer observer)throws Exception{
        byte[] pattern=new byte[64];for(int i=0;i<64;i++)pattern[i]=(byte)(i^0xa5);
        mark(report,"Native write probe: calibrating owned scratch buffer",observer);
        if(io.write(scratch,256,pattern)!=64||!Arrays.equals(pattern,io.publicRead(scratch,256,64)))throw new IllegalStateException("Native/public write mismatch; image writes refused");
        mark(report,"Native/public scratch write agreement: PASS",observer);
    }
    private static int[] patchCalibrated(Transfer io,Object target,int[] v,StringBuilder report,SonyAcceleration.Observer observer)throws Exception{
        if(v.length!=8||v[0]<64||v[1]<32||v[2]<=0||v[3]<=0||(v[2]&1)!=0||v[4]<0||v[5]<0||v[6]<0||(v[5]&1)!=0||v[0]+(long)v[5]>v[2]||v[1]+(long)v[6]>v[3]||2L*v[2]*v[3]+v[4]>v[7])throw new IllegalStateException("Scaled image descriptor mismatch");
        int x=((v[0]-64)/2)&~7,y=((v[1]-32)/2)&~7;byte[] row=bars(64);
        mark(report,"Scaled template: "+v[0]+"x"+v[1]+", canvas="+v[2]+"x"+v[3]+", data-offset="+v[4]+", patch="+x+","+y+" 64x32",observer);
        for(int i=0;i<32;i++){
            long offset=v[4]+2L*((y+i+v[6])*(long)v[2]+x+v[5]);
            if(offset<0||offset+row.length>v[7])throw new IllegalStateException("Patch exceeds allocation");
            if(i==0||i==16||i==31)mark(report,"Writing scaled test patch row "+i,observer);
            if(io.write(target,(int)offset,row)!=row.length)throw new IllegalStateException("Patch write incomplete");
        }
        for(int i:new int[]{0,16,31}){
            int offset=(int)(v[4]+2L*((y+i+v[6])*(long)v[2]+x+v[5]));
            if(!Arrays.equals(row,io.read(target,offset,row.length)))throw new IllegalStateException("Patch readback mismatch");
        }
        mark(report,"Scaled image patch readback: PASS",observer);return new int[]{x,y};
    }
    static void run(Object processor,Object scratch,Object original,Class<?> memory,File card,StringBuilder report,SonyAcceleration.Observer observer,Runnable releaseProbeResources){
        Object filter=null,target=null,exporter=null;InputStream stream=null;FileOutputStream file=null;
        try{
            if(!NativeGrain.AVAILABLE){mark(report,"Native write probe unavailable",observer);return;}
            mark(report,"Native write probe: creating scaler-owned image template",observer);
            Class<?> optimized=Class.forName("com.sony.scalar.graphics.OptimizedImage"),scale=Class.forName("com.sony.scalar.graphics.imagefilter.ScaleImageFilter");
            filter=scale.newInstance();
            scale.getMethod("setSource",optimized,boolean.class).invoke(filter,original,false);
            int width=((Integer)original.getClass().getMethod("getWidth").invoke(original)).intValue()/2,height=((Integer)original.getClass().getMethod("getHeight").invoke(original)).intValue()/2;
            scale.getMethod("setDestSize",int.class,int.class).invoke(filter,width,height);
            mark(report,"Executing write-probe scaler",observer);
            if(!Boolean.TRUE.equals(scale.getMethod("execute").invoke(filter)))throw new IllegalStateException("Write-probe scale failed");
            target=scale.getMethod("getOutput").invoke(filter);
            if(target==null||target==original||!Boolean.TRUE.equals(target.getClass().getMethod("isValid").invoke(target)))throw new IllegalStateException("No separate valid scaler output");
            Object sourceAddress=processor.getClass().getMethod("getProperty",memory,String.class).invoke(processor,original,"memory-address");
            Object targetAddress=processor.getClass().getMethod("getProperty",memory,String.class).invoke(processor,target,"memory-address");
            int sourceSize=Integer.parseInt((String)processor.getClass().getMethod("getProperty",memory,String.class).invoke(processor,original,"memory-size"));
            int targetSize=Integer.parseInt((String)processor.getClass().getMethod("getProperty",memory,String.class).invoke(processor,target,"memory-size"));
            if(!separate((String)sourceAddress,sourceSize,(String)targetAddress,targetSize))throw new IllegalStateException("Scaled image allocation overlaps original or separation is unverified; writes refused");
            mark(report,"Scaled template memory differs from original: PASS",observer);
            String[] keys={"image-target-width","image-target-height","image-canvas-width","image-canvas-height","image-data-offset","image-target-x-offset","image-target-y-offset","memory-size"};
            int[] v=new int[keys.length];for(int i=0;i<v.length;i++)v[i]=Integer.parseInt((String)processor.getClass().getMethod("getProperty",memory,String.class).invoke(processor,target,keys[i]));
            if(v[0]!=width||v[1]!=height)throw new IllegalStateException("Scaled target dimensions differ from requested template");
            Transfer transfer=new Transfer(){
                public int write(Object memory,int offset,byte[] data){return NativeGrain.sonyWrite(memory,offset,data);}
                public byte[] read(Object memory,int offset,int count){return NativeGrain.sonyRead(memory,offset,count);}
                public byte[] publicRead(Object memory,int offset,int count)throws Exception{
                    byte[] data=new byte[count];int n=((Integer)memory.getClass().getMethod("read",byte[].class,int.class,int.class,int.class).invoke(memory,data,0,count,offset)).intValue();
                    if(n!=count)throw new IllegalStateException("Public scratch read incomplete");return data;
                }
            };
            calibrate(transfer,scratch,report,observer);
            mark(report,"Releasing DSP probe resources before JPEG encoding",observer);releaseProbeResources.run();
            Class<?> type=Class.forName("com.sony.scalar.graphics.JpegExporter"),options=Class.forName("com.sony.scalar.graphics.JpegExporter$Options");
            exporter=type.newInstance();Object settings=options.newInstance();options.getField("quality").setInt(settings,options.getField("FINE").getInt(null));
            mark(report,"Encoding unchanged scaler template control",observer);
            stream=SonyJpegStream.encode(exporter,target,settings,report,observer);
            byte[] control=new byte[8192];long controlBytes=0;int controlRead;while((controlRead=stream.read(control))!=-1)controlBytes+=controlRead;
            stream.close();stream=null;mark(report,"Unchanged template control: PASS ("+controlBytes+" bytes)",observer);
            patchCalibrated(transfer,target,v,report,observer);
            mark(report,"Encoding patched scaler-owned image",observer);
            stream=SonyJpegStream.encode(exporter,target,settings,report,observer);
            mark(report,"Writing root GLBUF.JPG test artifact",observer);
            file=new FileOutputStream(new File(card,"GLBUF.JPG"));byte[] bytes=new byte[8192];int count;long total=0;
            while((count=stream.read(bytes))!=-1){file.write(bytes,0,count);total+=count;}
            file.getFD().sync();mark(report,"Color-bar test JPEG exported: GLBUF.JPG ("+total+" bytes); playback database untouched",observer);
        }catch(Exception e){mark(report,"Native write probe FAILED: "+SonyGallery.error(e),observer);}
         catch(LinkageError e){mark(report,"Native write bridge unavailable: "+e,observer);}
        finally{
            if(file!=null)try{file.close();}catch(Exception e){}
            if(stream!=null)try{stream.close();}catch(Exception e){}
            if(exporter!=null)try{exporter.getClass().getMethod("release").invoke(exporter);}catch(Exception e){}catch(LinkageError e){}
            if(filter!=null)try{filter.getClass().getMethod("release").invoke(filter);}catch(Exception e){}catch(LinkageError e){}
            if(target!=null&&target!=original)try{target.getClass().getMethod("release").invoke(target);}catch(Exception e){}catch(LinkageError e){}
        }
    }
}
