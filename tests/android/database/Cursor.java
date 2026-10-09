package android.database;
public interface Cursor {int getColumnIndex(String name);boolean moveToNext();int getInt(int column);String getString(int column);void close();}
