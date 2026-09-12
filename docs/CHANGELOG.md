# Changelog（live2d_luncher 项目）

本项目自身的变更记录。根目录 `CHANGELOG.md` 为上游 Cubism SDK 自带记录，与本项目的迭代无关。

格式参照 [Keep a Changelog](https://keepachangelog.com/en/1.0.0/)。

## [0.9] - 2026-09-12

### Added

* **3D 模型支持（l3d）**：launcher 新增 glTF 骨骼动画渲染管线，资产按"1 个模型 + n 个动作骨骼"组织，与 Live2D 模型并存于同一套 `/api/models` 资产管理（上传/选择/删除/持久化全复用）。
  * **导出管线** `tools/blend_to_model3d.py` + `tools/blend_export_worker.py`：Blender（5.x LTS 实测）无头导出 `.blend` → l3d 包（zip：`manifest.json` + `model.glb` + `anims/<动作>.glb`）。逐动作**单步重开 blend 导出**（glTF 导出器 ACTIONS 模式会带上文件里全部 stash 的动作，只有"文件里只留目标动作"是确定性的）；动作名 = 文件名主干；自动把 `idle*` 动作写为 `default_animation`（上屏自动循环）；无骨架 blend 合法（静态网格）。附 `tools/make_test_blend.py` 合成带骨架+3 动作+贴图的测试工程。
  * **设备端 l3d 运行时**（`Sample/src/minimum/java/com.live2d.demo.minimum/l3d/`，纯 Java + GLES20，Java 7 语法）：GLB 解析（自有子集：POSITION/NORMAL/TEXCOORD_0/JOINTS_0/WEIGHTS_0、LINEAR/STEP）、节点树全局矩阵、骨骼动画（NLERP 四元数，关节的 translation 通道丢弃——Blender 骨骼动画 location 恒 0，应用会清掉 rest 骨长偏移使骨架塌缩）、按包目录懒加载动作、相机按变换后包围盒自动取景、pose（x/y/zoom）与 flipV 镜像补偿语义对齐 Live2D。
  * **蒙皮 = CPU 实现**：每帧把蒙皮顶点写入动态 VBO（pos3+nor3(0)+uv2 流式上传），着色器退化为 `uMVP × position` + 贴图采样。这是对本机驱动的适配（见 Fixed），也是 375 骨模型唯一可行路径（uniform 调色板与 float 纹理调色板都不可用）。
  * **控制面**：`GET/POST /api/control/animation`（动作清单/播放/停止单次/倍速）；descriptor 增加 `type`（`live2d`/`l3d`）与 l3d 的 `format_version`/`animations`；`/api/status` 增加 `type`；Live2D 专属控制端点在 l3d 上屏时返回 `409`（pose/animation 共享）。上传按包内容自动识别类型，l3d 校验 manifest/glTF 魔数。
  * `docs/API.md` v0.6（§5 包格式、§6.8、错误码、已知限制）。

### Changed（性能优化，同日第二轮）

* miku_eve 实测 **14 → 36fps**（分段计时驱动，探针保留在 L3dScene/L3dModel，默认关闭、置 `perfDbg=1` 开启）：
  * **蒙皮热循环重写**：52.5ms → 11ms/帧——结果写普通 `float[]` 暂存后一次性 `put`（原先 76k 次逐 float `Buffer.put` 是最大开销）、单权重快速路径（刚性绑定部分免 4 权重累加）、动态 VBO 改 pos3+uv2 紧凑布局（stride 20B）。
  * **动作时间轴去重**：采样导出的动作所有通道共享同一时间轴，去重后每帧每轴只算一次段索引（带跨帧缓存）；实测 apply 仅 0.7ms（此前大头其实在 globals）。
  * 剩余分段（120 帧均值）：`globals≈12.2ms`（379 节点全局矩阵 + 375 调色板，Java 数组开销，下一轮优化目标）、`skin≈11.1ms`、`draw≈0.9ms`。
* **修复优化引入的 UV 错位**：动态 VBO 改紧凑布局时 stride 改了但 uv 属性偏移漏改（仍 24，读到下一顶点的位置数据），贴图全串（腿乱纹/衣服糊白/裙斑马纹）；改为蒙皮图元 uv@12、静态图元 uv@24。**教训：改顶点布局必须同步核对全部 vertexAttribPointer 偏移，且改完必须截图目检**（性能数字正常不代表画面正常）。

### Fixed

* **本机（Adreno 304 老驱动）三个 GL 特性不可用/误编译，l3d 管线全部绕开**（各有真机截图证据链）：
  1. `mat4 长乘法链`（`uProjView * uViewPose * uModel * p`）误编译——渲染出巨大错位图形；改 CPU 预乘单个 `uMVP`。
  2. `mat4 数组 uniform`（≤64 骨 uniform 调色板）`glUniform4fv` 恒报 `GL_INVALID_OPERATION`（换 `name[0]` 寻址也一样）——弃用 uniform 调色板。
  3. `OES_texture_float` + 顶点纹理取样（float 纹理骨骼调色板）采样结果不可靠（CPU 同数据正确、GPU 蒙皮后顶点飞散）——弃用 VTF。
* **`ByteBuffer.wrap(bin, start, span)` position 语义坑**：wrap 后 `position(i*stride)` 设的是**绝对数组下标**（capacity=整个数组），i=0 时跳回 bin[0]——JOINTS_0/WEIGHTS_0 全部读成 POSITION 数据（该 bug 在 miku_eve 上表现为蒙皮坐标 1e38、画面全黑；testrig 恰好所有 rest 调色板相近而未被暴露）。修为 wrap 全数组 + 显式 `position(start)`/`limit(start+span)`，调用方以返回时 position 为基址。
* **Live2D 遗留 enabled 顶点属性数组**指向其自身小 buffer：l3d draw 时 GL 校验所有 enabled 数组越界报 `GL_INVALID_OPERATION`、画面全黑——帧首 `resetAttribArrays()` 全量禁用。
* miku_eve 源资产（FBX 导入链）带垃圾权重顶点：rest 蒙皮坐标 1e38（有限但离谱），取景包围盒改 0.5%~99.5% 分位数，蒙皮输出做合法性盒（出界顶点收缩为中心，退化三角形不可见）。

### Verified

* 真机（C2-CMCC）：`testrig`（合成 3 骨 3 动作）与 `miku_eve`（375 骨、3 网格 9.5k 顶点、3 贴图、3 动作，样例 .blend 导出）全链路：上传 → 选择上屏 → 默认/指定动作循环播放（截图连续帧差异证实动画）→ 停止 → 切回 Live2D 无回归（22fps）。l3d 静置/动画实测 14~15fps（GLThread 单核 CPU 蒙皮），RSS 35MB 稳定；负向用例（未知动作、live2d 上屏时 POST animation 409、坏 zip 400）全过。screencap 中人物倒立为预期（投影镜像补偿，实体屏正显）。

## [0.8] - 2026-09-11

### Added

* `/api/light` 舞台灯控制面（机身 18 颗 LED，SN3218 驱动；与 GL 渲染无关）。
  * `LightController`：连系统守护 socket `/dev/socket/zhcctrl`，依次写握手 `zhc_client_ctrl`、`LEDInit`，之后 `LEDA,<灯号>,<亮度>,...` 下发；进程内保持**一条长连接**（守护每接受一个连接起一个 pthread 且不回收已断开的 fd，故不反复连），写失败自动重连一次；所有写操作串行，闪烁定时走专用 HandlerThread。
  * 模式 `off` / `stage`（18 颗分段全亮）/ `red` / `green` / `blue` / `color`（任意色）/ `custom`（逐灯 1..18、亮度 0..255）；`blink` 在**设备端** 1s 交替亮灭（带 generation 防重入，避免重启闪烁时留下两条定时链）；三色 `brightness` 与舞台 `stage_a`/`stage_b` 可调。`mode` 为必填（无"保持当前"的省略语义）。
  * `color` 模式：`rgb:[r,g,b]` 或 `color:"#rrggbb"`/`"#rgb"` 混三色通道，`brightness` 当总调光系数（默认 255 = 不缩放）；`blink` 同样可用。状态里加 `rgb`，因守护是**逐通道更新**，本地按通道累积跟踪（`custom` 未提及的通道保持原值），使 `rgb` 尽量等于设备实际电平。
  * 舞台灯亮度档位读 `/sys/class/zhc_version/hardware_version`（对齐 voice 的 `VoiceApp`：`==2` → 7/40，否则 10/45；文件不存在时默认 `3` → 10/45，本机即此情形）。
  * `GET /api/status` 增加 `light` 摘要。
* `tools/light_rainbow.py`：Mac 侧刷色脚本（HSV 匀速旋转色相 → `POST /api/light {"mode":"color","rgb":[...]}`）。单条 keep-alive 连接（高频刷色不堆 TIME_WAIT）、默认 15Hz、Ctrl-C 退出自动关灯；可 `--hue-min/--hue-max` 限定色域、`--duration` 限时、`--brightness/--saturation` 调节。实测 15Hz 稳定下发 60 条/4s，守护侧无报错；连不上时给出 adb forward / 息屏两条自查提示。
* `docs/API.md` v0.5：新增 §8 舞台灯（含 SELinux 依赖、守护 fd 泄漏两条警告、灯不亮排查顺序），后续章节顺延；概述能力表补齐此前漏掉的「语音采集」与新增的「舞台灯」两行；编排示例补灯光情态时序；错误码速查补灯光相关文案。

### Fixed

* **`off` 没把暖白舞台环关掉**。原先 `off` 发的是 `LEDA,12,0,15,0,18,0`——只有三色通道，照抄了出厂 voice `closeLED` 的语义（暖白环是常亮氛围灯、状态机只管三色）。用户说“关灯”时看到的却是暖白环继续亮。现改为发 18 路全 0（`LEDA,1,0,...,18,0`），`off` = 真·全灭；闪烁的“灭”相位仍只关三色（闪灯不该动暖白环）。
* 状态新增 `ring_on`（暖白环开关，本地跟踪）：`stage` 依档位置位、`off` 置 false、三色模式不动它、`custom` 碰过暖白灯则无法断言——**不确定时该字段缺省**，不猜。文档补“两组灯的关系”对照表（哪个模式动哪组）。

### 研究（逆向修正，无代码）

* 修正此前逆向文档对连接方式的描述：`zhcctrl` 的 socket 是**文件系统路径 `/dev/socket/zhcctrl`**（init 建、经 `ANDROID_SOCKET_` 把 fd 交给守护），**不是抽象命名空间**。voice 里 `LocalSocketAddress("zhcctrl", Namespace.RESERVED)` 的 `RESERVED` 语义是"保留 socket 目录"即 `/dev/socket/<name>`，与 `Namespace.FILESYSTEM` + 绝对路径等价——两条实测都连通，而 `FILESYSTEM` + 相对名 `"zhcctrl"` 报 `No such file or directory`。
* 守护**支持多客户端并发**（每次连接日志 `accepted ok , create pthread`），故与 voice 等其它控制方并存不会互斥（以最后一次写入为准）；但它接受后不回收已断开的 fd，`/proc/net/unix` 会累积已连接条目。
* 可连性依赖 **SELinux `Permissive`**：策略对 `untrusted_app` 写 `socket_device:sock_file` 与 `connectto init:unix_stream_socket` 均是 `denied`（avc 有记录），仅因本机 permissive 才放行。换 enforcing 固件会被拦。

### Verified

* 真机（C2-CMCC）：app（`untrusted_app` 域）连接守护成功，守护日志齐全 —— `accepted ok , create pthread` / `CLIENT Verification Success 15  15` / `libLedBreath: led Init ok`。
* `GET/POST /api/light` 全模式与负向用例实测通过：未知/缺失 mode、`stage`+`blink`、`custom` 缺 `leds`、id/level 越界、坏 JSON 均 `400`；`DELETE` `405`（带 `Allow`）、`/api/light/foo` `404`。闪烁 3s 内 `seq` 走 3 拍（4→7），符合 1s 交替节奏。
* `color` 模式实测：`rgb:[255,120,0]` 原值下发；`#00ff80` + `brightness:128` → `rgb` 回读 `[0,128,64]`（缩放正确）；`#f80` 短写法 → `[255,136,0]`；`color` 缺 rgb/color、rgb 长度非 3、通道越界、hex 非法均 `400`。`custom` 只发 `{"id":12,200}` 再发 `{"id":15,77}`，`rgb` 依次为 `[200,0,0]`、`[200,77,0]`，通道累积跟踪符合预期。
* **物理点亮由用户目测确认**（`stage` 生效，2026-09-11）：协议层由守护日志证实，物理层由人眼确认。
* `off` 语义修正后实测：`stage` → `ring_on:true`；`color` → 环不动（仍 true）；`off` → `ring_on:false` 且 `rgb:[0,0,0]`；进程刚起时 `ring_on` 缺省（不猜）。
* 注：曾试图用机身摄像头做客观验证，失败——其自动曝光/白平衡持续抖动（同状态相邻帧 U/V 漂 ±4、亮度漂 ±20）盖过了灯的贡献，「灭/蓝/灭/红」交替的色度方向对得上但不可复现。以后要验灯别走摄像头这条路。

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
