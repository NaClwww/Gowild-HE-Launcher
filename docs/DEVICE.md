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
