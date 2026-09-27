/*
 * Copyright(c) Live2D Inc. All rights reserved.
 *
 * Use of this source code is governed by the Live2D Open Software license
 * that can be found at http://live2d.com/eula/live2d-open-software-license-agreement_en.html.
 */

package com.live2d.demo.minimum;

import com.live2d.demo.LAppDefine;
import com.live2d.sdk.cubism.framework.effect.CubismBreath;
import com.live2d.sdk.cubism.framework.effect.CubismEyeBlink;
import com.live2d.sdk.cubism.framework.CubismDefaultParameterId;
import com.live2d.sdk.cubism.framework.CubismFramework;
import com.live2d.sdk.cubism.framework.CubismModelSettingJson;
import com.live2d.sdk.cubism.framework.ICubismModelSetting;
import com.live2d.sdk.cubism.framework.id.CubismId;
import com.live2d.sdk.cubism.framework.id.CubismIdManager;
import com.live2d.sdk.cubism.framework.math.CubismMatrix44;
import com.live2d.sdk.cubism.framework.model.CubismMoc;
import com.live2d.sdk.cubism.framework.model.CubismUserModel;
import com.live2d.sdk.cubism.framework.motion.ACubismMotion;
import com.live2d.sdk.cubism.framework.motion.CubismExpressionMotion;
import com.live2d.sdk.cubism.framework.motion.CubismMotion;
import com.live2d.sdk.cubism.framework.motion.CubismMotionManager;
import com.live2d.sdk.cubism.framework.motion.IFinishedMotionCallback;
import com.live2d.sdk.cubism.framework.rendering.CubismRenderer;
import com.live2d.sdk.cubism.framework.rendering.android.CubismOffscreenSurfaceAndroid;
import com.live2d.sdk.cubism.framework.rendering.android.CubismRendererAndroid;
import com.live2d.sdk.cubism.framework.utils.CubismDebug;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

public class LAppMinimumModel extends CubismUserModel {
    public LAppMinimumModel(String modelDirName) {
        CubismIdManager idManager = CubismFramework.getIdManager();

        idParamAngleX = idManager.getId(CubismDefaultParameterId.ParameterId.ANGLE_X.getId());
        idParamAngleY = idManager.getId(CubismDefaultParameterId.ParameterId.ANGLE_Y.getId());
        idParamAngleZ = idManager.getId(CubismDefaultParameterId.ParameterId.ANGLE_Z.getId());
        idParamBodyAngleX = idManager.getId(CubismDefaultParameterId.ParameterId.BODY_ANGLE_X.getId());
        idParamEyeBallX = idManager.getId(CubismDefaultParameterId.ParameterId.EYE_BALL_X.getId());
        idParamEyeBallY = idManager.getId(CubismDefaultParameterId.ParameterId.EYE_BALL_Y.getId());

        modelHomeDirectory = modelDirName;
    }

    public void loadAssets(final String dir, final String fileName) {
        modelHomeDirectory = dir;
        String filePath = modelHomeDirectory + fileName;

        // Setup model
        setupModel(filePath);

        // Setup renderer.
        CubismRenderer renderer = CubismRendererAndroid.create();
        setupRenderer(renderer);

        setupTextures();
    }

    /**
     * Delete the model which LAppModel has.
     */
    public void deleteModel() {
        delete();
    }

