#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/build/face-tests"
mkdir -p "$OUT/java"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}"
"${JAVA_BIN}javac" -d "$OUT/java" \
    "$ROOT/app/src/main/java/com/live2d/demo/minimum/control/FaceTrackingMath.java" \
    "$ROOT/app/src/main/java/com/live2d/demo/minimum/control/FaceTrackingMotion.java" \
    "$ROOT/tools/tests/FaceTrackingTest.java"
"${JAVA_BIN}java" -cp "$OUT/java" com.live2d.demo.minimum.control.FaceTrackingTest
cmake -S "$ROOT/app/src/main/jni/face" -B "$OUT/native" -DCMAKE_BUILD_TYPE=Debug \
    -DCMAKE_CXX_FLAGS="-fsanitize=address,undefined -fno-omit-frame-pointer" \
    -DCMAKE_EXE_LINKER_FLAGS="-fsanitize=address,undefined"
cmake --build "$OUT/native" --parallel 2
ctest --test-dir "$OUT/native" --output-on-failure
