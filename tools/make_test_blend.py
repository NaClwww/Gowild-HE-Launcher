# blender --background --python tools/make_test_blend.py -- <out.blend>
#
# 合成一个带骨架 + 多动作 + 贴图的最小测试工程，用于端到端验证导出管线与
# 设备端 l3d 运行时（样例 blend 是空场景，覆盖不了骨骼动画链路）：
#   - 3 节骨骼链（沿 Z 立起），圆柱网格自动权重蒙皮
#   - 棋盘格贴图（打包进 blend）
#   - 3 个动作：idle（缓慢摆动）/ wave（剧烈扭动）/ step（快速小摆）
# 已在 Blender 5.2 LTS 验证。

import bpy
import sys
import math

out_path = None
argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
if not argv:
    print("usage: blender --background --python make_test_blend.py -- out.blend")
    sys.exit(2)
out_path = argv[0]


def log(msg):
    print("[make_test_blend] " + msg)


# ---- 清空默认场景 ----
bpy.ops.wm.read_factory_settings(use_empty=True)
scene = bpy.context.scene
scene.render.fps = 24

# ---- 骨架：3 节链 ----
arm_data = bpy.data.armatures.new("Rig")
arm = bpy.data.objects.new("Rig", arm_data)
scene.collection.objects.link(arm)
bpy.context.view_layer.objects.active = arm
bpy.ops.object.mode_set(mode="EDIT")
bones = []
for i in range(3):
    eb = arm_data.edit_bones.new("b%d" % (i + 1))
    eb.head = (0, 0, i * 1.0)
    eb.tail = (0, 0, (i + 1) * 1.0)
    if i > 0:
        eb.parent = bones[i - 1]
        eb.use_connect = True
    bones.append(eb)
bpy.ops.object.mode_set(mode="OBJECT")

# ---- 网格：圆柱套在骨骼链上 ----
bpy.ops.mesh.primitive_cylinder_add(vertices=16, radius=0.45, depth=3.0,
                                    location=(0, 0, 1.5))
mesh = bpy.context.active_object
mesh.name = "Body"
bpy.ops.object.mode_set(mode="EDIT")
bpy.ops.mesh.select_all(action="SELECT")
bpy.ops.uv.cube_project(cube_size=1.0)
bpy.ops.object.mode_set(mode="OBJECT")

# 自动权重蒙皮（先选网格、再选骨架且骨架为 active）
mesh.select_set(True)
arm.select_set(True)
bpy.context.view_layer.objects.active = arm
bpy.ops.object.parent_set(type="ARMATURE_AUTO")
mesh.select_set(False)
arm.select_set(False)

# ---- 材质 + 棋盘格贴图（打包）----
img = bpy.data.images.new("checker", 128, 128, alpha=False)
px = [0.0] * (128 * 128 * 4)
for y in range(128):
    for x in range(128):
        c = 0.95 if ((x // 16 + y // 16) % 2 == 0) else 0.25
        i = (y * 128 + x) * 4
        px[i] = c
        px[i + 1] = c
        px[i + 2] = c
        px[i + 3] = 1.0
img.pixels = px
img.pack()

mat = bpy.data.materials.new("Skin")
mat.use_nodes = True
nt = mat.node_tree
bsdf = nt.nodes.get("Principled BSDF")
tex = nt.nodes.new("ShaderNodeTexImage")
tex.image = img
nt.links.new(tex.outputs["Color"], bsdf.inputs["Base Color"])
mesh.data.materials.append(mat)

# ---- 动作 ----
def make_action(name, frames, amp, phase_per_bone):
    act = bpy.data.actions.new(name)
    act.use_fake_user = True  # 未指回骨架的动作 0 用户会在保存时被清掉
    ad = arm.animation_data_create()
    ad.action = act
    if ad.action_slot is None and act.slots:
        for slot in act.slots:
            if slot.target_id_type == "OBJECT":
                ad.action_slot = slot
                break
    for pb in arm.pose.bones:
        pb.rotation_mode = "XYZ"
    for f in range(1, frames + 1):
        t = (f - 1) / float(frames - 1)
        for i, pb in enumerate(arm.pose.bones):
            pb.rotation_euler = (math.sin(t * 2 * math.pi + i * phase_per_bone) * amp, 0, 0)
            pb.keyframe_insert(data_path="rotation_euler", frame=f)
    # 5.x slotted actions：fcurve 在 layer/strip/channelbag 里
    n_fc = 0
    for layer in act.layers:
        for strip in layer.strips:
            bag = strip.channelbag(ad.action_slot) if ad.action_slot else None
            if bag is not None:
                n_fc += len(bag.fcurves)
    log("action %r: fcurves=%d slots=%d range=%s" % (
        act.name, n_fc, len(act.slots), tuple(act.frame_range)))
    return act


make_action("idle", 49, math.radians(8), 0.9)
make_action("wave", 25, math.radians(35), 2.1)
make_action("step", 13, math.radians(15), 1.4)
arm.animation_data.action = None

# ---- 保存 ----
bpy.ops.wm.save_as_mainfile(filepath=out_path)
log("saved " + out_path)
log("summary: meshes=%d armatures=%d actions=%d images=%d" % (
    sum(1 for o in scene.objects if o.type == "MESH"),
    sum(1 for o in scene.objects if o.type == "ARMATURE"),
    len(bpy.data.actions), len(bpy.data.images)))
