# NV21 → JPEG native 编码器

`libnv21jpeg.so`：把相机预览的 NV21 帧编成 JPEG，供 `CameraController` 的
`/api/camera/stream`（MJPEG）与 `/api/camera/frame`（单帧）使用。

## 为什么是 native

`android.graphics.YuvImage.compressToJpeg` 每次调用会泄漏约 **2.5KB native 堆**
（本机实测 **58MB/h**，拉流整夜累积到 674MB 后被内核 LMK 在 adj 0 杀掉；
StackOverflow [15696165](https://stackoverflow.com/questions/15696165/) 是同一问题的公开案例），
而框架内无法修补。绕开它的替代方案都不成立：

| 方案 | 结论 |
|---|---|
| Java 侧 YUV→RGB + `Bitmap.compress` | 不漏了，但实测 **72ms + 52ms** 每帧，camera-pump 从 24% 顶到 95% |
| `ScriptIntrinsicYuvToRGB`（RenderScript） | 本机 ROM 上 ``libRSCpuRef`` 里 **SIGBUS**，不可用 |
| HAL 直出 JPEG（`preview-format=jpeg`） | 该 HAL 的 `preview-format-values` 只有 `yuv420sp,yuv420p,nv12-venus` |
| MediaCodec 硬件 JPEG | `/etc/media_codecs.xml` 里没有 jpeg 编码器 |
| **libjpeg-turbo raw YCbCr 通路** | ✅ 本方案 |

## 关键点

NV21 本身就是 Y/Cb/Cr 4:2:0，与 JPEG 内部存法一致，所以走 libjpeg-turbo 的
`raw_data_in` + `JCS_YCbCr` 可以**完全不做颜色空间转换**：只把平面按行拷成
libjpeg 要的排布（顺便完成上下/左右镜像，逐行 `memcpy` 而非逐像素），
DCT/Huffman 交给它的 NEON 内核。

两个易错处已在主机侧单测覆盖（见 `tools/test_nv21jpeg.c`）：

* **平面拆分**：NV21 的 Y 平面 + 交错 VU 要拆成 Y/Cb/Cr 三个平面，其中
  **V 在前 → 对应 JPEG 的 Cr，U → Cb**（顺序写反会让红蓝互换）。
* **底部填充**：`jpeg_write_raw_data` 按整数个 iMCU 行工作，高度不足一个
  iMCU 时要用最后一行填充（代码里 `yRowsAlloc`/`cRowsAlloc`）。

另外 `error_exit` 必须换成 `longjmp`——libjpeg 默认实现是 `exit(1)`，
一帧坏数据就会杀掉整个进程。

## 重新构建

`.so` 是作为预编译产物提交的（本机没有 SDK 自带 cmake，且只用 armeabi-v7a
一个 ABI，这样也不受 gradle 增量收集抽风影响）。改了 C 代码后：

```bash
# 需要 cmake(>=3.10) 与 NDK 源码包
ANDROID_NDK_HOME=/path/to/android-ndk-r25c \
LJT_DIR=/path/to/libjpeg-turbo-3.0.4 \
tools/build-native.sh
```

产物落到 `app/src/main/jniLibs/armeabi-v7a/libnv21jpeg.so`，gradle 会直接打包。

### 工具链上的两个坑（本次踩过）

* **NDK 的 macOS 包必须在 Apple Silicon 上原生可用**：实测 **r25c 的 `clang-14`
  是 universal 二进制（x86_64 + arm64）**，prebuilt 目录虽叫 `darwin-x86_64`
  但能原生跑，不需要 Rosetta。反倒是名字带 `-darwin-aarch64` 的旧包
  （**r22b** 验证过）里面全是 x86_64，名不副实——不要被文件名骗。
* **libjpeg-turbo 3.0.4 拒绝 `add_subdirectory()` 集成**（其 CMakeLists 会直接
  `message(FATAL_ERROR)` 要求改用 `ExternalProject_Add`），所以它必须作为
  独立工程先编成 `jpeg-static` 静态库，再由本脚本用 NDK 的
  `armv7a-linux-androideabi21-clang` 把 wrapper 链上去。
* 用 CMake 4.x 时，libjpeg-turbo 声明的是 `cmake_minimum_required(2.8.12...3.28)`，
  需要 `-DCMAKE_POLICY_VERSION_MINIMUM=3.5`（4.x 已移除对 <3.5 的兼容）。

主机侧正确性单测（不需要 NDK）：

```bash
LJT=/path/to/libjpeg-turbo-3.0.4
cmake -S $LJT -B /tmp/ljt-host -DCMAKE_POLICY_VERSION_MINIMUM=3.5 -DENABLE_SHARED=FALSE
cmake --build /tmp/ljt-host -j8
cc -O2 -o /tmp/t tools/test_nv21jpeg.c app/src/main/jni/nv21_jpeg_core.c \
   -I$LJT -I/tmp/ljt-host -I app/src/main/jni /tmp/ljt-host/libjpeg.a
/tmp/t /tmp/nv21out   # 生成若干测试 JPEG，用 djpeg 解码核对方向与红蓝
```

## 文件

| 文件 | 作用 |
|---|---|
| `nv21_jpeg_core.{h,c}` | 编码核心，不依赖 JNI，可在主机上单测 |
| `nv21_jpeg.c` | JNI 绑定（`com.live2d.demo.minimum.control.Nv21JpegEncoder`） |

构建由 `tools/build-native.sh` 驱动（没有 CMakeLists：libjpeg-turbo 独立编静态库，
wrapper 用 NDK clang 直接编译链接）。
