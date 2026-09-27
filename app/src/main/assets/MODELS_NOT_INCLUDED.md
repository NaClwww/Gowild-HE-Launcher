# 示例模型未随仓库分发

`app/src/main/assets/` 下的 Live2D 官方示例模型目录（`Hiyori` `Mao` `Mark` `Natori` `Rice`
`Haru` `Wanko`）**不在本仓库内**——它们是 Live2D 的素材，按其授权条款不在此再分发。

如需本地跑通 Live2D 示例渲染：

1. 从上游 [CubismJavaSamples](https://github.com/Live2D/CubismJavaSamples) 下载
   `app/src/main/assets/` 里的同名目录，原样放进本目录（已在 `.gitignore` 覆盖，不会被提交）；
2. `minimum` 变体默认加载 `Hiyori`，放回后即可渲染。

不放模型也不影响：l3d（glTF）渲染管线、模型管理 API（运行时从设备侧加载模型）与控制面
均不依赖这些目录。
