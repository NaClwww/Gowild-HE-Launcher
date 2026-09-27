package com.live2d.demo.minimum;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.KeyEvent;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.live2d.demo.minimum.control.CameraController;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

/**
 * 扫码配网：机身触摸条"扫码键"（KEYCODE_F2）进入。
 * 相机预览 + ZXing 解 WiFi 二维码（WIFI:S:ssid;T:WPA;P:pwd;;）→ 连接 → 结果提示 → 自动关闭。
 *
 * 摄像头互斥：进入时 CameraController.beginScanSession()（保存现场、关停监控流），
 * 退出时 endScanSession()（按原状态恢复，流客户端重连即恢复）。
 * 投影对 framebuffer 是上下镜像（V）：根视图 scale(1,-1) 补偿（文字经双次 V 翻转正显），
 * TextureView 画面随视图变换翻转，预览在其上再补一次 V 翻转使实体屏方向自然。
 */
public class ScanActivity extends Activity implements TextureView.SurfaceTextureListener {
    private static final String TAG = "ScanActivity";

    private static final int PREVIEW_W = 640;
    private static final int PREVIEW_H = 480;
    private static final int DISPLAY_DEGREES = 0; // 投影已由根视图 V 翻转补偿，预览不再另转
    private static final long TIMEOUT_MS = 60_000L;
    private static final long CONNECT_POLL_MS = 12_000L;

    private final MultiFormatReader reader = new MultiFormatReader();
    private final Handler ui = new Handler();

    private FrameLayout root;
    private TextureView textureView;
    private OverlayView overlay;
    private android.hardware.Camera camera;
    private HandlerThread decodeThread;
    private Handler decodeHandler;

    private volatile boolean connecting = false;
    private int frameCount = 0;