    /**
     * モデルの更新処理。モデルのパラメーターから描画状態を決定する
     */
    public void update() {
        isUpdated(false);

        final float deltaTimeSeconds = LAppMinimumPal.getDeltaTime();
        _userTimeSeconds += deltaTimeSeconds;

        dragManager.update(deltaTimeSeconds);

        // モーションによるパラメーター更新の有無
        boolean isMotionUpdated = false;

        // 前回セーブされた状態をロード
        model.loadParameters();

        // モーションの再生がない場合、待機モーションの中からランダムで再生する
        // （Idle グループを持たないモデルでは、軽量 w-* 動作を間隔で挿し、
        //   無い時間は呼吸+まばたき+物理が待機を担う）
        if (motionManager.isFinished()) {
            if (idleEnabled) {
                if (modelSetting.getMotionCount(LAppDefine.MotionGroup.IDLE.getId()) > 0) {
                    startMotion(LAppDefine.MotionGroup.IDLE.getId(), 0, LAppDefine.Priority.IDLE.getPriority());
                } else if (idleCandidates.size() > 0) {
                    idleTimerSeconds += deltaTimeSeconds;
                    if (idleTimerSeconds >= idleIntervalSeconds) {
                        startMotion(idleCandidates.get(random.nextInt(idleCandidates.size())), 0,
                            LAppDefine.Priority.IDLE.getPriority());
                        idleTimerSeconds = 0f;
                    }
                }
            }
        } else {
            // モーションを更新
            isMotionUpdated = motionManager.updateMotion(model, deltaTimeSeconds);
        }

        // モデルの状態を保存
        model.saveParameters();

        // eye blink
        // メインモーションの更新がないときだけまばたきする
        if (!isMotionUpdated) {
            if (eyeBlink != null) {
                eyeBlink.updateParameters(model, deltaTimeSeconds);
            }
        }
        // 表情でパラメータ更新（相対変化）
        if (expressionManager != null) {
            expressionManager.updateMotion(model, deltaTimeSeconds);
        }

        // 面部叠加层（face_* 动作组）：后于眨眼套用，动作已键的面部参数覆盖眨眼
        faceMotionManager.updateMotion(model, deltaTimeSeconds);

        // ドラッグ追従機能
        // ドラッグによる顔の向きの調整
        float dragX = dragManager.getX();
        float dragY = dragManager.getY();

        model.addParameterValue(idParamAngleX, dragX * 30); // -30から30の値を加える
        model.addParameterValue(idParamAngleY, dragY * 30);
        model.addParameterValue(idParamAngleZ, dragX * dragY * (-30));

        // ドラッグによる体の向きの調整
        model.addParameterValue(idParamBodyAngleX, dragX * 10); // -10から10の値を加える

        // ドラッグによる目の向きの調整
        model.addParameterValue(idParamEyeBallX, dragX);  // -1から1の値を加える
        model.addParameterValue(idParamEyeBallY, dragY);

        // Breath Function
        if (breath != null) {
            breath.updateParameters(model, deltaTimeSeconds);
        }

        // Physics Setting
        if (physics != null) {
            physics.evaluate(model, deltaTimeSeconds);
        }

        // Pose Setting
        if (pose != null) {
            pose.updateParameters(model, deltaTimeSeconds);
        }

        // 控制面直控（参数 TTL 覆盖 + 口型混合器）：最后套用，烘焙前生效
        applyControlOverrides();

        model.update();

        isUpdated(true);
    }

    /**
     * 引数で指定したモーションの再生を開始する。
     * コールバック関数が渡されなかった場合にそれをnullとして同メソッドを呼び出す。
     *
     * @param group    モーショングループ名
     * @param number   グループ内の番号
     * @param priority 優先度
     * @return 開始したモーションの識別番号を返す。個別のモーションが終了したか否かを判別するisFinished()の引数で使用する。開始できない時は「-1」
     */
    public int startMotion(final String group, int number, int priority) {
        return startMotion(group, number, priority, null);
    }

    /**
     * 引数で指定したモーションの再生を開始する。
     *
     * @param group                   モーショングループ名
     * @param number                  グループ内の番号
     * @param priority                優先度
     * @param onFinishedMotionHandler モーション再生終了時に呼ばれるコールバック関数。nullの場合は呼ばれない。
     * @return 開始したモーションの識別番号を返す。個別のモーションが終了したか否かを判定するisFinished()の引数で使用する。開始できない時は「-1」
     */
    public int startMotion(final String group,
                           int number,
                           int priority,
                           IFinishedMotionCallback onFinishedMotionHandler) {
        if (priority == LAppDefine.Priority.FORCE.getPriority()) {
            motionManager.setReservationPriority(priority);
        } else if (!motionManager.reserveMotion(priority)) {
            if (LAppDefine.DEBUG_LOG_ENABLE) {
                CubismFramework.coreLogFunction("[APP] cannot start motion.");
            }
            return -1;
        }

        final String fileName = modelSetting.getMotionFileName(group, number);

        // ex) idle_0
        String name = group + "_" + number;

        CubismMotion motion = (CubismMotion) motions.get(name);

        if (motion == null) {
            // 懒加载：缓存未命中且模型确实声明了该动作文件时，从存储加载并回填缓存
            // （官方 5-r.1 最小示例此处条件为 fileName.equals("")，属条件反置且未回填，已修复）
            if (!fileName.equals("")) {
                String path = modelHomeDirectory + fileName;

                byte[] buffer;
                buffer = LAppMinimumPal.loadFileAsBytes(path);

                CubismMotion tmpMotion = loadMotion(buffer, onFinishedMotionHandler);
                if (tmpMotion != null) {
                    motion = tmpMotion;

                    float fadeInTime = modelSetting.getMotionFadeInTimeValue(group, number);
                    if (fadeInTime != -1.0f) {
                        motion.setFadeInTime(fadeInTime);
                    }

                    float fadeOutTime = modelSetting.getMotionFadeOutTimeValue(group, number);
                    if (fadeOutTime != -1.0f) {
                        motion.setFadeOutTime(fadeOutTime);
                    }

                    motion.setEffectIds(eyeBlinkIds, lipSyncIds);
                    motions.put(name, motion);
                }
            }
        } else {
            motion.setFinishedMotionHandler(onFinishedMotionHandler);
        }

        if (motion == null) {
            return -1;
        }

        if (LAppDefine.DEBUG_LOG_ENABLE) {
            CubismFramework.coreLogFunction("[APP] start motion: " + group + "_" + number);
        }

        return motionManager.startMotionPriority(motion, priority);
    }

