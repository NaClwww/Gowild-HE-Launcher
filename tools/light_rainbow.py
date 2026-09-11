#!/usr/bin/env python3
"""舞台灯彩色循环：让机身的彩色通道连续变色。

设备端 `/api/light`（见 `docs/API.md` §8）只有 3 颗彩色发光体（12=红、15=绿、18=蓝），
本脚本在 HSV 空间匀速旋转色相 → 转 RGB → `POST {"mode":"color","rgb":[r,g,b]}`，
观感就是连续变色（灯本身没有渐变动画，靠高频刷色做出来）。

用法：

    python3 tools/light_rainbow.py                      # 默认 15Hz、10s 转一圈色相
    python3 tools/light_rainbow.py --cycle 4            # 4s 一圈，转得更快
    python3 tools/light_rainbow.py --hue-min .33 --hue-max .52   # 只在绿~青绿之间循环（初音色域）
    python3 tools/light_rainbow.py --brightness 150 --saturation .7  # 暗一点、淡一点
    python3 tools/light_rainbow.py --duration 30        # 演 30 秒就自动收工
    python3 tools/light_rainbow.py --base http://192.168.1.23:8900   # 直连设备 IP
    python3 tools/light_rainbow.py --keep               # Ctrl-C 后保留当前色（默认关灯）

Ctrl-C 退出；默认退出时把灯关掉（`{"mode":"off"}`，18 路全灭）。

macOS 26 Tahoe 注意：第三方 python 访问**局域网**会被 TCC 静默拦（`Errno 65 No route to host`），
所以默认走 adb forward 的 127.0.0.1（loopback 不受该限制）：

    adb forward tcp:8900 tcp:8900

要用 `--base` 直连设备 IP，请从有“本地网络”权限的终端（或 LaunchDaemon）里运行。
"""

import argparse
import colorsys
import http.client
import json
import sys
import time
from urllib.parse import urlparse

PATH = "/api/light"


class Device:
    """极简 HTTP 客户端：单条 keep-alive 连接，避免高频刷色时堆 TIME_WAIT。"""

    def __init__(self, base):
        u = urlparse(base if "//" in base else "http://" + base)
        self.host = u.hostname or "127.0.0.1"
        self.port = u.port or 80
        self.base = base
        self.conn = None

    def close(self):
        if self.conn is not None:
            try:
                self.conn.close()
            except Exception:
                pass
            self.conn = None

    def _request(self, method, body=None):
        if self.conn is None:
            self.conn = http.client.HTTPConnection(self.host, self.port, timeout=3)
        headers = {}
        if body is not None:
            headers["Content-Type"] = "application/json"
        self.conn.request(method, PATH, body, headers)
        resp = self.conn.getresponse()
        data = resp.read()
        if resp.status != 200:
            raise RuntimeError("HTTP %d: %s" % (resp.status, data[:200].decode("utf-8", "replace")))
        return json.loads(data.decode("utf-8"))

    def _call(self, method, obj=None):
        """带一次重连重试（连接可能被设备侧超时回收）。"""
        last = None
        for _ in range(2):
            try:
                body = None if obj is None else json.dumps(obj, separators=(",", ":")).encode("utf-8")
                return self._request(method, body)
            except Exception as e:  # 连接类错误 → 丢弃连接重试一次
                last = e
                self.close()
        raise RuntimeError(str(last))

    def get(self):
        return self._call("GET")

    def post(self, obj):
        return self._call("POST", obj)


def hsv_to_255(h, s, v):
    r, g, b = colorsys.hsv_to_rgb(h % 1.0, max(0.0, min(1.0, s)), max(0.0, min(1.0, v)))
    return int(round(r * 255)), int(round(g * 255)), int(round(b * 255))


