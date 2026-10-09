package com.sony.scalar.hardware;
public class DeviceBuffer extends DeviceMemory {
    public static int allocations,releases;public static boolean corrupt;
    private final byte[] data;
    public DeviceBuffer(int size){data=new byte[size];allocations++;}
    public int getSize(){return data.length;}
    public int write(byte[] src){return write(src,0,src.length,0);}
    public int read(byte[] dst){return read(dst,0,dst.length,0);}
    public int write(byte[] src,int offset,int size,int target){System.arraycopy(src,offset,data,target,size);return size;}
    public int read(byte[] dst,int offset,int size,int source){System.arraycopy(data,source,dst,offset,size);if(corrupt)dst[offset]^=1;return size;}
    public void release(){super.release();releases++;}
}
