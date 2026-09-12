package com.live2d.demo.minimum.l3d;

/**
 * 一条骨骼动画（l3d 包 anims/*.glb 里 animations[0] 的运行时形态）。
 * 通道目标按节点名绑定到 L3dModel；只支持 LINEAR/STEP（本仓导出管线采样输出即 LINEAR）。
 */
final class L3dClip {
    static final int PATH_T = 0;
    static final int PATH_R = 1;
    static final int PATH_S = 2;
    private static final int[] PATH_N = {3, 4, 3};

    final String name;
    final float durationS;
    private final int[] targets;
    private final int[] paths;
    private final float[][] times;    // [ch][key]
    private final float[][] values;   // [ch][key * n]
    // 采样导出的动作所有通道共享同一时间轴：去重后每帧每轴只算一次段索引（带跨帧缓存）
    private final int[] chanUniq;     // [ch] → uniqTimes 下标
    private final float[][] uniqTimes;
    private final int[] uniqSeg;      // [uniq] → 上一帧段索引（时间连贯时 O(1)）

    private L3dClip(String name, float durationS, int[] targets, int[] paths,
                    float[][] times, float[][] values) {
        this.name = name;
        this.durationS = durationS;
        this.targets = targets;
        this.paths = paths;
        this.times = times;
        this.values = values;
        this.chanUniq = new int[targets.length];
        java.util.ArrayList<float[]> uniq = new java.util.ArrayList<float[]>();
        for (int c = 0; c < times.length; c++) {
            int found = -1;
            for (int u = 0; u < uniq.size(); u++) {
                if (java.util.Arrays.equals(uniq.get(u), times[c])) {
                    found = u;
                    break;
                }
            }
            if (found < 0) {
                uniq.add(times[c]);
                found = uniq.size() - 1;
            }
            chanUniq[c] = found;
        }
        this.uniqTimes = uniq.toArray(new float[uniq.size()][]);
        this.uniqSeg = new int[uniqTimes.length];
    }

    /**
     * 从 anim glb 解析并按节点名绑定到 model。绑定失败的通道直接报错——
     * 骨架不一致的动作放上去会出现局部僵死，宁可明确拒绝。
     */
    static L3dClip load(L3dGlb glb, L3dModel model, String name)
        throws L3dGlb.L3dException, org.json.JSONException {
        if (glb.length("animations") == 0) {
            throw new L3dGlb.L3dException("animation file has no animations: " + name);
        }
        if (glb.length("animations") > 1) {
            throw new L3dGlb.L3dException(
                "animation file has " + glb.length("animations") + " animations, expect 1: " + name);
        }
        org.json.JSONObject anim = glb.obj(0, "animations");
        org.json.JSONArray jSamplers = anim.getJSONArray("samplers");
        org.json.JSONArray jChannels = anim.getJSONArray("channels");

        int n = jChannels.length();
        int[] targets = new int[n];
        int[] paths = new int[n];
        float[][] times = new float[n][];
        float[][] values = new float[n][];

        float duration = 0f;
        int bound = 0;
        for (int i = 0; i < n; i++) {
            org.json.JSONObject ch = jChannels.getJSONObject(i);
            String tp = ch.getJSONObject("target").optString("path", "");
            int path;
            if (tp.equals("translation")) path = PATH_T;
            else if (tp.equals("rotation")) path = PATH_R;
            else if (tp.equals("scale")) path = PATH_S;
            else continue; // weights/morph 目标本仓不用
            // glTF：target.node 是本 anim 文件 nodes[] 的下标 → 按名字对到模型节点
            org.json.JSONArray animNodes = glb.array("nodes");
            int localNode = ch.getJSONObject("target").optInt("node", -1);
            String nodeName = "";
            if (localNode >= 0 && animNodes != null && localNode < animNodes.length()) {
                nodeName = animNodes.getJSONObject(localNode).optString("name", "");
            }
            Integer nodeIdx = nodeName.equals("") ? null : model.nodeIndex(nodeName);
            if (path == PATH_T && nodeIdx != null && model.isJointNode(nodeIdx)) {
                // Blender 骨骼动画 location 恒为 0：T 通道会把关节 rest 平移（骨长偏移）
                // 覆盖成 0，整副骨架塌到原点。关节的平移不参与动画，丢弃该通道。
                continue;
            }
            if (nodeIdx == null) {
                throw new L3dGlb.L3dException(
                    "channel target node not in model: " + nodeName + " (clip " + name + ")");
            }
            org.json.JSONObject sampler = jSamplers.getJSONObject(ch.getInt("sampler"));
            String interp = sampler.optString("interpolation", "LINEAR");
            if (!interp.equals("LINEAR") && !interp.equals("STEP")) {
                throw new L3dGlb.L3dException(
                    "unsupported interpolation " + interp + " on clip " + name);
            }
            float[] t = glb.readFloats(sampler.getInt("input"));
            float[] v = glb.readFloats(sampler.getInt("output"));
            int nc = PATH_N[path];
            if (v.length < t.length * nc) {
                throw new L3dGlb.L3dException("sampler output truncated on clip " + name);
            }
            if (t.length > 0 && t[t.length - 1] > duration) duration = t[t.length - 1];
            targets[bound] = nodeIdx;
            paths[bound] = path;
            times[bound] = t;
            values[bound] = v;
            bound++;
        }
        if (bound == 0) {
            throw new L3dGlb.L3dException("no usable channels in clip " + name);
        }
        return new L3dClip(name, duration, java.util.Arrays.copyOf(targets, bound),
            java.util.Arrays.copyOf(paths, bound), java.util.Arrays.copyOf(times, bound),
            java.util.Arrays.copyOf(values, bound));
    }

