package com.live2d.demo.minimum.l3d;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.HashMap;

import static android.opengl.GLES20.GL_ARRAY_BUFFER;
import static android.opengl.GLES20.GL_CLAMP_TO_EDGE;
import static android.opengl.GLES20.GL_ELEMENT_ARRAY_BUFFER;
import static android.opengl.GLES20.GL_FLOAT;
import static android.opengl.GLES20.GL_NEAREST;
import static android.opengl.GLES20.GL_RGBA;
import static android.opengl.GLES20.GL_TEXTURE0;
import static android.opengl.GLES20.GL_TEXTURE_2D;
import static android.opengl.GLES20.GL_TEXTURE_MAG_FILTER;
import static android.opengl.GLES20.GL_TEXTURE_MIN_FILTER;
import static android.opengl.GLES20.GL_TEXTURE_WRAP_S;
import static android.opengl.GLES20.GL_TEXTURE_WRAP_T;
import static android.opengl.GLES20.GL_UNSIGNED_SHORT;
import static android.opengl.GLES20.glActiveTexture;
import static android.opengl.GLES20.glBindBuffer;
import static android.opengl.GLES20.glBindTexture;
import static android.opengl.GLES20.glBufferData;
import static android.opengl.GLES20.glDeleteBuffers;
import static android.opengl.GLES20.glDeleteTextures;
import static android.opengl.GLES20.glDrawElements;
import static android.opengl.GLES20.glEnableVertexAttribArray;
import static android.opengl.GLES20.glGenBuffers;
import static android.opengl.GLES20.glGenTextures;
import static android.opengl.GLES20.glTexImage2D;
import static android.opengl.GLES20.glTexParameteri;
import static android.opengl.GLES20.glUniform1i;
import static android.opengl.GLES20.glUniform4fv;
import static android.opengl.GLES20.glUniformMatrix4fv;
import static android.opengl.GLES20.glVertexAttribPointer;

/**
 * l3d 模型：节点树 + 网格 + 材质贴图 + CPU 蒙皮。
 * 生命周期绑定 GL 线程：load（解析+上传）、skinFrame/updateGlobals、draw、close。
 *
 * 蒙皮在 CPU 侧完成（原因见 L3dRenderer 类注释：本机驱动对 float 纹理采样、
 * mat4 数组 uniform、mat4 长乘法链全部不可靠）。每帧 skinFrame() 把蒙皮后的
 * 顶点写进动态 VBO（pos3 + nor3(0) + uv2 = 8 floats），静态图元用原始静态 VBO
 * （pos3 + nor3 + uv2 + joint4 + weight4 = 16 floats）。两者的 pos/uv 偏移一致。
 */
final class L3dModel {
    private static final String TAG = "L3dModel";
    private static final int MAX_JOINTS_HARD = 1024;

    // ---- 场景结构 ----
    final Node[] nodes;
    private final int[] rootNodes;
    private final Mesh[] meshes;
    private final Skin skin;                            // 无骨架模型为 null
    private final HashMap<String, Integer> nodeByName;
    private final int[] materialTex;                    // materialIdx → texId（0=无）
    private final float[] materialColor;                // materialIdx*4 → rgba
    private final float[] sceneMin = new float[3];
    private final float[] sceneMax = new float[3];

    // ---- CPU 蒙皮 ----
    private final float[] jointPalette;                 // 16*J 列主序（recomputeBounds 也用）

    static final class Node {
        String name;
        int parent = -1;
        int[] children = new int[0];
        int mesh = -1;
        final float[] t = new float[3];                 // rest TRS（无动作时的姿态）
        final float[] r = new float[4];
        final float[] s = new float[3];
        boolean restIsMatrix;                           // glTF node 用 matrix 代替 TRS
        final float[] restMatrix = new float[16];
        final float[] local = new float[16];
        final float[] global = new float[16];
    }

    /** 蒙皮图元的 CPU 缓存与动态 VBO。 */
    static final class SkinCache {
        float[] pos;
        float[] uv;                                     // 4 分量填充，取 [0..1]
        float[] joint;
        float[] weight;
        float[] staging;                                // 顶点数 * 5（pos3+uv2），热循环写这里
        FloatBuffer buf;                                // 顶点数 * 5（put 一次批量）
        int dynVbo;
    }