    /**
     * 面部叠加层用 MotionManager（face_* 动作组），与身体层并行更新。
     */
    private final CubismMotionManager faceMotionManager = new CubismMotionManager();

    // ---- 控制面入口（GL 线程调用）----

    /** 身体层播放动作组（本模型每组 1 个动作）。 */
    public int playMotion(String group, int priority) {
        return startMotion(group, 0, priority);
    }

    /** 面部叠加层播放 face_* 动作组，与身体动作并行。返回 -1 表示组不存在或加载失败。 */
    public int playFaceMotion(String group) {
        if (modelSetting == null || modelSetting.getMotionCount(group) <= 0) return -1;
        final String fileName = modelSetting.getMotionFileName(group, 0);
        if (fileName.equals("")) return -1;

        byte[] buffer = LAppMinimumPal.loadFileAsBytes(modelHomeDirectory + fileName);
        CubismMotion motion = loadMotion(buffer);
        if (motion == null) return -1;

        float fadeIn = modelSetting.getMotionFadeInTimeValue(group, 0);
        if (fadeIn != -1.0f) motion.setFadeInTime(fadeIn);
        float fadeOut = modelSetting.getMotionFadeOutTimeValue(group, 0);
        if (fadeOut != -1.0f) motion.setFadeOutTime(fadeOut);
        motion.setEffectIds(eyeBlinkIds, lipSyncIds);

        return faceMotionManager.startMotionPriority(motion, 3);
    }

    /** 清除面部叠加层（当前表情动作立即淡出停止）。 */
    public void clearFaceMotion() {
        faceMotionManager.stopAllMotions();
    }

    /** 全部动作组名（身体 w-* 与面部 face_*）。 */
    public String[] getMotionGroupNames() {
        int n = modelSetting.getMotionGroupCount();
        String[] groups = new String[n];
        for (int i = 0; i < n; i++) {
            groups[i] = modelSetting.getMotionGroupName(i);
        }
        return groups;
    }

    public boolean hasMotionGroup(String group) {
        return modelSetting != null && modelSetting.getMotionCount(group) > 0;
    }

    // ---- 控制面直控状态 ----
    private static final long AUTO_CLOSE_NANOS = 150_000_000L;   // 口型停推自动闭合
    private static final float MOUTH_SCALE_Y_WEIGHT = 0.8f;      // ScaleY 通道权重

    private volatile float mouthLevel;
    private volatile long mouthUpdateNanos;
    private final ConcurrentHashMap<String, ParamOverride> paramOverrides =
        new ConcurrentHashMap<String, ParamOverride>();
    private volatile boolean idleEnabled = true;
    private volatile float idleIntervalSeconds = 15.0f;
    private float idleTimerSeconds;                              // 仅 GL 线程读写
    private final Random random = new Random();
    private List<String> idleCandidates = new ArrayList<String>();

    private static final class ParamOverride {
        final float value;
        final long expiresAtNanos;

        ParamOverride(float value, long expiresAtNanos) {
            this.value = value;
            this.expiresAtNanos = expiresAtNanos;
        }
    }

