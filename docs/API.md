# live2d_luncher 设备端控制面 API

| | |
|---|---|
| **文档版本** | 0.4 |
| **更新日期** | 2026-09-09 |
| **服务端口** | `8900`（HTTP，设备端常驻，随 app 前台启停） |
| **实测环境** | la0920 智能音箱 · Android 5.1.1 (API 22) · armeabi-v7a |
| **实现** | NanoHTTPD 2.3.1，`Sample/src/minimum/java/com.live2d.demo.minimum/control/` |

本文档是 `live2d_luncher` 设备端 HTTP 控制面的完整参考。所有端点均在真机实测通过。

---

## 目录

1. [概述](#1-概述)
2. [接入](#2-接入)
3. [通用约定](#3-通用约定)
4. [系统状态 `/api/status`](#4-系统状态)
5. [模型资产 `/api/models`](#5-模型资产)
6. [运行时控制 `/api/control/*`](#6-运行时控制)
7. [摄像头 `/api/camera`](#7-摄像头)
8. [语音采集 `/api/voice/*`](#8-语音采集-apivoice)
9. [端到端编排示例](#9-端到端编排示例)
10. [运维注意事项](#10-运维注意事项)
11. [已知限制](#11-已知限制)
12. [路线图](#12-路线图)
- [附录 A：错误码速查](#附录-a错误码速查)

---

## 1. 概述

设备上运行一个单 Activity 应用：GLSurfaceView 全屏渲染 Live2D 模型（设备投影光路上下颠倒、纯黑背景已在应用内补偿），同时 NanoHTTPD 在 `:8900` 提供 JSON 控制面与 MJPEG 视频流。Mac 侧（语音网关/编排器）通过该控制面驱动桌宠的一切表现。

线程模型：HTTP 连接由 NanoHTTPD 每连接一线程处理；涉及渲染的写操作（换模型、播动作等）投递到 GL 线程命令队列，在帧边界执行——**HTTP 线程从不直接触碰 Live2D 对象**，因此渲染不会因控制请求卡顿。

能力总览：

| 资源组 | 路由前缀 | 能力 |
|---|---|---|
| 系统状态 | `/api/status` | app 版本、当前模型、实测 FPS、运行时长、摄像头摘要 |
| 模型资产 | `/api/models` | 查询 / 上传（zip）/ 选择上屏 / 删除，内置+外置双源 |
| 运行时控制 | `/api/control/*` | 姿态、身体动作、面部表情、参数直控、口型、视线随动、待机策略 |
| 摄像头 | `/api/camera` | 开关控制、MJPEG 实时流、单帧快照 |

## 2. 接入

### 2.1 传输

```bash
# 开发（USB）：adb 端口转发后走本机回环
adb forward tcp:8900 tcp:8900
BASE=http://127.0.0.1:8900

# 生产（局域网）：直接访问设备 IP
BASE=http://<设备IP>:8900
```

### 2.2 鉴权

当前版本**无鉴权**，局域网内明文开放。务必只在与设备同网段的可信环境使用；token 鉴权在路线图中（见 §11）。

### 2.3 前置条件

- 设备屏幕必须点亮：**息屏触发 `onStop`，HTTP 服务随之停止**。调试前先 `adb shell input keyevent KEYCODE_WAKEUP`。
- 厂商魔改 adbd 的挑战认证过期时（shell 报 `Who are you ? (O_O)???`），重跑 `calc_adbd_auth.py` 后**必须重建 `adb forward`**（见 §9）。

## 3. 通用约定

### 3.1 请求与响应格式

- 除模型上传（raw zip 二进制）外，请求与响应体均为 `application/json`，UTF-8。
- **调用成败以 HTTP 状态码为准**。错误体统一为 `{"ok":false,"error":"原因"}`；部分 2xx 响应附带 `"ok":true`（历史兼容，勿依赖其存在）。
- 未知字段忽略；缺省字段取默认值（各端点注明）。

### 3.2 状态码语义

| 状态码 | 含义 |
|---|---|
| `200` | 同步完成 |
| `201` | 资源已创建（模型上传） |
| `202` | 已受理，GL 线程异步执行中（换模型/播动作），毫秒级内生效 |
| `400` | 请求体不合法（JSON 解析失败、缺必填字段、zip 校验失败等） |
| `403` | 禁止操作（如删除内置模型） |
| `404` | 资源不存在（模型名/动作组名/路由） |
| `405` | 方法不支持，响应带 `Allow` 头 |
| `409` | 冲突（同名模型已存在 / 删除正在上屏的模型） |
| `503` | 暂不可用（渲染器未就绪 / 无已加载模型 / 摄像头开启失败） |

### 3.3 异步执行模型（202）

`202` 表示命令已入队、将由 GL 线程在下一帧边界执行。对调用方的语义：

- 命令**最终会执行**（除非随后发生换模型等状态重置），无需轮询确认；
- 需要"执行完成"通知的场景（如换模型完成），目前以轮询 `GET /api/status` 的 `model` 字段变化为准。

### 3.4 坐标系

- 凡涉及 `y` 的字段（pose / lookat）：**`y+` 为实体屏向上**。设备投影光路上下颠倒已在应用内镜像补偿，调用方按正常直觉传值即可。
- `lookat` 的 x/y 为归一化坐标 `-1..1`；`pose` 的 x/y 为逻辑坐标位移 `±2`。
- 注意：`adb screencap` 截图所见为 framebuffer 原始方向（上下颠倒），与实体屏相反；验证画面以实体屏为准（见 §9）。

## 4. 系统状态

### `GET /api/status`

服务与渲染器健康摘要。探活、换模型完成轮询均用它。

```json
{
  "app": "live2d_luncher",
  "version": "0.1",
  "model": "21miku",
  "fps": 23.41,
  "uptime_s": 5,
  "camera": { "available": true, "on": false }
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `app` | string | 固定 `live2d_luncher` |
| `version` | string | app 版本号 |
| `model` | string | 当前上屏模型名；未加载时缺省 |
| `last_error` | string | 仅在模型加载失败等异常时出现 |
| `fps` | number | 实测渲染帧率（1s 滑动窗口） |
| `uptime_s` | number | 进程运行秒数 |
| `camera` | object | 摄像头摘要（详见 §7） |

## 5. 模型资产

模型仓库双源：`builtin`（APK assets，只读）与 `external`（`/sdcard/live2d/models/`，可传可删）。上屏模型由 `selected` 持久化项决定，app 启动自动加载。

### 5.1 `GET /api/models` — 列出全部模型

```json
{
  "models": [
    {
      "name": "21miku",
      "source": "external",
      "moc3_version": "3.3 (0x02)",
      "moc3_bytes": 1055488,
      "motion_groups": 427,
      "physics": true,
      "textures": 1,
      "loaded": true,
      "selected": true
    }
  ]
}
```

| 字段 | 说明 |
|---|---|
| `source` | `builtin` / `external` |
| `moc3_version` | 服务端解析 moc3 文件头得出的格式版本（3.0 / 3.3 / 4.0 / 4.2 / 5.0） |
| `motion_groups` | model3.json 声明的动作组数（即 §6.2/6.3 `groups` 列表的长度上限） |
| `loaded` | 当前已上屏 |
| `selected` | 持久化选中项（重启自动加载） |

### 5.2 `POST /api/models` — 上传模型

```bash
curl -X POST --data-binary @model.zip "$BASE/api/models?name=21miku"
```

- **Body 为模型 zip 整包**（`model3.json` + `moc3` + 贴图 + `motions/`，目录结构随意，服务端自动定位 model3.json），`Content-Type` 不限。
- `?name=`：可选。缺省取 model3.json 文件名主干；合法字符 `[A-Za-z0-9._-]`，≤64 字节。
- 服务端校验：zip 内必须存在 `*.model3.json`；moc3 魔数 `MOC3` 且版本字节 ∈ 1..5；上限 200MB；已做 Zip Slip 防护。

| 状态码 | 场景 |
|---|---|
| `201` | 上传成功，返回 descriptor |
| `400` | zip 不合法 / moc3 校验失败 / 超限 |
| `409` | 同名模型已存在（先 DELETE 再传） |

### 5.3 `GET /api/models/{name}` — 查询单个

`200` + descriptor；`404` 不存在。

### 5.4 `DELETE /api/models/{name}` — 删除

`200` + `{"deleted":"name"}`。

| 状态码 | 场景 |
|---|---|
| `403` | 内置模型不可删 |
| `404` | 不存在 |
| `409` | 该模型正在上屏（先 select 其他模型再删） |

### 5.5 `POST /api/models/{name}/select` — 加载上屏

```bash
curl -X POST "$BASE/api/models/21miku/select"
# 202 {"ok":true,"loading":"21miku"}
```

- **异步**（202）：GL 线程解析 moc3 + 上传贴图，实测 **2~2.5s** 完成上屏；期间画面停留在旧模型最后一帧，无黑屏。以 `/api/status` 的 `model` 变化为准。
- 选中项**持久化**：重启 app 自动加载。
- 切换语义：**姿态（pose）保留**；动作 / 表情 / 参数直控 / 口型等临场状态清空（新模型不继承）；待机策略回到默认 `on`（见 §10）。

| 状态码 | 场景 |
|---|---|
| `202` | 已受理 |
| `400` | 名字不合法 |
| `404` | 模型不存在 |
| `503` | 渲染器未就绪（app 刚启动数秒内，重试即可） |

## 6. 运行时控制

> 前置：渲染器就绪且有已加载模型，否则所有 control 端点返回 `503`（渲染器未就绪时 `error` 为 `renderer not ready`，稍后重试）。

参数生效优先级（高 → 低）：

```
参数直控 / 口型（每帧覆盖） > 物理 / 呼吸 > 面部表情层 > 身体动作层 > 待机
```

### 6.1 姿态 — `GET|PUT /api/control/pose`

模型位置与缩放。**支持部分字段更新**（只传要改的字段），**写入即持久化**（重启、切模型均保留）。

```bash
curl "$BASE/api/control/pose"
# {"x":0.0,"y":0.12,"zoom":1.35}
curl -X PUT -d '{"zoom":1.35,"y":0.12}' "$BASE/api/control/pose"
```

| 字段 | 范围 | 说明 |
|---|---|---|
| `x` | ±2 | 逻辑坐标水平位移 |
| `y` | ±2 | 垂直位移，**y+ 实体屏向上** |
| `zoom` | 0.2~5 | 绕屏幕中心缩放 |

超范围值会被夹紧（clamp）后生效。`POST` 等价于 `PUT`。

### 6.2 身体动作 — `GET|POST /api/control/motion`

```bash
curl "$BASE/api/control/motion"
# {"groups":["w-special15-hug", "..."]}      （21miku 共 427 组）
curl -X POST -d '{"group":"w-special15-hug","priority":3}' "$BASE/api/control/motion"
```

| 参数 | 默认 | 说明 |
|---|---|---|
| `group` | 必填 | 动作组名，来自 `groups` 列表 |
| `priority` | `3` | `1` 待机级（可被 2/3 打断）；`2` 普通；`3` 强制 |

- `202` 受理；`404` 组不存在；`400` JSON 不合法。
- 动作为一次性，播完自动回落待机态。身体层与面部表情层相互独立。

### 6.3 面部表情（叠加层）— `GET|POST /api/control/expression`

```bash
curl -X POST -d '{"group":"face_smile_01"}' "$BASE/api/control/expression"  # 叠加表情
curl -X POST -d '{"clear":true}'            "$BASE/api/control/expression"  # 清除
```

- 面部层独立于身体层：表情键入的面部参数（嘴/眉/眼）**覆盖身体层与眨眼**，未键入的参数不受影响。可与身体动作并行。
- 在 21miku 上，表情即 `face_*` 动作组（151 个）。
- `202` 受理（`clear` 时响应带 `"cleared":true`）；`404` 组不存在。

### 6.4 参数直控 — `GET|POST /api/control/param`

对任意 Live2D 参数按 TTL 逐帧覆盖，是粒度最细的控制通道。

```bash
# 单参数：TTL 内每帧覆盖 ParamAngleZ = -25，1500ms 后自动回落
curl -X POST -d '{"id":"ParamAngleZ","value":-25,"ttl_ms":1500}' "$BASE/api/control/param"

# 批量
curl -X POST -d '{"params":[{"id":"ParamAngleX","value":10,"ttl_ms":800},
                            {"id":"ParamBodyAngleZ","value":5,"ttl_ms":800}]}' "$BASE/api/control/param"

# 清空全部覆盖
curl -X POST -d '{"clear":true}' "$BASE/api/control/param"

# 查询生效中的覆盖数量
curl "$BASE/api/control/param"   # {"active":2}
```

| 字段 | 默认 | 说明 |
|---|---|---|
| `id` | 必填 | 参数 ID（如 `ParamAngleX`） |
| `value` | 必填 | 目标值 |
| `ttl_ms` | 500 | 有效期 0~60000ms，超限夹紧 |

- TTL 内**每帧覆盖**（优先级最高，见本节开头优先级链），过期自动回落，无需恢复指令；需要持续压住就按节奏续推。
- `200` + `{"ok":true,"applied":N}`；`400` 缺 id / JSON 不合法。

### 6.5 口型 — `GET|POST /api/control/lipsync`

```bash
curl -X POST -d '{"level":0.95}' "$BASE/api/control/lipsync"   # level ∈ 0..1
curl "$BASE/api/control/lipsync"
# {"level":0.0,"auto_close_ms":150.0,"weights":{"open_y":1.0,"scale_y":0.8}}
```

- 内部双通道混合：`ParamMouthOpenY` ×1.0 + `ParamMouthScaleY` ×0.8（权重暂为固定值，可调化在路线图）。
- **停推 150ms 自动闭合**：调用方按 ~30Hz 持续推 `level`，断流自动闭嘴，无需显式关闭。
- `200`；`400` 缺 `level`。

### 6.6 视线/头随动 — `GET|POST /api/control/lookat`

```bash
curl -X POST -d '{"x":-0.7,"y":0.4}' "$BASE/api/control/lookat"
curl -X POST -d '{"reset":true}'     "$BASE/api/control/lookat"
```

| 字段 | 说明 |
|---|---|
| `x`, `y` | 归一化 `-1..1`，**y+ 实体屏向上**；x 自动按画面纵横比映射 |
| `reset` | `true` 时归零（回正视线） |

- 驱动眼神与头部朝向（内置缓动，近似触摸拖拽效果）；目标持续生效直到 reset 或新目标。
- `200` + 当前 `{ok,x,y}`；`400` 既无 x/y 也无 reset。

### 6.7 待机策略 — `GET|POST /api/control/idle`

```bash
curl "$BASE/api/control/idle"                               # {"mode":"on","interval_s":15.0}
curl -X POST -d '{"mode":"off"}'               "$BASE/api/control/idle"
curl -X POST -d '{"mode":"on","interval_s":30}' "$BASE/api/control/idle"
```

| 字段 | 说明 |
|---|---|
| `mode` | `on` / `off`（或 `enabled`: bool） |
| `interval_s` | 插播间隔，3~300s，超限夹紧 |

- `on`：模型自带 Idle 组则循环播（官方模型行为）；否则从轻量动作池（名称含 `tilthead`/`nod` 的 `w-*`）每 `interval_s` 随机插播一个。
- `off`：不自动播动作，仅呼吸 + 眨眼 + 物理残留。
- ⚠️ 当前为**会话级状态**：切模型或重启 app 回到默认 `on`（持久化见路线图 `/api/config`）。
- `200`；`400` mode 非法。

## 7. 摄像头

设备后置摄像头，640×480，MJPEG 输出。与 Live2D 渲染并行服务，互不影响（实测模型 FPS 无变化）。

**生命周期**（省电 + 隐私设计）：

```
POST {"on":true}            ──显式开启──▶ 常开，直到 POST {"on":false}
GET  /stream | /frame       ──隐式开启──▶ 无人取帧持续 10s 后看门狗自动关机
POST {"on":false}           ──显式关闭──▶ 立即关机，进行中的流收到 EOF（不会复活摄像头）
```

### 7.1 `GET /api/camera` — 状态

```json
{
  "available": true,
  "on": true,
  "explicit": true,
  "clients": 0,
  "width": 640,
  "height": 480,
  "fps": 10,
  "quality": 60,
  "frame_seq": 1268
}
```

| 字段 | 说明 |
|---|---|
| `available` | 设备是否有摄像头（无则其余操作均 `503`） |
| `on` | 当前是否预览出帧 |
| `explicit` | 是否处于显式开启状态 |
| `clients` | 活跃流连接数 |
| `width`/`height` | 实际协商出的预览分辨率 |
| `fps` | 编码限频目标（10）；实际帧率受 HAL 供帧限制（本机实测 ~6–7） |
| `quality` | JPEG 编码质量 |
| `frame_seq` | 累计出帧数（单调递增，可用于丢帧检测） |
| `last_error` | 仅在开启失败等异常时出现 |

### 7.2 `POST /api/camera` — 开关与画质

```bash
curl -X POST -d '{"on":true}'               "$BASE/api/camera"
curl -X POST -d '{"on":true,"quality":75}'  "$BASE/api/camera"
curl -X POST -d '{"on":false}'              "$BASE/api/camera"
```

| 字段 | 默认 | 说明 |
|---|---|---|
| `on`（或 `enabled`） | 保持不变 | 显式开/关 |
| `quality` | 60 | JPEG 质量 30~95，超限夹紧，可单独设置 |

- `200` + 最新状态；`400` JSON 不合法；`503` 开机失败（无摄像头 / HAL 异常，看 `last_error`）。
- ⚠️ **开机渲染预热闸门**：进程刚启动、GL 上下文与模型首批贴图未就绪时，开启请求会先等渲染预热（最长 4s）再真正打开摄像头；等待超时则返回 `503` + `renderer warming up, retry later`。这是硬约束——预热期间开摄像头会让老 HAL 与首批 GL 对象创建抢驱动，整批贴图静默丢失（模型变黑剪影 + 贴图碎片，该进程内不自愈）。表现为开机后第一次请求比平时慢几秒，属正常。
- ⚠️ 显式开启时即使无人取帧也持续编码（耗电），用完请显式关闭。

### 7.3 `GET /api/camera/stream` — MJPEG 实时流

```bash
curl -s -m 5 -o stream.mjpeg "$BASE/api/camera/stream"   # 抓 5 秒流
# 或浏览器直接打开 URL（<img src> 亦可），实时预览
```

- 响应为 `multipart/x-mixed-replace; boundary=frame`，每帧记录含 `Content-Type: image/jpeg` 与 `Content-Length`，浏览器原生支持。
- **隐式开启**：若摄像头未开，服务端自动开机并等待首帧（预热 ~1s 内），对调用方透明。
- 实测吞吐：640×480 q60 ≈ **6–7fps / 60 KB·s⁻¹**（USB 与 Wi-Fi 均可流畅；限频目标 10fps，因源帧率与目标不成整数倍，实际落在隔帧编码）。
- 流结束条件（服务端 EOF）：客户端断开（正常）；摄像头被显式关闭；连续 15s 无新帧（客户端应重连）。EOF 后客户端重连即恢复（隐式重开）。
- `503`：无摄像头 / 开机失败 / 预热超时（2.5s）。

### 7.4 `GET /api/camera/frame` — 单帧快照

```bash
curl -s -o snap.jpg "$BASE/api/camera/frame"   # 200 → image/jpeg
```

- 隐式开启（预热中返回 `503` + `camera warming up`，稍后重试即可）。
- `200` JPEG / `503` 无摄像头或未出帧。

> ⚠️ 画面为传感器原生方向（HAL `orientation=180`），服务端不做旋转/镜像；需要正向画面由 Mac 侧处理。无音频轨。

## 8. 语音采集 `/api/voice/*`

麦克风原始音频上行，供 Mac 侧 ASR。路由形如 `/api/voice/{name}/*`，`{name}` 为**采集源名**（当前仅 `mic`，后续新源在此扩展）。输出 **PCM16LE 单声道**，默认 16 kHz，8k~48k 可调。与 Live2D 渲染、摄像头并行互不影响。

**生命周期**（与摄像头同款设计）：

```
POST /mic/start 或 {"on":true}  ──显式开启──▶ 常开，直到 stop / {"on":false}
GET  /mic/stream                ──隐式开启──▶ 无人读取持续 10s 后看门狗自动关
stop / {"on":false}             ──显式关闭──▶ 立即停，进行中的流收到 EOF（重连即隐式重开）
```

- 采集源为 `VOICE_RECOGNITION`（无 AGC，原始电平，适合 ASR 特征）。
- `rate` 变更会重开 AudioRecord，瞬时可能掉 ~100ms 数据。
- ⚠️ 采集与扬声器播放同时进行时会拾到本机外放声（无回声消除），网关侧注意时序错开。

### 8.1 `GET /api/voice/mic` — 状态

```json
{
  "available": true,
  "on": false,
  "explicit": false,
  "clients": 0,
  "rate": 16000,
  "format": "pcm16le mono",
  "seq": 0,
  "drops": 0
}
```

| 字段 | 说明 |
|---|---|
| `available` | 设备是否有麦克风 |
| `on` / `explicit` | 采集中 / 是否显式开启 |
| `clients` | 活跃流连接数 |
| `rate` | 当前采样率 |
| `seq` | 累计采集块数（40ms/块，单调递增） |
| `drops` | 队列满被丢弃的最旧块计数（**有读者时稳态为 0**；显式开启但无人读会持续增长，属预期） |

### 8.2 `POST /api/voice/mic` — 开关与采样率

```bash
curl -X POST -d '{"on":true,"rate":16000}' "$BASE/api/voice/mic"
curl -X POST -d '{"on":false}'             "$BASE/api/voice/mic"
```

| 字段 | 默认 | 说明 |
|---|---|---|
| `on`（或 `enabled`） | 保持不变 | 显式开/关 |
| `rate` | 16000 | 采样率 8000~48000，超限夹紧；变更即重开采集 |

- `200` + 最新状态；`400` JSON 不合法；`503` 开启失败。

### 8.3 `POST /api/voice/mic/start` · `POST /api/voice/mic/stop`

等价于 `{"on":true}` / `{"on":false}` 的快捷形式。`start` 可带 `{"rate":16000}`（空 body 合法，沿用当前速率）。

### 8.4 `GET /api/voice/mic/stream` — PCM 实时流（ASR 接入口）

```bash
curl -s "$BASE/api/voice/mic/stream" > asr.pcm     # 持续读取，Ctrl-C 停止
# 或 Python: requests.get(url, stream=True) 逐块喂 ASR
```

- 响应为 chunked `application/octet-stream`，**裸 PCM16LE 单声道**（无任何封装帧），按 40ms 块到达。
- **隐式开启**：未开会话时自动开启（即开即用）；客户端断开后无人读取 10s 自动关机。
- EOF 条件：麦克风被显式关闭；连续 5s 无数据。**EOF 后重连即恢复**（隐式重开），建议客户端带重连循环。
- 消费不过来时丢**最旧**块保新块（`drops` 计数）；正常读取速率下实测稳态零丢块（4s 抓取 121,600 字节 @16 kHz，drops 无增长）。
- `503`：无麦克风 / 开启失败。

## 9. 端到端编排示例

Mac 侧让模型"打招呼说话"的推荐时序：

```
1. POST /api/control/motion      {"group":"w-normal15-greeting"}   # 起手动作（身体层）
2. POST /api/control/expression  {"group":"face_smile_01"}         # 配合表情（面部层，可并行）
3. 以 30Hz 循环:
   POST /api/control/lipsync {"level": <由 TTS 音频实时计算的包络>}   # 口型随声动
   （停止推 150ms 后自动闭嘴；/api/say 上线后此步由设备端接管）
4. 动作播完自动回落；POST /api/control/expression {"clear":true}     # 收尾
```

摄像头注视编排（打开摄像头看用户 + 模型看向用户方向）：

```
1. GET  /api/camera/frame                       # 隐式开机 + 取帧（Mac 侧做人脸方位估计）
2. POST /api/control/lookat {"x":..,"y":..}     # 模型看向估计方位
```

语音听写编排（设备拾音 → Mac ASR；说话回应时先停拾音防自拾）：

```
1. GET  /api/voice/mic/stream                   # 持续读 PCM 喂 ASR（断线自动重连）
2.（ASR 出结果 → 网关处理 → TTS）
3. TTS 播放期间暂停读 /api/voice/mic/stream      # 防拾到本机外放
```

## 10. 运维注意事项

| 项 | 说明 |
|---|---|
| 厂商 adbd 认证 | shell 出现 `Who are you ? (O_O)??? (seed)` 挑战时，重跑 `python3 calc_adbd_auth.py`（CRC32 应答自动提交）；**之后必须重建 `adb forward tcp:8900 tcp:8900`**——认证过期为常见"HTTP 000"故障根因 |
| 截图方向 | `adb screencap` 所见为 framebuffer 原始方向（上下颠倒）；实体屏方向相反。验证画面以实体屏为准 |
| 息屏 | 触发 `onStop` → HTTP 服务停止。调试前 `adb shell input keyevent KEYCODE_WAKEUP`；桌面化（HOME + KEEP_SCREEN_ON）后根治 |
| 性能基线 | 21miku ≈ 22-23 FPS，Hiyori ≈ 36 FPS（老 SoC）；摄像头开启对模型 FPS 无影响 |
| 摄像头画面暗 | 摄像头无补光，夜间画面很暗属正常（无夜视模式） |

## 11. 已知限制

| 项 | 现状 |
|---|---|
| 鉴权 | 无（局域网明文） |
| 待机策略持久化 | 会话级，切模型 / 重启回默认 `on` |
| 口型权重 | 硬编码（open_y=1.0 / scale_y=0.8），不可调 |
| 呼吸 / 眨眼开关 | 未提供（常开） |
| 摄像头方向 / 音频 | 传感器原生方向未旋转；无音频轨 |
| 响应包裹 | 部分成功响应缺 `"ok":true`（以 HTTP 状态码为准） |

## 12. 路线图

| 端点 | 内容 |
|---|---|
| `POST /api/voice/play*` | 语音播放流：Mac 分块推 TTS 音频（pcm16le），设备端 AudioTrack 播放；播放侧算 RMS 自动喂口型（替代 30Hz 包络下发，天然同步） |
| `GET/PUT /api/config` | 口型权重、待机策略持久化、呼吸/眨眼开关、鉴权 token |
| `POST /api/costume` | 换装（同布局贴图变体热切换 / Parts 开关，视模型资源而定） |
| 事件上行 | 实体屏点击命中区域 → 通知 Mac 网关（WebSocket / 长轮询） |

---

## 附录 A：错误码速查

| 码 | 典型 `error` 文案 | 处置 |
|---|---|---|
| 400 | `invalid JSON body` / `missing level (0..1)` / `missing param id` | 修正请求体 |
| 403 | 内置模型删除 | 不可操作 |
| 404 | `model not found: X` / `motion group not found: X` / `no route: X` | 核对名称与路由 |
| 405 | （带 `Allow` 头） | 换用允许的方法 |
| 409 | `model already exists` / `model is loaded, select another first` | 先删后传 / 先切走再删 |
| 503 | `renderer not ready, retry later` / `no model loaded` / `camera open failed` / `no camera on device` / `camera warming up` / `microphone start failed` | 稍后重试或检查设备能力 |