    static final class Primitive {
        int vbo;                                        // 静态：pos3|nor3|uv2|j4|w4 = 16 floats
        int ebo;
        int indexCount;
        boolean skinned;
        SkinCache skinCache;                            // skinned 时非 null
        int material;
        int node;                                       // 静态网格用其全局矩阵
    }

    static final class Mesh {
        Primitive[] prims;
    }

    static final class Skin {
        int[] joints;
        float[] inverseBind;                            // 16*J
    }

    private L3dModel(Node[] nodes, int[] roots, Mesh[] meshes, Skin skin,
                     HashMap<String, Integer> byName, int[] matTex, float[] matColor,
                     float[] minB, float[] maxB) {
        this.nodes = nodes;
        this.rootNodes = roots;
        this.meshes = meshes;
        this.skin = skin;
        this.nodeByName = byName;
        this.materialTex = matTex;
        this.materialColor = matColor;
        System.arraycopy(minB, 0, sceneMin, 0, 3);
        System.arraycopy(maxB, 0, sceneMax, 0, 3);
        int n = skin != null ? skin.joints.length : 0;
        this.jointPalette = new float[16 * n];
    }

    float[] sceneMin() {
        return sceneMin;
    }

    float[] sceneMax() {
        return sceneMax;
    }

    int jointCount() {
        return skin != null ? skin.joints.length : 0;
    }

    Integer nodeIndex(String name) {
        return nodeByName.get(name);
    }

    /** 该节点是否为蒙皮关节（关节的 translation 通道会破坏 rest 骨长偏移，须忽略）。 */
    boolean isJointNode(int idx) {
        if (skin == null) return false;
        for (int j : skin.joints) {
            if (j == idx) return true;
        }
        return false;
    }

    // ---- 解析 + GL 上传（GL 线程） ----

