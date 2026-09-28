# Gowild-HE-Launcher

在老旧安卓音箱（la0920 智能音箱 · Android 5.1.1 · armeabi-v7a）上运行的 Live2D / 3D 桌面启动器，
基于 Live2D Cubism Java SDK（Cubism 5-r.1）示例工程扩展，已剥离上游 demo 与模型转换管线，
只保留 launcher 本体。
上游原版说明与变更记录见 [README.upstream.md](README.upstream.md) / [CHANGELOG.upstream.md](CHANGELOG.upstream.md)。

## 它能做什么

- **Live2D 渲染**：Cubism 官方示例模型 + 自有模型（不入库）。
- **l3d —— 设备端 glTF 骨骼动画渲染管线**（自研，Java 手写 glTF 2.0 解析，无第三方 3D 库）：
  蒙皮、形态键表情（sparse accessor）、单动作绑定、一路优化到 60 fps（vsync 上限）。
  模型转换（`.blend` → l3d 包）属于上游转换工程，不在本仓库。
- **HTTP 控制面**（NanoHTTPD :8900，随 app 前台启停，全部端点真机实测）：系统状态 / 模型资产与播放控制 / 摄像头 / 舞台灯 / 语音采集。
- **外设**：18 路 LED 舞台灯（zhcctrl 本地 socket）、摄像头 JPEG 推流（libjpeg-turbo native 通路）、麦克风采集。
- **本地人脸随动**：Live2D 可离线看向摄像头中的人脸，单线程 NEON 检测、CPU 预算自动降频；默认关闭。[开启与性能验证](docs/LOCAL_FACE_TRACKING.md)。

## 构建

Android Studio 打开工程，或命令行（JDK 17+，系统默认 Java 8 时需显式指定）：

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew assembleDebug
```

- 产物：`app/build/outputs/apk/debug/app-debug.apk`
- 包名 `com.live2d.demo.minimum`（沿用旧 Minimum flavor，设备可直接覆盖升级）
- SDK 路径写在本机 `local.properties`（不入库）
- 源码：`app/src/main/java/com/live2d/demo/minimum/` 为 launcher 本体（含 `control/` 控制面与 `l3d/` 3D 渲染），`com/live2d/demo/` 下为与上游共用的 `LAppDefine` / `TouchManager`
- Live2D 官方示例模型不入库：首次构建前按 [app/src/main/assets/MODELS_NOT_INCLUDED.md](app/src/main/assets/MODELS_NOT_INCLUDED.md) 从上游仓库放置到 `app/src/main/assets/`

## 使用

### 部署

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- 应用注册为 **HOME 桌面**：安装后在系统桌面选择器里选一次即成为默认桌面，开机自启（BootReceiver）拉起。
- 首次配网可在应用内扫码（ScanActivity，WiFi 二维码）。

### 控制面速览

服务随 app 前台启停，端口 `8900`：

```sh
# 开发（USB）：adb forward tcp:8900 tcp:8900 后走本机回环
BASE=http://127.0.0.1:8900     # 生产（局域网）改为设备 IP

curl $BASE/api/status                      # 系统状态
curl $BASE/api/models                      # 模型清单（Live2D / l3d）
curl -X POST $BASE/api/models/21miku/select           # 模型上屏
curl -X POST --data-binary @eve_559010221.glb \
     "$BASE/api/models/miku_eve/animations?name=eve_559010221"   # l3d 追加动作
curl -X POST $BASE/api/control/animation -d '{"name":"eve_117010202","loop":true}'
curl "$BASE/api/camera/frame" > snap.jpg   # 摄像头单帧；/api/camera/stream 为 MJPEG 流
curl -X POST $BASE/api/light -d '{"mode":"off"}'      # 18 路舞台灯
```

Live2D 侧另有姿态 / 身体动作 / 表情 / 参数直控 / 口型 / 视线随动 / 待机策略等端点，
语音采集提供 PCM 实时流（ASR 接入口）。**完整参考（含鉴权、坐标系、异步语义）：[docs/API.md](docs/API.md)**。

## 文档

| 文档 | 内容 |
|---|---|
| [docs/API.md](docs/API.md) | 设备端 HTTP 控制面完整参考（v0.7） |
| [docs/DEVICE.md](docs/DEVICE.md) | 目标设备情况 |
| [CHANGELOG.md](CHANGELOG.md) | 本项目版本记录 |
| [README.upstream.md](README.upstream.md) | 上游 Cubism SDK 示例说明 |

## 目录

```
├─ Core/          Live2D Cubism Core（Live2DCubismCore.aar，按 Core/RedistributableFiles.txt 再分发）
├─ Framework/     Cubism Java Framework
├─ app/src/main/  launcher 本体（java / assets / res / jni / jniLibs）
├─ tools/         构建与测试工具（native 库构建 build-native.sh、灯光测试 light_rainbow.py、nv21jpeg 单测）
└─ docs/          API 与设备文档
```

## 许可与版权

本项目包含 Live2D Cubism Components，受其多层许可约束（全文见 [LICENSE.md](LICENSE.md)，中文版链接同文件内）：

| 组成部分 | 许可协议 | 本仓库的处理 |
|---|---|---|
| 本项目 Java 源码（自上游 Cubism Java Samples 修改，含 `app/` 与 `Framework/`） | [Live2D Open Software License](https://www.live2d.com/eula/live2d-open-software-license-agreement_cn.html) | 源文件头部的 Live2D Inc. 版权与许可声明**原样保留**，修改衍生同样受该协议约束 |
| Live2D Cubism Core（`Core/android/Live2DCubismCore.aar`） | [Live2D Proprietary Software License](https://www.live2d.com/eula/live2d-proprietary-software-license-agreement_cn.html) | 按 [Core/RedistributableFiles.txt](Core/RedistributableFiles.txt) 允许的范围随应用再分发 |
| Live2D 官方示例模型（Hiyori、Natori、Mao、Rice、Haru、Mark、Wanko） | [Live2D Free Material License](https://www.live2d.com/eula/live2d-free-material-license-agreement_cn.html)，且每个模型另有各自条款（[清单](https://docs.live2d.com/cubism-editor-manual/sample-model/)） | **不入库**：模型不入 git，仅本地放置用于开发；对外分发含模型的 APK 前须自行确认各模型条款 |

**商用注意**：年营业额 1000 万日元以上的企业使用 Cubism SDK 发布产品，须另行取得 [Cubism SDK Release License](https://www.live2d.com/zh-CHS/download/cubism-sdk/release-license/)。

其余部分：仓库不含自有转换模型、厂商 APK 与构建产物；`apks/`（本地构建输出）已 gitignore。
