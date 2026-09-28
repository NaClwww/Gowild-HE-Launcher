package com.live2d.demo.minimum.control;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;

import org.json.JSONObject;

/**
 * 屏幕亮度控制：/api/brightness。
 *
 * 双层写入：
 *   1. 窗口覆盖（WindowManager.LayoutParams.screenBrightness）——立即生效。
 *      本应用是全屏 HOME 桌面，窗口覆盖即整块实体屏（投影）的亮度。
 *   2. Settings.System.SCREEN_BRIGHTNESS ——系统级持久（重启后仍是新值），
 *      需 WRITE_SETTINGS（API 22 安装时授予）。
 *
 * 另以 SharedPreferences 兜底持久：重启/切窗口后由 attach() 重放窗口覆盖，
 * 即便系统设置写入被厂商 ROM 拒绝也能恢复档位。窗口属性必须经 UI 线程改
 * （HTTP 线程只投递，不直接触碰 Window）。
 */
public class BrightnessController {
    private static final String TAG = "BrightnessController";

    private static final String PREFS = "brightness";
    private static final String KEY_VALUE = "value";
    /** 与系统 SCREEN_BRIGHTNESS 同刻度；1 起——0 档在投影上几乎不可见，无操作意义。 */
    public static final int MIN = 1;
    public static final int MAX = 255;
    private static final int DEFAULT = 255;

    private static volatile BrightnessController sInstance;

    private final Handler _ui = new Handler(Looper.getMainLooper());
    /** 最近一次生效的档位（1..255）；-1 表示尚未应用过（用系统当前值）。 */
    private volatile int _value = -1;
    private volatile Activity _activity;

    public static BrightnessController get() {
        BrightnessController c = sInstance;
        if (c == null) {
            synchronized (BrightnessController.class) {
                if (sInstance == null) sInstance = new BrightnessController();
                c = sInstance;
            }
        }
        return c;
    }

    private BrightnessController() {}

    /** Activity 现场建立（onCreate）时挂接并重放持久化档位。 */
    public void attach(Activity activity) {
        _activity = activity;
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int saved = prefs.getInt(KEY_VALUE, -1);
        if (saved >= MIN) {
            applyToWindow(activity, saved);
            _value = saved;
        } else {
            _value = readSystem(activity.getContentResolver());
        }
    }

    public void detach() {
        _activity = null;
    }

    /** 当前档位：优先最近生效值，未应用过则读系统设置。 */
    public int getValue() {
        int v = _value;
        if (v >= MIN) return v;
        Activity a = _activity;
        return a != null ? readSystem(a.getContentResolver()) : DEFAULT;
    }

    /**
     * 设置亮度（HTTP 线程调用安全）：夹紧到 1..255 后写窗口 + 系统设置 + SharedPreferences。
     * @return false 表示当前无 Activity 窗口可用
     */
    public boolean setValue(int raw) {
        final int v = Math.max(MIN, Math.min(MAX, raw));
        final Activity a = _activity;
        if (a == null || a.isFinishing()) return false;
        applyToWindow(a, v);
        _value = v;

        SharedPreferences prefs = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putInt(KEY_VALUE, v).apply();
        try {
            Settings.System.putInt(a.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, v);
        } catch (Exception e) {
            // 厂商 ROM 可能拒绝写系统设置；窗口覆盖 + 本地持久已够用，不算失败
            Log.w(TAG, "persist to Settings.System failed: " + e);
        }
        return true;
    }

    private void applyToWindow(final Activity a, final int v) {
        final float f = v / (float) MAX;
        _ui.post(new Runnable() {
            @Override public void run() {
                try {
                    Window w = a.getWindow();
                    if (w == null) return;
                    WindowManager.LayoutParams lp = w.getAttributes();
                    lp.screenBrightness = f;
                    w.setAttributes(lp);
                } catch (Exception e) {
                    Log.w(TAG, "apply window brightness failed: " + e);
                }
            }
        });
    }

    private static int readSystem(ContentResolver r) {
        try {
            int v = Settings.System.getInt(r, Settings.System.SCREEN_BRIGHTNESS, DEFAULT);
            return Math.max(MIN, Math.min(MAX, v));
        } catch (Exception e) {
            return DEFAULT;
        }
    }

    /** GET /api/brightness 响应体。 */
    public JSONObject statusJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("available", _activity != null);
            o.put("value", getValue());
            o.put("min", MIN);
            o.put("max", MAX);
        } catch (Exception ignored) {}
        return o;
    }
}