    static L3dModel load(L3dGlb glb) throws L3dGlb.L3dException, org.json.JSONException {
        JSONObject j = glb.json;
        JSONArray jNodes = j.optJSONArray("nodes");
        if (jNodes == null || jNodes.length() == 0) {
            throw new L3dGlb.L3dException("glb has no nodes");
        }

        Node[] nodes = new Node[jNodes.length()];
        boolean[] hasParent = new boolean[nodes.length];
        for (int i = 0; i < nodes.length; i++) {
            JSONObject n = jNodes.getJSONObject(i);
            Node nd = new Node();
            nd.name = n.optString("name", "node" + i);
            JSONArray t = n.optJSONArray("translation");
            for (int c = 0; c < 3; c++) {
                nd.t[c] = t != null && t.length() > c ? (float) t.optDouble(c, 0) : 0f;
            }
            JSONArray r = n.optJSONArray("rotation");
            for (int c = 0; c < 4; c++) {
                nd.r[c] = r != null && r.length() > c ? (float) r.optDouble(c, c == 3 ? 1 : 0)
                    : (c == 3 ? 1f : 0f);
            }
            JSONArray s = n.optJSONArray("scale");
            for (int c = 0; c < 3; c++) {
                nd.s[c] = s != null && s.length() > c ? (float) s.optDouble(c, 1) : 1f;
            }
            JSONArray m = n.optJSONArray("matrix");
            if (m != null && m.length() == 16) {
                nd.restIsMatrix = true;
                for (int c = 0; c < 16; c++) nd.restMatrix[c] = (float) m.optDouble(c, 0);
            }
            nd.mesh = n.optInt("mesh", -1);
            nodes[i] = nd;
        }
        for (int i = 0; i < nodes.length; i++) {
            JSONArray ch = jNodes.getJSONObject(i).optJSONArray("children");
            if (ch == null) continue;
            nodes[i].children = new int[ch.length()];
            for (int c = 0; c < ch.length(); c++) {
                int ci = ch.getInt(c);
                nodes[i].children[c] = ci;
                nodes[ci].parent = i;
                hasParent[ci] = true;
            }
        }
        int rootN = 0;
        for (boolean hp : hasParent) if (!hp) rootN++;
        int[] roots = new int[rootN];
        int ri = 0;
        for (int i = 0; i < nodes.length; i++) if (!hasParent[i]) roots[ri++] = i;

        HashMap<String, Integer> byName = new HashMap<String, Integer>();
        for (int i = 0; i < nodes.length; i++) byName.put(nodes[i].name, i);

        // 骨架（本仓管线单骨架约定）
        Skin skin = null;
        JSONArray jSkins = j.optJSONArray("skins");
        if (jSkins != null && jSkins.length() > 0) {
            JSONObject js = jSkins.getJSONObject(0);
            JSONArray jj = js.getJSONArray("joints");
            skin = new Skin();
            skin.joints = new int[jj.length()];
            for (int i = 0; i < jj.length(); i++) skin.joints[i] = jj.getInt(i);
            if (skin.joints.length > MAX_JOINTS_HARD) {
                throw new L3dGlb.L3dException("too many joints: " + skin.joints.length);
            }
            float[] ibm = glb.readFloats(js.getInt("inverseBindMatrices"));
            if (ibm.length < 16 * skin.joints.length) {
                throw new L3dGlb.L3dException("inverseBindMatrices truncated");
            }
            skin.inverseBind = ibm;
        }

        // 材质：只用 baseColorTexture / baseColorFactor（导出管线约定）
        JSONArray jMats = j.optJSONArray("materials");
        int matN = jMats != null ? jMats.length() : 0;
        int[] matTex = new int[matN];
        float[] matColor = new float[matN * 4];
        for (int i = 0; i < matN; i++) {
            JSONObject pbr = jMats.getJSONObject(i).optJSONObject("pbrMetallicRoughness");
            if (pbr == null) continue;
            JSONArray f = pbr.optJSONArray("baseColorFactor");
            for (int c = 0; c < 4; c++) {
                matColor[i * 4 + c] = f != null && f.length() > c ? (float) f.optDouble(c, 1) : 1f;
            }
        }

        // 图片 → GL 纹理（串行解码 + 立即 recycle，多张 2048 级贴图不同时占堆）
        HashMap<Integer, Integer> imageTex = new HashMap<Integer, Integer>();
        int imgN = glb.length("images");
        for (int i = 0; i < imgN; i++) {
            byte[] bytes = glb.readImageBytes(i);
            Bitmap bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bm == null) {
                Log.w(TAG, "image " + i + " decode failed, using 1x1 fallback");
                imageTex.put(i, uploadTexture(null));
                continue;
            }
            imageTex.put(i, uploadTexture(bm));
            bm.recycle();
        }
        for (int i = 0; i < matN; i++) {
            JSONObject pbr = jMats.getJSONObject(i).optJSONObject("pbrMetallicRoughness");
            if (pbr == null || !pbr.has("baseColorTexture")) continue;
            int texIdx = pbr.getJSONObject("baseColorTexture").optInt("index", -1);
            JSONArray jTexs = j.optJSONArray("textures");
            if (texIdx < 0 || jTexs == null || texIdx >= jTexs.length()) continue;
            Integer tex = imageTex.get(jTexs.getJSONObject(texIdx).optInt("source", -1));
            if (tex != null) matTex[i] = tex;
        }