    /** 把剪辑在时刻 t（秒，调用方已做循环/钳制）的采样写到节点上。 */
    void apply(L3dModel model, float t) {
        // 段索引按唯一时间轴计算（跨帧缓存，播放时间连贯时每轴 O(1)）
        for (int u = 0; u < uniqTimes.length; u++) {
            float[] ts = uniqTimes[u];
            int last = ts.length - 1;
            int seg;
            if (t <= ts[0]) {
                seg = -1;
            } else if (t >= ts[last]) {
                seg = last - 1;
            } else {
                seg = uniqSeg[u];
                if (seg < 0 || seg >= last || ts[seg] > t) {
                    seg = 0;
                }
                while (seg < last - 1 && ts[seg + 1] <= t) {
                    seg++;
                }
            }
            uniqSeg[u] = seg;
        }
        for (int c = 0; c < targets.length; c++) {
            L3dModel.Node node = model.nodes[targets[c]];
            float[] vs = values[c];
            int nc = PATH_N[paths[c]];
            float[] ts = uniqTimes[chanUniq[c]];
            int last = ts.length - 1;
            int seg = uniqSeg[chanUniq[c]];
            if (seg < 0) {
                write(node, paths[c], vs, 0, 0f);
                continue;
            }
            float t0 = ts[seg];
            float t1 = ts[Math.min(seg + 1, last)];
            float u = t1 > t0 ? (t - t0) / (t1 - t0) : 0f;
            int o = seg * nc;
            if (seg == last - 1 && t >= t1) {
                write(node, paths[c], vs, (seg + 1) * nc, 1f);
            } else {
                write(node, paths[c], vs, o, u);
            }
        }
    }

    private static void write(L3dModel.Node node, int path, float[] vs, int o, float u) {
        switch (path) {
            case PATH_T:
                node.t[0] = vs[o];
                node.t[1] = vs[o + 1];
                node.t[2] = vs[o + 2];
                return;
            case PATH_S:
                node.s[0] = vs[o];
                node.s[1] = vs[o + 1];
                node.s[2] = vs[o + 2];
                return;
            default:
                // 四元数 NLERP：本仓动作相邻帧角差小，等价 SLERP 的视觉精度
                float x0 = vs[o], y0 = vs[o + 1], z0 = vs[o + 2], w0 = vs[o + 3];
                float x1 = vs[o + 4], y1 = vs[o + 5], z1 = vs[o + 6], w1 = vs[o + 7];
                float d = x0 * x1 + y0 * y1 + z0 * z1 + w0 * w1;
                if (d < 0f) {
                    x1 = -x1;
                    y1 = -y1;
                    z1 = -z1;
                    w1 = -w1;
                }
                node.r[0] = x0 + (x1 - x0) * u;
                node.r[1] = y0 + (y1 - y0) * u;
                node.r[2] = z0 + (z1 - z0) * u;
                node.r[3] = w0 + (w1 - w0) * u;
                float len = (float) Math.sqrt(node.r[0] * node.r[0] + node.r[1] * node.r[1]
                    + node.r[2] * node.r[2] + node.r[3] * node.r[3]);
                if (len > 1e-6f) {
                    node.r[0] /= len;
                    node.r[1] /= len;
                    node.r[2] /= len;
                    node.r[3] /= len;
                }
        }
    }
}
