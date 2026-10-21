# FULiveUniDemo iOS 本地打包

iOS 使用 `FURenderKit.framework`，桥接源码在 `scripts/build-ios-framework/src/ios/`。官方 framework 包含渲染引擎和 bundle 资源，桥接 framework 由本机编译。

## 准备

- macOS、Xcode、Node.js。
- 与本项目 DCloud npm 依赖版本匹配的 iOS 离线 SDK 和 HBuilder 宿主。
- `nativeplugins/FaceUnity-Nama/ios/FURenderKit.framework/`，包含其 bundle 资源。
- 自己的 `authpack.h`，放入 `scripts/build-ios-framework/src/ios/auth/`。
- 真机和匹配 Bundle ID 的 Apple Developer 签名、FaceUnity 授权。

授权文件和编译后含授权的 `FaceUnityNama.framework` 均只保留在本机。默认编译 arm64 真机版本，不包含模拟器版本。

## 编译

在项目根目录执行：

```bash
npm install
npm run type-check
npm run build:ios-framework
npm run build:custom -- app-plus
```

桥接产物为 `nativeplugins/FaceUnity-Nama/ios/FaceUnityNama.framework/`，前端产物在 `dist/build/app/`（以编译器输出路径为准）。

编译默认使用仓库中的 UniApp 桩头文件。需要使用离线 SDK 的真实头文件时，通过 `UNIAPP_IOS_INC` 指向包含 `DCUniModule.h` 的目录。

## 宿主集成

1. 在本机离线 HBuilder 工程中配置与授权匹配的 Bundle ID 和签名。
2. 同时加入并嵌入 `FaceUnityNama.framework`、`FURenderKit.framework`，保留官方 framework 的全部 bundle 资源。
3. 按 `nativeplugins/FaceUnity-Nama/package.json` 链接系统 frameworks，并注册 `NamaModule` 原生插件。
4. 将生成的前端资源复制到宿主的 `apps/<appid>/www/`，appid 与项目 `src/manifest.json` 保持一致。
5. 在 Xcode 中选择连接的 iPhone，构建并安装。使用 CocoaPods 本地引用时，修改 framework 声明后重新执行 `pod install`。

只改页面时重新生成并同步前端资源；修改 Objective-C 或授权文件后重新编译桥接并重建宿主。

## 检查

- 快速拖动滑杆、切换美颜项目后，参数和界面状态一致。
- 切换滤镜保留各滤镜的独立强度。
- 相机、图片和视频页面往返后，美颜参数仍有效。
- 拍照、长按录像和视频导出正常，画面比例、声音和末帧表现符合预期。
- 分别在低、中、高性能设备验证 SDK 的机型分级和受限效果。

动态 framework 由宿主在嵌入时统一签名。不要额外执行 `strip -S -x` 修改桥接二进制，以免破坏签名信息。