    /** 直控参数（TTL 内每帧压过动作与物理，过期自动回落）。 */
    public void setParamOverride(String id, float value, long ttlMs) {
        long ttl = Math.max(0, Math.min(ttlMs, 60000L));
        paramOverrides.put(id, new ParamOverride(value, System.nanoTime() + ttl * 1000000L));
    }

    public void clearParamOverrides() {
        paramOverrides.clear();
    }

    public int overrideCount() {
        return paramOverrides.size();
    }

    /** 口型混合器输入 0..1；持续推送期间有效，停推 150ms 自动闭合。 */
    public void setMouthLevel(float level) {
        mouthLevel = Math.max(0f, Math.min(1f, level));
        mouthUpdateNanos = System.nanoTime();
    }

    public float getMouthLevel() {
        return mouthLevel;
    }

    public void setIdleEnabled(boolean enabled) {
        idleEnabled = enabled;
    }

    public boolean isIdleEnabled() {
        return idleEnabled;
    }

    public void setIdleIntervalSeconds(float seconds) {
        idleIntervalSeconds = Math.max(3f, Math.min(300f, seconds));
    }

    public float getIdleIntervalSeconds() {
        return idleIntervalSeconds;
    }

    /** 应用控制面直控。必须在 model.update() 之前（烘焙前最后一手）。 */
    private void applyControlOverrides() {
        // 口型双通道：OpenY 全权重 + ScaleY 0.8
        float level = mouthLevel;
        if (System.nanoTime() - mouthUpdateNanos > AUTO_CLOSE_NANOS) {
            level = 0f;
        }
        if (level > 0.001f) {
            CubismIdManager idManager = CubismFramework.getIdManager();
            model.setParameterValue(idManager.getId("ParamMouthOpenY"), Math.min(1.0f, level));
            model.setParameterValue(idManager.getId("ParamMouthScaleY"),
                Math.min(1.0f, level * MOUTH_SCALE_Y_WEIGHT));
        }

        // 参数 TTL 覆盖
        if (!paramOverrides.isEmpty()) {
            long now = System.nanoTime();
            CubismIdManager idManager = CubismFramework.getIdManager();
            for (Map.Entry<String, ParamOverride> e : paramOverrides.entrySet()) {
                if (e.getValue().expiresAtNanos < now) {
                    paramOverrides.remove(e.getKey());
                    continue;
                }
                model.setParameterValue(idManager.getId(e.getKey()), e.getValue().value);
            }
        }
    }

    public void draw(CubismMatrix44 matrix) {
        if (model == null) {
            LAppMinimumDelegate.getInstance().getActivity().finish();
        }

        // キャッシュ変数の定義を避けるために、multiplyByMatrix()ではなく、multiply()を使用する。
        CubismMatrix44.multiply(
            modelMatrix.getArray(),
            matrix.getArray(),
            matrix.getArray()
        );

        this.<CubismRendererAndroid>getRenderer().setMvpMatrix(matrix);
        this.<CubismRendererAndroid>getRenderer().drawModel();
    }

    public CubismOffscreenSurfaceAndroid getRenderingBuffer() {
        return renderingBuffer;
    }

    /**
     * .moc3ファイルの整合性をチェックする。
     *
     * @param mocFileName MOC3ファイル名
     * @return MOC3に整合性があるかどうか。整合性があればtrue。
     */
    public boolean hasMocConsistencyFromFile(String mocFileName) {
        assert mocFileName != null && !mocFileName.isEmpty();

        String path = mocFileName;
        path = modelHomeDirectory + path;

        byte[] buffer = LAppMinimumPal.loadFileAsBytes(path);
        boolean consistency = CubismMoc.hasMocConsistency(buffer);

        if (!consistency) {
            CubismDebug.cubismLogInfo("Inconsistent MOC3.");
        } else {
            CubismDebug.cubismLogInfo("Consistent MOC3.");
        }

        return consistency;
    }

