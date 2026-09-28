package com.live2d.demo.minimum.l3d;

import android.opengl.GLES20;
import android.util.Log;

import com.live2d.demo.minimum.LAppMinimumPal;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;

/**
 * l3d 场景：模型 + 动作状态 + 相机自动取景 + 屏幕空间姿态，GL 线程专用。
 * 资产 = manifest.json 约定的包目录（model.glb + anims/*.glb）。
 *
 * 相机无旋转、沿 +Z 看向模型：projView = P * T(-eye)；
 * 屏幕空间姿态 uViewPose = flipV · zoom · translate（对齐 Live2D 的 pose 语义：
 * y+ 实体屏向上、zoom 绕中心、flipV 补投影光路上下镜像）。
 */
public final class L3dScene {
    private static final String TAG = "L3dScene";
    private static final float FOV_RAD = (float) Math.toRadians(40);

    private final String homeDir;
    private final L3dModel model;
    private final L3dRenderer renderer;
    private final HashMap<String, AnimEntry> anims = new HashMap<String, AnimEntry>();
    private final String defaultAnimation;

    // ---- 播放状态（volatile 供控制面线程读） ----
    private volatile String playingName;
    private volatile boolean loop = true;
    private volatile float speed = 1f;
    private double playhead;
    private L3dClip active;

    static final class AnimEntry {
        final String file;          // 相对包目录
        final float durationS;      // manifest 元数据（解析前的声明值）
        L3dClip clip;               // 懒解析（首个 play 时）

        AnimEntry(String file, float durationS) {
            this.file = file;
            this.durationS = durationS;
        }
    }

    private L3dScene(String homeDir, L3dModel model, L3dRenderer renderer,
                     HashMap<String, AnimEntry> anims, String defaultAnimation) {
        this.homeDir = homeDir;
        this.model = model;
        this.renderer = renderer;
        this.anims.putAll(anims);
        this.defaultAnimation = defaultAnimation;
    }

    /** 解析包目录并上传 GL。GL 线程调用；失败抛异常由 manager 记入 last_error。 */
    public static L3dScene create(String homeDir) throws Exception {
        byte[] mb = LAppMinimumPal.loadFileAsBytes(homeDir + "manifest.json");
        JSONObject manifest = new JSONObject(new String(mb, "UTF-8"));
        int fmt = manifest.optInt("format_version", -1);
        if (fmt != 1) {
            throw new L3dGlb.L3dException("unsupported l3d format_version: " + fmt);
        }
        String modelFile = manifest.optString("model", "model.glb");

        L3dRenderer renderer = new L3dRenderer();
        try {
            byte[] gb = LAppMinimumPal.loadFileAsBytes(homeDir + modelFile);
            L3dGlb glb = L3dGlb.parse(gb);
            L3dModel model = L3dModel.load(glb);

            HashMap<String, AnimEntry> anims = new HashMap<String, AnimEntry>();
            JSONArray ja = manifest.optJSONArray("animations");
            if (ja != null) {
                for (int i = 0; i < ja.length(); i++) {
                    JSONObject a = ja.getJSONObject(i);
                    String an = a.optString("name", "");
                    String af = a.optString("file", "");
                    if (!an.equals("") && !af.equals("")) {
                        anims.put(an, new AnimEntry(af, (float) a.optDouble("duration_s", 0)));
                    }
                }
            }
            L3dScene scene = new L3dScene(homeDir, model, renderer, anims,
                manifest.optString("default_animation", null));
            // 装载即播默认动作（导出管线约定：名为 idle* 的动作；没有则静置 rest pose）
            if (scene.defaultAnimation != null && anims.containsKey(scene.defaultAnimation)) {
                scene.play(scene.defaultAnimation, true, 1f);
            }
            return scene;
        } catch (Throwable t) {
            renderer.release();
            throw t;
        }
    }

    // ---- 控制面入口（经 manager 投递到 GL 线程执行） ----

    /** 播放动作；未知名字或解析失败返回 false。 */
    public boolean play(String name, boolean loopArg, float speedArg) {
        AnimEntry e = anims.get(name);
        if (e == null) return false;
        if (e.clip == null) {
            try {
                byte[] ab = LAppMinimumPal.loadFileAsBytes(homeDir + e.file);
                e.clip = L3dClip.load(L3dGlb.parse(ab), model, name);
                Log.i(TAG, "clip loaded: " + name + " (" + e.clip.durationS + "s)");
            } catch (Exception ex) {
                Log.w(TAG, "clip load failed: " + name, ex);
                return false;
            }
        }
        active = e.clip;
        playhead = 0;
        loop = loopArg;
        speed = Math.max(0.05f, Math.min(4f, speedArg));
        playingName = name;
        return true;
    }

    public void stop() {
        active = null;
        playingName = null;
    }

    public String getPlayingName() {
        return playingName;
    }

    public boolean isLoop() {
        return loop;
    }

    public float getSpeed() {
        return speed;
    }

    java.util.Set<String> animationNames() {
        return anims.keySet();
    }

    // ---- 每帧（GL 线程） ----

