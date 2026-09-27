package com.live2d.demo.minimum.control;

import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import com.live2d.demo.minimum.LAppMinimumDelegate;
import com.live2d.demo.minimum.LAppMinimumLive2DManager;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 摄像头控制：Camera1 API（API22 无运行时权限）+ NV21→JPEG 编码。
 * 供 ControlServer 提供 /api/camera（开关）与 /api/camera/stream（MJPEG）。
 *
 * 生命周期：explicitOn=true 时保持常开；流/快照访问会隐式开启，
 * 无显式开启且 10s 无人取帧时自动关闭（省电+隐私）。
 * 所有 Camera 操作都在专用 cameraThread 上执行。
 */
public class CameraController {
    private static final String TAG = "CameraController";

    private static final long AUTO_OFF_NANOS = 10_000_000_000L;
    /** 编码帧率上限：NV21 翻转 + JPEG 编码约占 1/3 核，而渲染线程本就钉满 1 核，
     *  取 10fps（面部追踪够用）把这块开销压到 ~1/4 核，给渲染让出 CPU。 */
    private static final int TARGET_FPS = 10;
    private static final int TARGET_W = 640;
    private static final int TARGET_H = 480;

    /** 渲染预热等待：首批 GL 对象（sink 贴图/模型贴图）创建期间 HAL 抢驱动会让整批贴图静默丢失。 */
    private static final long WARM_WAIT_MS = 4000;
    private static final long WARM_POLL_MS = 100;
    /** 兜底：进程已运行这么久仍未预热（GL 起不来），不再拦摄像头——摄像头是独立功能。 */
    private static final long WARM_ESCAPE_UPTIME_S = 20;

    private static final CameraController INSTANCE = new CameraController();

    public static CameraController get() {
        return INSTANCE;
    }

    private final AtomicInteger clients = new AtomicInteger(0);

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private Camera camera;
    /** GL 线程创建的假预览屏（有真实 EGL 上下文）；老 HAL 无 preview surface 时回调不出帧。 */
    private volatile android.graphics.SurfaceTexture previewSink;
    private volatile boolean explicitOn = false;
    private volatile boolean available = false;
    private volatile boolean starting = false;
    private volatile int width = 0;
    private volatile int height = 0;
    private volatile int jpegQuality = 60;
    private volatile long lastUseNanos = 0;
    private volatile long lastEncodeNanos = 0;
    private volatile long frameSeq = 0;
    /** 摄像头关闭代数：每次真实关闭 +1，用于让旧流感知“已被显式关闭”并退出。 */
    private volatile long generation = 0;
    private volatile byte[] latestJpeg = null;
    private volatile String lastError = null;

    private CameraController() {
    }

    /** 设备是否有摄像头。 */
    public boolean isAvailable() {
        return available;
    }

    /** 摄像头是否正在预览/出帧。 */
    public boolean isOn() {
        return camera != null;
    }

    public boolean isExplicitOn() {
        return explicitOn;
    }

