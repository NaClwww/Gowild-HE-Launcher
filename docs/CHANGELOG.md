# Changelog（live2d_luncher 项目）

本项目自身的变更记录。根目录 `CHANGELOG.md` 为上游 Cubism SDK 自带记录，与本项目的迭代无关。

格式参照 [Keep a Changelog](https://keepachangelog.com/en/1.0.0/)。

## [0.7] - 2026-09-10

### Fixed

* **开机自启后渲染再次损坏（黑剪影 / 白底 / 左下角贴图碎片），根因是摄像头与 GL 初始化的竞态**——不是着色器链接（本次 `Program link log` 计数为 0，链接重试工作正常），也不是"加载慢"（12s 后依旧损坏且该进程内不自愈）。
  * 机理：HTTP 控制面在 `onCreate/onStart` 就可服务，而 GL 上下文与模型贴图要等 surface 建立后才开始创建。开机时外部客户端（Mac 侧 `face_tracker.py` 拉 MJPEG 流）一连上就隐式开摄像头，此时 `previewSink` 还是 null，`openOnThread` 退化成裸 `SurfaceTexture(0)`——老 QCOM HAL 便与首批 GL 对象（sink 贴图、模型贴图/着色器）创建并发抢驱动，整批贴图静默丢失：采样 (0,0,0,1) → 黑剪影，个别侥幸上传成功的图集 → 碎片。
  * 修复：`CameraController.ensureRenderWarm()` 闸门——真正 open 前必须等 `GL 就绪 && 模型已加载 && 已产出过帧`，最长等 4s（客户端请求只是慢几秒，不出错）；超时且进程运行超 20s（GL 起不来）则放行并告警，避免把摄像头功能连带卡死。`previewSink` 缺失的退化路径补告警日志便于定位。
  * 实证：相机开→关时渲染 100% 正常；关→开（启动后开）渲染正常；启动瞬间连摄像头则必坏。修复后带 face_tracker 冷启动连续 6 次全部干净（167–168KB 截图 vs 损坏态 12–14KB），且开机请求被闸门压后约 3s（日志：启动 21:19:04 → 21:19:07.034 才 probe/open），MJPEG 流恢复出帧（frame_seq 15fps 递增）。
* **扫码取景拉伸**：相机 640×480（4:3）与屏 1024×600 比例不同，TextureView 原先 MATCH_PARENT 强制拉满。改为按预览宽高比 letterbox 居中（`applyPreviewLayout()`，surface 尺寸变化时重算），多余方向留黑边，取景不再变形。

### Added

* **开机自启双路**（对齐出厂 eve/voice 的做法）：`MainActivityMinimum` 的 HOME 过滤器补 `android.intent.category.DEFAULT`（此前只有 HOME，系统不认"始终"选择，每次开机弹 ResolverActivity 选择框盖在桌面上，看起来像黑屏）；`BootReceiver` 改为 `onReceive` 内立即 `startActivity`（原先延迟 5s，广播结束后进程无组件驻留被 AMS 当空进程回收，`Killing ... empty`，定时器永远走不到）。

### Changed

* **摄像头编码帧率 15 → 10fps**（`CameraController.TARGET_FPS`）：实测线程级 CPU 显示 `camera-pump` 占 36%（NV21 翻转 + JPEG 编码），而 GLThread 本就钉满 1 核，合计 139%；降帧后 camera-pump 23.7%、合计 125.5%。**但渲染 fps 几乎没变（~20）**——GLThread 是自身单线程管线卡在 45ms/帧，腾出 CPU 换不来帧率，所以真要提帧只有 Native SDK 一条路。副作用：15fps 源 + 100ms 整数倍限频会落到"隔帧编码"，实际出帧 ≈6–7fps（面部追踪无感；想精确落在 10fps 需要追赶式节拍，已评估后按"不折腾"保持简单限频）。
* **`GET /api/camera` 的 `fps` 字段**：原硬编码 15，改读 `CameraController.getTargetFps()`，避免限频值变化后误报。

## [0.6] - 2026-09-10

### Fixed

* **冷启动模型渲染损坏（黑剪影+贴图碎片+图集碎块）**：la0920 老驱动在**新进程的首批 GLSL 程序链接必发失败**（`GL_LINK_STATUS=FALSE` 且 info log 为空，Cubism 全部 7 个着色器程序中招；此后再链接必然成功，故运行时切模型正常、唯独冷启动烂）。修复：`CubismShaderAndroid.linkProgram` 失败后重试（≤10 次、间隔 20ms）。冷启动链接错误 7 → 0。**此故障与任何近期功能无关，是潜伏的驱动怪癖**——此前冷启动后从未目检画面，故一直未暴露。
* **GL 生命周期加固**（配合扫码界面覆盖主 Activity 的往返）：`setPreserveEGLContextOnPause(true)` + `onStop` 不再拆除 Cubism 现场 + `GLRenderer.onSurfaceCreated` 在上下文重建时整模重载（链接重试兜底）。往返实测渲染完好。

### Added

* **扫码配网**：机身触摸条"扫码键"（tp-key，短按 `KEYCODE_F2`）→ `ScanActivity` 相机取景 + ZXing（core 3.3.3，NV21 Y 平面逐 2 帧解码）→ 解析 `WIFI:S/T/P/H;;`（含 `\` 转义）→ `WifiManager` 删旧同SSID配置 + addNetwork/enableNetwork/reconnect → 轮询 12s 验证 SSID+IP → 结果提示 2s 自动关闭；60s 超时/BACK 退出。
  * 摄像头互斥与状态切换：`CameraController.beginScanSession/endScanSession`——进入时保存 explicit 现场并关停监控流（流 EOF，客户端重连恢复），期间 `turnOn` 一律拒绝；结束时按保存值恢复。扫码期间主 Activity `onStop` 连带控制面 HTTP 停服（现有生命周期行为）。
  * 投影补偿（实测修正）：投影对 framebuffer 是**上下镜像（V）而非 180°旋转**——根视图 `scale(1,-1)`，TextureView 预览再补一次 V 翻转，`setDisplayOrientation(0)`；overlay 中心不画（PorterDuff CLEAR 挖孔在硬件层下露出黑底）。
  * 按键侦察：扫码键=tp-key F2（≥2s 特长按另有 `hum_sensor` F6 信号备用）；老 `input` 工具键码表是旧编号（131=F1），测试用符号名 `KEYCODE_F2`。
  * compileSdk 34 无 `WifiConfiguration.GroupCiphers`（WEP 路径删，一律 PSK/NONE）。
  * 注：本功能曾随渲染损坏排查整体回滚，根因确认与扫码无关后已带修正重落地。

### Verified

* 真机：冷启动渲染正确（链接错误 0）；F2 → ScanActivity 上屏 → BACK → Live2D 完好（session begin/end 日志齐全、控制面恢复、fps 正常）。QR 解码→连网的实物端到端待用户持码实测。

## [0.5] - 2026-09-09

### Added

* `/api/voice/*` 语音采集面（纯采集，播放端待 TTS 联调时再做）：路由 `/api/voice/{name}/*`，当前源仅 `mic`。
  * `VoiceController`：AudioRecord（VOICE_RECOGNITION 源，无 AGC）采集线程 → 有界队列（40ms 块，满丢最旧计 `drops`）；显式开启常开，隐式（流打开）10s 无人读看门狗自动关。
  * `MicStream`：chunked 裸 PCM16LE 单声道流，EOF 后重连即隐式重开。
  * 端点：`GET/POST /api/voice/mic`（状态 / `{"on","rate":8000..48000}`）、`POST /mic/start`、`/mic/stop`、`GET /mic/stream`（ASR 接入口）。
  * 未知采集源 / 未知子路由返回 404 并提示可用项。
* `docs/API.md` v0.4：新增 §8 语音采集，后续章节顺延；编排示例补语音听写流程；路线图播放端改为 `POST /api/voice/play*`（播放侧 RMS 自动喂口型方案）。

### Fixed

* 摄像头输出上下颠倒：JPEG 编码前在 NV21 域做行翻转（`FLIP_V=true` 硬编码于 `CameraController`，无 API 变化；左右镜像 `FLIP_H=false` 留常量待定）。
* 麦克风流断续（两处叠加）：① 采集循环每块后 `sleep(10)` 造成产出仅 ~80% 实时，HAL 初始缓冲耗尽后播放端周期性饿死——删除 sleep，节拍交给阻塞读；② 多客户端共享单队列互相抢块（8200 ASR 常驻监听时，新读者只能拿到一半）——改为每客户端独立队列 + 广播（慢客户端丢自己最旧块并单独计 drops），15s 未读的僵死客户端自动回收。修后实测：单读者 0.97 实时率；ASR+抓流并发时两端均满速零丢块。

### Verified

* 真机：`start` → 16kHz 出流（3s 90,880 字节，seq=72 块）；稳态读取零丢块（4s 121,600 字节，drops 无增长）；客户端断开计数归零；隐式开启 12s 后看门狗自动关；`stop` 即时生效且活跃流 EOF；404/405 负向全过。实测底噪 RMS≈0.0014（VOICE_RECOGNITION 无 AGC 属正常）。

## [0.4] - 2026-09-09

### Fixed

* pose 的 `y` 方向与文档相反（y+ 实测向实体屏下方）：修正 `LAppMinimumLive2DManager` 位移符号并截图验证 y+ = 实体屏向上。

### 研究（无代码保留）

* 性能定位：临时视口缩放实验（代码已回退）证明 GPU 填充非瓶颈（渲染分辨率 1/16 时 fps 不变）、idle 动作求值 ~4%；Miku 22fps 为 CPU bound（Java 框架每帧 drawable 循环 + JNI + GLES20 绑定；Core 形变/物理为 native，两 SDK 相同）。GL surface 1024×575 对 1024×600 面板天然点对点。

## [0.3] - 2026-09-09

### Added

* `/api/camera` 摄像头控制面：显式开关（`POST {"on":false/true}`）、JPEG 质量设置、MJPEG 实时流（`GET /api/camera/stream`，multipart/x-mixed-replace）、单帧快照（`GET /api/camera/frame`）。
  * `CameraController`：Camera1 API + 专用 HandlerThread，NV21→JPEG（默认 640×480，限频 15fps），隐式开启 + 10s 无取帧看门狗自动关机。
  * `MjpegStream`：multipart 记录流封装，连续 15s 无新帧 EOF。
* `/api/status` 增加 `camera` 摘要字段。
* 文档重组：`API.md` 迁入 `docs/` 并重写为规范参考（v0.3）；新增本变更记录 `docs/CHANGELOG.md`。

### Fixed

* 老 QCOM HAL 预览回调静默不出帧：GL 线程（`onSurfaceCreated`）创建带 EGL 上下文的 `SurfaceTexture` 假预览屏注入相机（无上下文线程创建的 `SurfaceTexture(0)` 不可靠）。
* 显式关闭摄像头后 MJPEG 流把相机隐式复活：`closeOnThread` 改为先递增关闭代数再摘除 camera 引用，消除 `isOn()=false` 与换代数之间的竞态窗口；流检测到代数变化即 EOF。
* app 退后台（`onStop`）时显式关停摄像头并清除 explicit 标记，不再依赖看门狗。

### Verified

* 真机：`POST on` → `frame_seq` 增长（~10fps）；单帧为合法 640×480 JPEG；3s 流 ≈ 330KB/30 帧；显式关闭时活跃流 5s 内 EOF 且相机保持关闭。

## [0.2] - 2026-09-08

### Added

* `/api/control/*` 运行时控制全套：pose（部分更新+持久化）、motion（优先级 1-3）、expression（面部叠加层+clear）、param（TTL 逐帧覆盖，单条/批量/clear）、lipsync（双通道混合+150ms 自动闭合）、lookat（归一化目标+reset）、idle（on/off + 插播间隔）。
* 面部表情叠加层（独立 CubismMotionManager，键入参数覆盖眨眼）。
* 无 Idle 组模型的轻量动作池插播策略（`w-*` tilthead/nod 随机）。
* `API.md` v0.1。

### Fixed

* 官方样例 `startMotion` 反转条件 bug 与 429 动作预载卡死（改惰性加载）。
* minimum 变体缺 EyeBlink/Breath 创建。

## [0.1] - 2026-09-08

### Added

* Live2D 桌面最小渲染（投影上下镜像补偿 + 纯黑背景）。
* 模型资产管理 `/api/models`：内置+外置（`/sdcard/live2d/models/`）双源扫描、zip 上传（Zip Slip 防护、moc3 魔数/版本校验）、选择上屏（202 异步，~2.4s）、删除保护（内置/上屏中）。
* `GET /api/status`、NanoHTTPD `:8900` 控制面、GL 线程命令队列。

[0.3]: ./API.md
[0.2]: ./API.md
[0.1]: ./API.md
