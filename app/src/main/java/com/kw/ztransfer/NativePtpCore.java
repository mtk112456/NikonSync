package com.kw.ztransfer;

/**
 * JNI Native Bridge to libzrelay_core.so
 * Provides low-level PTP/IP protocol framing, transaction counting,
 * and Nikon vendor parameter mapping.
 */
public final class NativePtpCore implements AutoCloseable {
    public static final boolean isLoaded;
    private static NativePtpCore sInstance;
    private long nativeHandle;

    static {
        boolean loaded = false;
        try {
            System.loadLibrary("zrelay_core");
            loaded = true;
        } catch (UnsatisfiedLinkError e) {
            e.printStackTrace();
            loaded = false;
        }
        isLoaded = loaded;
    }

    public static synchronized NativePtpCore getInstance() {
        if (sInstance == null) {
            sInstance = new NativePtpCore();
        }
        return sInstance;
    }

    public NativePtpCore() {
        if (isLoaded) {
            this.nativeHandle = n01();
            if (this.nativeHandle == 0) {
                throw new IllegalStateException("Native PTP core initialization failed (handle is 0)");
            }
        }
    }

    public boolean isValid() {
        return this.nativeHandle != 0;
    }

    // JNI Declarations matching libzrelay_core.so
    private native long n01();
    private native void n02(long handle);
    private native byte[] n03(long handle, byte[] data, String str);
    private native byte[] n04(long handle, long param);
    private native void n05(long handle);
    private native void n06(long handle);
    private native void n07(long handle);
    private native void n08(long handle);
    private native void n09(long handle, boolean flag);
    private native boolean n10(long handle);
    private native boolean n11(long handle);
    private native long n12(long handle);
    private native void n13(long handle, int param);
    private native void n14(long handle, int[] params, boolean f1, boolean f2);
    private native int n15(int code);
    private native int n16(int c1, int c2);
    private native String n17(int c1, int c2);
    private native void n18(long handle);

    // High-Level Java Wrapper Methods
    public int decodeResponseCode(int code) {
        return n15(code);
    }

    public int getVendorConstant(int c1, int c2) {
        return n16(c1, c2);
    }

    public String getVendorDescription(int c1, int c2) {
        return n17(c1, c2);
    }

    public long nextTransactionId() {
        return isValid() ? n12(this.nativeHandle) : 1;
    }

    public boolean isSessionOpen() {
        return isValid() && n10(this.nativeHandle);
    }

    public boolean isTransportActive() {
        return isValid() && n11(this.nativeHandle);
    }

    public void onSessionOpened() {
        if (isValid()) n07(this.nativeHandle);
    }

    public void onSessionClosed() {
        if (isValid()) n08(this.nativeHandle);
    }

    public void setTransferMode(boolean flag) {
        if (isValid()) n09(this.nativeHandle, flag);
    }

    public void keepAlive() {
        if (isValid()) n05(this.nativeHandle);
    }

    public void reset() {
        if (isValid()) n06(this.nativeHandle);
    }

    public byte[] encodePayload(byte[] data, String token) {
        return isValid() ? n03(this.nativeHandle, data, token) : data;
    }

    public byte[] decodeBuffer(long size) {
        return isValid() ? n04(this.nativeHandle, size) : new byte[0];
    }

    @Override
    public synchronized void close() {
        if (this.nativeHandle != 0) {
            long handle = this.nativeHandle;
            this.nativeHandle = 0;
            n02(handle);
        }
    }
}
