/*
 * Copyright(c) Live2D Inc. All rights reserved.
 *
 * Use of this source code is governed by the Live2D Open Software license
 * that can be found at http://live2d.com/eula/live2d-open-software-license-agreement_en.html.
 */

package com.live2d.demo.minimum;

import android.app.Activity;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.WindowManager;

import com.live2d.demo.minimum.control.CameraController;
import com.live2d.demo.minimum.control.ControlServer;
import com.live2d.demo.minimum.control.FaceTracker;

import java.io.IOException;

public class MainActivityMinimum extends Activity {
    private static final String TAG = "MainActivityMinimum";
    private static final String VOLUME_PREFS = "volume";
    private static final String LAST_AUDIBLE_VOLUME = "last_audible_volume";

    private GLSurfaceView _glSurfaceView;
    private VolumeOverlayView _volumeOverlay;
    private WindowManager _windowManager;
    private boolean _volumeOverlayAttached;
    private boolean _resumed;
    private ControlServer _controlServer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 桌面化：常亮（息屏会停控制面与渲染）
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        _glSurfaceView = new GLSurfaceView(this);
        _glSurfaceView.setEGLContextClientVersion(2);       // OpenGL ES 2.0を利用
        // 暂停（扫码界面覆盖）时尽量保留 EGL 上下文；即便上下文被系统回收，
        // GLRenderer 的 onSurfaceCreated 也会整模重载（配合着色器链接重试）
        _glSurfaceView.setPreserveEGLContextOnPause(true);

        GLRendererMinimum _glRenderer = new GLRendererMinimum();

        _glSurfaceView.setRenderer(_glRenderer);
        _glSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        setContentView(_glSurfaceView);
        _volumeOverlay = new VolumeOverlayView(this);
        // GL 模型已单独补偿投影翻转；Android 叠层也要做同样的垂直翻转。
        _volumeOverlay.setScaleY(-1f);
        _windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    protected void onStart() {
        super.onStart();

        LAppMinimumDelegate.getInstance().onStart(this);

        // HTTP 控制面（:8900）
        if (_controlServer == null) {
            _controlServer = new ControlServer(8900);
            try {
                _controlServer.start();
                Log.i(TAG, "control server started on :8900");
            } catch (IOException e) {
                Log.e(TAG, "control server failed to start", e);
                _controlServer = null;
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        _resumed = true;
        _glSurfaceView.onResume();
        _glSurfaceView.post(new Runnable() {
            @Override public void run() {
                if (!_resumed || _volumeOverlayAttached || _windowManager == null || isFinishing()) return;
                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    android.graphics.PixelFormat.TRANSLUCENT);
                params.token = getWindow().getDecorView().getWindowToken();
                if (params.token == null) return;
                _windowManager.addView(_volumeOverlay, params);
                _volumeOverlayAttached = true;
            }
        });
        FaceTracker.get().resume(this);
    }

    @Override
    protected void onPause() {
        super.onPause();

        _resumed = false;
        _volumeOverlay.hide();
        if (_volumeOverlayAttached) {
            _windowManager.removeViewImmediate(_volumeOverlay);
            _volumeOverlayAttached = false;
        }
        FaceTracker.get().pause();
        _glSurfaceView.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();

        // 摄像头随服务一起停（清掉 explicit 标记，不等空闲看门狗）
        CameraController.get().turnOff(true);

        if (_controlServer != null) {
            _controlServer.stop();
            _controlServer = null;
        }

        LAppMinimumDelegate.getInstance().onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        LAppMinimumDelegate.getInstance().onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        // 机身触摸条"扫码键"：短按 KEYCODE_F2 → 进入扫码配网（抢在 onStop 清相机现场前保存状态）
        if (keyCode == android.view.KeyEvent.KEYCODE_F2
            && !CameraController.get().isScanActive()) {
            CameraController.get().beginScanSession();
            startActivity(new android.content.Intent(this, ScanActivity.class));
            return true;
        }
        // 触摸条"+/-"键（出厂 eve 固件：F4=+、F1=-，经 Unity volume 模块调音量）→ 媒体音量。
        // gpio/耳机口的 VOLUME_UP/DOWN 一并接住自己调，不放给系统：系统音量面板经
        // 投影光路会上下镜像（见 DEVICE.md），静默调流不出 UI。
        if (keyCode == android.view.KeyEvent.KEYCODE_F4
            || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) {
            adjustMediaVolume(true);
            return true;
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_F1
            || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) {
            adjustMediaVolume(false);
            return true;
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_F3) {
            // 长按产生的重复 DOWN 不应连续切换静音状态。
            if (event.getRepeatCount() == 0) toggleMediaMute();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void adjustMediaVolume(boolean up) {
        android.media.AudioManager am =
            (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) {
            return;
        }
        int before = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
        am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
            up ? android.media.AudioManager.ADJUST_RAISE : android.media.AudioManager.ADJUST_LOWER,
            0);
        int after = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
        int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
        if (after > 0) {
            getSharedPreferences(VOLUME_PREFS, MODE_PRIVATE).edit()
                .putInt(LAST_AUDIBLE_VOLUME, after).apply();
        }
        _volumeOverlay.show(after, max, false);
        Log.i(TAG, "volume " + (up ? "up" : "down") + ": " + before + " -> " + after);
    }

    private void toggleMediaMute() {
        android.media.AudioManager am =
            (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return;

        int max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
        int before = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
        int target;
        if (before > 0) {
            getSharedPreferences(VOLUME_PREFS, MODE_PRIVATE).edit()
                .putInt(LAST_AUDIBLE_VOLUME, before).apply();
            target = 0;
        } else {
            target = getSharedPreferences(VOLUME_PREFS, MODE_PRIVATE)
                .getInt(LAST_AUDIBLE_VOLUME, Math.max(1, max / 3));
            target = Math.max(1, Math.min(max, target));
        }
        am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, target, 0);
        int after = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
        _volumeOverlay.show(after, max, after == 0);
        Log.i(TAG, "volume mute toggle: " + before + " -> " + after);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        FaceTracker.get().manualOverride();
        float pointX = event.getX();
        float pointY = event.getY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                LAppMinimumDelegate.getInstance().onTouchBegan(pointX, pointY);
                break;
            case MotionEvent.ACTION_UP:
                LAppMinimumDelegate.getInstance().onTouchEnd(pointX, pointY);
                break;
            case MotionEvent.ACTION_MOVE:
                LAppMinimumDelegate.getInstance().onTouchMoved(pointX, pointY);
                break;
        }
        return super.onTouchEvent(event);
    }
}
