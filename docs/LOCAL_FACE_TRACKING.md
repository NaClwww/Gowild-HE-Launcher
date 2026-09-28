# 本地人脸随动与 CPU 预算

分支 `feat/local-face-tracking`。将原 backend 的“角色看向人脸”行为放到 launcher，
不依赖 backend、不上传图像。它不是人眼注视点估计。

## 开启

默认关闭；配置持久化，开启后随 launcher 前台恢复。

```sh
BASE=http://127.0.0.1:8900  # USB 使用 adb forward tcp:8900 tcp:8900；或设备局域网地址
curl -X POST "$BASE/api/control/tracking" -d '{"enabled":true}'
curl "$BASE/api/control/tracking"
curl -X POST "$BASE/api/control/tracking" -d '{"enabled":false}'
```

`POST /api/camera {"on":false}` 同时持久化关闭追踪，避免相机被重新打开。
扫码、Activity 暂停、切换到 l3d 时停止检测并释放追踪的相机占用；回到前台 Live2D
后自动恢复。HTTP lookat / 触摸接管 5 秒。仅关闭追踪时，已有视频流或显式开启的相机继续工作。

## CPU 路径

```text
Camera1 NV21 回调（复用 HAL 缓冲区）
  → 到期且 worker 空闲时，采样到复用的小 NV21 缓冲区
  → 立即归还 HAL 缓冲区，忙时丢帧，不排队
  → 后台优先级单线程：小图 BGR 转换 → YuNet 原生检测
  → 最新检测结果槽位
  → GL 线程 8Hz 随动控制 → Live2D 现有 drag 缓动
```

- 默认输入宽 192，4:3 摄像头为 192×144；每次采样约 40.5KiB，原 640×480 帧约 450KiB。
- 采样前检查频率与 busy；跳过的帧不拷贝、不做颜色转换。业务缓冲区在尺寸不变时复用。
- 没有 JPEG 编解码、loopback HTTP、OpenCV/ncnn 运行时；不注册 MJPEG 客户端。
- ARM NEON，`-O3`，关闭 OpenMP，只有一个推理线程；不占用 GL 线程做推理。
- 模型与推理代码来自 libfacedetection，固定上游提交 `acf7b254121927e7dced30e233a2e03119f28ea2`。
  它是 YuNet 作者的原生实现，权重不等同于旧 backend 的 `2023mar.onnx`；暗光、侧脸、小脸需实机对比。
  上游推理内部仍有临时 native 分配，不能把该实现描述为“完全零分配”。

每帧用线程 CPU 时钟统计“相机线程采样 + worker 颜色转换及推理”，控制下一次采样间隔：

```text
interval_ms = max(1000 / max_fps,
                  max(本次CPU耗时, EMA CPU耗时) * 1000 / cpu_budget_ms,
                  本次检测墙钟耗时,
                  无人超过4秒时的1000ms)
```

默认预算 `80ms / 秒`，相当于平均约 8% 的一个 CPU 核。例：检测耗时 40ms，间隔至少
500ms，约 2Hz；耗时 160ms，降到约 0.5Hz，不用最低 1Hz 限制突破预算。
这是反馈控制目标，不是硬实时 CPU 限额；首帧、负载突变会产生短暂超额。
预算不含相机 HAL、正常预览回调固定开销、渲染、其他流消费者的 JPEG 编码和原生库首次初始化。

## 参数与状态

GET 返回配置和运行状态；POST 局部更新配置，非法值返回 400 且整次更新不生效。

| 配置 | 默认 | 范围 / 用途 |
|---|---:|---|
| enabled | false | 开关 |
| input_width | 192 | 128..320，32 的倍数；更小更省 CPU，小脸识别会下降 |
| max_fps | 5 | 1..8，检测频率上限；8Hz 随动控制独立 |
| cpu_budget_ms | 80 | 10..300，每秒允许的检测 CPU 毫秒目标 |
| gain | 0.3 | 0..1，转头 / 眼神幅度 |
| mx / my | 1 / 1 | 只能 ±1，左右 / 上下校准 |

状态字段：`mode`（disabled/starting/tracking/holding/wander/manual/paused/inactive_model/error）、
`camera_active`、`busy`、`detect_cpu_ms`（EMA）、`detect_wall_ms`、`sample_cpu_ms`、
`interval_ms`、`detect_fps`（最近两次有效完成的间隔）、`total_cpu_ms`（累计采样+检测 CPU）、
`detected_frames`、`dropped_busy`、`faces`、`confidence`、`result_age_ms`、`render_fps`、`last_error`。
CPU 统计包括颜色转换与后处理，不仅仅是网络推理。error 后重新 POST 配置可重试。

随动保留旧代码的 gain=.3、响应幂次=.6、8Hz 下 EMA=.28、deadband=.03、丢脸保持4秒、
30秒零点自适应与无人随机游走。摄像头采样与现有 JPEG 共用翻转配置（当前上下翻转），
归一化后按旧版实测符号输出；方向跟反时调 `mx/my`。
当前选择置信度最高的人脸，多人场景可能切换目标，与旧 backend 一致。

## 构建与检查

提交预编译 `liblocalface.so`（与已有 nv21jpeg 路径一致），普通 Gradle 构建不需要下载模型。
修改 native 后必须先重建原生库，再构建 APK：

```sh
ANDROID_NDK_HOME=/path/to/android-ndk bash tools/build-face-native.sh
JAVA_HOME=/path/to/jdk21 ./gradlew assembleDebug
JAVA_HOME=/path/to/jdk21 bash tools/test-face.sh
```

host tests 覆盖 NV21 采样 / VU 顺序 / 镜像、预算降频、检测过期、保持与游走、native 输入检查、
多尺寸检测与缓冲区复用。native host test 开启 AddressSanitizer/UBSan。
这些检查不代表 ARM 真机性能验收。

## 真机 CPU 对比

固定同一个 Live2D 模型、画面和灯光，分别测试关闭 / 开启追踪，每次预热后采样 60 秒。
测试过程中不要打开 MJPEG 流；如需比较同时看流的使用场景，单独测一组。

```sh
python tools/benchmark-face.py --base "$BASE" --seconds 60 > tracking.csv
```

这个脚本只读状态，不会启用追踪、取 JPEG 或修改设备配置。
比较 `render_fps`、CSV 的 `measured_cpu_ms_per_s` 与跟手程度；同时用设备进程 / 线程 CPU
观测确认相机固定开销。至少覆盖正面、左右移动、暗光、无人、多人，以及扫码往返 / 明确关相机。
再做 30 分钟常开观察内存是否稳定。

若预算下频率过低，优先将 `input_width` 降至160或128；仍不够再讨论提高预算。
不要只提高 `max_fps`：有效频率始终由实际耗时和预算决定。

当前开发环境没有连接 ADB 真机，尚未给出音箱上的 CPU / FPS / 温度结论。
