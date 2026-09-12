package com.live2d.demo.minimum.l3d;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * GLB 容器解析 + 访问器读取。只覆盖自有导出管线（tools/blend_to_model3d.py，
 * Blender glTF 导出器）会用到的子集：POSITION/NORMAL/TEXCOORD_0/JOINTS_0/WEIGHTS_0、
 * LINEAR|STEP 动画采样、bufferView 对齐读取、内嵌 PNG/JPEG 图片。
 */
public final class L3dGlb {
    public JSONObject json;
    public byte[] bin;

    private L3dGlb() {
    }

    public static L3dGlb parse(byte[] data) throws L3dException {
        if (data == null || data.length < 20 || data[0] != 'g' || data[1] != 'l'
            || data[2] != 'T' || data[3] != 'F') {
            throw new L3dException("not a GLB (bad magic)");
        }
        // GLB 头：magic(0..4) + version(4..8) + length(8..12)。旧实现把 version 误当总长，
        // 这里干脆不信任头部长度，以实际字节数界定 chunk 循环（对 padding/坏长度都稳）。
        L3dGlb glb = new L3dGlb();
        int off = 12;
        while (off + 8 <= data.length) {
            long len = (data[off] & 0xFFL) | (data[off + 1] & 0xFFL) << 8
                | (data[off + 2] & 0xFFL) << 16 | (data[off + 3] & 0xFFL) << 24;
            int type = (data[off + 4] & 0xFF) | (data[off + 5] & 0xFF) << 8
                | (data[off + 6] & 0xFF) << 16 | (data[off + 7] & 0xFF) << 24;
            int body = off + 8;
            if (type == 0x4E4F534A) { // 'JSON'（小端四字节即 0x4E4F534A）
                int ilen = (int) Math.min(len, data.length - body);
                try {
                    glb.json = new JSONObject(new String(data, body, ilen, "UTF-8"));
                } catch (Exception e) {
                    throw new L3dException("json chunk parse failed: " + e);
                }
            } else if (type == 0x004E4942) { // 'BIN\0'
                int ilen = (int) Math.min(len, data.length - body);
                glb.bin = new byte[ilen];
                System.arraycopy(data, body, glb.bin, 0, ilen);
            }
            if (len <= 0) break;
            off = body + (int) len;
        }
        if (glb.json == null) throw new L3dException("GLB has no JSON chunk");
        return glb;
    }

    /** 业务错误（加载失败原因，供上层透出到 /api/status last_error）。 */
    public static class L3dException extends RuntimeException {
        L3dException(String msg) {
            super(msg);
        }
    }

    // ---- 访问器读取 ----

    /** bufferView 字节区间视图（accessor.byteOffset + 语义 stride）。 */
    private ByteBuffer view(int bv, int accessorByteOffset, int elementSize, int count)
        throws L3dException {
        try {
            JSONObject bvj = json.getJSONArray("bufferViews").getJSONObject(bv);
            int start = bvj.optInt("byteOffset", 0) + accessorByteOffset;
            int stride = bvj.optInt("byteStride", elementSize);
            int span = stride * (count - 1) + elementSize;
            if (bin == null || start < 0 || start + span > bin.length) {
                throw new L3dException("accessor range out of BIN chunk (bv=" + bv + ")");
            }
            // 注意：wrap(bin, start, span) 的 position 语义是"绝对数组下标"，
            // 后续 position(i*stride) 会跳回数组头部（实测踩坑）。这里 wrap 全数组、
            // 显式 position(start)、limit(start+span)，调用方用返回时的 position 作为基址。
            ByteBuffer b = ByteBuffer.wrap(bin);
            b.order(ByteOrder.LITTLE_ENDIAN);
            b.position(start);
            b.limit(start + span);
            return b;
        } catch (L3dException e) {
            throw e;
        } catch (Exception e) {
            throw new L3dException("bad bufferView " + bv + ": " + e);
        }
    }

    private static int comps(String type) throws L3dException {
        if (type.equals("SCALAR")) return 1;
        if (type.equals("VEC2")) return 2;
        if (type.equals("VEC3")) return 3;
        if (type.equals("VEC4")) return 4;
        if (type.equals("MAT4")) return 16;
        throw new L3dException("unsupported accessor type: " + type);
    }

    /** FLOAT 访问器 → float[count*nc]。 */
    float[] readFloats(int accIndex) throws L3dException {
        try {
            JSONObject acc = json.getJSONArray("accessors").getJSONObject(accIndex);
            int nc = comps(acc.getString("type"));
            int n = acc.getInt("count");
            if (acc.getInt("componentType") != 5126) {
                throw new L3dException("readFloats: accessor " + accIndex + " is not FLOAT");
            }
            int elem = nc * 4;
            if (!acc.has("bufferView")) {
                // sparse accessor（Blender 形态键）：无基底 bufferView = 全零基底，
                // 再叠 sparse 差分。漏 applySparse 会把所有形态键形变量读成 0。
                float[] out = new float[n * nc];
                applySparse(acc, out, n, nc);
                return out;
            }
            int bv = acc.getInt("bufferView");
            ByteBuffer b = view(bv, acc.optInt("byteOffset", 0), elem, n);
            float[] out = new float[n * nc];
            int stride = json.getJSONArray("bufferViews").getJSONObject(bv)
                .optInt("byteStride", elem);
            if (stride == elem) {
                b.asFloatBuffer().get(out);
            } else {
                final int baseF = b.position();
                for (int i = 0; i < n; i++) {
                    b.position(baseF + i * stride);
                    for (int c = 0; c < nc; c++) out[i * nc + c] = b.getFloat();
                }
            }
            applySparse(acc, out, n, nc);
            return out;
        } catch (L3dException e) {
            throw e;
        } catch (Exception e) {
            throw new L3dException("readFloats(" + accIndex + "): " + e);
        }
    }