    // model3.jsonからモデルを生成する
    private boolean setupModel(String model3JsonPath) {
        byte[] model3Json = LAppMinimumPal.loadFileAsBytes(model3JsonPath);

        CubismModelSettingJson modelSetting = null;
        modelSetting = new CubismModelSettingJson(model3Json);

        if (modelSetting != null) {
            this.modelSetting = modelSetting;
        }

        // model3.jsonが上手く読み込まれていない場合終了する
        if (this.modelSetting.getJson() == null) {
            if (LAppDefine.DEBUG_LOG_ENABLE) {
                CubismFramework.coreLogFunction("[ERROR]model3.json is not found");
            }
            LAppMinimumDelegate.getInstance().getActivity().finish();
        }

        // まばたき・呼吸（minimum では初期化されないためここで作る）
        if (modelSetting.getEyeBlinkParameterCount() > 0) {
            eyeBlink = CubismEyeBlink.create(modelSetting);
        } else {
            eyeBlink = CubismEyeBlink.create(); // モデル既定パラメータ(ParamEyeL/Ropen)を使用
        }
        breath = CubismBreath.create();
        {
            List<CubismBreath.BreathParameterData> breathParameters =
                new ArrayList<CubismBreath.BreathParameterData>();
            breathParameters.add(new CubismBreath.BreathParameterData(idParamAngleX, 0.0f, 15.0f, 6.5345f, 0.5f));
            breathParameters.add(new CubismBreath.BreathParameterData(idParamAngleY, 0.0f, 8.0f, 3.5345f, 0.5f));
            breathParameters.add(new CubismBreath.BreathParameterData(idParamAngleZ, 0.0f, 10.0f, 5.5345f, 0.5f));
            breathParameters.add(new CubismBreath.BreathParameterData(idParamBodyAngleX, 0.0f, 4.0f, 15.5345f, 0.5f));
            breathParameters.add(new CubismBreath.BreathParameterData(
                CubismFramework.getIdManager().getId(CubismDefaultParameterId.ParameterId.BREATH.getId()),
                0.5f, 0.5f, 3.2345f, 0.5f));
            breath.setParameters(breathParameters);
        }

        // Load Cubism Model
        {
            String path = this.modelSetting.getModelFileName();
            if (!path.equals("")) {
                String modelPath = modelHomeDirectory + path;
                byte[] buffer = LAppMinimumPal.loadFileAsBytes(modelPath);

                loadModel(buffer, mocConsistency);
            }
        }

        // load expression files(.exp3.json)
        // 表情モーションの読み込み
        if (this.modelSetting.getExpressionCount() > 0) {
            final int count = this.modelSetting.getExpressionCount();

            for (int i = 0; i < count; i++) {
                String name = this.modelSetting.getExpressionName(i);

                String path = this.modelSetting.getExpressionFileName(i);
                String modelPath = modelHomeDirectory + path;

                byte[] buffer = LAppMinimumPal.loadFileAsBytes(modelPath);

                CubismExpressionMotion motion = loadExpression(buffer);

                expressions.put(name, motion);
            }
        }

        // Physics
        {
            String path = this.modelSetting.getPhysicsFileName();
            if (!path.equals("")) {
                String modelPath = modelHomeDirectory + path;
                byte[] buffer = LAppMinimumPal.loadFileAsBytes(modelPath);

                loadPhysics(buffer);
            }
        }

        // Pose
        {
            String path = this.modelSetting.getPoseFileName();
            if (!path.equals("")) {
                String modelPath = modelHomeDirectory + path;

                byte[] buffer = LAppMinimumPal.loadFileAsBytes(modelPath);

                loadPose(buffer);
            }
        }

        // Load UserData
        {
            String path = this.modelSetting.getUserDataFile();
            if (!path.equals("")) {
                String modelPath = modelHomeDirectory + path;
                byte[] buffer = LAppMinimumPal.loadFileAsBytes(modelPath);

                loadUserData(buffer);
            }
        }


        // Set layout
        Map<String, Float> layout = new HashMap<String, Float>();
        this.modelSetting.getLayoutMap(layout);

        // If layout information exists, the model matrix is set up from it.
        if (this.modelSetting.getLayoutMap(layout)) {
            modelMatrix.setupFromLayout(layout);
        }

        model.saveParameters();

        // Load motions
        // 大規模モデル(数百モーション)の起動固まりを避けるため、既定では遅延ロード
        if (LAppDefine.PRELOAD_MOTIONS) {
            for (int i = 0; i < modelSetting.getMotionGroupCount(); i++) {
                String group = modelSetting.getMotionGroupName(i);
                preLoadMotionGroup(group);
            }
        }

        // 轻量待机动作池（无 Idle 组的模型用）：w-* 里的歪头/点头系
        idleCandidates = new ArrayList<String>();
        for (int i = 0; i < modelSetting.getMotionGroupCount(); i++) {
            String g = modelSetting.getMotionGroupName(i);
            if (g.startsWith("w-") && (g.contains("tilthead") || g.contains("nod"))) {
                idleCandidates.add(g);
            }
        }

        motionManager.stopAllMotions();

        return true;
    }

