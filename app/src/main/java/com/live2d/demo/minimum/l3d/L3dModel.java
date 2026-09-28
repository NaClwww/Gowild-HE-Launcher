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
import static android.opengl.GLES20.GL_LINEAR;
import static android.opengl.GLES20.GL_LINEAR_MIPMAP_LINEAR;
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
    private float frameMinZ;
    private float frameMaxZ;

    // ---- CPU 蒙皮 ----
    private final float[] jointPalette;                 // 16*J 列主序（recomputeBounds 也用）
    // 按需计算表：只有带权关节及其祖先链的全局矩阵影响渲染（miku_eve 96/375 带权、
    // 120/379 节点需要），其余节点每帧跳过；topoOrder 保证父先于子
    private boolean[] jointWeighted;                    // [skin.joints 下标]
    private int[] topoNeeded;                           // 需要计算的节点拓扑序
    private int[] topoParent;                           // 与 topoNeeded 对齐的父下标（-1=根）

    static final class Node {
        String name;
        int parent = -1;
        int[] children = new int[0];
        int mesh = -1;
        final float[] t = new float[3];                 // 当前 TRS（动作写入这里）
        final float[] r = new float[4];
        final float[] s = new float[3];
        final float[] restT = new float[3];             // rest TRS 快照（resetPose 的唯一来源）
        final float[] restR = new float[4];
        final float[] restS = new float[3];
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
        // morph targets 挂在 glTF primitive 层（不是 mesh 层！），POSITION 形变量
        int targetCount;
        float[][] morphTargets;
    }

    static final class Mesh {
        Primitive[] prims;
        // morph（形态键）：targets[k] = POSITION 形变量（顶点数*3），weights 每帧由动作写入
        int targetCount;
        float[][] morphTargets;
        float[] morphWeights;
        float[] morphDefaults;
        int[] activeScratch = new int[1];            // load 时按 targetCount 扩
        int activeN;
    }

    static final class Skin {
        int[] joints;
        float[] inverseBind;                            // 16*J
    }

    private L3dModel(Node[] nodes, int[] roots, Mesh[] meshes, Skin skin,
                     HashMap<String, Integer> byName, int[] matTex, float[] matColor,
                     float[] minB, float[] maxB,
                     boolean[] jointWeighted, int[] topoNeeded, int[] topoParent) {
        this.nodes = nodes;
        this.rootNodes = roots;
        this.meshes = meshes;
        this.skin = skin;
        this.nodeByName = byName;
        this.materialTex = matTex;
        this.materialColor = matColor;
        System.arraycopy(minB, 0, sceneMin, 0, 3);
        System.arraycopy(maxB, 0, sceneMax, 0, 3);
        frameMinZ = minB[2];
        frameMaxZ = maxB[2];
        int n = skin != null ? skin.joints.length : 0;
        this.jointPalette = new float[16 * n];
        this.jointWeighted = jointWeighted;
        this.topoNeeded = topoNeeded;
        this.topoParent = topoParent;
    }

    float[] sceneMin() {
        return sceneMin;
    }

    float[] sceneMax() {
        return sceneMax;
    }

    float frameMinZ() {
        return frameMinZ;
    }

    float frameMaxZ() {
        return frameMaxZ;
    }

    int jointCount() {
        return skin != null ? skin.joints.length : 0;
    }

    Integer nodeIndex(String name) {
        return nodeByName.get(name);
    }

    // ---- L3dClip 用（同包） ----

    int nodeMeshIdx(int nodeIdx) {
        return nodeIdx >= 0 && nodeIdx < nodes.length ? nodes[nodeIdx].mesh : -1;
    }

    int meshTargetCount(int meshIdx) {
        return meshIdx >= 0 && meshIdx < meshes.length ? meshes[meshIdx].targetCount : 0;
    }

    float[] morphWeightsOf(int meshIdx) {
        return meshIdx >= 0 && meshIdx < meshes.length ? meshes[meshIdx].morphWeights : null;
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
            // rest TRS 快照：node.t/r/s 会被动作就地改写，resetPose 只能从这里还原
            System.arraycopy(nd.t, 0, nd.restT, 0, 3);
            System.arraycopy(nd.r, 0, nd.restR, 0, 4);
            System.arraycopy(nd.s, 0, nd.restS, 0, 3);
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
        boolean[] jointWeighted = null;                 // load 局部：带权关节标记
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
            jointWeighted = new boolean[skin.joints.length];
        }
        // 按需计算表（load 内局部，构造时传入实例）
        int[] topoNeeded = null;
        int[] topoParent = null;

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
            // mesh.weights = morph 默认权重（glTF 层级：weights 在 mesh、targets 在 primitive）
            JSONArray jw = jMeshes.getJSONObject(mi).optJSONArray("weights");
            int wn = jw != null ? jw.length() : 0;
            // morphWeights 统一按 targetCount 展开（解析在 prim 循环里补齐；
            // 这里先按 weights 长度占位，prim 循环后再校正）
            mesh.morphDefaults = new float[wn];
            for (int t = 0; t < wn; t++) mesh.morphDefaults[t] = (float) jw.optDouble(t, 0);
            mesh.morphWeights = new float[wn];
            mesh.activeScratch = new int[wn];
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

                // morph targets：primitive 层。只取 POSITION 形变量（NORMAL 忽略，unlit）
                org.json.JSONArray jTargets = jp.optJSONArray("targets");
                if (jTargets != null && jTargets.length() > 0) {
                    pr.targetCount = jTargets.length();
                    pr.morphTargets = new float[pr.targetCount][];
                    for (int t = 0; t < pr.targetCount; t++) {
                        pr.morphTargets[t] = glb.readFloats(
                            jTargets.getJSONObject(t).getInt("POSITION"));
                    }
                    if (mesh.targetCount == 0) mesh.targetCount = pr.targetCount;
                }

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
                    // 标记带权关节（一次性；updateGlobals 只算这些关节及其祖先链）
                    for (int v = 0; v < vc; v++) {
                        for (int w = 0; w < 4; w++) {
                            if (weight[v * 4 + w] > 0f) {
                                int jIdx = (int) (joint[v * 4 + w] + 0.5f);
                                if (jIdx >= 0 && jIdx < jointWeighted.length) {
                                    jointWeighted[jIdx] = true;
                                }
                            }
                        }
                    }
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
            if (mesh.targetCount > mesh.morphWeights.length) {
                int tc = mesh.targetCount;
                float[] w2 = new float[tc];
                System.arraycopy(mesh.morphWeights, 0, w2, 0, mesh.morphWeights.length);
                mesh.morphWeights = w2;
                float[] d2 = new float[tc];
                System.arraycopy(mesh.morphDefaults, 0, d2, 0,
                    Math.min(mesh.morphDefaults.length, tc));
                mesh.morphDefaults = d2;
                mesh.activeScratch = new int[tc];
            }
            meshes[mi] = mesh;
        }
        if (!haveBounds) {
            for (int c = 0; c < 3; c++) {
                minB[c] = -1f;
                maxB[c] = 1f;
            }
        }

        // 需要参与全局矩阵计算的节点 = 带权关节 ∪ 其全部祖先（构造拓扑序，父先于子）
        if (skin != null) {
            boolean[] nodeNeeded = new boolean[nodes.length];
            for (int ji = 0; ji < skin.joints.length; ji++) {
                if (!jointWeighted[ji]) continue;
                int x = skin.joints[ji];
                while (x >= 0 && !nodeNeeded[x]) {
                    nodeNeeded[x] = true;
                    x = nodes[x].parent;
                }
            }
            // 真拓扑序（DFS 后序）：glTF 节点数组不保证父在下标上先于子，
            // 按 arrOrder 直接过滤会在子先出现时用到未初始化的父矩阵
            int[] emitState = new int[nodes.length];        // 0=未访问 1=栈中 2=完成
            java.util.ArrayList<Integer> order = new java.util.ArrayList<Integer>();
            java.util.HashMap<Integer, Integer> remap = new java.util.HashMap<Integer, Integer>();
            for (int i = 0; i < nodes.length; i++) {
                if (nodeNeeded[i]) {
                    emitNeeded(i, nodeNeeded, nodes, emitState, order);
                }
            }
            for (int i = 0; i < order.size(); i++) {
                remap.put(order.get(i), i);
            }
            topoNeeded = new int[order.size()];
            topoParent = new int[order.size()];
            for (int i = 0; i < order.size(); i++) {
                int ni = order.get(i);
                topoNeeded[i] = ni;
                Integer pp = remap.get(nodes[ni].parent);
                topoParent[i] = pp != null ? pp : -1;
            }
        }

        L3dModel model = new L3dModel(nodes, roots, meshes, skin, byName, matTex, matColor,
            minB, maxB, jointWeighted, topoNeeded, topoParent);
        model.resetPose();
        model.updateGlobals();
        model.recomputeBounds(glb);
        // 一次性诊断：rest 调色板应等于单位阵（global × inverseBind），偏离说明 rest 解析/蒙皮有误
        if (skin != null) {
            float worst = 0f;
            int worstJ = -1;
            for (int ji = 0; ji < skin.joints.length; ji++) {
                if (jointWeighted != null && !jointWeighted[ji]) continue;
                for (int c = 0; c < 16; c++) {
                    float expect = (c % 5 == 0) ? 1f : 0f;
                    float d = Math.abs(model.jointPalette[ji * 16 + c] - expect);
                    if (d > worst) {
                        worst = d;
                        worstJ = ji;
                    }
                }
            }
            Log.i(TAG, "restPaletteDev max=" + worst + " at joint " + worstJ);
        }
        int morphMeshes = 0, morphTargets = 0;
        for (Mesh m : meshes) {
            if (m.targetCount > 0) {
                morphMeshes++;
                morphTargets = Math.max(morphTargets, m.targetCount);
            }
        }
        Log.i(TAG, "loaded: nodes=" + nodes.length + " meshes=" + meshes.length
            + " joints=" + (skin != null ? skin.joints.length : 0)
            + " morphMeshes=" + morphMeshes + " morphTargets<=" + morphTargets
            + " skinning=cpu"
            + " bounds=[" + fmt(model.sceneMin) + " .. " + fmt(model.sceneMax) + "]");
        return model;
    }

    private static String fmt(float[] v) {
        return v[0] + "," + v[1] + "," + v[2];
    }

    /**
     * 贴图上传：mipmap + 线性过滤。
     * 模型在屏上只有百来像素宽（脸 ~60px）而贴图 512²，是重度缩小采样；细线特征
     * （嘴线、睫毛）在缺 mip 时会被双线性采成"串珠/虚线"（设备上"嘴角怪怪的"就是这个，
     * Blender 有 mip 所以是干净细线）。
     *
     * mip 链在 CPU 侧逐级 Bitmap 缩放后按 level 上传，不用 glGenerateMipmap——
     * 本机驱动已有三处静默失效前科（见 L3dRenderer 注释），实测 glGenerateMipmap
     * 后细线仍是串珠，即 mip 未真正生效。
     */
    private static int uploadTexture(Bitmap bm) {
        int[] tex = new int[1];
        glGenTextures(1, tex, 0);
        glBindTexture(GL_TEXTURE_2D, tex[0]);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        if (bm != null) {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bm, 0);
            Bitmap prev = bm;
            int level = 1;
            while (prev.getWidth() > 1 && prev.getHeight() > 1) {
                Bitmap half = Bitmap.createScaledBitmap(prev,
                    Math.max(1, prev.getWidth() / 2), Math.max(1, prev.getHeight() / 2), true);
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, level, half, 0);
                if (prev != bm) prev.recycle();
                prev = half;
                level++;
            }
            if (prev != bm) prev.recycle();
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

    /** 全部节点回到 rest，morph 权重回默认。 */
    void resetPose() {
        for (Mesh mesh : meshes) {
            if (mesh.morphWeights != null) {
                System.arraycopy(mesh.morphDefaults, 0, mesh.morphWeights, 0,
                    mesh.targetCount);
                mesh.activeN = 0;
            }
        }
        for (Node nd : nodes) {
            if (nd.restIsMatrix) continue;              // matrix 节点无 TRS 通道
            System.arraycopy(nd.restT, 0, nd.t, 0, 3);
            System.arraycopy(nd.restR, 0, nd.r, 0, 4);
            System.arraycopy(nd.restS, 0, nd.s, 0, 3);
        }
    }

    /**
     * 把 node.t/r/s 重建成 local 矩阵。必须在动作写入 t/r/s（L3dClip.apply）之后、
     * 算全局矩阵之前调用——否则动作用的是上一帧的姿态（曾依赖这一帧延迟"碰巧能看"，
     * 修掉 rest 快照后就会整体冻在 rest）。
     */
    private void buildLocals() {
        for (Node nd : nodes) {
            if (nd.restIsMatrix) {
                System.arraycopy(nd.restMatrix, 0, nd.local, 0, 16);
            } else {
                L3dMat.trs(nd.local, nd.t, nd.r, nd.s);
            }
        }
    }

    /** 动作应用后调用：只算需要节点（带权关节+祖先）的全局矩阵，再算带权关节调色板。 */
    void updateGlobals() {
        long g0 = globDbg >= 0 ? System.nanoTime() : 0;
        buildLocals();
        if (topoNeeded != null) {
            for (int ti = 0; ti < topoNeeded.length; ti++) {
                Node nd = nodes[topoNeeded[ti]];
                int pi = topoParent[ti];
                if (pi < 0) {
                    System.arraycopy(nd.local, 0, nd.global, 0, 16);
                } else {
                    L3dMat.mul(nd.global, nodes[topoNeeded[pi]].global, nd.local);
                }
            }
        } else {
            for (int root : rootNodes) {
                updateGlobalRecursive(root, null);
            }
        }
        long g1 = globDbg >= 0 ? System.nanoTime() : 0;
        if (skin != null && jointWeighted != null) {
            for (int ji = 0; ji < skin.joints.length; ji++) {
                if (!jointWeighted[ji]) continue;   // 无权重的调色板条目永不被采样
                L3dMat.mulOffset(jointPalette, ji * 16,
                    nodes[skin.joints[ji]].global, 0, skin.inverseBind, ji * 16);
            }
        }
        if (globDbg >= 0) {
            globDbg++;
            if (globDbg % 120 == 0) {
                android.util.Log.i(TAG, "globDbg: recurse=" + ((g1 - g0) / 1000) + "uS"
                    + " palette=" + ((System.nanoTime() - g1) / 1000) + "uS");
            }
        }
    }

    private static int countTrue(boolean[] a) {
        int c = 0;
        for (boolean v : a) if (v) c++;
        return c;
    }

    /** needed 子树的 DFS 后序（父先出）；iterativeDepth 足够（骨架链深 ~数十） */
    private static void emitNeeded(int i, boolean[] needed, Node[] nodes,
                                   int[] state, java.util.ArrayList<Integer> order) {
        if (state[i] != 0) return;
        state[i] = 1;
        int p = nodes[i].parent;
        if (p >= 0 && needed[p]) {
            emitNeeded(p, needed, nodes, state, order);
        }
        state[i] = 2;
        order.add(i);
    }

    private static int globDbg = 0;

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
        float minZ = sceneMin[2];
        float maxZ = sceneMax[2];
        if (skin == null) {
            frameMinZ = minZ;
            frameMaxZ = maxZ;
            return;
        }
        final float[] pal = jointPalette;
        final int jointN = skin.joints.length;
        for (Mesh mesh : meshes) {
            // 活跃 morph 目标（权重超阈值的形态键），每帧一次
            mesh.activeN = 0;
            if (mesh.morphWeights != null) {
                for (int k = 0; k < mesh.targetCount; k++) {
                    if (Math.abs(mesh.morphWeights[k]) > 0.002f) {
                        mesh.activeScratch[mesh.activeN++] = k;
                    }
                }
            }
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
                final float[][] mT = pr.morphTargets;
                final float[] mW = mesh.morphWeights;
                final int mN = pr.morphTargets != null ? mesh.activeN : 0;
                for (int v = 0, p = 0, q = 0; v < vc; v++, p += 3, q += 4) {
                    float px = pos[p];
                    float py = pos[p + 1];
                    float pz = pos[p + 2];
                    // morph：base + Σ w_k·形变量（表情形态键，先于蒙皮）
                    for (int a = 0; a < mN; a++) {
                        final int k = mesh.activeScratch[a];
                        final float w = mW[k];
                        final float[] d = mT[k];
                        px += w * d[p];
                        py += w * d[p + 1];
                        pz += w * d[p + 2];
                    }
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
                    if (!Float.isNaN(z) && !Float.isInfinite(z)) {
                        if (z < minZ) minZ = z;
                        if (z > maxZ) maxZ = z;
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
        frameMinZ = minZ;
        frameMaxZ = maxZ;
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
        frameMinZ = mn[2];
        frameMaxZ = mx[2];
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