    public int getClientCount() {
        return clients.get();
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getJpegQuality() {
        return jpegQuality;
    }

    public long getFrameSeq() {
        return frameSeq;
    }

    public long getGeneration() {
        return generation;
    }

    /** GL 线程（onSurfaceCreated）注入假预览屏；先于任何 open 调用即生效。 */
    public void setPreviewSink(android.graphics.SurfaceTexture sink) {
        previewSink = sink;
    }

    public String getLastError() {
        return lastError;
    }

    /** 编码帧率上限（/api/camera 的 fps 字段）。 */
    public int getTargetFps() {
        return TARGET_FPS;
    }

    /**
     * 摄像头开启闸门：等渲染预热完成再放行。
     *
     * 开机时 HTTP 服务先于 GL 就绪可用，外部客户端（如面部追踪）一连上就隐式开摄像头，
     * 此时 sink 还没有 EGL 上下文，会退化成裸 SurfaceTexture(0)，HAL 便与首批 GL 对象
     * （sink 贴图、模型贴图/着色器）创建并发抢驱动——实测结果是整批贴图静默丢失，
     * 模型渲染成黑剪影 + 贴图碎片，且该进程内不会自愈。此处等到 GL 就绪、模型首批
     * 贴图上传完成、并且真的出过帧之后才允许 open。
     */
    private boolean ensureRenderWarm() {
        if (isRenderWarm()) return true;
        long deadline = System.currentTimeMillis() + WARM_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(WARM_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                lastError = "interrupted while waiting renderer";
                return false;
            }
            if (isRenderWarm()) return true;
        }
        if (LAppMinimumDelegate.peekUptimeSeconds() > WARM_ESCAPE_UPTIME_S) {
            Log.w(TAG, "renderer still cold after " + WARM_WAIT_MS + "ms, starting camera anyway");
            return true;
        }
        lastError = "renderer warming up, retry later";
        Log.i(TAG, "camera start deferred: renderer warming up");
        return false;
    }

    /** GL 就绪 + 模型首批贴图已上传 + 已产出过帧。 */
    private static boolean isRenderWarm() {
        return LAppMinimumDelegate.isGlReady()
            && LAppMinimumLive2DManager.peekCurrentModel() != null
            && LAppMinimumDelegate.peekFps() > 0f;
    }

