package com.live2d.demo.minimum.control;

/** Single-worker JNI entry points. No camera-size RGB/JPEG intermediate. */
final class LocalFaceDetector {
    static { System.loadLibrary("localface"); }
    static native long create();
    static native void destroy(long handle);
    static native boolean detect(long handle, byte[] nv21, int width, int height, float[] out);
    private LocalFaceDetector() {}
}
