package com.sony.scalar.hardware;
public class DeviceMemory {
    private boolean live=true;
    public boolean isValid(){return live;}
    public void release(){if(!live)throw new AssertionError("Double memory release");live=false;}
}
