package com.live2d.demo.minimum.control;

import java.util.Arrays;

public final class FaceTrackingTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        check(FaceTrackingMath.intervalMs(20, 30, 5, 80, false) == 250, "80ms/core-second budget");
        check(FaceTrackingMath.intervalMs(5, 10, 5, 80, false) == 200, "max FPS");
        check(FaceTrackingMath.intervalMs(200, 210, 5, 80, false) == 2500, "slow CPU must fall below 1Hz");
        check(FaceTrackingMath.intervalMs(5, 500, 5, 80, false) == 500, "wall latency bound");
        check(FaceTrackingMath.intervalMs(5, 10, 5, 80, true) == 1000, "idle scanning");
        check(FaceTrackingMath.intervalMs(200, 210, 5, 80, true) == 2500, "idle still respects budget");

        byte[] src = new byte[24], out = new byte[6];
        for (int i = 0; i < 16; i++) src[i] = (byte) i;
        for (int i = 16; i < 24; i++) src[i] = (byte) (100 + i - 16);
        FaceTrackingMath.sample(src, 4, 4, out, 2, 2, false, false);
        check(Arrays.equals(out, new byte[]{0, 2, 8, 10, 100, 101}), "NV21 reduction and VU ordering");
        FaceTrackingMath.sample(src, 4, 4, out, 2, 2, true, false);
        check(Arrays.equals(out, new byte[]{12, 14, 4, 6, 104, 105}), "vertical flip including chroma");
        FaceTrackingMath.sample(src, 4, 4, out, 2, 2, true, true);
        check(Arrays.equals(out, new byte[]{15, 13, 7, 5, 106, 107}), "both flips including chroma");
        byte[] copy = new byte[24];
        FaceTrackingMath.sample(src, 4, 4, copy, 4, 4, false, false);
        check(Arrays.equals(src, copy), "identity");
        boolean rejected = false;
        try { FaceTrackingMath.sample(new byte[10], 4, 4, out, 2, 2, false, false); }
        catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "short camera frame rejected");

        FaceTrackingMotion motion = new FaceTrackingMotion();
        motion.step(1000, 1000, true, .5f, -.3f, .75f, .3f, 1, 1);
        check(motion.mode.equals("tracking") && motion.x > 0 && motion.y < 0, "tracking directions");
        motion.step(2000, 1000, true, .5f, -.3f, .75f, .3f, 1, 1);
        float heldX = motion.x, heldY = motion.y;
        motion.step(2700, 1000, true, .5f, -.3f, .75f, .3f, 1, 1);
        check(motion.mode.equals("holding") && motion.x == heldX && motion.y == heldY, "stale result holds");
        motion.step(4999, 1000, true, .5f, -.3f, .75f, .3f, 1, 1);
        check(motion.mode.equals("holding"), "four second loss grace");
        motion.step(5001, 1000, true, .5f, -.3f, .75f, .3f, 1, 1);
        check(motion.mode.equals("wander"), "same stale detection must not refresh last-face time");
        motion.reset();
        motion.step(6000, 6000, true, .5f, -.3f, .75f, .3f, -1, -1);
        check(motion.x < 0 && motion.y > 0, "calibration signs");
        for (int i = 0; i < 1000; i++) {
            motion.step(8000 + i * 125, -1, false, 0, 0, .75f, .3f, 1, 1);
            check(Math.abs(motion.x) <= .3f && Math.abs(motion.y) <= .3f, "bounded wander");
        }
        System.out.println("Face tracking math, freshness, hold/wander and NV21 tests passed");
    }
}