    /**
     * モーションデータをグループ名から一括でロードする。
     * モーションデータの名前はModelSettingから取得する。
     *
     * @param group モーションデータのグループ名
     **/
    private void preLoadMotionGroup(final String group) {
        final int count = modelSetting.getMotionCount(group);

        for (int i = 0; i < count; i++) {
            // ex) idle_0
            String name = group + "_" + i;

            String path = modelSetting.getMotionFileName(group, i);
            if (!path.equals("")) {
                String modelPath = modelHomeDirectory + path;

                if (LAppDefine.DEBUG_LOG_ENABLE) {
                    CubismFramework.coreLogFunction("[APP]load motion: " + path + " ==>[" + group + "_" + i + "]");
                }

                byte[] buffer;
                buffer = LAppMinimumPal.loadFileAsBytes(modelPath);

                CubismMotion tmp = loadMotion(buffer);
                if (tmp == null) {
                    continue;
                }
                CubismMotion motion = tmp;

                final float fadeInTime = modelSetting.getMotionFadeInTimeValue(group, i);
                if (fadeInTime != -1.0f) {
                    motion.setFadeInTime(fadeInTime);
                }

                final float fadeOutTime = modelSetting.getMotionFadeOutTimeValue(group, i);
                if (fadeOutTime != -1.0f) {
                    motion.setFadeOutTime(fadeOutTime);
                }

                motion.setEffectIds(eyeBlinkIds, lipSyncIds);

                motions.put(name, motion);
            }
        }
    }

    /**
     * OpenGLのテクスチャユニットにテクスチャをロードする
     */
    private void setupTextures() {
        for (int modelTextureNumber = 0; modelTextureNumber < modelSetting.getTextureCount(); modelTextureNumber++) {
            // テクスチャ名が空文字だった場合はロード・バインド処理をスキップ
            if (modelSetting.getTextureFileName(modelTextureNumber).equals("")) {
                continue;
            }

            // OpenGL ESのテクスチャユニットにテクスチャをロードする
            String texturePath = modelSetting.getTextureFileName(modelTextureNumber);
            texturePath = modelHomeDirectory + texturePath;

            LAppMinimumTextureManager.TextureInfo texture =
                LAppMinimumDelegate.getInstance()
                    .getTextureManager()
                    .createTextureFromPngFile(texturePath);
            final int glTextureNumber = texture.id;

            ((CubismRendererAndroid) getRenderer()).bindTexture(modelTextureNumber, glTextureNumber);

            // AndroidのdecodeStreamメソッドで読む場合は恐らく乗算済みアルファとなる。
            this.<CubismRendererAndroid>getRenderer().isPremultipliedAlpha(true);
        }
    }


    private ICubismModelSetting modelSetting;
    /**
     * モデルのホームディレクトリ
     */
    private String modelHomeDirectory;
    /**
     * デルタ時間の積算値[秒]
     */
    private float _userTimeSeconds;

    private final List<CubismId> eyeBlinkIds = new ArrayList<CubismId>();
    private final List<CubismId> lipSyncIds = new ArrayList<CubismId>();
    /**
     * 読み込まれているモーションのマップ
     */
    private final Map<String, ACubismMotion> motions = new HashMap<String, ACubismMotion>();
    /**
     * 読み込まれている表情のマップ
     */
    private final Map<String, ACubismMotion> expressions = new HashMap<String, ACubismMotion>();

    /**
     * パラメーターID: ParamAngleX
     */
    private final CubismId idParamAngleX;
    /**
     * パラメーターID: ParamAngleY
     */
    private final CubismId idParamAngleY;
    /**
     * パラメーターID: ParamAngleZ
     */
    private final CubismId idParamAngleZ;
    /**
     * パラメーターID: ParamBodyAngleX
     */
    private final CubismId idParamBodyAngleX;
    /**
     * パラメーターID: ParamEyeBallX
     */
    private final CubismId idParamEyeBallX;
    /**
     * パラメーターID: ParamEyeBallY
     */
    private final CubismId idParamEyeBallY;
    /**
     * フレームバッファ以外の描画先
     */
    private CubismOffscreenSurfaceAndroid renderingBuffer;
}
