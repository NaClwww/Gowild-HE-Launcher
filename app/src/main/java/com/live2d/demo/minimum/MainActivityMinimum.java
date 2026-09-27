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

import com.live2d.demo.minimum.control.CameraController;
import com.live2d.demo.minimum.control.ControlServer;

import java.io.IOException;

public class MainActivityMinimum extends Activity {
    private static final String TAG = "MainActivityMinimum";

    private GLSurfaceView _glSurfaceView;
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
        _glSurfaceView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();

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
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
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
