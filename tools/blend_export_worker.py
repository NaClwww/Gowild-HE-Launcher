# blender --background <file.blend> --python tools/blend_export_worker.py -- <mode> [args]
#
# 单步导出 worker（由 tools/blend_to_model3d.py 驱动，每个输出文件独立起一次
# Blender 进程）。为什么按文件重启：glTF 导出器在 ACTIONS 模式下会导出文件里
# 全部 stash 的动作、SCENE 模式实测也会混入，靠清 active/NLA 隔不干净；
# 只有"文件里只留目标动作"是确定性的。
#
# 模式：
#   probe                      打印 "PROBE_JSON:" + 单行 JSON（fps/骨架/动作清单）
#   model   <out.glb>          导出无动画的模型（网格+骨架 rest+蒙皮+贴图）
#   anim    <action> <out.glb> 只留指定动作并指到骨架，导出单动画文件
#
# 已在 Blender 5.2 LTS 验证（slotted actions）。

import bpy
import json
import sys


def log(msg):
    print("[blend_export_worker] " + msg)


def remove_all_actions_except(keep_name=None):
    for act in list(bpy.data.actions):
        if keep_name is not None and act.name == keep_name:
            continue
        bpy.data.actions.remove(act)


def clear_object_animation():
    for ob in bpy.data.objects:
        ad = ob.animation_data
        if ad is None:
            continue
        ad.action = None
        if ad.action_slot is not None:
            try:
                ad.action_slot = None
            except RuntimeError:
                pass
        while ad.nla_tracks:
            ad.nla_tracks.remove(ad.nla_tracks[0])
    for scene in bpy.data.scenes:
        scene.animation_data_clear()


def scene_fps():
    return float(bpy.context.scene.render.fps) / float(bpy.context.scene.render.fps_base or 1.0)


def pick_armature():
    meshes = [ob for ob in bpy.context.scene.objects if ob.type == "MESH"]
    votes = {}
    for ob in meshes:
        parent = ob.parent
        while parent is not None:
            if parent.type == "ARMATURE":
                votes[parent.name] = votes.get(parent.name, 0) + 1
                break
            parent = parent.parent
        for m in ob.modifiers:
            if m.type == "ARMATURE" and m.object is not None:
                votes[m.object.name] = votes.get(m.object.name, 0) + 1
    if not votes:
        return None
    return max(votes.items(), key=lambda kv: kv[1])[0]


def export_glb(path, anim_mode="ACTIONS"):
    opts = {
        "filepath": path,
        "export_format": "GLB",
        "export_apply": True,
        "export_cameras": False,
        "export_lights": False,
        "export_animation_mode": anim_mode,
        "export_optimize_animation_size": False,
    }
    props = bpy.ops.export_scene.gltf.get_rna_type().properties.keys()
    kw = {k: v for k, v in opts.items() if k in props}
    bpy.ops.export_scene.gltf(**kw)


def action_compatible_slot(act, arm):
    if act.slots:
        ad = arm.animation_data_create()
        for slot in act.slots:
            if slot.target_id_type == "OBJECT":
                return slot
    return None


def merge_gltf_animations(path, name):
    """SCENE/ACTIONS 模式会按来源 ID 拆成多条 glTF 动画（骨骼一条、形态键一条）。
    设备端约定单文件单动画：把所有动画的 channel/sampler 并入第一条并更名。
    纯 JSON 手术，BIN chunk 与 accessor 引用原样不动。"""
    import json as _json
    import struct as _struct
    with open(path, "rb") as f:
        data = f.read()
    jlen, = _struct.unpack_from("<I", data, 12)
    js = _json.loads(data[20:20 + jlen].decode("utf-8"))
    anims = js.get("animations", [])
    if len(anims) <= 1:
        if anims:
            anims[0]["name"] = name
            _rewrite_glb(path, data, js)
        return
    base = anims[0]
    for extra in anims[1:]:
        off = len(base["samplers"])
        for sm in extra.get("samplers", []):
            base["samplers"].append(sm)
        for ch in extra.get("channels", []):
            ch = dict(ch)
            ch["sampler"] = ch["sampler"] + off
            base["channels"].append(ch)
    base["name"] = name
    js["animations"] = [base]
    _rewrite_glb(path, data, js)
    log("merged %d animations -> 1 (%d channels)" % (
        len(anims), len(base["channels"])))


def _rewrite_glb(path, old_data, js):
    import json as _json
    import struct as _struct
    jlen, = _struct.unpack_from("<I", old_data, 12)
    bin_off = 20 + jlen
    bin_len, = _struct.unpack_from("<I", old_data, bin_off)
    bin_chunk = old_data[bin_off + 8: bin_off + 8 + bin_len]
    jbytes = _json.dumps(js, separators=(",", ":")).encode("utf-8")
    if len(jbytes) % 4:
        jbytes += b" " * (4 - len(jbytes) % 4)
    total = 12 + 8 + len(jbytes) + 8 + len(bin_chunk)
    with open(path, "wb") as f:
        f.write(b"glTF")
        f.write(_struct.pack("<II", 2, total))
        f.write(_struct.pack("<I", len(jbytes)))
        f.write(b"JSON")
        f.write(jbytes)
        f.write(_struct.pack("<I", len(bin_chunk)))
        f.write(b"BIN\x00")
        f.write(bin_chunk)


