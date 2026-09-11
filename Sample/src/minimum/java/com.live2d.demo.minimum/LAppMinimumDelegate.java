/*
 * Copyright(c) Live2D Inc. All rights reserved.
 *
 * Use of this source code is governed by the Live2D Open Software license
 * that can be found at http://live2d.com/eula/live2d-open-software-license-agreement_en.html.
 */

package com.live2d.demo.minimum;

import android.app.Activity;
import android.opengl.GLES20;
import android.os.Build;
import android.util.Log;
import com.live2d.demo.LAppDefine;
import com.live2d.sdk.cubism.framework.CubismFramework;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static android.opengl.GLES20.*;

public class LAppMinimumDelegate {
    public static LAppMinimumDelegate getInstance() {
        if (s_instance == null) {
            s_instance = new LAppMinimumDelegate();
        }
        return s_instance;
    }

    /**
     * クラスのインスタンス（シングルトン）を解放する。
     */
    public static void releaseInstance() {
        if (s_instance != null) {
            s_instance = null;
        }
    }

    public void onStart(Activity activity) {
        // 幂等：扫码往返等场景 onStart 会重复进入，现场还在就直接复用
        if (textureManager == null) {
            textureManager = new LAppMinimumTextureManager();
        }
        if (view == null) {
            view = new LAppMinimumView();
        }

        LAppMinimumPal.updateTime();
        this.activity = activity;
    }

    /**
     * 被其他界面（扫码等）覆盖时不再拆除 Cubism 现场：
     * GL 上下文由 setPreserveEGLContextOnPause 保留；即便被回收，
     * GLRenderer.onSurfaceCreated 也会整模重载（着色器链接有重试兜底）。
     * 真正的进程退出走 onDestroy / run() 的 System.exit 路径。
     */
    public void onStop() {
    }

    public void onDestroy() {
        releaseInstance();
    }

    public void onSurfaceCreated() {
        // テクスチャサンプリング設定
        GLES20.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        GLES20.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);

        // 透過設定
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        // 摄像头假预览屏：必须在持有 EGL 上下文的线程创建，否则老 HAL 预览回调不出帧
        try {
            int[] sinkTex = new int[1];
            GLES20.glGenTextures(1, sinkTex, 0);
            com.live2d.demo.minimum.control.CameraController.get()
                .setPreviewSink(new android.graphics.SurfaceTexture(sinkTex[0]));
        } catch (Throwable t) {
            Log.w("[APP]", "camera preview sink setup failed", t);
        }

        // Initialize Cubism SDK framework（onStop 不再 dispose，这里只初始化一次）
        if (!s_frameworkInitialized) {
            CubismFramework.initialize();
            s_frameworkInitialized = true;
        }
    }

    public void onSurfaceChanged(int width, int height) {
        // 描画範囲指定
        GLES20.glViewport(0, 0, width, height);
        windowWidth = width;
        windowHeight = height;

        // AppViewの初期化
        view.initialize();
        view.initializeSprite();

        isActive = true;
        s_glReady = true;
    }

    public void run() {
        // 控制面投递的命令在帧边界执行（GL 线程）
        drainCommands();

        // 時間更新
        LAppMinimumPal.updateTime();

        // 画面初期化（投影光路上下颠倒：纯黑背景）
        glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glClearDepthf(1.0f);

        if (view != null) {
            view.render();
        }

        sampleFps();

        // アプリケーションを非アクティブにする
        if (!isActive) {
            activity.finishAndRemoveTask();
            System.exit(0);
        }
    }

    public void onTouchBegan(float x, float y) {
        mouseX = x;
        mouseY = y;

        if (view != null) {
            isCaptured = true;
            view.onTouchesBegan(mouseX, mouseY);
        }
    }

    public void onTouchEnd(float x, float y) {
        mouseX = x;
        mouseY = y;

        if (view != null) {
            isCaptured = false;
            view.onTouchesEnded(mouseX, mouseY);
        }
    }

    public void onTouchMoved(float x, float y) {
        mouseX = x;
        mouseY = y;

        if (isCaptured && view != null) {
            view.onTouchesMoved(mouseX, mouseY);
        }
    }

    // getter, setter群
    public LAppMinimumTextureManager getTextureManager() {
        return textureManager;
    }

    public LAppMinimumView getView() {
        return view;
    }

    public int getWindowWidth() {
        return windowWidth;
    }

    public int getWindowHeight() {
        return windowHeight;
    }

    public Activity getActivity() {
        return activity;
    }

    // ---- 控制面接入点 ----

    /** 供 HTTP 线程投递命令，在 GL 线程帧边界执行。 */
    public void post(Runnable command) {
        commandQueue.add(command);
    }

    private void drainCommands() {
        Runnable command;
        while ((command = commandQueue.poll()) != null) {
            try {
                command.run();
            } catch (Throwable t) {
                Log.e("[APP]", "command failed", t);
            }
        }
    }

    /** GL 渲染是否已就绪（onSurfaceChanged 之后）。 */
    public static boolean isGlReady() {
        return s_glReady;
    }

    public static float peekFps() {
        return s_fps;
    }

    public static long peekUptimeSeconds() {
        return (System.currentTimeMillis() - s_processStartMs) / 1000;
    }

    private static void sampleFps() {
        s_frameCount++;
        long now = System.currentTimeMillis();
        long elapsed = now - s_fpsWindowStartMs;
        if (elapsed >= 1000) {
            s_fps = s_frameCount * 1000f / elapsed;
            s_frameCount = 0;
            s_fpsWindowStartMs = now;
        }
    }

    private static final long s_processStartMs = System.currentTimeMillis();
    private static volatile boolean s_glReady;
    private static volatile float s_fps;
    private static long s_fpsWindowStartMs = System.currentTimeMillis();
    private static int s_frameCount;

    private final Queue<Runnable> commandQueue = new ConcurrentLinkedQueue<Runnable>();

    private LAppMinimumDelegate() {
        // Set up Cubism SDK framework.
        cubismOption.logFunction = new LAppMinimumPal.PrintLogFunction();
        cubismOption.loggingLevel = LAppDefine.cubismLoggingLevel;

        CubismFramework.cleanUp();
        CubismFramework.startUp(cubismOption);
    }

    private static LAppMinimumDelegate s_instance;
    private static boolean s_frameworkInitialized;
    private Activity activity;

    private final CubismFramework.Option cubismOption = new CubismFramework.Option();

    private LAppMinimumTextureManager textureManager;
    private LAppMinimumView view;
    private int windowWidth;
    private int windowHeight;
    private boolean isActive;

    /**
     * クリックしているか
     */
    private boolean isCaptured;
    /**
     * マウスのX座標
     */
    private float mouseX;
    /**
     * マウスのY座標
     */
    private float mouseY;
}
