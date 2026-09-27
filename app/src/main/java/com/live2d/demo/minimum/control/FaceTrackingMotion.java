package com.live2d.demo.minimum.control;

import java.util.Random;

/** GL-thread-only controller; detection and rendering have independent rates. */
final class FaceTrackingMotion {
    private final Random random = new Random();
    private long lastFace = -1, lastStep = -1, nextWander, lastObservation = -1;
    private float targetX, targetY, zeroX, zeroY;
    float x, y;
    String mode = "starting";

    void reset() {
        lastFace = lastStep = lastObservation = -1;
        nextWander = 0;
        x = y = targetX = targetY = zeroX = zeroY = 0;
        mode = "starting";
    }

    void step(long now, long observedAt, boolean face, float cx, float cy, float aspect,
              float gain, int mx, int my) {
        float dt = lastStep < 0 ? .125f : Math.min(1f, (now - lastStep) / 1000f);
        lastStep = now;
        if (face && observedAt >= 0 && now - observedAt <= 1500) {
            if (observedAt > lastObservation) {
                float k = lastObservation < 0 ? 0 : Math.min(.2f, (observedAt - lastObservation) / 30000f);
                zeroX = FaceTrackingMath.clamp(zeroX + (cx - zeroX) * k, -.45f, .45f);
                zeroY = FaceTrackingMath.clamp(zeroY + (cy - zeroY) * k, -.45f, .45f);
                lastObservation = observedAt;
            }
            lastFace = observedAt; // Never refresh freshness by rereading the same detection.
            targetX = mx * gain * FaceTrackingMath.curve(FaceTrackingMath.clamp(cx - zeroX, -1, 1));
            targetY = my * gain * FaceTrackingMath.curve(FaceTrackingMath.clamp((cy - zeroY) / Math.max(.05f, aspect), -1, 1));
            mode = "tracking";
        } else if (lastFace >= 0 && now - lastFace < 4000) {
            lastObservation = -1; // Do not learn a zero point over time when no face was observed.
            mode = "holding";
            return;
        } else {
            lastObservation = -1;
            if (!mode.equals("wander")) nextWander = now;
            mode = "wander";
            if (now >= nextWander) {
                targetX = gain * (random.nextFloat() * 2 - 1) * .36f;
                targetY = gain * (random.nextFloat() * 2 - 1) * .21f;
                nextWander = now + 1200 + random.nextInt(2001);
            }
        }
        // EMA .28 at 8Hz, adjusted to elapsed time so rendering FPS does not change the feel.
        float alpha = 1f - (float) Math.pow(1 - .28, dt * 8);
        x += alpha * (targetX - x);
        y += alpha * (targetY - y);
    }
}
