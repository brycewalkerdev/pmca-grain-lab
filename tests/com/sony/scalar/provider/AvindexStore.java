package com.sony.scalar.provider;
import android.content.ContentResolver;
import android.net.Uri;
public final class AvindexStore {
    public static String[] ids={"abcd"};public static boolean update=true,cancel;public static String observed;
    public static String[] getExternalMediaIds(){return ids;}
    public static final class Images {
        public static boolean waitAndUpdateDatabase(ContentResolver resolver,String id){observed=id;return update;}
        public static boolean cancelWaitAndUpdateDatabase(ContentResolver resolver,String id){cancel=true;return true;}
        public static final class Media {public static Uri getContentUri(String id){return Uri.parse("content://com.sony.scalar.providers.avindex/"+id+"/images/media");}}
    }
}
