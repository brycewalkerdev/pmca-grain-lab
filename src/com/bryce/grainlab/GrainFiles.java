package com.bryce.grainlab;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;

/** Camera-free card browsing and collision-free, eight-character output names. */
final class GrainFiles {
    static List<File> list(File root, boolean copies) throws IOException {
        List<File> files = new ArrayList<File>();
        File directory=new File(root,"DCIM");
        if(copies){
            File legacy=new File(root,"GRAIN");walk(legacy,legacy.getCanonicalPath(),0,files,false);
            File[] folders=directory.listFiles();
            if(folders!=null)for(File folder:folders)if(grainFolder(folder.getName()))walk(folder,folder.getCanonicalPath(),0,files,false);
        }else walk(directory,directory.getCanonicalPath(),0,files,true);
        Collections.sort(files, new Comparator<File>() {
            public int compare(File a, File b) {
                long at = a.lastModified(), bt = b.lastModified();
                int date = bt < at ? -1 : bt > at ? 1 : 0;
                return date != 0 ? date : a.getPath().compareTo(b.getPath());
            }
        });
        return files;
    }

    private static void walk(File directory, String boundary, int depth, List<File> files,boolean excludeGrain) throws IOException {
        if (depth > 3 || !directory.exists()) return;
        File[] entries = directory.listFiles();
        if (entries == null) throw new IOException("Cannot read " + directory.getName());
        for (File entry : entries) {
            String path = entry.getCanonicalPath();
            if (!path.startsWith(boundary + File.separator)) continue;
            if(entry.isDirectory()){
                if(!excludeGrain||!grainFolder(entry.getName()))walk(entry,boundary,depth+1,files,excludeGrain);
            }
            else if (jpeg(entry.getName()) && entry.length() > 0) files.add(entry);
        }
    }
    static boolean grainFolder(String name){return name.matches("[1-9][0-9]{2}GRAIN");}
    static int folderNumber(File file){
        String folder=file.getParentFile().getName();return folder.matches("[1-9][0-9]{2}[A-Za-z0-9_]{5}")?Integer.parseInt(folder.substring(0,3)):-1;
    }
    static int fileNumber(File file){
        String name=file.getName().toUpperCase(Locale.US);
        return name.matches("[A-Z0-9_]{4}[0-9]{4}\\.JPG")?Integer.parseInt(name.substring(4,8)):-1;
    }
    static String relative(File root,File file)throws IOException{
        String prefix=root.getCanonicalPath()+File.separator,path=file.getCanonicalPath();
        if(!path.startsWith(prefix))throw new IOException("Image outside memory card");
        return path.substring(prefix.length()).replace(File.separatorChar,'/');
    }

    static boolean jpeg(String name) {
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg");
    }

    static File reserve(File root) throws IOException {
        File dcim=new File(root,"DCIM");
        if(!dcim.isDirectory()&&!dcim.mkdirs())throw new IOException("Cannot create DCIM");
        File[] folders=dcim.listFiles();if(folders==null)throw new IOException("Cannot read DCIM");
        Set<Integer> used=new HashSet<Integer>();List<File> own=new ArrayList<File>();
        for(File folder:folders){
            if(folder.isDirectory()&&folder.getName().matches("[1-9][0-9]{2}[A-Za-z0-9_]{5}"))used.add(Integer.valueOf(Integer.parseInt(folder.getName().substring(0,3))));
            if(folder.isDirectory()&&grainFolder(folder.getName()))own.add(folder);
        }
        Collections.sort(own);
        for(File folder:own){File file=reserveIn(folder);if(file!=null)return file;}
        for(int n=100;n<=999;n++)if(!used.contains(Integer.valueOf(n))){
            File folder=new File(dcim,String.format(Locale.US,"%03dGRAIN",n));
            if(!folder.mkdir())continue;
            File file=reserveIn(folder);if(file!=null)return file;
        }
        throw new IOException("No free DCF grain folder");
    }
    private static File reserveIn(File folder)throws IOException{
        /* A DCF folder/file number is a database key, independent of the filename prefix. */
        Set<Integer> used=new HashSet<Integer>();File[] files=folder.listFiles();
        if(files==null)throw new IOException("Cannot read grain folder");
        for(File file:files){String name=file.getName().toUpperCase(Locale.US);if(name.matches("[A-Z0-9_]{4}[0-9]{4}\\.[A-Z0-9]{3}"))used.add(Integer.valueOf(Integer.parseInt(name.substring(4,8))));}
        for(int n=1;n<=9999;n++)if(!used.contains(Integer.valueOf(n))){
            File file=new File(folder,String.format(Locale.US,"GLAB%04d.JPG",n));if(file.createNewFile())return file;
        }return null;
    }
}
