# 设备与开发环境笔记（live2d_luncher）

记硬件/工具链层面的既定事实与坑，不属于 API 变更（API 走 `docs/API.md`，变更走 `docs/CHANGELOG.md`）。

## 显示：实体屏 ↔ `adb screencap` 是上下镜像关系

**结论：`adb shell screencap` 拿到的是 framebuffer 合成结果，它与实体屏上人眼看到的画面相差一次「垂直翻转」（上下镜像，V）。左右不换——不是 180° 旋转。**

```
framebuffer（screencap 所见）          实体屏（人眼所见）
   头顶在下方、脚在上方        ──V 翻──▶   正常直立
```

### 为什么

投光/面板这条显示通路本身把画面上下镜像了。应用侧为此在渲染时做了一次反向补偿，于是"写进 framebuffer 的画面"是倒的，"打上实体屏的画面"才是正的：

* Live2D 模型：`LAppMinimumLive2DManager.FLIP_VERTICALLY = true` → `projection.scaleRelative(1.0f, -1.0f)`（注释："投影光路上下颠倒"）。
* 扫码界面：根视图 `setScaleY(-1)` 补偿（所以界面里按正常方向绘制的文字，实体屏上正显），`TextureView` 预览再补一次 `setScaleY(-1)`，`setDisplayOrientation(0)`。

### 之所以确定是 V 而不是 180°

扫码界面最初按 180°（`scale(-1,-1)`）补偿时，用户实测反馈"显示上下镜像，左右镜像。全反了"——即两个轴都错了；改成只翻 V 之后画面正确。所以镜像只有垂直一个轴。

### 实操影响

* **别用截图判断朝向**：截图里的人物是倒的（这是预期现象，不是渲染坏了）。要"以人的视角"看，先把截图上下翻转。
* **位置也一起翻了**：截图里的左下角，对应实体屏的左上角。排查"某处花屏"时要先换位再想。
* **截图里的文字是上下镜像的**（左右正常），翻过来即可正常阅读。
* **触摸坐标不跟着翻**：镜像发生在显示通路，触摸输入仍按屏幕/framebuffer 坐标上报，因此凡是把触摸映射到"已镜像的渲染"上的地方，都要对应取反 y（如 `LAppMinimumLive2DManager.onDrag` 里的 `FLIP_VERTICALLY ? -y : y`）。
* 摄像头流不受此影响：`/api/camera/*` 出的是传感器图像，其上下翻转是在 NV21 域另做的（`CameraController.FLIP_V`），与显示通路的镜像无关。

### 相关参数（la0920 / C2-CMCC）

| 项 | 值 |
|---|---|
| 面板分辨率 | 1024×600（横屏） |
| density | 160（1dp = 1px） |
| `screencap` 输出 | 1024×600（与面板一致） |
| 投影补偿 | 仅 V 翻转：`scale(1,-1)` |

截图取证建议：`adb exec-out screencap -p > shot.png`（或 `screencap -p /sdcard/x.png` + `pull`）。**PNG 体积本身就是好用的快速判据**——正常渲染 150–200KB，渲染损坏（黑剪影/白底/纯色）只有 4–14KB。

## 触摸条按键（tp-key）

触摸条走 `/dev/input/event6`（tp-key），上报 `KEY_F1..KEY_F5`，经 Generic.kl 映射为 Android `KEYCODE_F1..F5`（131–135）。五键语义按出厂 eve（`cn.gowild.robotlife.eve` MainActivity 的 sparse-switch 反编译，2026-09-28）：

| 触摸条 | Android 键码 | 出厂行为 | 本项目行为 |
|---|---|---|---|
| −（减） | F1 (131) | `ChangeVolume(false)`（发 Unity volume 模块） | 音量−：`STREAM_MUSIC` 静默调一档 |
| 扫码 | F2 (132) | `ScanQRCode()` → QRcodeActivity | 进 ScanActivity（0.6 起，实测互证） |
| 静音 | F3 (133) | `Silent()` | 媒体音量静音／恢复此前音量，显示“静音”提示 |
| +（加） | F4 (134) | `ChangeVolume(true)` | 音量+：同 F1 反向 |
| 蓝牙/工厂 | F5 (135) | USB 模式进工厂测试；蓝牙配对 3s 组合 | 未接（预留） |

* gpio/耳机口的 `VOLUME_UP/DOWN`（24/25）出厂等价于 +/−（onKeyUp 走 `ChangeVolume`）；USB 键盘 A/B（29/30）与 F4/F1 同分支，应是开发期调试键。launcher 把 VOLUME 两键一并接住自己调流，不放给系统——系统音量面板经投影光路会上下镜像；应用自身显示已做投影补偿的音量条，约 2.4 秒后淡出。
* F3 在音量大于 0 时保存当前档位并静音；再按恢复保存的档位。保存值跨 launcher 重启保留，长按产生的重复按键不会反复切换。
* 按键日志：`adb logcat -s MainActivityMinimum:*`（`volume up/down: x -> y`）。
* 测试用符号名 `adb shell input keyevent KEYCODE_F4`；本机 input 的数字编号是旧表（0.6 已踩过）。

## adb 认证与隐形选择框（两个形似"死机"的坑）

**厂商魔改 adbd：每次重启后 adb shell 要过挑战应答。** 症状：重启后任何 shell 命令只回 `Who are you ? (O_O)??? (<seed>)`（pull/push 等文件通道不受影响）。应答：`adb shell auth:<crc32>`，其中 crc32 = `zlib.crc32("Who are you ? (O_O)??? (<seed>) zhihuichuang")`（ASCII，取无符号 32 位）；成功回 `Welcome home . O(^_^)O~~`。用户侧有自动化脚本 `calc_adbd_auth.py`（探测→计算→提交→复验）。

