#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to an installed Android NDK}"
cmake -S "$ROOT/app/src/main/jni/face" -B "$ROOT/build/face-armv7" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=armeabi-v7a -DANDROID_PLATFORM=android-21 \
    -DANDROID_ARM_NEON=ON -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release
cmake --build "$ROOT/build/face-armv7" --parallel 2
mkdir -p "$ROOT/app/src/main/jniLibs/armeabi-v7a"
cp "$ROOT/build/face-armv7/liblocalface.so" "$ROOT/app/src/main/jniLibs/armeabi-v7a/"
for strip in "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/bin/llvm-strip; do
    "$strip" --strip-unneeded "$ROOT/app/src/main/jniLibs/armeabi-v7a/liblocalface.so"
    break
done
