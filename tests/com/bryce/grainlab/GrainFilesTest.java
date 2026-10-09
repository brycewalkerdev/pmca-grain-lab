package com.bryce.grainlab;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

public final class GrainFilesTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("grain-card-").toFile();
        try {
            File folder = new File(root, "DCIM/101MSDCF"); check(folder.mkdirs(), "make source folder");
            File older = new File(folder, "DSC00001.JPG"); Files.write(older.toPath(), new byte[] {(byte)0xff,(byte)0xd8}); older.setLastModified(10000);
            File newer = new File(folder, "DSC00002.jpeg"); Files.write(newer.toPath(), new byte[] {(byte)0xff,(byte)0xd8}); newer.setLastModified(20000);
            new File(folder, "RAW.ARW").createNewFile(); new File(folder, "PART.TMP").createNewFile();
            List<File> originals = GrainFiles.list(root, false);
            check(originals.size() == 2 && originals.get(0).equals(newer), "all DCIM folders, JPEG only, newest first");
            File first = GrainFiles.reserve(root), second = GrainFiles.reserve(root);
            check(!first.equals(second) && first.exists() && second.exists(), "reserve does not overwrite");
            check(first.getName().equals("GLAB0001.JPG")&&second.getName().equals("GLAB0002.JPG"),"DCF 8.3 names");
            check(first.getParentFile().getName().equals("100GRAIN"),"dedicated unused DCF directory number");
            check(GrainFiles.folderNumber(first)==100&&GrainFiles.fileNumber(first)==1,"native database keys");
            check(GrainFiles.list(root, true).isEmpty(), "hide unfinished reserved copies");
            Files.write(first.toPath(), new byte[] {(byte)0xff,(byte)0xd8});
            check(GrainFiles.list(root, true).size() == 1, "browse completed output folder independently");
            check(GrainFiles.list(root, false).size() == 2, "output copies are excluded from originals");
            check(older.exists() && newer.exists(), "originals unchanged");
            File legacy=new File(root,"GRAIN");check(legacy.mkdir(),"legacy folder");Files.write(new File(legacy,"GRN00001.JPG").toPath(),new byte[]{1});
            check(GrainFiles.list(root,true).size()==2,"legacy copies retained in browser");
            Files.write(new File(first.getParentFile(),"DSC_0003.ARW").toPath(),new byte[]{1});
            File third=GrainFiles.reserve(root);check(third.getName().equals("GLAB0004.JPG"),"DCF number collisions include RAW and other prefixes");
            check(GrainFiles.relative(root,first).equals("DCIM/100GRAIN/GLAB0001.JPG"),"card-relative saved name");
            System.out.println("PASS: card discovery, JPEG filtering, newest first, unique copies, originals retained");
        } finally { remove(root); }
    }
    private static void remove(File file) {
        File[] children = file.listFiles(); if (children != null) for (File child : children) remove(child);
        if (!file.delete()) throw new AssertionError("Temporary test cleanup failed: " + file);
    }
}