**隐形 ResolverActivity 陷阱：`adb install -r` 后"卡死"。** 重装 launcher 会清掉默认桌面设置，系统随即弹桌面选择框；该对话框在本机**不渲染任何内容**（uiautomator 只有空 `android:id/content`），却抢走焦点——launcher 最后一帧还在 SurfaceView 的 GL surface 上（盖住对话框），画面定格、触摸/按键全无响应，形似死机。恢复：

```sh
adb shell am start -n com.live2d.demo.minimum/.MainActivityMinimum
```

* 开机场景不受影响：BootReceiver 显式 startActivity 直接拿焦点，实测重启后干净进桌面（2026-09-28）。
* 本机无物理 HOME 键（键表里只有 F1–F5、VOLUME、POWER、LINEFEED），HOME 只会经 adb 触发。
* 与本项目竞争 HOME 的只有出厂 eve（`com.android.launcher` 无 HOME 过滤器）；`android.settings.HOME_SETTINGS` 打开为空页，走不通。

## 舞台灯：`zhcctrl` 守护与它的 socket

机身 18 颗 LED（SN3218 芯片，`/dev/sn3218`）由 init 起的系统守护 `/system/bin/zhcctrl` 驱动；守护不随任何 app 生命周期，出厂 voice 的 `LEDManager` 原本是唯一业务控制方，现由 `LightController`（`/api/light`）以同一协议接管。

| 项 | 实测值 |
|---|---|
| socket | `/dev/socket/zhcctrl`（**文件系统路径**） |
| socket 权限 | `srw-rw-rw- system system`，label `u:object_r:socket_device:s0`；父目录 `/dev/socket` `drwxr-xr-x root root` |
| 守护进程 | pid 随开机变（如 264），root，命令行 `/system/bin/zhcctrl` |
| 取 socket 方式 | 从环境 `ANDROID_SOCKET_zhcctrl` 取 init 传的 fd（`strings` 有 `Failed to get socket from environment`） |
| SELinux | 本机 `getenforce` = **Permissive** |
| 命令字 | `zhc_client_ctrl`（握手）、`LEDInit`、`LEDA`、`LEDB`、`LEDStop` |

### 连接与命名空间（此处曾被逆向文档写错）

`Namespace.RESERVED` 不是"抽象命名空间"，而是 **Android 保留 socket 目录**，即 `/dev/socket/<name>`；`Namespace.FILESYSTEM` 才是按字面路径（相对名会按 cwd 解析）。所以 voice 的 `new LocalSocketAddress("zhcctrl", Namespace.RESERVED)` 与 `Namespace.FILESYSTEM` + `"/dev/socket/zhcctrl"` **等价**。实测三种写法：

| 写法 | 结果 |
|---|---|
| `FILESYSTEM` + `/dev/socket/zhcctrl` | 连通（**本实现采用**） |
| `RESERVED` + `zhcctrl` | 连通（解析成同一路径，即 voice 的写法） |
| `ABSTRACT` + `zhcctrl` | `IOException: Connection refused` —— 守护不建抽象 socket，`/proc/net/unix` 里也没有 `@zhcctrl` |
| `FILESYSTEM` + `zhcctrl`（相对名） | `IOException: No such file or directory` |

判断是否真被守护接手，看 logcat：`E/zhcctrl: accepted ok , create pthread` → `D/zhcctrl: CLIENT Verification Success 15  15`（15 = 握手串长度）→ `D/libLedBreath: led Init ok`。客户端断开时守护打 `[ERROR] failed to read data  read_len 0`（正常，别当故障）。

### 守护行为

* **多客户端**：每接受一个连接就 `create pthread`，故与 voice / 工厂测试 app 并存不互斥，以最后一次写入为准。
* **不回收 fd**：客户端断开后已接受的 fd 不关闭，`/proc/net/unix` 里 `/dev/socket/zhcctrl` 的"已连接（St=03）"条目会累积。因此客户端应**连一次长期持有**，不要反复连。
* 命令是 ASCII 文本、**无分隔符**，守护按已知命令字在流里逐个解析（所以 `zhc_client_ctrl` + `LEDInit` 连着写没问题）。

### LEDA 是逐通道更新（关灯别只关三色）

`LEDA,<灯号>,<亮度>,...` 只更新命令里提到的通道，其余通道保持原值——守护没有“整体状态”，所以**关灯必须显式把所有要关的通道都写 0**。18 路分两组：15 颗暖白“舞台环”（1-9、10/11/13/14/16/17）与 3 颗彩色通道（12=红、15=绿、18=蓝）。

出厂 voice 的 `LEDManager.closeLED()` 只发 `LEDA,12,0,15,0,18,0`，即只关三色通道——暖白环照旧亮着。照抄这个语义会得到“关了灯但还亮着”的 bug（本项目的 `off` 因此改成发 18 路全 0）。

### 硬件版本与亮度档

voice 的 `VoiceApp.checkoutEnv()` 从 `/sys/class/zhc_version/hardware_version` 读版本（缺省 3），舞台灯亮度按 `==2 ? (7,40) : (10,45)` 分两段。本机**没有** `/sys/class/zhc_version/`，故走默认 3 → `(10,45)`；`stage_a=10` 只占 4% 占空，肉眼很弱，要醒目就调高。

### 直连验证用的一次性小工具

设备上没有 `nc`，验证协议可以走 `app_process`：Mac 侧 `javac`（android.jar 做 bootclasspath）+ `d8` 打成 dex → `adb push` → `adb shell "CLASSPATH=/data/local/tmp/x.dex app_process /system/bin <MainClass> ..."`。本次即用此法在改 app 之前先把命名空间与协议确认下来。
