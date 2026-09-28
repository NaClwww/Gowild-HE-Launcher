package com.live2d.demo.minimum.control;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Debug;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;

import com.live2d.demo.minimum.LAppMinimumDelegate;
import com.live2d.demo.minimum.LAppMinimumLive2DManager;

import org.json.JSONException;
import org.json.JSONObject;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Local face detection. Camera -> one reusable sampled frame -> one CPU worker -> GL.
 * No video client/JPEG work, unbounded queues, HTTP loopback or GL-thread inference.
 */
public final class FaceTracker {
    private static final FaceTracker INSTANCE = new FaceTracker();
    public static FaceTracker get() { return INSTANCE; }

    private static final class Config {
        final boolean enabled;
        final int width, fps, budget, mx, my;
        final float gain;
        Config(JSONObject in, Config old) throws JSONException {
            Iterator<String> keys = in.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                if (!k.equals("enabled") && !k.equals("input_width") && !k.equals("max_fps")
                    && !k.equals("cpu_budget_ms") && !k.equals("gain") && !k.equals("mx") && !k.equals("my"))
                    throw new IllegalArgumentException("unknown field: " + k);
            }
            if (in.has("enabled") && !(in.get("enabled") instanceof Boolean))
                throw new IllegalArgumentException("enabled must be boolean");
            enabled = in.has("enabled") ? in.getBoolean("enabled") : old != null && old.enabled;
            width = integer(in, "input_width", old == null ? 192 : old.width, 128, 320);
            if (width % 32 != 0) throw new IllegalArgumentException("input_width must be a multiple of 32");
            fps = integer(in, "max_fps", old == null ? 5 : old.fps, 1, 8);
            budget = integer(in, "cpu_budget_ms", old == null ? 80 : old.budget, 10, 300);
            mx = integer(in, "mx", old == null ? 1 : old.mx, -1, 1);
            my = integer(in, "my", old == null ? 1 : old.my, -1, 1);
            if (mx == 0 || my == 0) throw new IllegalArgumentException("mx/my must be -1 or 1");
            gain = (float) number(in, "gain", old == null ? .3 : old.gain, 0, 1);
        }
        private static double number(JSONObject in, String key, double fallback, double min, double max) throws JSONException {
            if (!in.has(key)) return fallback;
            Object raw = in.get(key);
            if (!(raw instanceof Number)) throw new IllegalArgumentException(key + " must be numeric");
            double v = ((Number) raw).doubleValue();
            if (Double.isNaN(v) || Double.isInfinite(v) || v < min || v > max)
                throw new IllegalArgumentException(key + " out of range: " + min + ".." + max);
            return v;
        }
        private static int integer(JSONObject in, String key, int fallback, int min, int max) throws JSONException {
            double v = number(in, key, fallback, min, max);
            if (v != Math.rint(v)) throw new IllegalArgumentException(key + " must be an integer");
            return (int) v;
        }
        JSONObject json() throws JSONException {
            return new JSONObject().put("enabled", enabled).put("input_width", width)
                .put("max_fps", fps).put("cpu_budget_ms", budget).put("gain", (double) gain)
                .put("mx", mx).put("my", my);
        }
    }

    private static final class Detection {
        final long epoch, time;
        final float x, y, confidence, aspect;
        final int faces;
        Detection(long epoch, long time, float[] out, float aspect) {
            this.epoch = epoch; this.time = time;
            x = out[0]; y = out[1]; confidence = out[2]; faces = (int) out[3]; this.aspect = aspect;
        }
    }

    private volatile Config config;
    private SharedPreferences prefs;
    private volatile boolean foreground, cameraReady, faulted;
    private volatile String lastError, mode = "disabled";
    private volatile long manualUntil, nextFrameAt, intervalMs = 200, detectedFrames, droppedBusy;
    private volatile double cpuMs, wallMs, sampleMs, detectFps, totalCpuMs;
    private volatile Detection detection;
    private final AtomicLong epoch = new AtomicLong();
    private final AtomicBoolean busy = new AtomicBoolean();
    private Handler worker;
    // Owned by the single in-flight frame, protected by busy until inference completes.
    private byte[] sampled;
    private int frameW, frameH;
    private long frameEpoch, frameAt;
    private double frameSampleCpu;
    private Config frameConfig;
    // Worker-only data.
    private long nativeHandle, lastCompleted, lastFaceAt;
    private final float[] output = new float[4];
    // GL-only data.
    private final FaceTrackingMotion motion = new FaceTrackingMotion();
    private long glEpoch = -1, nextControlAt;
    private boolean owned;
    private float sentX, sentY;

    private FaceTracker() {
        try { config = new Config(new JSONObject(), null); }
        catch (JSONException e) { throw new AssertionError(e); }
    }

    public synchronized void resume(Context context) {
        if (prefs == null) {
            prefs = context.getApplicationContext().getSharedPreferences("face_tracking", Context.MODE_PRIVATE);
            try { config = new Config(new JSONObject(prefs.getString("config", "{}")), null); }
            catch (Exception e) { lastError = "saved config ignored: " + e.getMessage(); }
        }
        foreground = true;
        invalidate();
        scheduleSupervisor();
    }

    public synchronized void pause() {
        foreground = false;
        invalidate();
        CameraController.get().setTrackingRequested(false);
        scheduleSupervisor();
    }

    /** Atomic validation: no partial preference/camera changes on invalid input. */
    public synchronized void configure(JSONObject in) throws JSONException {
        Config next = new Config(in, config);
        config = next;
        if (prefs != null) prefs.edit().putString("config", next.json().toString()).apply();
        faulted = false;
        lastError = null;
        invalidate();
        if (!next.enabled) CameraController.get().setTrackingRequested(false);
        scheduleSupervisor();
    }

    public void disable() throws JSONException { configure(new JSONObject().put("enabled", false)); }

    private void invalidate() {
        epoch.incrementAndGet();
        detection = null;
        cameraReady = false;
        nextFrameAt = 0;
    }

    /** Called on actual camera close, so an old worker result cannot revive a closed session. */
    void cameraClosed() { invalidate(); }

    public void manualOverride() {
        manualUntil = SystemClock.uptimeMillis() + 5000;
    }

    boolean wantsCamera() {
        return config.enabled && foreground && !faulted
            && "live2d".equals(LAppMinimumLive2DManager.peekCurrentType())
            && !CameraController.get().isScanActive();
    }

    private synchronized void scheduleSupervisor() {
        if (worker == null) {
            if (!foreground || !config.enabled) return;
            HandlerThread thread = new HandlerThread("local-face", Process.THREAD_PRIORITY_BACKGROUND);
            thread.start();
            worker = new Handler(thread.getLooper());
        }
        worker.removeCallbacks(supervise);
        worker.post(supervise);
    }

    private final Runnable supervise = new Runnable() {
        @Override public void run() {
            CameraController camera = CameraController.get();
            if (!wantsCamera()) {
                cameraReady = false;
                camera.setTrackingRequested(false);
                if (nativeHandle != 0) {
                    LocalFaceDetector.destroy(nativeHandle);
                    nativeHandle = 0;
                }
            } else {
                try {
                    if (nativeHandle == 0) {
                        nativeHandle = LocalFaceDetector.create();
                        if (nativeHandle == 0) throw new IllegalStateException("detector allocation failed");
                    }
                    camera.setTrackingRequested(true);
                    boolean opened = camera.turnOnForTracking();
                    // onPause / disable / scan may have arrived during camera startup.
                    cameraReady = opened && wantsCamera();
                    if (!cameraReady) {
                        detection = null;
                        lastError = camera.getLastError();
                        if (!wantsCamera()) camera.setTrackingRequested(false);
                    } else lastError = null;
                } catch (LinkageError | RuntimeException e) {
                    fail("detector startup failed: " + e);
                }
            }
            synchronized (FaceTracker.this) {
                // A lifecycle/config update can arrive while turnOn waits for GL/HAL.
                // Clean up before replacing a queued supervisor, under the same lock as updates.
                if (!wantsCamera()) {
                    cameraReady = false;
                    camera.setTrackingRequested(false);
                    if (nativeHandle != 0) {
                        LocalFaceDetector.destroy(nativeHandle);
                        nativeHandle = 0;
                    }
                }
                worker.removeCallbacks(this);
                if (foreground && config.enabled && !faulted) worker.postDelayed(this, 1000);
            }
        }
    };

    private void fail(String error) {
        lastError = error;
        faulted = true;
        invalidate();
        CameraController.get().setTrackingRequested(false);
        if (nativeHandle != 0) {
            LocalFaceDetector.destroy(nativeHandle);
            nativeHandle = 0;
        }
    }

    /** Camera thread. Sample only after rate/backpressure checks, then return HAL buffer immediately. */
    void offer(byte[] nv21, int width, int height, boolean flipV, boolean flipH) {
        long now = SystemClock.uptimeMillis();
        if (!cameraReady || !wantsCamera() || now < nextFrameAt || now < manualUntil) return;
        if (!busy.compareAndSet(false, true)) { droppedBusy++; return; }
        long cpuStart = Debug.threadCpuTimeNanos();
        try {
            frameEpoch = epoch.get();
            frameConfig = config;
            frameW = frameConfig.width;
            frameH = Math.max(32, Math.min(320, (int) ((long) frameW * height / width) & ~1));
            int size = frameW * frameH * 3 / 2;
            if (sampled == null || sampled.length != size) sampled = new byte[size];
            FaceTrackingMath.sample(nv21, width, height, sampled, frameW, frameH, flipV, flipH);
            frameAt = now;
            frameSampleCpu = (Debug.threadCpuTimeNanos() - cpuStart) / 1e6;
            if (!worker.post(infer)) busy.set(false);
        } catch (RuntimeException e) {
            lastError = "frame rejected: " + e;
            nextFrameAt = now + 1000;
            busy.set(false);
        }
    }

    private final Runnable infer = new Runnable() {
        @Override public void run() {
            try {
                if (frameEpoch != epoch.get() || !cameraReady || !wantsCamera() || nativeHandle == 0) return;
                long start = SystemClock.uptimeMillis(), cpuStart = Debug.threadCpuTimeNanos();
                if (!LocalFaceDetector.detect(nativeHandle, sampled, frameW, frameH, output)) {
                    fail("native face detection failed; toggle enabled to retry");
                    return;
                }
                long now = SystemClock.uptimeMillis();
                double cost = (Debug.threadCpuTimeNanos() - cpuStart) / 1e6 + frameSampleCpu;
                totalCpuMs += cost;
                cpuMs = cpuMs == 0 ? cost : cpuMs * .8 + cost * .2;
                sampleMs = frameSampleCpu;
                wallMs = now - start;
                if (frameEpoch != epoch.get() || !cameraReady || !wantsCamera()) return;
                detectedFrames++;
                detectFps = lastCompleted == 0 ? 0 : 1000.0 / Math.max(1, now - lastCompleted);
                lastCompleted = now;
                if (output[3] > 0) lastFaceAt = now;
                detection = new Detection(frameEpoch, frameAt, output, (float) frameH / frameW);
                intervalMs = FaceTrackingMath.intervalMs(Math.max(cost, cpuMs), wallMs,
                    frameConfig.fps, frameConfig.budget, now - lastFaceAt > 4000);
                nextFrameAt = Math.max(now, frameAt + intervalMs);
            } catch (LinkageError | RuntimeException e) {
                fail("face detection failed: " + e);
            } finally {
                busy.set(false);
            }
        }
    };

    /** Called at the model's frame boundary, never posts per-frame GL commands. */
    public void applyOnGlThread(LAppMinimumLive2DManager manager) {
        long now = SystemClock.uptimeMillis();
        boolean manual = now < manualUntil;
        Detection d = detection;
        long currentEpoch = epoch.get();
        if (!wantsCamera() || !cameraReady || manual || d == null || d.epoch != currentEpoch) {
            if (owned && !manual) manager.lookAtReset();
            owned = false;
            motion.reset();
            mode = !config.enabled ? "disabled" : faulted ? "error" : manual ? "manual"
                : !foreground || CameraController.get().isScanActive() ? "paused"
                : !"live2d".equals(LAppMinimumLive2DManager.peekCurrentType()) ? "inactive_model" : "starting";
            return;
        }
        if (glEpoch != currentEpoch) { motion.reset(); glEpoch = currentEpoch; nextControlAt = 0; }
        if (now < nextControlAt) return;
        nextControlAt = now + 125;
        Config c = config;
        motion.step(now, d.time, d.faces > 0, d.x, d.y, d.aspect, c.gain, c.mx, c.my);
        mode = motion.mode;
        if (!owned || Math.abs(motion.x - sentX) > .03f || Math.abs(motion.y - sentY) > .03f) {
            manager.lookAt(motion.x, motion.y);
            sentX = motion.x; sentY = motion.y;
        }
        owned = true;
    }

    public JSONObject status() throws JSONException {
        Detection d = detection;
        if (d != null && d.epoch != epoch.get()) d = null;
        String currentMode = !config.enabled ? "disabled" : faulted ? "error" : !foreground ? "paused" : mode;
        JSONObject out = config.json().put("mode", currentMode).put("backend", "libfacedetection-neon")
            .put("camera_active", cameraReady).put("busy", busy.get())
            .put("detect_cpu_ms", cpuMs).put("detect_wall_ms", wallMs).put("sample_cpu_ms", sampleMs)
            .put("interval_ms", intervalMs).put("detect_fps", cameraReady ? detectFps : 0)
            .put("total_cpu_ms", totalCpuMs)
            .put("detected_frames", detectedFrames).put("dropped_busy", droppedBusy)
            .put("faces", d == null ? 0 : d.faces).put("confidence", d == null ? 0 : d.confidence)
            .put("result_age_ms", d == null ? -1 : SystemClock.uptimeMillis() - d.time)
            .put("render_fps", (double) LAppMinimumDelegate.peekFps());
        if (lastError != null) out.put("last_error", lastError);
        return out;
    }
}
