package com.live2d.demo.minimum.control;

/** Android-independent preprocessing and scheduling, also exercised by host tests. */
final class FaceTrackingMath {
    private FaceTrackingMath() {}

    static long intervalMs(double cpuMs, double wallMs, int maxFps, int budgetMs, boolean idle) {
        // budgetMs is CPU milliseconds per second, for ONE core, not device CPU %.
        // No lower FPS floor: expensive devices must be allowed to fall below 1Hz.
        return (long) Math.ceil(Math.max(idle ? 1000 : 1000.0 / maxFps,
            Math.max(cpuMs * 1000.0 / budgetMs, wallMs)));
    }

    static float clamp(float value, float lo, float hi) {
        return Math.max(lo, Math.min(hi, value));
    }

    static float curve(float value) {
        return Math.copySign((float) (Math.pow(Math.abs(value), .6) * Math.pow(.35, .4)), value);
    }

    /** Sample just a small NV21 buffer while the camera owns the original callback buffer.
     * No full-frame copy, no allocation, no color conversion, no retaining HAL buffers.
     * flipV/flipH must match CameraController's JPEG orientation.
     */
    static void sample(byte[] src, int sw, int sh, byte[] dst, int dw, int dh,
                       boolean flipV, boolean flipH) {
        if (sw < 2 || sh < 2 || dw < 2 || dh < 2 || ((sw | sh | dw | dh) & 1) != 0
            || src.length < (long) sw * sh * 3 / 2 || dst.length < (long) dw * dh * 3 / 2)
            throw new IllegalArgumentException("invalid NV21 dimensions");
        for (int y = 0; y < dh; y++) {
            int sy = y * sh / dh;
            if (flipV) sy = sh - 1 - sy;
            int row = sy * sw;
            for (int x = 0; x < dw; x++) {
                int sx = x * sw / dw;
                if (flipH) sx = sw - 1 - sx;
                dst[y * dw + x] = src[row + sx];
            }
        }
        // VU pairs: sample the source chroma cell corresponding to the output 2x2 cell.
        for (int y = 0; y < dh; y += 2) {
            int sy = y * sh / dh;
            if (flipV) sy = sh - 1 - sy;
            int row = sw * sh + (sy / 2) * sw;
            for (int x = 0; x < dw; x += 2) {
                int sx = x * sw / dw;
                if (flipH) sx = sw - 1 - sx;
                int from = row + (sx & ~1), to = dw * dh + (y / 2) * dw + x;
                dst[to] = src[from];
                dst[to + 1] = src[from + 1];
            }
        }
    }
}