    public void update(float dtSeconds, int width, int height, float poseX, float poseY, float poseZoom) {
        final long perfT0 = perfDbg >= 0 ? System.nanoTime() : 0;
        final boolean prof = perfDbg >= 0;
        if (active != null) {
            playhead += dtSeconds * speed;
            float dur = active.durationS;
            if (dur > 0) {
                if (loop) {
                    playhead %= dur;
                } else if (playhead >= dur) {
                    playhead = dur;
                    playingName = null;     // 单次播完：停在末帧
                }
            }
        }
        long a0 = prof ? System.nanoTime() : 0;
        model.resetPose();
        long a1 = prof ? System.nanoTime() : 0;
        if (active != null) {
            active.apply(model, (float) playhead);
        }
        long a2 = prof ? System.nanoTime() : 0;
        model.updateGlobals();
        long a3 = prof ? System.nanoTime() : 0;
        if (prof) { perfReset += a1 - a0; perfApply += a2 - a1; perfGlobals += a3 - a2; }
        final long perfT1 = prof ? System.nanoTime() : 0;
        // The projection must use this frame's skinned vertices. A hand moving
        // toward the camera can pass the rest-pose depth range and disappear.
        long t0 = prof ? System.nanoTime() : 0;
        model.skinFrame();
        long t1 = prof ? System.nanoTime() : 0;

        // 相机：rest 包围盒自动取景（无旋转，看向 -Z）
        float[] mn = model.sceneMin();
        float[] mx = model.sceneMax();
        float cx = finiteHalf(mn[0], mx[0]);
        float cy = finiteHalf(mn[1], mx[1]);
        float cz = finiteHalf(mn[2], mx[2]);
        float radius = 0.5f * (float) Math.sqrt(
            (mx[0] - mn[0]) * (mx[0] - mn[0])
                + (mx[1] - mn[1]) * (mx[1] - mn[1])
                + (mx[2] - mn[2]) * (mx[2] - mn[2]));
        if (Float.isInfinite(radius) || Float.isNaN(radius) || radius < 1e-4f) radius = 1f;
        float dist = radius / (float) Math.tan(FOV_RAD / 2f) * 1.05f;
        if (Float.isInfinite(dist) || Float.isNaN(dist)) dist = 10f;
        float aspect = (float) width / (float) Math.max(1, height);

        float[] proj = projScratch;
        // view[14] = cz - dist, so a world-space z becomes depth dist - cz - z.
        // Use the current skinned depth, with a small margin for interpolation.
        // This keeps face decals precise without clipping limbs or hair that
        // extend beyond the rest-pose box.
        float frameMinZ = model.frameMinZ();
        float frameMaxZ = model.frameMaxZ();
        float pad = Math.max(radius * 0.05f, (frameMaxZ - frameMinZ) * 0.05f);
        float near = dist - cz - frameMaxZ - pad;
        float far = dist - cz - frameMinZ + pad;
        if (Float.isNaN(near) || Float.isInfinite(near)
            || Float.isNaN(far) || Float.isInfinite(far)
            || !(near > radius * 0.02f) || !(far > near + 1e-3f)) {
            near = Math.max(dist - radius * 2.5f, radius * 0.05f);
            far = dist + radius * 3f;
        }
        L3dMat.perspective(proj, FOV_RAD, aspect, near, far);
        float[] view = viewScratch;
        L3dMat.identity(view);
        view[12] = -cx;
        view[13] = -(cy + radius * 0.25f);
        view[14] = cz - dist;

        // 复合矩阵 = P · pose · V（pose 是视空间操作，必须夹在 P 与 V 之间；
        // 曾错写成 P·V·pose，flip/zoom 作用在模型空间、再被相机平移推出视野）。
        // 全部 CPU 预乘：本机驱动误编译 mat4 长乘法链，见 L3dRenderer 注释。
        // pose = flipV·zoom·translate；y+ 实体屏向上，flipV 补投影光路上下镜像。
        float[] pose = poseScratch;
        L3dMat.viewPose(pose, aspect, poseX, poseY, poseZoom);
        L3dMat.flipV(pose);
        L3dMat.mul(pose, pose, view);       // pose·V
        L3dMat.mul(proj, proj, pose);       // P·pose·V

        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthFunc(GLES20.GL_LEQUAL);
        renderer.resetAttribArrays();
        renderer.useProgram();
        model.draw(renderer, proj);
        L3dRenderer.endFrame();
        if (perfDbg >= 0) {
            long t2 = System.nanoTime();
            perfAnim += perfT1 - perfT0;
            perfSkin += t1 - t0;
            perfDraw += t2 - t1;
            perfFrames++;
            if (perfFrames == 120) {
                android.util.Log.i(TAG, "perf(uS): anim=" + (perfAnim / 120 / 1000)
                    + " [reset=" + (perfReset / 120 / 1000)
                    + " apply=" + (perfApply / 120 / 1000)
                    + " globals=" + (perfGlobals / 120 / 1000) + "]"
                    + " skin=" + (perfSkin / 120 / 1000)
                    + " draw=" + (perfDraw / 120 / 1000));
                perfAnim = 0; perfSkin = 0; perfDraw = 0; perfFrames = 0;
                perfReset = 0; perfApply = 0; perfGlobals = 0;
            }
        }
    }

    private static float finiteHalf(float a, float b) {
        float h = (a + b) / 2f;
        return (Float.isInfinite(h) || Float.isNaN(h)) ? 0f : h;
    }


    private int perfDbg = 0;   // 置 1 开启每 120 帧的分段耗时日志
    private long perfAnim, perfSkin, perfDraw, perfReset, perfApply, perfGlobals;
    private int perfFrames;

    private final float[] projScratch = new float[16];
    private final float[] viewScratch = new float[16];
    private final float[] poseScratch = new float[16];

    public void close() {
        model.close();
        renderer.release();
    }
}