        // 网格
        JSONArray jMeshes = j.optJSONArray("meshes");
        Mesh[] meshes = new Mesh[jMeshes != null ? jMeshes.length() : 0];
        float[] minB = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] maxB = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        boolean haveBounds = false;
        for (int mi = 0; mi < meshes.length; mi++) {
            JSONArray jPrims = jMeshes.getJSONObject(mi).getJSONArray("primitives");
            Mesh mesh = new Mesh();
            mesh.prims = new Primitive[jPrims.length()];
            int owner = -1;
            for (int n = 0; n < nodes.length; n++) {
                if (nodes[n].mesh == mi) {
                    owner = n;
                    break;
                }
            }
            for (int pi = 0; pi < jPrims.length(); pi++) {
                JSONObject jp = jPrims.getJSONObject(pi);
                JSONObject attrs = jp.getJSONObject("attributes");
                Primitive pr = new Primitive();
                pr.node = owner;
                pr.material = jp.optInt("material", -1);
                pr.skinned = attrs.has("JOINTS_0") && attrs.has("WEIGHTS_0") && skin != null;

                int vc = glb.obj(attrs.getInt("POSITION"), "accessors").getInt("count");
                float[] pos = glb.readFloats(attrs.getInt("POSITION"));
                float[] nor = attrs.has("NORMAL")
                    ? glb.readFloats(attrs.getInt("NORMAL")) : null;
                float[] uv = attrs.has("TEXCOORD_0")
                    ? glb.readAttrib4(attrs.getInt("TEXCOORD_0"), false) : null;
                float[] joint = pr.skinned
                    ? glb.readAttrib4(attrs.getInt("JOINTS_0"), false) : null;
                float[] weight = pr.skinned
                    ? glb.readAttrib4(attrs.getInt("WEIGHTS_0"), true) : null;

                if (pr.skinned) {
                    // CPU 蒙皮：原始数据留在 Java 缓存，顶点每帧变换后进动态 VBO
                    SkinCache sc = new SkinCache();
                    sc.pos = pos;
                    sc.uv = uv;
                    sc.joint = joint;
                    sc.weight = weight;
                    sc.staging = new float[vc * 5];
                    sc.buf = ByteBuffer
                        .allocateDirect(vc * 5 * 4)
                        .order(ByteOrder.nativeOrder())
                        .asFloatBuffer();
                    int[] dyn = new int[1];
                    glGenBuffers(1, dyn, 0);
                    sc.dynVbo = dyn[0];
                    glBindBuffer(GL_ARRAY_BUFFER, sc.dynVbo);
                    glBufferData(GL_ARRAY_BUFFER, vc * 5 * 4, null, GLES20.GL_STREAM_DRAW);
                    pr.skinCache = sc;
                    pr.vbo = sc.dynVbo;
                } else {
                    float[] packed = new float[vc * 16];
                    for (int v = 0; v < vc; v++) {
                        int o = v * 16;
                        packed[o] = pos[v * 3];
                        packed[o + 1] = pos[v * 3 + 1];
                        packed[o + 2] = pos[v * 3 + 2];
                        if (nor != null) {
                            packed[o + 3] = nor[v * 3];
                            packed[o + 4] = nor[v * 3 + 1];
                            packed[o + 5] = nor[v * 3 + 2];
                        }
                        if (uv != null) {
                            packed[o + 6] = uv[v * 4];
                            packed[o + 7] = uv[v * 4 + 1];
                        }
                        for (int c = 0; c < 3; c++) {
                            float val = pos[v * 3 + c];
                            if (val < minB[c]) minB[c] = val;
                            if (val > maxB[c]) maxB[c] = val;
                        }
                    }
                    haveBounds = true;
                    int[] vboArr = new int[1];
                    glGenBuffers(1, vboArr, 0);
                    pr.vbo = vboArr[0];
                    glBindBuffer(GL_ARRAY_BUFFER, pr.vbo);
                    FloatBuffer fb = ByteBuffer
                        .allocateDirect(packed.length * 4)
                        .order(ByteOrder.nativeOrder())
                        .asFloatBuffer();
                    fb.put(packed);
                    fb.position(0);
                    glBufferData(GL_ARRAY_BUFFER, packed.length * 4, fb, GLES20.GL_STATIC_DRAW);
                }

                int[] idx = glb.readIndices(jp.getInt("indices"));
                int maxIdx = 0;
                for (int iv : idx) if (iv > maxIdx) maxIdx = iv;
                if (maxIdx > 65535) {
                    throw new L3dGlb.L3dException(
                        "index out of GLES2 ushort range (" + maxIdx + "); simplify mesh");
                }
                short[] s16 = new short[idx.length];
                for (int iv = 0; iv < idx.length; iv++) s16[iv] = (short) idx[iv];
                int[] eboArr = new int[1];
                glGenBuffers(1, eboArr, 0);
                pr.ebo = eboArr[0];
                glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, pr.ebo);
                ShortBuffer sb = ByteBuffer
                    .allocateDirect(s16.length * 2)
                    .order(ByteOrder.nativeOrder())
                    .asShortBuffer();
                sb.put(s16);
                sb.position(0);
                glBufferData(GL_ELEMENT_ARRAY_BUFFER, s16.length * 2, sb, GLES20.GL_STATIC_DRAW);
                pr.indexCount = idx.length;

                mesh.prims[pi] = pr;
            }
            meshes[mi] = mesh;
        }
        if (!haveBounds) {
            for (int c = 0; c < 3; c++) {
                minB[c] = -1f;
                maxB[c] = 1f;
            }
        }

        L3dModel model = new L3dModel(nodes, roots, meshes, skin, byName, matTex, matColor,
            minB, maxB);
        model.resetPose();
        model.updateGlobals();
        model.recomputeBounds(glb);
        Log.i(TAG, "loaded: nodes=" + nodes.length + " meshes=" + meshes.length
            + " joints=" + (skin != null ? skin.joints.length : 0)
            + " skinning=cpu"
            + " bounds=[" + fmt(model.sceneMin) + " .. " + fmt(model.sceneMax) + "]");
        return model;
    }

    private static String fmt(float[] v) {
        return v[0] + "," + v[1] + "," + v[2];
    }

    private static int uploadTexture(Bitmap bm) {
        int[] tex = new int[1];
        glGenTextures(1, tex, 0);
        glBindTexture(GL_TEXTURE_2D, tex[0]);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        if (bm != null) {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bm, 0);
        } else {
            ByteBuffer white = ByteBuffer.allocateDirect(4);
            white.put(new byte[]{(byte) 255, (byte) 255, (byte) 255, (byte) 255});
            white.position(0);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 1, 1, 0, GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE, white);
        }
        return tex[0];
    }

    // ---- 每帧（GL 线程） ----

    /** 全部节点回到 rest。 */
    void resetPose() {
        for (Node nd : nodes) {
            if (nd.restIsMatrix) {
                System.arraycopy(nd.restMatrix, 0, nd.local, 0, 16);
            } else {
                L3dMat.trs(nd.local, nd.t, nd.r, nd.s);
            }
        }
    }

    /** 动作应用后调用：自根向叶算全局矩阵，再算关节调色板。 */
    void updateGlobals() {
        for (int root : rootNodes) {
            updateGlobalRecursive(root, null);
        }
        if (skin != null) {
            for (int ji = 0; ji < skin.joints.length; ji++) {
                L3dMat.mulOffset(jointPalette, ji * 16,
                    nodes[skin.joints[ji]].global, 0, skin.inverseBind, ji * 16);
            }
        }
    }

    private void updateGlobalRecursive(int idx, float[] parentGlobal) {
        Node nd = nodes[idx];
        if (parentGlobal == null) {
            System.arraycopy(nd.local, 0, nd.global, 0, 16);
        } else {
            L3dMat.mul(nd.global, parentGlobal, nd.local);
        }
        for (int ch : nd.children) {
            updateGlobalRecursive(ch, nd.global);
        }
    }

    /**
     * CPU 蒙皮：蒙皮图元顶点变换后写动态 VBO（pos3+uv2 紧凑布局，stride 20B、uv@12）。
     * 热循环三原则（实测 52ms → 见 CHANGELOG）：结果写普通 float[]（免 Buffer.put 逐次
     * 调用）、单权重快速路径（免 4 权重累加）、最后一次性 buf.put 批量上传。
     */
    void skinFrame() {
        if (skin == null) return;
        final float[] pal = jointPalette;
        final int jointN = skin.joints.length;
        for (Mesh mesh : meshes) {
            for (Primitive pr : mesh.prims) {
                if (!pr.skinned || pr.skinCache == null) continue;
                SkinCache sc = pr.skinCache;
                final float[] pos = sc.pos;
                final float[] uv = sc.uv;
                final float[] joint = sc.joint;
                final float[] weight = sc.weight;
                final float[] out = sc.staging;
                final int vc = pos.length / 3;
                final boolean clamp = clampRange > 0f;
                final float cX = clampCenter[0], cY = clampCenter[1], cZ = clampCenter[2];
                int o = 0;
                for (int v = 0, p = 0, q = 0; v < vc; v++, p += 3, q += 4) {
                    final float px = pos[p];
                    final float py = pos[p + 1];
                    final float pz = pos[p + 2];
                    final float w0 = weight[q];
                    final float w1 = weight[q + 1];
                    final float w2 = weight[q + 2];
                    final float w3 = weight[q + 3];
                    float x, y, z;
                    if (w1 < 1e-6f && w2 < 1e-6f && w3 < 1e-6f) {
                        // 单权重快速路径（刚性绑定部分）
                        final int b = (int) (joint[q] + 0.5f) * 16;
                        if (b >= 0 && b < jointN * 16) {
                            x = w0 * (pal[b] * px + pal[b + 4] * py + pal[b + 8] * pz + pal[b + 12]);
                            y = w0 * (pal[b + 1] * px + pal[b + 5] * py + pal[b + 9] * pz + pal[b + 13]);
                            z = w0 * (pal[b + 2] * px + pal[b + 6] * py + pal[b + 10] * pz + pal[b + 14]);
                        } else {
                            x = px; y = py; z = pz;
                        }
                    } else {
                        x = y = z = 0f;
                        float wv = w0;
                        if (wv > 0f) {
                            int b = (int) (joint[q] + 0.5f) * 16;
                            if (b >= 0 && b < jointN * 16) {
                                x += wv * (pal[b] * px + pal[b + 4] * py + pal[b + 8] * pz + pal[b + 12]);
                                y += wv * (pal[b + 1] * px + pal[b + 5] * py + pal[b + 9] * pz + pal[b + 13]);
                                z += wv * (pal[b + 2] * px + pal[b + 6] * py + pal[b + 10] * pz + pal[b + 14]);
                            }
                        }
                        wv = w1;
                        if (wv > 0f) {
                            int b = (int) (joint[q + 1] + 0.5f) * 16;
                            if (b >= 0 && b < jointN * 16) {
                                x += wv * (pal[b] * px + pal[b + 4] * py + pal[b + 8] * pz + pal[b + 12]);
                                y += wv * (pal[b + 1] * px + pal[b + 5] * py + pal[b + 9] * pz + pal[b + 13]);
                                z += wv * (pal[b + 2] * px + pal[b + 6] * py + pal[b + 10] * pz + pal[b + 14]);
                            }
                        }
                        wv = w2;
                        if (wv > 0f) {
                            int b = (int) (joint[q + 2] + 0.5f) * 16;
                            if (b >= 0 && b < jointN * 16) {
                                x += wv * (pal[b] * px + pal[b + 4] * py + pal[b + 8] * pz + pal[b + 12]);
                                y += wv * (pal[b + 1] * px + pal[b + 5] * py + pal[b + 9] * pz + pal[b + 13]);
                                z += wv * (pal[b + 2] * px + pal[b + 6] * py + pal[b + 10] * pz + pal[b + 14]);
                            }
                        }
                        wv = w3;
                        if (wv > 0f) {
                            int b = (int) (joint[q + 3] + 0.5f) * 16;
                            if (b >= 0 && b < jointN * 16) {
                                x += wv * (pal[b] * px + pal[b + 4] * py + pal[b + 8] * pz + pal[b + 12]);
                                y += wv * (pal[b + 1] * px + pal[b + 5] * py + pal[b + 9] * pz + pal[b + 13]);
                                z += wv * (pal[b + 2] * px + pal[b + 6] * py + pal[b + 10] * pz + pal[b + 14]);
                            }
                        }
                    }
                    if (clamp) {
                        // 垃圾权重顶点（坐标 1e38 级）收缩到中心 → 退化三角形不可见
                        if (Float.isNaN(x) || Float.isNaN(y) || Float.isNaN(z)
                            || Float.isInfinite(x) || Float.isInfinite(y) || Float.isInfinite(z)
                            || Math.abs(x - cX) > clampRange
                            || Math.abs(y - cY) > clampRange
                            || Math.abs(z - cZ) > clampRange) {
                            x = cX;
                            y = cY;
                            z = cZ;
                        }
                    }
                    out[o] = x;
                    out[o + 1] = y;
                    out[o + 2] = z;
                    if (uv != null) {
                        out[o + 3] = uv[q];
                        out[o + 4] = uv[q + 1];
                    } else {
                        out[o + 3] = 0f;
                        out[o + 4] = 0f;
                    }
                    o += 5;
                }
                FloatBuffer buf = sc.buf;
                buf.clear();
                buf.put(out, 0, vc * 5);
                buf.position(0);
                glBindBuffer(GL_ARRAY_BUFFER, sc.dynVbo);
                glBufferData(GL_ARRAY_BUFFER, vc * 5 * 4, buf, GLES20.GL_STREAM_DRAW);
            }
        }
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }
        
    void draw(L3dRenderer r, float[] pvPose) {
        glUniformMatrix4fv(r.locMVP, 1, false, pvPose, 0);
        for (Mesh mesh : meshes) {
            for (Primitive pr : mesh.prims) {
                if (!pr.skinned && pr.node >= 0) {
                    // 静态图元：uMVP = pvPose * nodeGlobal（CPU 预乘，规避驱动乘法链 bug）
                    L3dMat.mul(mvpScratch, pvPose, nodes[pr.node].global);
                    glUniformMatrix4fv(r.locMVP, 1, false, mvpScratch, 0);
                }
                int mat = pr.material;
                float[] color = mat >= 0 && mat * 4 + 3 < materialColor.length
                    ? colorSlice(materialColor, mat * 4) : WHITE;
                glUniform4fv(r.locBaseColor, 1, color, 0);
                int tex = mat >= 0 && mat < materialTex.length ? materialTex[mat] : 0;
                glActiveTexture(GL_TEXTURE0);
                glBindTexture(GL_TEXTURE_2D, tex);
                glUniform1i(r.locBaseTex, 0);
                glUniform1i(r.locHasTex, tex != 0 ? 1 : 0);

                glBindBuffer(GL_ARRAY_BUFFER, pr.vbo);
                int stride = pr.skinned ? 5 * 4 : 16 * 4;
                if (r.locPos >= 0) {
                    glEnableVertexAttribArray(r.locPos);
                    glVertexAttribPointer(r.locPos, 3, GL_FLOAT, false, stride, 0);
                }
                if (r.locUv >= 0) {
                    glEnableVertexAttribArray(r.locUv);
                    // 动态 VBO 布局 pos3+uv2（uv@12）；静态 VBO pos3+nor3+uv2（uv@24）
                    glVertexAttribPointer(r.locUv, 2, GL_FLOAT, false, stride,
                        pr.skinned ? 12 : 24);
                }
                glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, pr.ebo);
                glDrawElements(GLES20.GL_TRIANGLES, pr.indexCount, GL_UNSIGNED_SHORT, 0);
            }
        }
        r.disableAttribs();
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, 0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0);
    }

    /**
     * 取景包围盒必须用"变换后"顶点：蒙皮顶点最终位置 = rest 调色板 × 原始坐标，
     * 调色板含骨架节点整条变换链（骨架对象可能带缩放），原始坐标的包围盒会差好几倍，
     * 相机按它取景就会贴脸或看不到。
     *
     * 用 0.5%~99.5% 分位数而不是 min/max：部分源资产（FBX 导入）带垃圾权重的顶点
     * rest 蒙皮后坐标达 1e38（有限但离谱），min/max 会被带飞；分位数天然抗离群，
     * 离群顶点本身在 skinFrame 里被收缩到中心（退化三角形，不可见）。
     */
    private void recomputeBounds(L3dGlb glb) throws L3dGlb.L3dException, org.json.JSONException {
        org.json.JSONArray jMeshes = glb.json.optJSONArray("meshes");
        if (jMeshes == null) return;
        float[] v3 = new float[3];
        // 第一遍：收集有限值（增长数组）
        float[][] cols = new float[3][1024];
        int[] cnt = {0};
        for (int mi = 0; mi < jMeshes.length(); mi++) {
            org.json.JSONArray jPrims = jMeshes.getJSONObject(mi).getJSONArray("primitives");
            int owner = -1;
            for (int n = 0; n < nodes.length; n++) {
                if (nodes[n].mesh == mi) {
                    owner = n;
                    break;
                }
            }
            for (int pi = 0; pi < jPrims.length(); pi++) {
                org.json.JSONObject attrs = jPrims.getJSONObject(pi).getJSONObject("attributes");
                float[] pos = glb.readFloats(attrs.getInt("POSITION"));
                boolean skinned = attrs.has("JOINTS_0") && attrs.has("WEIGHTS_0") && skin != null;
                float[] joint = skinned ? glb.readAttrib4(attrs.getInt("JOINTS_0"), false) : null;
                float[] weight = skinned ? glb.readAttrib4(attrs.getInt("WEIGHTS_0"), true) : null;
                int vc = pos.length / 3;
                for (int v = 0; v < vc; v++) {
                    if (skinned) {
                        v3[0] = v3[1] = v3[2] = 0f;
                        for (int w = 0; w < 4; w++) {
                            float weightV = weight[v * 4 + w];
                            if (weightV <= 0f) continue;
                            int jIdx = (int) (joint[v * 4 + w] + 0.5f);
                            if (jIdx < 0 || jIdx >= skin.joints.length) continue;
                            int o = jIdx * 16;
                            // 列主序 mat4 × vec3（w=1）
                            v3[0] += weightV * (jointPalette[o] * pos[v * 3]
                                + jointPalette[o + 4] * pos[v * 3 + 1]
                                + jointPalette[o + 8] * pos[v * 3 + 2] + jointPalette[o + 12]);
                            v3[1] += weightV * (jointPalette[o + 1] * pos[v * 3]
                                + jointPalette[o + 5] * pos[v * 3 + 1]
                                + jointPalette[o + 9] * pos[v * 3 + 2] + jointPalette[o + 13]);
                            v3[2] += weightV * (jointPalette[o + 2] * pos[v * 3]
                                + jointPalette[o + 6] * pos[v * 3 + 1]
                                + jointPalette[o + 10] * pos[v * 3 + 2] + jointPalette[o + 14]);
                        }
                    } else if (owner >= 0) {
                        float[] g = nodes[owner].global;
                        v3[0] = g[0] * pos[v * 3] + g[4] * pos[v * 3 + 1] + g[8] * pos[v * 3 + 2] + g[12];
                        v3[1] = g[1] * pos[v * 3] + g[5] * pos[v * 3 + 1] + g[9] * pos[v * 3 + 2] + g[13];
                        v3[2] = g[2] * pos[v * 3] + g[6] * pos[v * 3 + 1] + g[10] * pos[v * 3 + 2] + g[14];
                    } else {
                        v3[0] = pos[v * 3];
                        v3[1] = pos[v * 3 + 1];
                        v3[2] = pos[v * 3 + 2];
                    }
                    boolean finite = !Float.isInfinite(v3[0]) && !Float.isInfinite(v3[1])
                        && !Float.isInfinite(v3[2])
                        && !Float.isNaN(v3[0]) && !Float.isNaN(v3[1]) && !Float.isNaN(v3[2]);
                    if (finite) {
                        for (int c = 0; c < 3; c++) {
                            if (cnt[0] >= cols[c].length) {
                                float[] bigger = new float[cols[c].length * 2];
                                System.arraycopy(cols[c], 0, bigger, 0, cols[c].length);
                                cols[c] = bigger;
                            }
                            cols[c][cnt[0]] = v3[c];
                        }
                        cnt[0]++;
                    }
                }
            }
        }
        if (cnt[0] < 8) return;
        // 0.5% ~ 99.5% 分位数包围盒
        float[] mn = new float[3];
        float[] mx = new float[3];
        int lo = (int) (cnt[0] * 0.005f);
        int hi = Math.min(cnt[0] - 1 - lo, cnt[0] - 1);
        for (int c = 0; c < 3; c++) {
            java.util.Arrays.sort(cols[c], 0, cnt[0]);
            mn[c] = cols[c][lo];
            mx[c] = cols[c][hi];
        }
        System.arraycopy(mn, 0, sceneMin, 0, 3);
        System.arraycopy(mx, 0, sceneMax, 0, 3);
        // 蒙皮输出的合法性盒（中心 ± 对角范围×6）：出界顶点收缩到中心
        float cx = (mn[0] + mx[0]) / 2f;
        float cy = (mn[1] + mx[1]) / 2f;
        float cz = (mn[2] + mx[2]) / 2f;
        float range = Math.max(Math.max(mx[0] - mn[0], mx[1] - mn[1]), mx[2] - mn[2]);
        if (!Float.isInfinite(range) && !(range <= 0f)) {
            clampCenter[0] = cx;
            clampCenter[1] = cy;
            clampCenter[2] = cz;
            clampRange = range * 6f + 1f;
        }
    }

    private final float[] clampCenter = new float[3];
    private float clampRange = -1f;   // <=0 = 未启用

    private static final float[] WHITE = {1f, 1f, 1f, 1f};
    private final float[] mvpScratch = new float[16];
    private final float[] colorSlice = new float[4];

    private float[] colorSlice(float[] src, int off) {
        System.arraycopy(src, off, colorSlice, 0, 4);
        return colorSlice;
    }

    // ---- 清理 ----

    void close() {
        for (Mesh mesh : meshes) {
            for (Primitive pr : mesh.prims) {
                glDeleteBuffers(1, new int[]{pr.vbo}, 0);
                glDeleteBuffers(1, new int[]{pr.ebo}, 0);
            }
        }
        for (int tex : materialTex) if (tex != 0) glDeleteTextures(1, new int[]{tex}, 0);
    }
}
