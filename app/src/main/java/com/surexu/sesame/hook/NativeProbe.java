package com.surexu.sesame.hook;

public class NativeProbe {
    public static void init() {
        try {
            System.loadLibrary("nethook");
            initNative();
        } catch (Throwable t) {
            android.util.Log.i("XUHOOK", "init err: " + t);
        }
    }

    private static native void initNative();
}