def run(dev, args):
    period = 1.0 / args.hz
    t0 = time.monotonic()
    deadline = t0 + args.duration if args.duration > 0 else None
    next_t = t0
    sent = 0
    errors = 0

    try:
        while True:
            now = time.monotonic()
            if deadline is not None and now >= deadline:
                break
            if now < next_t:
                time.sleep(min(next_t - now, 0.05))
                continue
            next_t += period
            if next_t < time.monotonic():  # 落后太多（设备慢/网络卡）就丢弃积压，不追帧
                next_t = time.monotonic() + period

            frac = ((time.monotonic() - t0) / args.cycle) % 1.0
            hue = args.hue_min + (args.hue_max - args.hue_min) * frac
            r, g, b = hsv_to_255(hue, args.saturation, args.brightness / 255.0)
            try:
                dev.post({"mode": "color", "rgb": [r, g, b]})
                sent += 1
                if errors:
                    errors = 0
                if args.verbose:
                    print("hue=%.3f  #%02x%02x%02x" % (hue % 1.0, r, g, b))
            except Exception as e:
                errors += 1
                if errors == 1 or errors % 10 == 0:
                    print("下发失败 ×%d：%s" % (errors, e), file=sys.stderr)
                if errors >= 30:
                    print("连续失败 30 次，放弃（检查 app 是否在前台、屏幕是否亮着）", file=sys.stderr)
                    return 1
                time.sleep(0.5)
    finally:
        print("共下发 %d 条" % sent)
    return 0


def main():
    ap = argparse.ArgumentParser(description="让音箱舞台灯连续变色")
    ap.add_argument("--base", default="http://127.0.0.1:8900",
                    help="控制面地址，默认 adb forward 的 127.0.0.1:8900")
    ap.add_argument("--hz", type=float, default=15.0, help="刷色频率，默认 15")
    ap.add_argument("--cycle", type=float, default=10.0, help="色相转一圈的秒数，默认 10")
    ap.add_argument("--hue-min", type=float, default=0.0, help="色相下限 0..1（默认 0）")
    ap.add_argument("--hue-max", type=float, default=1.0, help="色相上限 0..1（默认 1=全彩虹）")
    ap.add_argument("--saturation", type=float, default=1.0, help="饱和度 0..1，默认 1")
    ap.add_argument("--brightness", type=int, default=255, help="亮度 0..255，默认 255")
    ap.add_argument("--duration", type=float, default=0, help="运行秒数，0=一直跑（默认）")
    ap.add_argument("--keep", action="store_true", help="退出时保留当前颜色（默认关灯）")
    ap.add_argument("--verbose", action="store_true", help="每次刷色打印色值")
    args = ap.parse_args()

    if args.hz <= 0 or args.cycle <= 0:
        ap.error("--hz / --cycle 必须为正数")
    if not 0 <= args.hue_min <= 1 or not 0 <= args.hue_max <= 1:
        ap.error("--hue-min / --hue-max 应在 0..1")
    if not 0 <= args.brightness <= 255:
        ap.error("--brightness 应在 0..255")

    dev = Device(args.base)
    try:
        st = dev.get()
    except Exception as e:
        print("连不上 %s：%s" % (args.base, e), file=sys.stderr)
        print("自查：① app 在前台且屏幕亮着（息屏会停掉 :8900，先 adb shell input keyevent KEYCODE_WAKEUP）", file=sys.stderr)
        print("      ② 走 loopback 需先 adb forward tcp:8900 tcp:8900（adbd 认证过期后会失效，重跑 calc_adbd_auth.py）",
              file=sys.stderr)
        return 1

    if not st.get("available", False):
        print("设备端连不上 zhcctrl 守护：%s" % st.get("last_error", "unknown"), file=sys.stderr)
        return 1

    print("开始刷色：%s  %.0fHz  色相 %.2f..%.2f / %.1fs 一圈  亮度 %d%s"
          % (args.base, args.hz, args.hue_min, args.hue_max, args.cycle, args.brightness,
             "  持续 %gs" % args.duration if args.duration > 0 else ""))
    code = 0
    try:
        code = run(dev, args)
    except KeyboardInterrupt:
        print("\n中断")
    finally:
        if args.keep:
            print("保留当前颜色（--keep）")
        else:
            try:
                dev.post({"mode": "off"})
                print("已关灯")
            except Exception as e:
                print("关灯失败：%s（可手动 POST {\"mode\":\"off\"}）" % e, file=sys.stderr)
        dev.close()
    return code


if __name__ == "__main__":
    sys.exit(main())
