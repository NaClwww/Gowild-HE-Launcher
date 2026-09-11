#!/usr/bin/env python3
# usage: python3 tools/blend_to_model3d.py <in.blend> <out.zip> [--name NAME] [--blender PATH]
#
# l3d 包导出驱动器：对同一个 blend 调起 Blender 无头跑 tools/blend_export_worker.py
# （probe → model.glb → 逐动作 anims/<name>.glb），打 zip 并写 manifest.json。
# 包格式约定见 docs/API.md §5（l3d）。
#
# 依赖：Blender 5.x（本机 /Applications/Blender.app）。宿主只要求 python3。

import argparse
import json
import os
import re
import struct
import subprocess
import sys
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
WORKER = os.path.join(HERE, "blend_export_worker.py")


def log(msg):
    print("[blend_to_model3d] " + msg)


def find_blender(explicit):
    if explicit:
        return explicit
    app = "/Applications/Blender.app/Contents/MacOS/Blender"
    if os.path.exists(app):
        return app
    from shutil import which
    p = which("blender")
    if p:
        return p
    print("blender not found (use --blender /Applications/Blender.app/Contents/MacOS/Blender)",
          file=sys.stderr)
    sys.exit(2)


def run_worker(blender, blend_path, args):
    cmd = [blender, "--background", blend_path, "--python", WORKER, "--"] + args
    p = subprocess.run(cmd, capture_output=True, text=True)
    if p.returncode != 0:
        sys.stderr.write(p.stdout + p.stderr)
        print("worker failed: " + " ".join(args), file=sys.stderr)
        sys.exit(2)
    return p.stdout


def sanitize(name):
    s = re.sub(r"[^A-Za-z0-9._-]", "_", name)
    s = re.sub(r"_+", "_", s).strip("_")
    return s if s else "model"


def glb_json(path):
    with open(path, "rb") as f:
        data = f.read()
    if len(data) < 20 or data[0:4] != b"glTF":
        return None
    json_len, = struct.unpack_from("<I", data, 12)
    return json.loads(data[20:20 + json_len].decode("utf-8"))


def anim_duration_s(path, fallback):
    """动画时长取采样时间轴 min/max（accessor 自带）；拿不到退回动作帧区间换算。"""
    try:
        j = glb_json(path)
        anim = j["animations"][0]
        t_in = anim["samplers"][0]["input"]
        acc = j["accessors"][t_in]
        return round(float(acc["max"][0]) - float(acc["min"][0]), 4)
    except Exception:
        return round(fallback, 4)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("blend")
    ap.add_argument("out_zip")
    ap.add_argument("--name")
    ap.add_argument("--blender", default=os.environ.get("BLENDER"))
    args = ap.parse_args()

    blend = os.path.abspath(args.blend)
    blender = find_blender(args.blender)
    out_zip = os.path.abspath(args.out_zip)

    out = run_worker(blender, blend, ["probe"])
    probe = None
    for line in out.splitlines():
        if line.startswith("PROBE_JSON:"):
            probe = json.loads(line[len("PROBE_JSON:"):])
            break
    if probe is None:
        print("probe produced no PROBE_JSON", file=sys.stderr)
        sys.exit(2)
    log("probe: meshes=%d armature=%s fps=%g actions=%d" % (
        probe["meshes"], probe["armature"], probe["fps"], len(probe["actions"])))
    if probe["meshes"] == 0:
        print("blend has no mesh objects", file=sys.stderr)
        sys.exit(2)

    pkg_name = sanitize(args.name or os.path.splitext(os.path.basename(blend))[0])
    tmp = tempfile.mkdtemp(prefix="l3d_")

    model_path = os.path.join(tmp, "model.glb")
    run_worker(blender, blend, ["model", model_path])
    mj = glb_json(model_path)
    if mj is None or not mj.get("meshes"):
        print("model.glb export failed", file=sys.stderr)
        sys.exit(2)
    if mj.get("animations"):
        print("model.glb unexpectedly has %d animations (stripping failed)" % len(mj["animations"]),
              file=sys.stderr)
        sys.exit(2)
    log("model.glb: %d nodes, %d meshes, %d skins, %d anims(=0 expected)" % (
        len(mj.get("nodes", [])), len(mj.get("meshes", [])),
        len(mj.get("skins", [])), len(mj.get("animations", []))))

    anims = []
    if probe["armature"]:
        seen = set()
        for act in probe["actions"]:
            if not act.get("compatible", True):
                log("skip action %r (no compatible OBJECT slot)" % act["name"])
                continue
            stem = sanitize(act["name"])
            base, n = stem, 1
            while stem in seen:
                n += 1
                stem = "%s_%d" % (base, n)
            seen.add(stem)
            anim_path = os.path.join(tmp, "anim_" + stem + ".glb")
            run_worker(blender, blend, ["anim", act["name"], anim_path])
            aj = glb_json(anim_path)
            n_anim = len(aj.get("animations", [])) if aj else 0
            if n_anim != 1:
                log("skip action %r (export produced %d animations)" % (act["name"], n_anim))
                continue
            frames = act["range"][1] - act["range"][0]
            duration = anim_duration_s(anim_path, frames / probe["fps"])
            anims.append({"name": stem, "file": "anims/" + stem + ".glb",
                          "duration_s": duration})
            log("anim %s: %.3fs" % (stem, duration))

    default_anim = next((a["name"] for a in anims if a["name"].lower().startswith("idle")), None)
    manifest = {
        "format_version": 1,
        "type": "l3d",
        "name": pkg_name,
        "generator": {"tool": "blend_to_model3d.py", "blender": probe["blender"]},
        "model": "model.glb",
        "animations": anims,
        "default_animation": default_anim,
    }

    os.makedirs(os.path.dirname(out_zip) or ".", exist_ok=True)
    with zipfile.ZipFile(out_zip, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2))
        z.write(model_path, "model.glb")
        for a in anims:
            z.write(os.path.join(tmp, "anim_" + a["name"] + ".glb"), a["file"])
    log("wrote %s (%.1f KB, %d animations)" % (
        out_zip, os.path.getsize(out_zip) / 1024.0, len(anims)))


if __name__ == "__main__":
    main()
