/*
 * Copyright(c) Live2D Inc. All rights reserved.
 *
 * Use of this source code is governed by the Live2D Open Software license
 * that can be found at http://live2d.com/eula/live2d-open-software-license-agreement_en.html.
 */

package com.live2d.demo.minimum;

import android.content.Context;
import android.util.Log;

import com.live2d.demo.minimum.control.ModelRepository;
import com.live2d.demo.minimum.l3d.L3dScene;
import com.live2d.sdk.cubism.framework.math.CubismMatrix44;

/**
 * CubismModel 管理类：模型生成/销毁/切换。只在 GL 线程触碰 model。
 */
public class LAppMinimumLive2DManager {
    public static LAppMinimumLive2DManager getInstance() {
        if (s_instance == null) {
            s_instance = new LAppMinimumLive2DManager();
        }
        return s_instance;
    }

    public static void releaseInstance() {
        s_instance = null;
    }

    // ---- 供控制面线程无锁读取的状态镜像（GL 线程写入） ----
    private static volatile String s_currentModelName;
    private static volatile String s_currentType = "live2d";   // "live2d" | "l3d"
    private static volatile String s_lastError;
    private static volatile String s_playingAnim;
    private static volatile boolean s_playingLoop = true;
    private static volatile float s_playingSpeed = 1f;

    public static String peekCurrentModel() {
        return s_currentModelName;
    }

    /** 当前上屏模型类型："live2d" | "l3d"。 */
    public static String peekCurrentType() {
        return s_currentType;
    }

    /** 当前播放中的 l3d 动作名（无则 null）。 */
    public static String peekPlayingAnim() {
        return s_playingAnim;
    }

    public static boolean peekPlayingLoop() {
        return s_playingLoop;
    }

    public static float peekPlayingSpeed() {
        return s_playingSpeed;
    }

    public static String peekLastError() {
        return s_lastError;
    }

    /**
     * 按名字加载模型：名字经 ModelRepository 解析（内置 assets 或外部存储），
     * 类型按 Descriptor.type 分流 Live2D / l3d 渲染管线。
     * 必须在 GL 线程调用。
     */
    public void loadModel(String name) {
        ModelRepository repo = ModelRepository.get(LAppMinimumDelegate.getInstance().getActivity());
        ModelRepository.Descriptor d = repo.find(name);
        if (d == null) {
            s_lastError = "model not found: " + name;
            return;
        }
        if ("l3d".equals(d.type)) {
            load3d(d);
        } else {
            loadLive2d(d, name);
        }
    }

    private void load3d(ModelRepository.Descriptor d) {
        try {
            L3dScene old = scene3d;
            LAppMinimumModel oldL2d = model;
            LAppMinimumDelegate.getInstance().getTextureManager().releaseAll();

            L3dScene created = L3dScene.create(d.homeDir);
            scene3d = created;
            model = null;
            s_currentModelName = d.name;
            s_currentType = "l3d";
            s_playingAnim = created.getPlayingName();
            s_playingLoop = true;
            s_playingSpeed = 1f;
            s_lastError = null;
            if (oldL2d != null) {
                oldL2d.deleteModel();
            }
            if (old != null) {
                old.close();
            }
        } catch (Throwable t) {
            Log.e("LAppMinimumLive2DManager", "l3d load failed: " + d.name, t);
            s_lastError = "l3d load failed: " + t.getMessage();
        }
    }

    private void loadLive2d(ModelRepository.Descriptor d, String name) {
        try {
            // 释放旧贴图显存后再装载新模型
            LAppMinimumDelegate.getInstance().getTextureManager().releaseAll();

            L3dScene old3d = scene3d;
            LAppMinimumModel old = model;
            LAppMinimumModel created = new LAppMinimumModel(d.homeDir);
            created.loadAssets(d.homeDir, d.model3FileName);
            model = created;
            scene3d = null;
            s_currentModelName = name;
            s_currentType = "live2d";
            s_playingAnim = null;
            s_lastError = null;
            if (old != null) {
                old.deleteModel();
            }
            if (old3d != null) {
                old3d.close();
            }
        } catch (Throwable t) {
            s_lastError = "load failed: " + t;
        }
    }

    /** GL 上下文重建后调用：整模重载（贴图与渲染器都绑在旧上下文上，无法局部修复）。 */
    public void reloadCurrentModel() {
        if (model != null && s_currentModelName != null) {
            Log.i("LAppMinimumLive2DManager", "reload model for new GL context: " + s_currentModelName);
            loadModel(s_currentModelName);
        }
    }

    // モデル更新処理及び描画処理を行う
    public void onUpdate() {
        com.live2d.demo.minimum.control.FaceTracker.get().applyOnGlThread(this);
        int width = LAppMinimumDelegate.getInstance().getWindowWidth();
        int height = LAppMinimumDelegate.getInstance().getWindowHeight();

        // l3d：独立渲染管线（自管投影/镜像/姿态，见 L3dScene）
        L3dScene s3d = scene3d;
        if (s3d != null) {
            long now = System.nanoTime();
            float dt = s_lastFrameNano == 0 ? 0f : (now - s_lastFrameNano) / 1e9f;
            s_lastFrameNano = now;
            s3d.update(Math.min(dt, 0.25f), width, height, poseX, poseY, poseZoom);
            s_playingAnim = s3d.getPlayingName();
            s_playingLoop = s3d.isLoop();
            s_playingSpeed = s3d.getSpeed();
            return;
        }
        s_lastFrameNano = 0;

        projection.loadIdentity();

        if (model.getModel().getCanvasWidth() > 1.0f && width < height) {
            // 横に長いモデルを縦長ウィンドウに表示する際モデルの横サイズでscaleを算出する
            model.getModelMatrix().setWidth(2.0f);
            projection.scale(1.0f, (float) width / (float) height);
        } else {
            projection.scale((float) height / (float) width, 1.0f);
        }

        // 必要があればここで乗算する
        if (viewMatrix != null) {
            viewMatrix.multiplyByMatrix(projection);
        }

        // 投影光路上下颠倒：屏幕空间垂直镜像
        if (FLIP_VERTICALLY) {
            projection.scaleRelative(1.0f, -1.0f);
        }

        // 用户姿态：先绕屏幕中心缩放，再按实体屏方向平移（y+ = 实体屏向上；
        // flip 已把线性部分的 y 取反，这里的 +poseY 经它映射后即实体屏向上）
        final float zoom = poseZoom;
        projection.scaleRelative(zoom, zoom);
        projection.translateRelative(poseX, poseY);

        model.update();
        model.draw(projection);     // 参照渡しなのでprojectionは変質する
    }

