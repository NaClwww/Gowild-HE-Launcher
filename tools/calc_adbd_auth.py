#!/usr/bin/env python3
"""Automatically complete the vendor adbd challenge-response authentication.

Designed for Python 3 on Windows. ``adb.exe`` is discovered from PATH.
With no positional argument the script performs authentication automatically.
Passing a challenge keeps the original offline-calculator mode available.
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import zlib
from dataclasses import dataclass


PROMPT = "Who are you ? (O_O)???"
SECRET = "zhihuichuang"
WELCOME = "Welcome home . O(^_^)O~~"
CHALLENGE_RE = re.compile(r"Who are you \? \(O_O\)\?\?\? \((\d+)\)")


class AdbError(RuntimeError):
    """An actionable adb error that can be shown directly to the user."""


@dataclass
class CommandResult:
    returncode: int
    stdout: str
    stderr: str

    @property
    def output(self) -> str:
        return "\n".join(part.strip() for part in (self.stdout, self.stderr) if part.strip())


def parse_seed(value: str) -> int:
    value = value.strip()
    if value.isdecimal():
        seed = int(value, 10)
    else:
        match = CHALLENGE_RE.search(value)
        if match is None:
            raise ValueError("未找到挑战数值；请传入完整提示或括号中的十进制数字")
        seed = int(match.group(1), 10)

    if not 0 <= seed <= 0xFFFFFFFF:
        raise ValueError("挑战数值必须是 32 位无符号整数")
    return seed


def calculate_auth_code(seed: int) -> tuple[int, str]:
    crc_text = f"{PROMPT} ({seed}) {SECRET}"
    auth_code = zlib.crc32(crc_text.encode("ascii")) & 0xFFFFFFFF
    return auth_code, crc_text


def run_adb(adb: str, args: list[str], timeout: float) -> CommandResult:
    creationflags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
    try:
        completed = subprocess.run(
            [adb, *args],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
            creationflags=creationflags,
            check=False,
        )
    except subprocess.TimeoutExpired as exc:
        raise AdbError(f"adb 命令等待超过 {timeout:g} 秒") from exc
    except OSError as exc:
        raise AdbError(f"无法启动 adb: {exc}") from exc

    return CommandResult(completed.returncode, completed.stdout, completed.stderr)


def list_devices(adb: str, timeout: float) -> dict[str, str]:
    result = run_adb(adb, ["devices"], timeout)
    if result.returncode != 0:
        raise AdbError(result.output or "adb devices 执行失败")

    devices: dict[str, str] = {}
    for line in result.stdout.splitlines()[1:]:
        fields = line.strip().split()
        if len(fields) >= 2:
            devices[fields[0]] = fields[1]
    return devices


def select_device(devices: dict[str, str], requested_serial: str | None) -> str:
    if requested_serial is not None:
        state = devices.get(requested_serial)
        if state is None:
            raise AdbError(f"未找到指定设备: {requested_serial}")
        if state != "device":
            raise AdbError(f"设备 {requested_serial} 当前状态为 {state!r}，不是可用的 device 状态")
        return requested_serial

    online = [serial for serial, state in devices.items() if state == "device"]
    if len(online) == 1:
        return online[0]
    if len(online) > 1:
        choices = "\n  ".join(online)
        raise AdbError(f"检测到多个在线设备，请用 -s 指定：\n  {choices}")

    if not devices:
        raise AdbError("没有检测到设备；请检查 USB/TCP 连接和 USB 调试开关")

    states = "\n  ".join(f"{serial}: {state}" for serial, state in devices.items())
    if any(state == "unauthorized" for state in devices.values()):
        states += "\n请先在设备屏幕上允许 Android 原生 RSA 调试授权。"
    raise AdbError(f"没有处于 device 状态的设备：\n  {states}")


def target_args(serial: str) -> list[str]:
    return ["-s", serial]


def probe_device(adb: str, serial: str, marker: str, timeout: float) -> CommandResult:
    return run_adb(adb, [*target_args(serial), "shell", "echo", marker], timeout)


def authenticate(adb: str, serial: str, timeout: float) -> bool:
    marker = f"__ADBD_AUTH_OK_{os.getpid()}__"
    print(f"设备      : {serial}")
    print("正在探测厂商认证状态……")

    probe = probe_device(adb, serial, marker, timeout)
    if marker in probe.stdout:
        print("结果      : 设备已经通过厂商认证，无需重复认证")
        return True

    try:
        seed = parse_seed(probe.output)
    except ValueError as exc:
        detail = probe.output or f"adb 返回码 {probe.returncode}，没有输出"
        raise AdbError(f"未能从设备响应中提取挑战值。\nadb 输出：\n{detail}") from exc

    auth_code, crc_text = calculate_auth_code(seed)
    print(f"challenge : {seed}")
    print(f"CRC 输入  : {crc_text}")
    print(f"auth code : {auth_code}")
    print("正在提交授权码……")

    response = run_adb(
        adb,
        [*target_args(serial), "shell", f"auth:{auth_code}"],
        timeout,
    )
    if response.output:
        print(f"设备响应  : {response.output}")

    verification = probe_device(adb, serial, marker, timeout)
    if marker not in verification.stdout:
        detail = verification.output or response.output or "设备没有返回验证标记"
        raise AdbError(f"授权码已提交，但复验没有通过。\nadb 输出：\n{detail}")

    print("结果      : 厂商认证成功，ADB service 已正常放行")
    return True


def print_offline_result(value: str, serial: str | None) -> None:
    seed = parse_seed(value)
    auth_code, crc_text = calculate_auth_code(seed)
    serial_arg = f"-s {serial} " if serial else ""
    print(f"challenge : {seed}")
    print(f"CRC 输入  : {crc_text}")
    print(f"auth code : {auth_code}")
    print(f"command   : adb {serial_arg}shell auth:{auth_code}")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="自动完成厂商魔改 adbd 的 CRC32 挑战认证（Windows/Python 3）"
    )
    parser.add_argument(
        "challenge",
        nargs="?",
        help="可选：只离线计算该挑战；省略时连接设备自动认证",
    )
    parser.add_argument("-s", "--serial", help="adb 设备序列号；多设备时必须指定")
    parser.add_argument(
        "--timeout",
        type=float,
        default=10.0,
        help="每条 adb 命令的超时秒数（默认 10）",
    )
    return parser


def main() -> int:
    args = build_parser().parse_args()
    if args.timeout <= 0:
        print("错误: --timeout 必须大于 0", file=sys.stderr)
        return 2

    try:
        if args.challenge is not None:
            print_offline_result(args.challenge, args.serial)
            return 0

        adb = shutil.which("adb")
        if adb is None:
            raise AdbError("PATH 中没有找到 adb.exe，请重新打开终端或检查 PATH")

        print(f"ADB       : {adb}")
        start = run_adb(adb, ["start-server"], args.timeout)
        if start.returncode != 0:
            raise AdbError(start.output or "adb start-server 执行失败")

        serial = select_device(list_devices(adb, args.timeout), args.serial)
        authenticate(adb, serial, args.timeout)
        return 0
    except (AdbError, ValueError) as exc:
        print(f"错误: {exc}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("\n已取消", file=sys.stderr)
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
