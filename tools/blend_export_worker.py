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


def export_glb(path):
    opts = {
        "filepath": path,
        "export_format": "GLB",
        "export_apply": True,
        "export_cameras": False,
        "export_lights": False,
        "export_animation_mode": "ACTIONS",
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
        act = bpy.data.actions[action_name]
        ad = arm.animation_data_create()
        ad.action = act
        if ad.action_slot is None and act.slots:
            for slot in act.slots:
                if slot.target_id_type == "OBJECT":
                    ad.action_slot = slot
                    break
        if ad.action_slot is None and act.slots:
            print("[blend_export_worker] ERROR: no OBJECT slot in action " + action_name,
                  file=sys.stderr)
            sys.exit(2)
        # 动作文件只保留骨架：移除全部网格对象后再导出（导出器按整个场景输出，
        # 不移除会把网格/材质/贴图整份重复进每个动作文件；worker 每次调用独立
        # 开 blend 且不保存，删除是安全的）。设备端按节点名绑定动画，网格节点
        # 消失不影响。
        for ob in [o for o in list(bpy.context.scene.objects) if o.type == "MESH"]:
            bpy.data.objects.remove(ob)
        export_glb(out_path)
        log("anim %s written (skeleton-only): %s" % (action_name, out_path))
        sys.exit(0)

    print("unknown mode: " + mode, file=sys.stderr)
    sys.exit(2)


main()
