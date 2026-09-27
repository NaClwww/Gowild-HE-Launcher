#!/usr/bin/env python3
# 单动作 glb 后处理器：把 l3d 动作文件裁到目标模型能用的最小集合。
#
#   python3 l3d_retarget_anims.py <model.glb> <in.glb> <out.glb>
#
# 做三件事：
#   1) 通道裁剪：丢掉目标节点名不在 model.glb 节点表里的通道（换骨架/换皮肤模型用；
#      eve 的 375 骨动作直接喂 nighcord 会因 16 根独有骨被设备端整包 400 拒收）。
#   2) 去冗余几何：动作文件里的 meshes/materials/textures/images/skins 运行时都不读
#      （weights 通道由模型侧解析：anim 节点名 → 模型节点 → 该节点的 mesh），剥掉。
#   3) BIN 重打包：只保留仍被动画引用的 accessor/bufferView，4 字节对齐重排。
#
# 设备端只从动作文件取 animations + nodes[].name，其余一律忽略，所以以上都是无损的；
# 保留 nodes 是因为通道目标按「本文件 nodes 下标 → 名字」解析。

import json
import struct
import sys


def read_glb(path):
    with open(path, "rb") as f:
        data = f.read()
    if data[0:4] != b"glTF":
        raise SystemExit("not a glb: " + path)
    jlen, = struct.unpack_from("<I", data, 12)
    js = json.loads(data[20:20 + jlen].decode("utf-8"))
    bin_off = 20 + jlen
    bin_chunk = b""
    if bin_off + 8 <= len(data):
        blen, btype = struct.unpack_from("<II", data, bin_off)
        if btype == 0x004E4942:  # BIN\0
            bin_chunk = data[bin_off + 8: bin_off + 8 + blen]
    return js, bin_chunk


def write_glb(path, js, bin_chunk):
    jbytes = json.dumps(js, separators=(",", ":")).encode("utf-8")
    if len(jbytes) % 4:
        jbytes += b" " * (4 - len(jbytes) % 4)
    padded = bin_chunk
    if len(padded) % 4:
        padded += b"\x00" * (4 - len(padded) % 4)
    total = 12 + 8 + len(jbytes) + (8 + len(padded) if padded else 0)
    with open(path, "wb") as f:
        f.write(b"glTF")
        f.write(struct.pack("<II", 2, total))
        f.write(struct.pack("<I", len(jbytes)))
        f.write(b"JSON")
        f.write(jbytes)
        if padded:
            f.write(struct.pack("<I", len(padded)))
            f.write(b"BIN\x00")
            f.write(padded)


def node_names(js):
    return [n.get("name", "") for n in js.get("nodes", [])]


def retarget(model_js, anim_js, anim_bin):
    model_nodes = set(node_names(model_js))
    names = node_names(anim_js)

    anims = anim_js.get("animations", [])
    if len(anims) != 1:
        raise SystemExit("expect exactly 1 animation, got %d" % len(anims))
    anim = anims[0]

    drop = []
    kept_ch = []
    used_samplers = set()
    for ci, ch in enumerate(anim.get("channels", [])):
        nidx = ch["target"].get("node", -1)
        nm = names[nidx] if 0 <= nidx < len(names) else ""
        if nm and nm in model_nodes:
            kept_ch.append(ch)
            used_samplers.add(ch["sampler"])
        else:
            drop.append(nm or ("#" + str(nidx)))
    if not drop:
        drop = []

    # 采样器重编号，只留被引用的
    remap = {}
    new_samplers = []
    for si in sorted(used_samplers):
        remap[si] = len(new_samplers)
        new_samplers.append(anim["samplers"][si])
    new_channels = []
    for ch in kept_ch:
        ch = dict(ch)
        ch["sampler"] = remap[ch["sampler"]]
        new_channels.append(ch)

    keep_acc = set()
    for sm in new_samplers:
        keep_acc.add(sm["input"])
        keep_acc.add(sm["output"])

    # accessor / bufferView 重打包（含 sparse 引用）
    accs = anim_js.get("accessors", [])
    bvs = anim_js.get("bufferViews", [])
    used_bv = set()
    for ai in keep_acc:
        a = accs[ai]
        if "bufferView" in a:
            used_bv.add(a["bufferView"])
        sp = a.get("sparse")
        if sp:
            used_bv.add(sp["indices"]["bufferView"])
            used_bv.add(sp["values"]["bufferView"])

    new_bin = bytearray()
    bv_remap = {}
    new_bvs = []
    for bi in sorted(used_bv):
        bv = bvs[bi]
        src = anim_bin[bv.get("byteOffset", 0): bv.get("byteOffset", 0) + bv["byteLength"]]
        off = len(new_bin)
        new_bin += src
        while len(new_bin) % 4:
            new_bin.append(0)
        nbv = dict(bv)
        nbv["byteOffset"] = off
        bv_remap[bi] = len(new_bvs)
        new_bvs.append(nbv)

    acc_remap = {}
    new_accs = []
    for ai in sorted(keep_acc):
        a = dict(accs[ai])
        if "bufferView" in a:
            a["bufferView"] = bv_remap[a["bufferView"]]
        new_accs.append(a)
        acc_remap[ai] = len(new_accs) - 1
    # sparse 的 bufferView 引用同样重排
    for a, src in zip(new_accs, [accs[ai] for ai in sorted(keep_acc)]):
        if src.get("sparse"):
            a["sparse"] = dict(src["sparse"])
            a["sparse"]["indices"] = dict(src["sparse"]["indices"])
            a["sparse"]["values"] = dict(src["sparse"]["values"])
            a["sparse"]["indices"]["bufferView"] = bv_remap[src["sparse"]["indices"]["bufferView"]]
            a["sparse"]["values"]["bufferView"] = bv_remap[src["sparse"]["values"]["bufferView"]]

    for sm in new_samplers:
        sm["input"] = acc_remap[sm["input"]]
        sm["output"] = acc_remap[sm["output"]]

    out = {
        "asset": anim_js.get("asset", {"version": "2.0"}),
        "scene": anim_js.get("scene", 0),
        "scenes": anim_js.get("scenes", [{"nodes": []}]),
        "nodes": [],
        "animations": [{"name": anim.get("name", ""), "samplers": new_samplers,
                        "channels": new_channels}],
        "accessors": new_accs,
        "bufferViews": new_bvs,
        "buffers": [{"byteLength": len(new_bin)}],
    }
    # 只保留名字与层级：节点下标必须原样保持（通道目标按本文件下标解析）
    for n in anim_js.get("nodes", []):
        nn = {"name": n.get("name", "")}
        if "children" in n:
            nn["children"] = n["children"]
        out["nodes"].append(nn)
    return out, bytes(new_bin), drop


def main():
    if len(sys.argv) != 4:
        print(__doc__ or "usage: l3d_retarget_anims.py <model.glb> <in.glb> <out.glb>")
        sys.exit(2)
    model_js, _ = read_glb(sys.argv[1])
    anim_js, anim_bin = read_glb(sys.argv[2])
    out, new_bin, drop = retarget(model_js, anim_js, anim_bin)
    write_glb(sys.argv[3], out, new_bin)
    print("OK %s channels=%d dropped=%d(%s) samplers=%d size=%.2fMB" % (
        sys.argv[3], len(out["animations"][0]["channels"]), len(drop),
        ",".join(sorted(set(drop))[:3]) if drop else "-",
        len(out["animations"][0]["samplers"]), len(new_bin) / 1048576.0))


if __name__ == "__main__":
    main()
