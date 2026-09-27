package com.live2d.demo.minimum.l3d;

import android.opengl.GLES20;
import android.util.Log;

/**
 * l3d 的 GLES20 着色器与 uniform 位置。
 *
 * 设计约束来自本机（la0920, Adreno 304 老驱动）实测：
 *   - mat4 长乘法链会误编译（渲染出巨大错位图形）→ CPU 预乘成单个 uMVP，着色器一次乘法；
 *   - mat4 数组 uniform 的 glUniform4fv 恒报 GL_INVALID_OPERATION；
 *   - float 纹理 + 顶点纹理取样（骨骼调色板）采样结果不可靠。
 *   ⇒ 蒙皮在 CPU 侧完成（L3dModel.skinFrame 每帧写动态 VBO），着色器只做
 *     uMVP × position + 贴图采样，不使用任何高阶特性。
 *
 * 老驱动坑（见 CHANGELOG [0.6]）：进程首批 GLSL 程序链接可能静默失败，照
 * CubismShaderAndroid 的修法做链接重试。
 */
final class L3dRenderer {
    private static final String TAG = "L3dRenderer";

    private final int program;
    final int locPos;
    final int locUv;
    final int locMVP;
    final int locBaseTex;
    final int locHasTex;
    final int locBaseColor;

    L3dRenderer() {
        // vUv 必须 highp：mediump 只有 10 位尾数，在 512² 贴图上 UV 量化误差 ≈0.5 texel，
        // 会把 2 texel 高的细线特征（嘴线、睫毛）打断成串珠/虚线（本机实测；
        // 同数据 CPU 光栅化是连续细线，Blender 亦然）。FRAGMENT_PRECISION_HIGH 未定义时
        // 才退回 mediump。
        String vs =
            "precision highp float;\n"
            + "attribute vec3 aPosition;\n"
            + "attribute vec2 aTexCoord;\n"
            + "uniform mat4 uMVP;\n"
            + "varying highp vec2 vUv;\n"
            + "void main() {\n"
            + "  gl_Position = uMVP * vec4(aPosition, 1.0);\n"
            + "  vUv = aTexCoord;\n"
            + "}\n";
        String fs =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n"
            + "precision highp float;\n"
            + "varying highp vec2 vUv;\n"
            + "#else\n"
            + "precision mediump float;\n"
            + "varying mediump vec2 vUv;\n"
            + "#endif\n"
            + "uniform sampler2D uBaseTex;\n"
            + "uniform vec4 uBaseColor;\n"
            + "uniform int uHasTex;\n"
            + "void main() {\n"
            + "  vec4 c = uBaseColor;\n"
            + "  if (uHasTex == 1) c = c * texture2D(uBaseTex, vUv);\n"
            + "  gl_FragColor = c;\n"
            + "}\n";
        program = buildProgram(vs, fs);

        locPos = GLES20.glGetAttribLocation(program, "aPosition");
        locUv = GLES20.glGetAttribLocation(program, "aTexCoord");
        locMVP = GLES20.glGetUniformLocation(program, "uMVP");
        locBaseTex = GLES20.glGetUniformLocation(program, "uBaseTex");
        locHasTex = GLES20.glGetUniformLocation(program, "uHasTex");
        locBaseColor = GLES20.glGetUniformLocation(program, "uBaseColor");
        Log.i(TAG, "shader ready: pos=" + locPos + " uv=" + locUv);
    }

    void useProgram() {
        GLES20.glUseProgram(program);
    }

    /**
     * 帧首全量禁用属性数组：属性 enable 是 context 级状态，Live2D 上一次绘制可能留下
     * enabled 数组指向它自己的小 buffer；本程序 draw 时 GL 校验所有 enabled 数组，
     * 越界即 GL_INVALID_OPERATION，画面全黑。
     */
    void resetAttribArrays() {
        int[] n = new int[1];
        GLES20.glGetIntegerv(GLES20.GL_MAX_VERTEX_ATTRIBS, n, 0);
        for (int i = 0; i < n[0]; i++) {
            GLES20.glDisableVertexAttribArray(i);
        }
    }

    /** 顶点属性数组复位（3D 与 Live2D 共用一个 context，状态不能带过去）。 */
    void disableAttribs() {
        if (locPos >= 0) GLES20.glDisableVertexAttribArray(locPos);
        if (locUv >= 0) GLES20.glDisableVertexAttribArray(locUv);
        GLES20.glUseProgram(0);
    }

    void release() {
        GLES20.glDeleteProgram(program);
    }

    /** 3D 绘制结束收尾：归还给 Live2D 一个干净的 GL 状态。 */
    static void endFrame() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    /**
     * 编译 + 链接（带重试：老 QCOM 驱动在进程首批程序链接上会静默失败，
     * 重试必然成功；详见 CHANGELOG [0.6]）。
     */
    private static int buildProgram(String vsSrc, String fsSrc) {
        int vs = compile(GLES20.GL_VERTEX_SHADER, vsSrc);
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, fsSrc);
        int prog = 0;
        for (int attempt = 0; attempt < 10; attempt++) {
            prog = GLES20.glCreateProgram();
            GLES20.glAttachShader(prog, vs);
            GLES20.glAttachShader(prog, fs);
            GLES20.glLinkProgram(prog);
            int[] status = new int[1];
            GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0);
            if (status[0] == GLES20.GL_TRUE) {
                if (attempt > 0) {
                    Log.w(TAG, "program linked after " + attempt + " retry(ies)");
                }
                break;
            }
            String log = GLES20.glGetProgramInfoLog(prog);
            Log.w(TAG, "Program link log (attempt " + attempt + "): " + log);
            GLES20.glDeleteProgram(prog);
            prog = 0;
            try {
                Thread.sleep(20);
            } catch (InterruptedException ignored) {
            }
        }
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        if (prog == 0) {
            throw new L3dGlb.L3dException("shader program failed to link after retries");
        }
        return prog;
    }

    private static int compile(int type, String src) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, src);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] != GLES20.GL_TRUE) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new L3dGlb.L3dException("shader compile failed: " + log);
        }
        return shader;
    }
}
