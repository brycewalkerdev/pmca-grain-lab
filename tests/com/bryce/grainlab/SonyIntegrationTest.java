package com.bryce.grainlab;
import android.content.ContentResolver;
import android.net.Uri;
import android.database.Cursor;
import java.io.File;
import com.sony.scalar.provider.AvindexStore;
import com.sony.scalar.graphics.OptimizedImage;
import com.sony.scalar.graphics.OptimizedImageFactory;
import com.sony.scalar.graphics.JpegExporter;
import com.sony.scalar.graphics.imagefilter.SoftFocusImageFilter;
public final class SonyIntegrationTest {
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        final boolean[] closed={false};
        ContentResolver resolver=new ContentResolver(){public Cursor query(Uri uri,String[] columns,String selection,String[] params,String sort){
            check(uri.toString().contains("/abcd/images/media"),"firmware media URI");
            final boolean identifiers=java.util.Arrays.asList(columns).contains("_data");
            return new Cursor(){boolean read;public int getColumnIndex(String name){return name.equals("dcf_folder_number")?0:name.equals("dcf_file_number")?1:2;}
                public boolean moveToNext(){if(read)return false;read=true;return true;}public int getInt(int column){return column==0?100:column==1&&JpegExporter.didSave&&!identifiers?2:1;}public String getString(int column){return "avindex://abcd/00000001-default/rsmpoint";}public void close(){closed[0]=true;}};
        }};
        SonyGallery gallery=new SonyGallery();SonyGallery.Result result=gallery.refresh(resolver,new File("DCIM/100GRAIN/GLAB0001.JPG"));
        check(result.indexed&&closed[0]&&"abcd".equals(AvindexStore.observed),"refresh and index readback");
        AvindexStore.update=false;check(!gallery.refresh(resolver,new File("DCIM/100GRAIN/GLAB0001.JPG")).indexed,"refresh failure is not success");AvindexStore.update=true;
        check(!gallery.refresh(resolver,new File("GRAIN/GRN00001.JPG")).indexed,"non-DCF path is not claimed indexed");
        AvindexStore.ids=new String[]{"one","two"};check(!gallery.refresh(resolver,new File("DCIM/100GRAIN/GLAB0001.JPG")).indexed,"ambiguous media rejected");AvindexStore.ids=new String[]{"abcd"};
        String report=SonyAcceleration.run(new File("image.jpg"));
        check(report.contains("MAIN decode")&&report.contains("JPEG stream"),"probe records firmware stages");
        check(OptimizedImage.allocations==OptimizedImage.releases&&JpegExporter.streamsClosed==1&&JpegExporter.releases==1,"probe frees all images and streams");
        check(report.contains("Scratch round trip: PASS")&&report.contains("Offset round trip: PASS")&&report.contains("Original image (read-only) public byte[] access: read=false, write=false"),"scratch transfer, offsets and image access descriptors");
        OptimizedImage untouched=new OptimizedImage(64,48);
        com.sony.scalar.hardware.DeviceBuffer.corrupt=true;
        StringBuilder bufferReport=new StringBuilder();SonyBufferProbe.run(untouched,bufferReport,null);
        check(bufferReport.toString().contains("Scratch transfer mismatch")&&untouched.isValid(),"corruption detected without releasing or writing original image");
        com.sony.scalar.hardware.DeviceBuffer.corrupt=false;untouched.release();
        check(com.sony.scalar.hardware.DSP.allocations==com.sony.scalar.hardware.DSP.releases&&com.sony.scalar.hardware.DeviceBuffer.allocations==com.sony.scalar.hardware.DeviceBuffer.releases&&OptimizedImage.allocations==OptimizedImage.releases,"buffer failure releases only owned resources");
        SoftFocusImageFilter.fail=true;report=SonyAcceleration.run(new File("image.jpg"));
        check(report.contains("SoftFocusImageFilter: skipped")&&!report.contains("Mock execute failure")&&OptimizedImage.allocations==OptimizedImage.releases,"known hanging soft focus is not executed");
        com.sony.scalar.graphics.imagefilter.ScaleImageFilter.fail=true;report=SonyAcceleration.run(new File("image.jpg"));
        check(report.contains("Mock scale execute failure")&&OptimizedImage.allocations==OptimizedImage.releases,"filter exception cleanup");
        com.sony.scalar.graphics.imagefilter.ScaleImageFilter.fail=false;
        OptimizedImageFactory.fail=true;check(SonyAcceleration.run(new File("image.jpg")).contains("no valid image"),"null decode handled");
        OptimizedImageFactory.fail=false;
        File card=java.nio.file.Files.createTempDirectory("sony-save-test").toFile();JpegExporter.card=card;
        new File(card,"DCIM/101GRAIN").mkdirs();new File(card,"DCIM/100GRAIN").mkdirs();
        final File sourcePhoto=new File(card,"DCIM/100MSDCF/DSC00001.JPG");sourcePhoto.getParentFile().mkdirs();java.nio.file.Files.write(sourcePhoto.toPath(),new byte[]{7});
        final SonyImageUpload.Pixels pixels=new SonyImageUpload.Pixels(){public int[] dimensions(File file){return new int[]{3000,2000};}public void upload(Object image,File file,int[] geometry){
            OptimizedImage output=(OptimizedImage)image;check(output.getWidth()==3000&&output.getHeight()==2000,"processed dimensions respected");check(geometry[0]==3000&&geometry[1]==2000,"native geometry reflects processed JPEG");output.uploaded=true;
        }};
        result=SonyCameraSave.save(resolver,card,sourcePhoto,new File(card,"DCIM/101GRAIN/GLAB0001.JPG"),"Host test",pixels);
        check(result.indexed&&result.detail.contains("100MSDCF/DSC00002.JPG")&&!SonyCameraSave.busy(),"processed pixels uploaded before native save and new catalog readback");
        check(java.nio.file.Files.readAllBytes(sourcePhoto.toPath())[0]==7,"original file unchanged");
        result=SonyCameraSave.save(resolver,card,sourcePhoto,new File(card,"processed.jpg"),"Host test",pixels);
        check(!result.indexed&&result.saved,"existing native photo cannot confirm another save");
        final int[] fullUploads={0};
        SonyImageUpload.Pixels fullPixels=new SonyImageUpload.Pixels(){public int[] dimensions(File f){return new int[]{6000,4000};}public void upload(Object image,File f,int[] v){OptimizedImage out=(OptimizedImage)image;check(out.getWidth()==6000&&out.getHeight()==4000,"full resolution preserved through intermediate template");out.uploaded=true;fullUploads[0]++;}};
        result=SonyCameraSave.save(resolver,card,sourcePhoto,new File(card,"processed.jpg"),"Host full test",fullPixels);
        check(result.saved&&fullUploads[0]==1&&OptimizedImage.allocations==OptimizedImage.releases,"full-resolution upload completes and releases intermediate image");
        JpegExporter.code=-2;result=SonyCameraSave.save(resolver,card,sourcePhoto,new File(card,"processed.jpg"),"Host test",pixels);
        check(!result.indexed&&!result.saved&&result.detail.contains("callback -2"),"native save error retained");
        SonyImageUpload.Pixels failing=new SonyImageUpload.Pixels(){public int[] dimensions(File f){return new int[]{3000,2000};}public void upload(Object image,File f,int[] v)throws Exception{throw new java.io.IOException("Upload mock failure");}};
        result=SonyCameraSave.save(resolver,card,sourcePhoto,new File(card,"processed.jpg"),"Host test",failing);
        check(!result.saved&&result.detail.contains("Upload mock failure")&&!SonyCameraSave.busy()&&OptimizedImage.allocations==OptimizedImage.releases,"failed upload cannot export original pixels; resources released");
        File reportFile=new File(card,"GLSAVE.TXT");
        check(reportFile.isFile(),"USB-readable diagnostic report written beside processed image");
        String diagnostic=new String(java.nio.file.Files.readAllBytes(reportFile.toPath()),"UTF-8");
        check(diagnostic.contains("Host test")&&diagnostic.contains("Uploading processed JPEG")&&diagnostic.contains("Upload mock failure"),"build, last stage and actual failure recorded");
        check(SonyCameraSave.report(new File(card,"missing/processed.jpg"),"report").contains("Diagnostic write failed"),"diagnostic write failures are visible");
        reportFile.delete();
        new File(card,"DCIM/101GRAIN/GLSAVE.TXT").delete();new File(card,"DCIM/101GRAIN").delete();
        new File(card,"DCIM/100GRAIN/GLSAVE.TXT").delete();new File(card,"DCIM/100GRAIN").delete();
        File probeFile=new File(card,"GLTEST.TXT");
        check(SonyCameraSave.writeReport(probeFile,"First test").contains("Report:"),"acceleration report saved in card root");
        check(SonyCameraSave.writeReport(probeFile,"Build 11\nSelected image: DSC00001.JPG\nMAIN failed").contains("Report:"),"next test replaces root report");
        String probeText=new String(java.nio.file.Files.readAllBytes(probeFile.toPath()),"UTF-8");
        check(probeText.contains("Build 11")&&probeText.contains("MAIN failed")&&!probeText.contains("First test"),"root report contains latest identity, selected image and failed result");
        probeFile.delete();
        new File(card,"DCIM/100MSDCF/DSC00002.JPG").delete();sourcePhoto.delete();new File(card,"DCIM/100MSDCF").delete();new File(card,"DCIM").delete();card.delete();
        OptimizedImageFactory.fail=false;OptimizedImageFactory.nativeOnly=true;
        StringBuilder decode=new StringBuilder();Object image=SonyImageReader.decodeMain("/mnt/sdcard/DCIM/101GRAIN/GLAB0001.JPG",decode);
        check(OptimizedImageFactory.observed.equals("file:///android/mnt/sdcard/DCIM/101GRAIN/GLAB0001.JPG")&&decode.toString().contains("returned no valid image"),"Sony native mount fallback after Android path failure");
        image.getClass().getMethod("release").invoke(image);OptimizedImageFactory.nativeOnly=false;
        check(SonyImageReader.paths("/android/mnt/sdcard/image.JPG").length==1&&SonyImageReader.paths("/data/image.JPG").length==1,"native paths and app-private paths are not prefixed twice");
        check(OptimizedImage.allocations==OptimizedImage.releases,"fallback reader resources released");
        SoftFocusImageFilter.fail=false;
        report=SonyAcceleration.run(resolver,new File("DCIM/100MSDCF/DSC00001.JPG"));
        check(report.contains("MAIN URI: avindex://abcd/")&&report.contains("JPEG stream"),"probe uses actual catalog identifier, not filesystem path");
        check(SonyGallery.imageId(resolver,new File("DCIM/101GRAIN/GLAB0001.JPG"))==null,"uncatalogued grain file cannot borrow an original's identifier");
        final java.util.concurrent.CountDownLatch gate=new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1);
        ContentResolver hanging=new ContentResolver(){public Cursor query(Uri uri,String[] columns,String selection,String[] params,String sort){
            entered.countDown();try{gate.await();}catch(InterruptedException e){throw new RuntimeException(e);}return resolver.query(uri,columns,selection,params,sort);
        }};
        File probeCard=java.nio.file.Files.createTempDirectory("sony-probe-timeout").toFile();
        report=SonyProbe.run(hanging,new File("DCIM/100MSDCF/DSC00001.JPG"),probeCard,"Build timeout test",1000);
        check(entered.getCount()==0&&report.contains("Wait timed out")&&SonyProbe.busy(),"blocked Sony call returns a bounded report and retains busy guard");
        String partial=new String(java.nio.file.Files.readAllBytes(new File(probeCard,"GLTEST.TXT").toPath()),"UTF-8");
        check(partial.contains("Reading Sony catalog identifier")&&partial.contains("Build timeout test"),"partial report flushed before blocked operation");
        check(SonyProbe.run(resolver,new File("image.jpg"),probeCard,"duplicate",1).contains("Previous Sony test"),"repeat probe refused while native call remains active");
        gate.countDown();for(int i=0;i<200&&SonyProbe.busy();i++)Thread.sleep(10);
        check(!SonyProbe.busy()&&OptimizedImage.allocations==OptimizedImage.releases,"late completion cleans up before clearing guard");
        new File(probeCard,"GLTEST.TXT").delete();probeCard.delete();
        final Object scratch=new Object(),opaqueImage=new Object();final byte[] expected=new byte[4096];
        for(int i=0;i<expected.length;i++)expected[i]=(byte)i;
        final int[] imageReads={0};SonyNativeSamples.Reader reader=new SonyNativeSamples.Reader(){public byte[] read(Object memory,int offset,int count){if(memory==scratch)return java.util.Arrays.copyOfRange(expected,offset,offset+count);imageReads[0]++;return new byte[count];}};
        int[] sampleOffsets=SonyNativeSamples.offsets(40157184,5504,3648,5456,3632,0);
        StringBuilder samples=new StringBuilder();SonyNativeSamples.capture(reader,scratch,opaqueImage,expected,sampleOffsets,samples,null);
        check(imageReads[0]==3&&samples.toString().contains("Native read-only image sampling: PASS"),"calibrated bounded image sample plan");
        samples=new StringBuilder();final int beforeReads=imageReads[0];expected[0]^=1;
        SonyNativeSamples.Reader mismatch=new SonyNativeSamples.Reader(){public byte[] read(Object memory,int offset,int count){if(memory==scratch)return new byte[count];imageReads[0]++;return new byte[count];}};
        try{SonyNativeSamples.capture(mismatch,scratch,opaqueImage,expected,sampleOffsets,samples,null);throw new AssertionError("Mismatch accepted");}catch(IllegalStateException e){}
        check(imageReads[0]==beforeReads,"native/public mismatch refuses image reads");
        try{SonyNativeSamples.offsets(100,5504,3648,5456,3632,0);throw new AssertionError("Invalid descriptor accepted");}catch(IllegalArgumentException e){}
        byte[] colorRow=SonyWriteProbe.bars(128);
        check(colorRow.length==256&&(colorRow[0]&255)==128&&(colorRow[1]&255)==255&&(colorRow[2]&255)==128&&(colorRow[3]&255)==255,"UYVY white test pixels");
        check((colorRow[124]&255)==128&&(colorRow[125]&255)==0&&(colorRow[128]&255)==128&&(colorRow[129]&255)==0,"active black bar and padded black pixels");
        try{SonyWriteProbe.bars(63);throw new AssertionError("Odd test canvas accepted");}catch(IllegalArgumentException e){}
        final byte[] scratchStore=new byte[4096],targetStore=new byte[12288];final boolean[] corruptWriteCheck={false};final int[] targetWrites={0};
        SonyWriteProbe.Transfer transfer=new SonyWriteProbe.Transfer(){
            public int write(Object memory,int offset,byte[] data){byte[] store=memory==scratch?scratchStore:targetStore;System.arraycopy(data,0,store,offset,data.length);if(memory==opaqueImage)targetWrites[0]++;return data.length;}
            public byte[] read(Object memory,int offset,int count){return java.util.Arrays.copyOfRange(memory==scratch?scratchStore:targetStore,offset,offset+count);}
            public byte[] publicRead(Object memory,int offset,int count){byte[] data=read(memory,offset,count);if(corruptWriteCheck[0])data[0]^=1;return data;}
        };
        SonyWriteProbe.fill(transfer,scratch,opaqueImage,colorRow,new StringBuilder(),null);
        check(targetWrites[0]==48&&java.util.Arrays.equals(colorRow,java.util.Arrays.copyOfRange(targetStore,47*256,48*256)),"owned color-bar image filled at padded row stride");
        corruptWriteCheck[0]=true;int writesBeforeMismatch=targetWrites[0];
        try{SonyWriteProbe.fill(transfer,scratch,opaqueImage,colorRow,new StringBuilder(),null);throw new AssertionError("Bad calibration accepted");}catch(IllegalStateException e){}
        check(targetWrites[0]==writesBeforeMismatch,"scratch calibration failure prevents all image writes");
        check(SonyWriteProbe.separate("4096",4096,"8192",4096)&&!SonyWriteProbe.separate("4096",4096,"6144",4096),"allocation separation and overlap guard");
        check(!SonyWriteProbe.separate("4096",4096,"1073745920",4096),"DSP address aliases are normalized before overlap check");
        corruptWriteCheck[0]=false;targetWrites[0]=0;java.util.Arrays.fill(targetStore,(byte)0x19);
        int[] patch=SonyWriteProbe.patch(transfer,scratch,opaqueImage,new int[]{128,48,128,48,0,0,0,12288},new StringBuilder(),null);
        check(patch[0]==32&&patch[1]==8&&targetWrites[0]==32,"bounded center patch fills only scaler test region");
        for(int y=0;y<48;y++)for(int x=0;x<256;x++)if(y<8||y>=40||x<64||x>=192)check(targetStore[y*256+x]==0x19,"pixels outside patch unchanged");
        OptimizedImage callbackImage=new OptimizedImage(64,48);JpegExporter callbackExporter=new JpegExporter();JpegExporter.Options callbackOptions=new JpegExporter.Options();callbackOptions.quality=2;
        JpegExporter.code=0;StringBuilder callbackReport=new StringBuilder();java.io.InputStream callbackStream=SonyJpegStream.encode(callbackExporter,callbackImage,callbackOptions,callbackReport,null);
        check(callbackStream!=null&&callbackReport.toString().contains("callback: 0, stream=true"),"native stream callback result captured");callbackStream.close();
        JpegExporter.code=-2;
        try{SonyJpegStream.encode(callbackExporter,callbackImage,callbackOptions,new StringBuilder(),null);throw new AssertionError("Native stream error ignored");}catch(IllegalStateException e){check(e.getMessage().contains("-2"),"exact native error preserved");}
        callbackImage.release();callbackExporter.release();
        System.out.println("PASS: Sony reflection, export callbacks versus catalog confirmation, grain-folder readback, error handling, USB diagnostics, resource cleanup");
    }
}