    /**
     * 画面をドラッグした時の処理
     *
     * @param x 画面のx座標
     * @param y 画面のy座標
     */
    public void onDrag(float x, float y) {
        if (scene3d != null || model == null) return;   // l3d 触摸不做视线随动
        model.setDragging(x, FLIP_VERTICALLY ? -y : y);
    }

    // ---- l3d 动作控制（控制面经 delegate.post 在 GL 线程调） ----

    /** 播放动作；模型非 l3d 或名字未知返回 false。 */
    public boolean playAnimation(String name, boolean loop, float speed) {
        L3dScene s3d = scene3d;
        if (s3d == null) return false;
        boolean ok = s3d.play(name, loop, speed);
        if (ok) {
            s_playingAnim = s3d.getPlayingName();
            s_playingLoop = loop;
            s_playingSpeed = s3d.getSpeed();
        }
        return ok;
    }

    public void stopAnimation() {
        L3dScene s3d = scene3d;
        if (s3d != null) s3d.stop();
        s_playingAnim = null;
    }

    // ---- 视线/头随动（控制面线程调用，dragManager 本就跨线程喂） ----
    private volatile float lookX;
    private volatile float lookY;

    /** 归一化坐标 -1..1，x 按画面纵横比映射到逻辑视图。 */
    public void lookAt(float nx, float ny) {
        if (model == null) return;
        lookX = clamp(nx, -1.0f, 1.0f);
        lookY = clamp(ny, -1.0f, 1.0f);
        int w = LAppMinimumDelegate.getInstance().getWindowWidth();
        int h = Math.max(1, LAppMinimumDelegate.getInstance().getWindowHeight());
        onDrag(lookX * ((float) w / (float) h), lookY);
    }

    public void lookAtReset() {
        lookAt(0f, 0f);
    }

    public float getLookX() {
        return lookX;
    }

    public float getLookY() {
        return lookY;
    }

    /**
     * シングルトンインスタンス
     */
    private static LAppMinimumLive2DManager s_instance;

    private LAppMinimumLive2DManager() {
        Context ctx = LAppMinimumDelegate.getInstance().getActivity();
        String saved = ModelRepository.get(ctx).getSelected();
        loadModel(saved != null ? saved : "Hiyori");
        if (s_currentModelName == null) {
            // 开机时外部存储可能未就绪导致选中模型加载失败：兜底到内置模型
            Log.w("LAppMinimumLive2DManager", "saved model failed, fallback to Hiyori");
            loadModel("Hiyori");
        }

        android.content.SharedPreferences p = ctx.getSharedPreferences("live2d", Context.MODE_PRIVATE);
        poseX = p.getFloat("pose_x", 0.0f);
        poseY = p.getFloat("pose_y", 0.0f);
        poseZoom = p.getFloat("pose_zoom", 1.0f);
    }

    /**
     * 投影光路上下颠倒时置 true，将渲染输出做屏幕空间垂直镜像。
     */
    private static final boolean FLIP_VERTICALLY = true;

    // ---- 用户姿态（volatile，控制面线程写、GL 线程每帧读） ----
    private volatile float poseX;
    private volatile float poseY;
    private volatile float poseZoom = 1.0f;

    public float getPoseX() {
        return poseX;
    }

    public float getPoseY() {
        return poseY;
    }

    public float getPoseZoom() {
        return poseZoom;
    }

    /**
     * 设置姿态并持久化。x/y 为实体屏方向的逻辑坐标位移（y+ 向上），zoom 围绕屏幕中心。
     */
    public void setPose(float x, float y, float zoom) {
        poseX = clamp(x, -2.0f, 2.0f);
        poseY = clamp(y, -2.0f, 2.0f);
        poseZoom = clamp(zoom, 0.2f, 5.0f);

        Context ctx = LAppMinimumDelegate.getInstance().getActivity();
        if (ctx != null) {
            ctx.getSharedPreferences("live2d", Context.MODE_PRIVATE).edit()
                .putFloat("pose_x", poseX)
                .putFloat("pose_y", poseY)
                .putFloat("pose_zoom", poseZoom)
                .commit();
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** 当前 Live2D 模型（可能为 null：l3d 上屏中、尚未加载或加载失败）。 */
    public LAppMinimumModel currentModel() {
        return model;
    }

    private LAppMinimumModel model;
    private L3dScene scene3d;                 // 非空 = 当前由 l3d 管线渲染
    private long s_lastFrameNano;

    private final CubismMatrix44 viewMatrix = CubismMatrix44.create();
    private final CubismMatrix44 projection = CubismMatrix44.create();
}
