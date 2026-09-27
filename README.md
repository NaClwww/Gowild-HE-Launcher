# live2d_luncher

在老旧安卓音箱（la0920 智能音箱 · Android 5.1.1 · armeabi-v7a）上运行的 Live2D / 3D 启动器，
基于 Live2D Cubism Java SDK（Cubism 5-r.1）示例工程扩展。
上游原版说明与变更记录见 [README.upstream.md](README.upstream.md) / [CHANGELOG.upstream.md](CHANGELOG.upstream.md)。

## 它能做什么

- **Live2D 渲染**：Cubism 官方示例模型 + 自有模型（不入库）。
- **l3d —— 设备端 glTF 骨骼动画渲染管线**（自研，Java 手写 glTF 2.0 解析，无第三方 3D 库）：
  - Blender 无头导出：`.blend` → l3d 包（1 模型 + n 动作），见 `tools/blend_export_worker.py` / `tools/blend_to_model3d.py`；
  - 蒙皮、形态键表情（sparse accessor）、单动作绑定；
  - 一路优化：渲染 14 → 60 fps（vsync 上限），单动作文件 3.67 → 2.14 MB。
- **HTTP 控制面**（NanoHTTPD :8900，随 app 前台启停，全部端点真机实测）：系统状态 / 模型资产与播放控制 / 摄像头 / 舞台灯 / 语音采集，完整参考 [docs/API.md](docs/API.md)。
- **外设**：18 路 LED 舞台灯（zhcctrl 本地 socket）、摄像头 JPEG 推流（libjpeg-turbo native 通路）、麦克风采集。

## 文档

| 文档 | 内容 |
|---|---|
| [docs/API.md](docs/API.md) | 设备端 HTTP 控制面完整参考（v0.7） |
| [docs/DEVICE.md](docs/DEVICE.md) | 目标设备情况 |
| [CHANGELOG.md](CHANGELOG.md) | 本项目版本记录 |
| [README.upstream.md](README.upstream.md) | 上游 Cubism SDK 示例说明 |

## 构建

Android Studio 打开工程，或：

```sh
./gradlew assembleMinimumDebug
```

SDK 路径写在本机 `local.properties`（不入库）。应用源码在 `Sample/src/`：
`full` / `main`（共享代码 + 官方示例模型 assets）/ `minimum`（实际部署到设备的精简版，控制面与 l3d 都在这里）。

## 目录

```
├─ Core/          Live2D Cubism Core（Live2DCubismCore.aar，按 Core/RedistributableFiles.txt 再分发）
├─ Framework/     Cubism Java Framework
├─ Sample/src/    应用（full / main / minimum）
├─ tools/         Blender 无头导出与 glb 后处理脚本（Python）
└─ docs/          API 与设备文档
```

## 许可与素材

- 本仓库基于 [Live2D Cubism Java Samples](https://github.com/Live2D/CubismJavaSamples) 修改，遵循其 [LICENSE.md](LICENSE.md)（Live2D Proprietary Software License Agreement）。
- `Sample/src/main/assets` 内的示例模型（Hiyori、Natori、Mao、Rice、Haru、Mark、Wanko）为 Live2D 官方素材，按 Live2D 素材授权条款使用。
- 仓库不含自有转换模型、厂商 APK 与构建产物；`apks/`（本地构建输出）已 gitignore。