    /** glTF sparse accessor：按 indices 把 values 写入基底（替换语义）。 */
    private void applySparse(JSONObject acc, float[] out, int n, int nc) throws L3dException {
        JSONObject sparse = acc.optJSONObject("sparse");
        if (sparse == null) return;
        try {
            JSONObject idx = sparse.getJSONObject("indices");
            JSONObject val = sparse.getJSONObject("values");
            int sc = sparse.getInt("count");
            int idxCT = idx.getInt("componentType");
            int idxE = idxCT == 5125 ? 4 : 2;
            ByteBuffer ib = view(idx.getInt("bufferView"), idx.optInt("byteOffset", 0),
                idxE, sc);
            ByteBuffer vb = view(val.getInt("bufferView"), val.optInt("byteOffset", 0),
                nc * 4, sc);
            for (int i = 0; i < sc; i++) {
                int bi = idxE == 4 ? ib.getInt() : ib.getShort() & 0xFFFF;
                if (bi < 0 || bi >= n) throw new L3dException("sparse index out of range");
                for (int c = 0; c < nc; c++) out[bi * nc + c] = vb.getFloat();
            }
        } catch (L3dException e) {
            throw e;
        } catch (Exception e) {
            throw new L3dException("applySparse: " + e);
        }
    }

    /** 索引访问器 → int[]（UBYTE/USHORT/UINT 都收）。 */
    int[] readIndices(int accIndex) throws L3dException {
        try {
            JSONObject acc = json.getJSONArray("accessors").getJSONObject(accIndex);
            int n = acc.getInt("count");
            int ct = acc.getInt("componentType");
            int elem = ct == 5125 ? 4 : ct == 5123 ? 2 : 1;
            ByteBuffer b = view(acc.getInt("bufferView"),
                acc.optInt("byteOffset", 0), elem, n);
            int[] out = new int[n];
            for (int i = 0; i < n; i++) {
                out[i] = ct == 5125 ? b.getInt() : ct == 5123 ? b.getShort() & 0xFFFF : b.get() & 0xFF;
            }
            return out;
        } catch (L3dException e) {
            throw e;
        } catch (Exception e) {
            throw new L3dException("readIndices(" + accIndex + "): " + e);
        }
    }

    /**
     * 顶点属性访问器 → float[count*4]（统一凑 4 分量，喂 vec4 顶点属性）。
     * joint/weight 的整数型归一化也在这处理。
     */
    float[] readAttrib4(int accIndex, boolean normalizeInts) throws L3dException {
        try {
            JSONObject acc = json.getJSONArray("accessors").getJSONObject(accIndex);
            int nc = comps(acc.getString("type"));
            int n = acc.getInt("count");
            int ct = acc.getInt("componentType");
            int esz = elemSize(ct) * nc;
            int bv = acc.getInt("bufferView");
            ByteBuffer b = view(bv, acc.optInt("byteOffset", 0), esz, n);
            final int base = b.position();   // accessor 数据区起点（绝对下标）
            int stride = json.getJSONArray("bufferViews").getJSONObject(bv)
                .optInt("byteStride", esz);
            boolean normalized = acc.optBoolean("normalized", false) || normalizeInts;
            float[] out = new float[n * 4];
            for (int i = 0; i < n; i++) {
                b.position(base + i * stride);
                for (int c = 0; c < nc; c++) {
                    float v;
                    if (ct == 5126) v = b.getFloat();
                    else if (ct == 5123) v = (b.getShort() & 0xFFFF) / (normalized ? 65535f : 1f);
                    else if (ct == 5121) v = (b.get() & 0xFF) / (normalized ? 255f : 1f);
                    else if (ct == 5122) v = b.getShort() / (normalized ? 32767f : 1f);
                    else v = b.get() / (normalized ? 127f : 1f);
                    out[i * 4 + c] = v;
                }
            }
            return out;
        } catch (L3dException e) {
            throw e;
        } catch (Exception e) {
            throw new L3dException("readAttrib4(" + accIndex + "): " + e);
        }
    }

    private static int elemSize(int ct) {
        switch (ct) {
            case 5126: return 4;
            case 5125: case 5123: case 5122: return 2;
            default: return 1;
        }
    }

    /** 图片字节（内嵌 bufferView；data URI 也支持）。 */
    byte[] readImageBytes(int imageIndex) throws L3dException {
        try {
            JSONObject img = json.getJSONArray("images").getJSONObject(imageIndex);
            String uri = img.optString("uri", "");
            if (uri.startsWith("data:")) {
                int comma = uri.indexOf(',');
                return android.util.Base64.decode(uri.substring(comma + 1), android.util.Base64.DEFAULT);
            }
            int bv = img.getInt("bufferView");
            JSONObject bvj = json.getJSONArray("bufferViews").getJSONObject(bv);
            int start = bvj.getInt("byteOffset");
            int len = bvj.getInt("byteLength");
            byte[] out = new byte[len];
            System.arraycopy(bin, start, out, 0, len);
            return out;
        } catch (L3dException e) {
            throw e;
        } catch (Exception e) {
            throw new L3dException("readImageBytes(" + imageIndex + "): " + e);
        }
    }

    // ---- 小工具 ----

    public JSONArray array(String key) {
        return json.optJSONArray(key);
    }

    public JSONObject obj(int index, String key) throws L3dException {
        try {
            return json.getJSONArray(key).getJSONObject(index);
        } catch (Exception e) {
            throw new L3dException(key + "[" + index + "] missing: " + e);
        }
    }

    public int length(String key) {
        JSONArray a = json.optJSONArray(key);
        return a != null ? a.length() : 0;
    }
}
