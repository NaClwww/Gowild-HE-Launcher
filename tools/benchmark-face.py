#!/usr/bin/env python3
"""Read-only face/render telemetry sampler. Compare runs with tracking on/off.

Uses only stdlib. CPU figures cover sampling and detector worker work, not camera
HAL, JPEG, render or total process CPU. /api/control/tracking itself never opens
a video stream or requests a JPEG.
"""
import argparse
import csv
import json
import sys
import time
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", required=True, help="e.g. http://127.0.0.1:8900")
    parser.add_argument("--seconds", type=int, default=60)
    args = parser.parse_args()
    if args.seconds < 1:
        parser.error("--seconds must be positive")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    writer = csv.writer(sys.stdout)
    writer.writerow(["elapsed_s", "mode", "render_fps", "detect_fps", "detect_cpu_ms",
                     "detect_wall_ms", "sample_cpu_ms", "interval_ms", "measured_cpu_ms_per_s",
                     "faces", "result_age_ms", "error"])
    start = time.monotonic()
    previous = None
    while time.monotonic() - start < args.seconds:
        tick = time.monotonic()
        try:
            with opener.open(args.base.rstrip("/") + "/api/control/tracking", timeout=5) as response:
                data = json.load(response)
            now = time.monotonic()
            total = data["total_cpu_ms"]
            cpu_rate = ""
            if previous and total >= previous[1]:
                cpu_rate = round((total - previous[1]) / (now - previous[0]), 2)
            previous = (now, total)
            writer.writerow([round(now - start, 2), data["mode"], data["render_fps"],
                             data["detect_fps"], data["detect_cpu_ms"], data["detect_wall_ms"],
                             data["sample_cpu_ms"], data["interval_ms"], cpu_rate,
                             data["faces"], data["result_age_ms"], data.get("last_error", "")])
        except (OSError, ValueError, KeyError) as error:
            print(f"telemetry failed: {error}", file=sys.stderr)
            previous = None
        sys.stdout.flush()
        time.sleep(max(0, 1 - (time.monotonic() - tick)))


if __name__ == "__main__":
    main()
