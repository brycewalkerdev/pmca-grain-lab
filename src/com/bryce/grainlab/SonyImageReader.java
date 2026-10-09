package com.bryce.grainlab;

/** Sony's playback decoder forwards paths outside Android's filesystem namespace. */
final class SonyImageReader {
    static String[] paths(String absolute){
        if(absolute.startsWith("/mnt/sdcard/"))return new String[]{absolute,"/android"+absolute};
        return new String[]{absolute};
    }
    static Object decodeMain(String absolute,StringBuilder report)throws Exception{
        for(String path:paths(absolute)){
            Object image=decodeUri("file://"+path,report);if(image!=null)return image;
        }
        throw new IllegalStateException("Sony MAIN decoder returned no valid image for any attempted path");
    }
    static Object decodeUri(String uri,StringBuilder report)throws Exception{
        Class<?> factory=Class.forName("com.sony.scalar.graphics.OptimizedImageFactory");
        Class<?> options=Class.forName("com.sony.scalar.graphics.OptimizedImageFactory$Options");
        Object settings=options.newInstance();options.getField("imageType").setInt(settings,options.getField("MAIN").getInt(null));
            Object image=null;
            report.append("MAIN URI: ").append(uri).append('\n');
            try{
                image=factory.getMethod("decodeImage",String.class,options).invoke(null,uri,settings);
                if(image!=null&&Boolean.TRUE.equals(image.getClass().getMethod("isValid").invoke(image))){
                    report.append("MAIN decode valid\n");return image;
                }
                report.append("MAIN decode returned no valid image\n");
            }catch(Exception e){report.append("MAIN decode error: ").append(SonyGallery.error(e)).append('\n');}
            if(image!=null)try{image.getClass().getMethod("release").invoke(image);}catch(Exception e){}catch(LinkageError e){}
        return null;
    }
}
