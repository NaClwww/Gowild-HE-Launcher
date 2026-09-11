#!/bin/bash
#
# 构建 libnv21jpeg.so（armeabi-v7a / android-21）并放到 Sample/src/main/jniLibs/。
#
# 为什么不用 gradle 的 externalNativeBuild：本机没有 SDK 自带 cmake，这颗设备也只用
# armeabi-v7a 一个 ABI；把 .so 作为预编译产物提交更省事，也不受 gradle 增量收集抽风影响。
# 改了 C 代码后重跑本脚本即可。
#
# 需要：
#   - cmake（>=3.10，PATH 里能找到）
#   - Android NDK。注意 macOS 的 NDK 包在 prebuilt 目录里放的是 universal 二进制
#     （r25c 的 clang-14 同时含 x86_64 与 arm64），所以 Apple Silicon 上不需要 Rosetta；
#     反倒名字带 -darwin-aarch64 的旧包（如 r22b）实测只有 x86_64。
#   - libjpeg-turbo 源码（本次用 3.0.4）。注意 3.0.4 的 CMakeLists 明确拒绝被
#     add_subdirectory() 集成，所以它必须作为一个独立工程先编成静态库。
#
# 用法：
#   ANDROID_NDK_HOME=/path/to/android-ndk-r25c \
#   LJT_DIR=/path/to/libjpeg-turbo-3.0.4 \
#   tools/build-native.sh
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NDK="${ANDROID_NDK_HOME:-/tmp/androidsdk/android-ndk-r25c}"
LJT="${LJT_DIR:-/tmp/androidsdk/libjpeg-turbo-3.0.4}"
ABI=armeabi-v7a
API=21

[ -d "$NDK" ] || { echo "找不到 NDK: $NDK（设 ANDROID_NDK_HOME）" >&2; exit 1; }
[ -d "$LJT" ] || { echo "找不到 libjpeg-turbo 源码: $LJT（设 LJT_DIR）" >&2; exit 1; }
command -v cmake >/dev/null || { echo "PATH 里没有 cmake" >&2; exit 1; }

TOOLCHAIN="$NDK/build/cmake/android.toolchain.cmake"
[ -f "$TOOLCHAIN" ] || { echo "找不到 NDK toolchain: $TOOLCHAIN" >&2; exit 1; }

HOST_TAG="$(basename "$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/ | head -1)")"
CC="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin/${ABI/armeabi-v7a/armv7a}-linux-androideabi${API}-clang"
# ABI 名到 clang wrapper 前缀的映射（只支持本项目要的这一个）
case "$ABI" in
    armeabi-v7a) TARGET_TRIPLE="armv7a-linux-androideabi" ;;
    arm64-v8a)   TARGET_TRIPLE="aarch64-linux-android" ;;
    *) echo "未支持的 ABI: $ABI" >&2; exit 1 ;;
esac
CC="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin/${TARGET_TRIPLE}${API}-clang"
[ -x "$CC" ] || { echo "找不到编译器: $CC" >&2; exit 1; }

LJT_BUILD="$ROOT/build/ljt-$ABI"
OUT_DIR="$ROOT/Sample/src/main/jniLibs/$ABI"
JNI_SRC="$ROOT/Sample/src/main/jni"

echo "NDK      : $NDK ($HOST_TAG)"
echo "CC       : $CC"
echo "libjpeg  : $LJT"
echo "输出     : $OUT_DIR/libnv21jpeg.so"

# ---- 1) libjpeg-turbo 静态库（独立工程；它拒绝 add_subdirectory 集成） ----
cmake -S "$LJT" -B "$LJT_BUILD" \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$API" \
    -DANDROID_ARM_NEON=ON \
    -DENABLE_SHARED=FALSE \
    -DENABLE_STATIC=TRUE \
    -DWITH_TURBOJPEG=FALSE \
    -DWITH_SIMD=TRUE \
    -DWITH_ARITH_DEC=FALSE \
    -DWITH_ARITH_ENC=FALSE \
    -DWITH_FUZZ=FALSE \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
    -Wno-deprecated > "$LJT_BUILD.configure.log" 2>&1 || {
        echo "libjpeg-turbo 配置失败，见 $LJT_BUILD.configure.log" >&2; tail -20 "$LJT_BUILD.configure.log" >&2; exit 1; }

cmake --build "$LJT_BUILD" -j"$(sysctl -n hw.ncpu 2>/dev/null || echo 4)" --target jpeg-static

# ---- 2) NV21 → JPEG wrapper ----
mkdir -p "$OUT_DIR"
"$CC" -O2 -fPIC -shared \
    -ffunction-sections -fdata-sections -Wl,--gc-sections \
    -o "$OUT_DIR/libnv21jpeg.so" \
    -I"$LJT" -I"$LJT_BUILD" \
    "$JNI_SRC/nv21_jpeg.c" \
    "$JNI_SRC/nv21_jpeg_core.c" \
    "$LJT_BUILD/libjpeg.a" \
    -llog

STRIP="$(ls "$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-strip 2>/dev/null | head -1 || true)"
[ -n "$STRIP" ] && "$STRIP" --strip-unneeded "$OUT_DIR/libnv21jpeg.so" || true

echo "--- 产物 ---"
ls -la "$OUT_DIR/libnv21jpeg.so"
file "$OUT_DIR/libnv21jpeg.so"