    @SuppressLint("MissingPermission")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!CameraController.get().isAvailable()) {
            toast("无摄像头"); finish();
            return;
        }
        CameraController.get().beginScanSession();

        Map<DecodeHintType, Object> hints = new EnumMap<DecodeHintType, Object>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, EnumSet.of(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        reader.setHints(hints);

        root = new FrameLayout(this);
        textureView = new TextureView(this);
        textureView.setSurfaceTextureListener(this);
        // 根视图已 V 翻转补偿投影；预览在此之上再补一次 V 翻转，实体屏上方向自然
        textureView.setScaleY(-1f);
        root.addView(textureView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay = new OverlayView();
        root.addView(overlay, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 投影补偿：根视图仅垂直翻转（V），文字经双次 V 翻转正显
        root.setScaleY(-1f);
        setContentView(root);

        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ui.postDelayed(timeoutRunnable, TIMEOUT_MS);

        decodeThread = new HandlerThread("qr-decode");
        decodeThread.start();
        decodeHandler = new Handler(decodeThread.getLooper());
    }

    private final Runnable timeoutRunnable = new Runnable() {
        @Override public void run() { toast("扫码超时"); finish(); }
    };

    // ---------------- 相机预览 ----------------

    @SuppressLint("MissingPermission")
    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        try {
            camera = android.hardware.Camera.open(0);
        } catch (Exception e) {
            Log.e(TAG, "camera open failed", e);
            toast("摄像头被占用"); finish();
            return;
        }
        try {
            android.hardware.Camera.Parameters p = camera.getParameters();
            p.setPreviewSize(PREVIEW_W, PREVIEW_H);
            camera.setParameters(p);
            camera.setDisplayOrientation(DISPLAY_DEGREES);
            camera.setPreviewTexture(surface);
            camera.startPreview();
            applyPreviewLayout();
            camera.setPreviewCallback(new android.hardware.Camera.PreviewCallback() {
                @Override
                public void onPreviewFrame(final byte[] data, android.hardware.Camera cam) {
                    frameCount++;
                    if (frameCount % 2 != 0 || connecting) return; // 每 2 帧解一次
                    decodeHandler.post(new Runnable() {
                        @Override public void run() { decode(data); }
                    });
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "preview start failed", e);
            toast("预览启动失败"); finish();
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        applyPreviewLayout();
    }

    /**
     * 相机 640x480（4:3）与屏幕比例不同，MATCH_PARENT 会拉伸画面。
     * 按预览宽高比 letterbox：居中、尽量铺满，多余方向留黑边。
     */
    private void applyPreviewLayout() {
        int sw = root.getWidth(), sh = root.getHeight();
        if (sw == 0 || sh == 0) {
            ui.post(new Runnable() { @Override public void run() { applyPreviewLayout(); } });
            return;
        }
        float ratio = (float) PREVIEW_W / PREVIEW_H;
        int w, h;
        if (sw / (float) sh > ratio) {
            h = sh; w = (int) (sh * ratio + 0.5f);
        } else {
            w = sw; h = (int) (sw / ratio + 0.5f);
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.gravity = android.view.Gravity.CENTER;
        textureView.setLayoutParams(lp);
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        releaseCamera();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }

    /** 解码线程：NV21 Y 平面 → ZXing（传感器原始方向为纯旋转，QR 解码天然支持）。命中 WiFi 码转 UI 线程连接。 */
    private void decode(byte[] nv21) {
        try {
            PlanarYUVLuminanceSource src = new PlanarYUVLuminanceSource(
                nv21, PREVIEW_W, PREVIEW_H, 0, 0, PREVIEW_W, PREVIEW_H, false);
            Result r = reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(src)));
            reader.reset();
            final String text = r.getText();
            Log.i(TAG, "qr: " + text);
            final WifiQr qr = WifiQr.parse(text);
            if (qr == null) {
                ui.post(new Runnable() { @Override public void run() { overlay.setMessage("不是WiFi二维码，继续扫描"); } });
                return;
            }
            connecting = true;
            ui.post(new Runnable() {
                @Override public void run() {
                    overlay.setMessage("正在连接 " + qr.ssid + " ...");
                    connectWifi(qr);
                }
            });
        } catch (Exception ignored) {
            // 未识别到二维码，继续扫
        }
    }

    // ---------------- WiFi 配网 ----------------

    private static class WifiQr {
        String ssid;
        String password;
        String security; // nopass / WEP / WPA
        boolean hidden;

        /** 解析 WIFI:S:ssid;T:WPA;P:pass;H:true;;（处理 \; \, \: \\ 转义） */
        static WifiQr parse(String text) {
            if (text == null || !text.startsWith("WIFI:")) return null;
            WifiQr q = new WifiQr();
            String body = text.substring(5);
            int i = 0;
            while (i < body.length()) {
                int semi = findUnescaped(body, i, ';');
                if (semi < 0) semi = body.length();
                String field = body.substring(i, semi);
                int colon = findUnescaped(field, 0, ':');
                if (colon > 0) {
                    String k = field.substring(0, colon);
                    String v = unescape(field.substring(colon + 1));
                    if (k.equals("S")) q.ssid = v;
                    else if (k.equals("P")) q.password = v;
                    else if (k.equals("T")) q.security = v.toUpperCase();
                    else if (k.equals("H") && v.equals("true")) q.hidden = true;
                }
                i = semi + 1;
            }
            if (q.ssid == null || q.ssid.length() == 0) return null;
            if (q.security == null) q.security = "WPA";
            return q;
        }

        private static int findUnescaped(String s, int from, char target) {
            for (int i = from; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\\') { i++; continue; }
                if (c == target) return i;
            }
            return -1;
        }

        private static String unescape(String s) {
            StringBuilder sb = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\\' && i + 1 < s.length()) { i++; c = s.charAt(i); }
                sb.append(c);
            }
            return sb.toString();
        }
    }

    private void connectWifi(final WifiQr qr) {
        new Thread(new Runnable() {
            @Override public void run() {
                final boolean ok = doConnect(qr);
                ui.post(new Runnable() {
                    @Override public void run() {
                        overlay.setMessage(ok ? "已连接 " + qr.ssid : "连接 " + qr.ssid + " 失败");
                        ui.postDelayed(new Runnable() { @Override public void run() { finish(); } }, 2000);
                    }
                });
            }
        }, "wifi-connect").start();
    }

    @SuppressLint("MissingPermission")
    private boolean doConnect(WifiQr qr) {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            // 删掉同 SSID 旧配置（密码可能变了）
            for (WifiConfiguration c : wm.getConfiguredNetworks()) {
                if (c.SSID != null && c.SSID.equals("\"" + qr.ssid + "\"")) {
                    wm.removeNetwork(c.networkId);
                }
            }
            WifiConfiguration wc = new WifiConfiguration();
            wc.SSID = "\"" + qr.ssid + "\"";
            wc.hiddenSSID = qr.hidden;
            if (qr.security.equals("NOPASS")) {
                wc.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            } else { // WPA/WPA2（WEP 已绝迹，compileSdk 34 亦无 GroupCiphers，一律按 PSK 处理）
                wc.preSharedKey = "\"" + qr.password + "\"";
            }
            int id = wm.addNetwork(wc);
            if (id == -1) return false;
            wm.saveConfiguration();
            wm.disconnect();
            wm.enableNetwork(id, true);
            wm.reconnect();
            // 轮询等连接 + 拿到 IP
            long deadline = System.currentTimeMillis() + CONNECT_POLL_MS;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(1000);
                android.net.wifi.WifiInfo info = wm.getConnectionInfo();
                if (info != null && info.getSSID() != null
                    && info.getSSID().contains(qr.ssid)
                    && info.getIpAddress() != 0) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "wifi connect failed", e);
            return false;
        }
    }

    // ---------------- 收尾 ----------------

    private void releaseCamera() {
        android.hardware.Camera cam = camera;
        camera = null;
        if (cam == null) return;
        try { cam.setPreviewCallback(null); } catch (Exception ignored) {}
        try { cam.stopPreview(); } catch (Exception ignored) {}
        try { cam.release(); } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(timeoutRunnable);
        releaseCamera();
        if (decodeThread != null) decodeThread.quitSafely();
        CameraController.get().endScanSession(); // 按现场恢复监控流
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 扫码界面内再按扫码键（F2）或 BACK 均退出返回主界面
        if (keyCode == KeyEvent.KEYCODE_F2 || keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); // 注：Toast 未经倒屏补偿
    }

    /** 取景遮罩 + 提示文字（随根视图一起被 V 翻转，实体屏上正显）。中心不画任何东西，直接透出预览。 */
    private class OverlayView extends android.view.View {
        private volatile String message = "对准WiFi二维码";
        private final Paint mask = new Paint();
        private final Paint frame = new Paint();
        private final Paint text = new Paint();

        OverlayView() {
            super(ScanActivity.this);
            mask.setColor(0x88000000);
            frame.setColor(0xFF33B5E5);
            frame.setStyle(Paint.Style.STROKE);
            frame.setStrokeWidth(4f);
            text.setColor(Color.WHITE);
            text.setTextSize(36f);
            text.setTextAlign(Paint.Align.CENTER);
            text.setAntiAlias(true);
        }

        void setMessage(String m) {
            message = m;
            postInvalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth(), h = getHeight();
            int side = Math.min(w, h) * 2 / 3;
            int l = (w - side) / 2, t = (h - side) / 2;
            canvas.drawRect(0, 0, w, t, mask);
            canvas.drawRect(0, t + side, w, h, mask);
            canvas.drawRect(0, t, l, t + side, mask);
            canvas.drawRect(l + side, t, w, t + side, mask);
            canvas.drawRect(l, t, l + side, t + side, frame);
            canvas.drawText(message, w / 2f, t + side + 80f, text);
        }
    }
}