def main():
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    if not argv:
        print("usage: ... -- probe | model <out.glb> | anim <action> <out.glb>")
        sys.exit(2)
    mode = argv[0]

    if mode == "probe":
        info = {
            "blender": bpy.app.version_string,
            "fps": scene_fps(),
            "armature": pick_armature(),
            "meshes": sum(1 for ob in bpy.context.scene.objects if ob.type == "MESH"),
            "actions": [],
        }
        arm = info["armature"]
        arm_ob = bpy.data.objects.get(arm) if arm else None
        for act in sorted(bpy.data.actions, key=lambda a: a.name):
            entry = {
                "name": act.name,
                "range": [round(act.frame_range[0], 3), round(act.frame_range[1], 3)],
            }
            if arm_ob is not None:
                entry["compatible"] = action_compatible_slot(act, arm_ob) is not None
            info["actions"].append(entry)
        print("PROBE_JSON:" + json.dumps(info, ensure_ascii=False))
        sys.exit(0)

    if mode == "model":
        out_path = argv[1]
        remove_all_actions_except(None)
        clear_object_animation()
        export_glb(out_path)
        log("model written: " + out_path)
        sys.exit(0)

    if mode == "anim":
        action_name, out_path = argv[1], argv[2]
        if action_name not in bpy.data.actions:
            print("[blend_export_worker] ERROR: action not found: " + action_name, file=sys.stderr)
            sys.exit(2)
        arm_name = pick_armature()
        arm = bpy.data.objects.get(arm_name) if arm_name else None
        if arm is None:
            print("[blend_export_worker] ERROR: no armature to drive actions", file=sys.stderr)
            sys.exit(2)
        remove_all_actions_except(action_name)
        clear_object_animation()
        # 动作文件多为复制副本，网格对象名带 .NNN 后缀（Face.003），先剥掉，
        # 才能与动作槽位记录的原始对象名（Face）匹配
        import re as _re
        for ob in list(bpy.context.scene.objects):
            if ob.type in ("MESH", "ARMATURE"):
                ob.name = _re.sub(r"\.\d{3}$", "", ob.name)
        act = bpy.data.actions[action_name]
        # 把动作挂到所有匹配槽位的对象上：表情链路是"Face 对象的自定义属性
        # （face_blink_L/R、face_mouth_open）→ 驱动器 → 形态键"，只挂骨架的话
        # 自定义属性不动画，驱动器输出恒 0，烘焙出的表情是一条平线
        ad = arm.animation_data_create()
        ad.action = act
        ad.action_slot = None
        if act.slots:
            for slot in act.slots:
                if slot.target_id_type == "OBJECT" and slot.name_display == arm.name:
                    ad.action_slot = slot
                    break
        if ad.action_slot is None and act.slots:
            for slot in act.slots:
                if slot.target_id_type == "OBJECT":
                    ad.action_slot = slot
                    break
        if ad.action_slot is None and act.slots:
            print("[blend_export_worker] ERROR: no OBJECT slot in action " + action_name,
                  file=sys.stderr)
            sys.exit(2)
        for slot in act.slots:
            if slot.target_id_type != "OBJECT" or slot.name_display == arm.name:
                continue
            ob = bpy.data.objects.get(slot.name)
            if ob is None:
                log("slot %s has no matching object, skipped" % slot.name)
                continue
            sad = ob.animation_data_create()
            sad.action = act
            sad.action_slot = slot
            log("action also bound to object %s (slot %s)" % (ob.name, slot.name_display))
        # 形态键烘焙：表情链路（自定义属性→驱动器→shape key）exporter 采不到，
        # 按帧求值后显式插入 fcurve，导出器才能输出 weights 通道
        scene = bpy.context.scene
        r0, r1 = act.frame_range
        scene.frame_start = int(r0)
        scene.frame_end = max(scene.frame_start, int(-(-r1 // 1)))
        mesh_obs = [ob for ob in scene.objects
                    if ob.type == "MESH" and ob.data.shape_keys]
        bakeDbg = False   # 置 True 输出逐帧烘焙采样值（排查表情链路用）
        # 注意：不能 clear shape_keys 的 animation_data——驱动器 fcurve 就住在里面，
        # 清掉后表情链路断、烘焙出全 0（fcurve 与驱动器同通道时驱动器优先，不冲突）
        for f in range(scene.frame_start, scene.frame_end + 1):
            scene.frame_set(f)
            bpy.context.view_layer.update()
            for ob in mesh_obs:
                for kb in ob.data.shape_keys.key_blocks:
                    if kb.name != "Basis":
                        kb.keyframe_insert(data_path="value", frame=f)
            if bakeDbg:
                sk0 = mesh_obs[0].data.shape_keys if mesh_obs else None
                kv = {kb.name: round(kb.value, 3) for kb in sk0.key_blocks
                      if kb.name in ("eye_close", "mouth_a")} if sk0 else {}
                print("[blend_export_worker] bakeDbg f=%d props=%s keys=%s" % (
                    f, {k: round(arm.get(k, -1), 3)
                        for k in ("face_blink_L", "face_mouth_open")}, kv))
        baked = sum(len(ob.data.shape_keys.key_blocks) - 1 for ob in mesh_obs)
        log("baked %d shape keys over frames [%d,%d]" % (
            baked, scene.frame_start, scene.frame_end))
        # 注意：morph（weights）通道以网格节点为目标，网格对象必须保留在场景里，
        # 不能为省体积剥掉（否则表情通道整体丢失）。
        # SCENE 模式把骨骼动作与形态键动画合并成同一条 glTF 动画
        # （ACTIONS 模式下两条 ID 各出一条动画，设备端约定单文件单动画）
        for ob in bpy.data.objects:
            ad = ob.animation_data
            if ad is not None and ad.action is None:
                ad.action = act  # 无动作对象挂上主动作，保证 SCENE 采样覆盖
        export_glb(out_path, anim_mode="SCENE")
        merge_gltf_animations(out_path, action_name)
        log("anim %s written: %s" % (action_name, out_path))
        sys.exit(0)

    print("unknown mode: " + mode, file=sys.stderr)
    sys.exit(2)


main()