    /** 打开（隐式或显式）。成功返回 true；失败置 lastError。 */
    public boolean turnOn(boolean explicit) {
        if (scanActive) {
            lastError = "camera busy: scan session";
            return false; // 扫码独占期间拒绝一切隐式/显式开启
        }
        lastUseNanos = System.nanoTime();
        if (camera != null) {
            if (explicit) explicitOn = true;
            return true;
        }
        if (!ensureProbed()) {
            lastError = "no camera on device";
            return false;
        }
        // 必须在真正 open 之前（probe 只查数量、不开设备）：摄像头一开，
        // HAL 就会与首批 GL 对象创建抢驱动
        if (!ensureRenderWarm()) return false;
        explicitOn |= explicit;
        if (starting) return true; // 正在开，按成功处理，调用方随后取帧重试
        starting = true;
        lastError = null;
        ensureThread();
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        cameraHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    openOnThread();
                } finally {
                    latch.countDown();
                }
            }
        });
        try {
            if (!latch.await(4, java.util.concurrent.TimeUnit.SECONDS)) {
                lastError = "camera open timeout";
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            starting = false;
        }
        if (camera == null && lastError == null) lastError = "camera open failed";
        return camera != null;
    }

    /** 关闭（显式关立即生效；隐式空闲超时由 onPreviewFrame 自查）。 */
    public void turnOff(boolean explicit) {
        if (explicit) explicitOn = false;
        if (camera == null) return;
        ensureThread();
        cameraHandler.post(new Runnable() {
            @Override
            public void run() {
                closeOnThread();
            }
        });
    }

    /**
     * 取最新 JPEG（给快照/流）。隐式开启 + 活跃时间戳由调用方维护。
     *
     * @return 编码后的 JPEG；尚未出帧时 null
     */
    public byte[] latestJpeg() {
        lastUseNanos = System.nanoTime();
        markFrameWanted();
        return latestJpeg;
    }

    public void registerClient() {
        clients.incrementAndGet();
        lastUseNanos = System.nanoTime();
        markFrameWanted();
    }

    public void unregisterClient() {
        clients.decrementAndGet();
        lastUseNanos = System.nanoTime();
    }

    public void setJpegQuality(int q) {
        jpegQuality = Math.max(30, Math.min(95, q));
    }

    // native 编码器句柄（尺寸变化时重建）
    private long jpegHandle;
    private int convW;
    private int convH;
    // 实拍画面上下颠倒，输出前翻转；左右是否镜像待定
    private static final boolean FLIP_V = true;
    private static final boolean FLIP_H = false;

    /** 有人要帧的时间戳（流注册 / 快照取帧）。见 framesWanted()。 */
    private volatile long wantFramesNanos = 0;
    private static final long FRAME_WANT_WINDOW_NANOS = 2_000_000_000L;

    private void markFrameWanted() {
        wantFramesNanos = System.nanoTime();
    }

    /**
     * 是否有消费者在等帧。没人等时 handleFrame 不做翻转/JPEG 编码——编码是本进程唯一
     * 的重量级 native 调用，实测每编一帧泄漏 1~2KB native 堆（整夜 ~600MB，被 LMK 杀掉），
     * 而客户端断开/未连时这些编码纯属白烧 CPU 且持续漏。
     */
    private boolean framesWanted() {
        return clients.get() > 0
            || System.nanoTime() - wantFramesNanos < FRAME_WANT_WINDOW_NANOS;
    }

    private void ensureThread() {
        if (cameraThread == null) {
            synchronized (this) {
                if (cameraThread == null) {
                    cameraThread = new HandlerThread("camera-pump");
                    cameraThread.start();
                    cameraHandler = new Handler(cameraThread.getLooper());
                }
            }
        }
    }

    private void openOnThread() {
        if (camera != null) return;
        try {
            Camera cam = Camera.open(findCameraId());
            try {
                Camera.Parameters p = cam.getParameters();
                Log.i(TAG, "probe zoom=" + p.get("zoom") + " max_zoom=" + p.get("max-zoom")
                    + " sizes=" + p.get("preview-size-values")
                    + " fps=" + p.get("preview-fps-range-values"));
                Camera.Size best = pickSize(p.getSupportedPreviewSizes());
                if (best != null) p.setPreviewSize(best.width, best.height);
                p.setPreviewFormat(ImageFormat.NV21);
                p.set("rotation", 0);
                cam.setParameters(p);
            } catch (Exception e) {
                Log.w(TAG, "setParameters failed, continue with defaults", e);
            }
            Camera.Size cur = cam.getParameters().getPreviewSize();
            width = cur.width;
            height = cur.height;
            // 老 QCOM HAL 不挂 preview surface 不出帧：优先用 GL 线程注入的 sink
            // （带真实 EGL 上下文）；GL 未就绪时退化为裸 SurfaceTexture(0) 兜底。
            try {
                SurfaceTexture sink = previewSink;
                if (sink == null) {
                    // 闸门（ensureRenderWarm）保证正常路径下 sink 已注入；走到这里说明 GL 没起来，
                    // 这批贴图/调用很可能静默失效 —— 留日志便于定位
                    Log.w(TAG, "preview sink missing at open: falling back to bare SurfaceTexture(0)");
                    sink = new SurfaceTexture(0);
                }
                sink.setDefaultBufferSize(width, height);
                cam.setPreviewTexture(sink);
            } catch (Exception e) {
                Log.w(TAG, "setPreviewTexture failed", e);
            }
            cam.setPreviewCallbackWithBuffer(new Camera.PreviewCallback() {
                @Override
                public void onPreviewFrame(byte[] data, Camera cam) {
                    handleFrame(data, cam);
                }
            });
            int bufSize = width * height * 3 / 2;
            for (int i = 0; i < 3; i++) cam.addCallbackBuffer(new byte[bufSize]);
            cam.startPreview();
            camera = cam;
            Log.i(TAG, "camera opened " + width + "x" + height);
        } catch (Exception e) {
            lastError = "camera open failed: " + e;
            Log.e(TAG, "open failed", e);
            camera = null;
        }
    }

    private void closeOnThread() {
        Camera cam = camera;
        if (cam == null) return;
        // 先换代数再动 camera：流在 isOn()=false 与换代数之间轮询时，
        // 必须已经能看到“这轮关闭已发生”，否则会在窗口期隐式重开、复活摄像头
        generation++;
        camera = null;
        try {
            cam.setPreviewCallbackWithBuffer(null);
            cam.stopPreview();
        } catch (Exception ignored) {
        }
        try {
            cam.release();
        } catch (Exception ignored) {
        }
        releaseJpegEncoder();
        latestJpeg = null;
        Log.i(TAG, "camera closed");
    }

    /** 相机回调线程：空闲看门狗 + 限频 + NV21 翻转 → JPEG。 */
    private void handleFrame(byte[] data, Camera cam) {
        long now = System.nanoTime();
        if (!explicitOn && clients.get() == 0 && now - lastUseNanos > AUTO_OFF_NANOS) {
            closeOnThread();
            return;
        }
        if (!framesWanted()) {
            cam.addCallbackBuffer(data);
            return;
        }
        long interval = 1_000_000_000L / TARGET_FPS;
        if (now - lastEncodeNanos < interval) {
            cam.addCallbackBuffer(data);
            return;
        }
        lastEncodeNanos = now;
        try {
            int w = width;
            int h = height;
            if (jpegHandle == 0 || convW != w || convH != h) {
                releaseJpegEncoder();
                jpegHandle = Nv21JpegEncoder.create(w, h);
                convW = w;
                convH = h;
            }
            if (jpegHandle != 0) {
                byte[] jpeg = Nv21JpegEncoder.encode(jpegHandle, data, jpegQuality,
                    FLIP_V, FLIP_H);
                if (jpeg != null) {
                    latestJpeg = jpeg;
                    frameSeq++;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "jpeg encode failed", t);
        }
        cam.addCallbackBuffer(data);
    }

    private void releaseJpegEncoder() {
        if (jpegHandle != 0) {
            Nv21JpegEncoder.destroy(jpegHandle);
            jpegHandle = 0;
        }
        convW = 0;
        convH = 0;
    }

    // ---------------- 扫码会话（摄像头互斥） ----------------
    // ScanActivity 独占相机：开始时记住现场并关停（监控流收到 EOF），
    // 结束时按原状态恢复（流客户端重连即恢复）；期间 turnOn 一律拒绝。

    private volatile boolean scanActive = false;
    private boolean preScanExplicit = false;

    /** 扫码是否进行中。 */
    public boolean isScanActive() {
        return scanActive;
    }

    /** 扫码开始：保存现场（explicit 状态）并关停预览。幂等。 */
    public void beginScanSession() {
        synchronized (this) {
            if (scanActive) return;
            scanActive = true;
            preScanExplicit = explicitOn;
        }
        Log.i(TAG, "scan session begin (preScanExplicit=" + preScanExplicit + ")");
        turnOff(false); // 关停预览，不清 explicit 标记（恢复时用保存值）
    }

    /** 扫码结束：按保存的现场恢复摄像头。幂等。 */
    public void endScanSession() {
        if (!scanActive) return;
        scanActive = false;
        Log.i(TAG, "scan session end, restore explicit=" + preScanExplicit);
        if (preScanExplicit) {
            turnOn(true);
        }
    }

    private static int findCameraId() {
        for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) return i;
        }
        return 0;
    }

    /** 挑最接近 640x480 的预览尺寸。 */
    private static Camera.Size pickSize(List<Camera.Size> sizes) {
        if (sizes == null || sizes.isEmpty()) return null;
        Camera.Size best = null;
        long bestDiff = Long.MAX_VALUE;
        for (Camera.Size s : sizes) {
            long diff = Math.abs((long) s.width * s.height - (long) TARGET_W * TARGET_H);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = s;
            }
        }
        return best;
    }

    /** 摄像头单例可用性探测（幂等，首次访问时执行）。 */
    public boolean ensureProbed() {
        if (!probed) {
            try {
                available = Camera.getNumberOfCameras() > 0;
            } catch (Throwable t) {
                available = false;
                lastError = "camera probe failed: " + t;
            }
            probed = true;
        }
        return available;
    }

    private volatile boolean probed = false;
}
