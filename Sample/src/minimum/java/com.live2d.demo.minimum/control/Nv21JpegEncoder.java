package com.live2d.demo.minimum.control;

/**
 * NV21 → JPEG 的 native 编码器（libjpeg-turbo，raw YCbCr 通路）。
 *
 * 存在的理由见 {@code src/main/jni/nv21_jpeg.c} 顶部注释：框架的
 * YuvImage.compressToJpeg 有 native 内存泄漏，而纯 Java 的替代方案太慢。
 * 句柄尺寸相关，模型/相机切换导致的尺寸变化要 destroy 后重建。
 */
final class Nv21JpegEncoder {
    private Nv21JpegEncoder() {
    }

    /** 创建编码器；失败返回 0。 */
    static native long create(int width, int height);

    /** 释放编码器。 */
    static native void destroy(long handle);

    /**
     * 编码一帧 NV21（同时按 flipV/flipH 镜像）。
     *
     * @return JPEG 字节；失败返回 null
     */
    static native byte[] encode(long handle, byte[] nv21, int quality,
                               boolean flipV, boolean flipH);

    static {
        System.loadLibrary("nv21jpeg");
    }
}
