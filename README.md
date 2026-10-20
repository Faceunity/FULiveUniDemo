# FULiveUniDemo

基于 **uni-app（Vue 3）** 的 FaceUnity Nama 美颜演示应用。

前端为 uni-app 页面；美颜通过本地原生插件 **FaceUnity-Nama** 接入。开发需 **自定义调试基座**；分发用 **云打包 / 正式包**。

Android 使用官方 `fu_core / fu_model / fu_beauty` Kit，iOS 使用官方 `FURenderKit.framework`。相机、图片及视频处理统一调用官方 Kit，替换此前直接封装底层 Nama 的实现。

## 目录结构

```
FULiveUniDemo/
├── src/
│   └── utils/nama-app.ts            # 两端插件调用与页面状态
├── nativeplugins/FaceUnity-Nama/    # 官方 Kit 依赖 + 本机编译的插件
├── scripts/build-bridge/            # Android 桥接 + authpack.java
└── scripts/build-ios-framework/     # iOS 桥接 + authpack.h
```

## 环境要求

- Node.js、`npm install`
- [HBuilderX](https://www.dcloud.io/hbuilderx.html)
- Android：JDK 8+、`adb`
- iOS：macOS、Xcode（仅编译 framework 时需要）

## 运行步骤

1. **安装依赖**
   ```bash
   npm install
   ```

2. **确认官方 Kit 依赖**
   - Android：`nativeplugins/FaceUnity-Nama/android/libs/` 下的三个 `fu_*.aar` 和 `exoplayer-core-2.11.3.aar`。
   - iOS：`nativeplugins/FaceUnity-Nama/ios/FURenderKit.framework/`，包括其 bundle 资源。
   - 模型和美颜 bundle 由官方 Kit 提供，无需下载 OSS 资源。

3. **放入 authpack**（仓库不含，从证书方获取，仅本机；各平台只维护 **一处**）  
   - Android：`scripts/build-bridge/src/com/faceunity/app/authpack.java`  
   - iOS：`scripts/build-ios-framework/src/ios/auth/authpack.h`  
   授权源码、含授权的自建 AAR/framework、签名文件均不提交到仓库。

4. **编译原生插件**（首次或改了桥接 / authpack 时）
   ```bash
   npm run build:native-bridge:win   # Windows → FaceUnity-Nama.aar
   npm run build:native-bridge       # macOS / Linux
   npm run build:ios-framework       # iOS，仅 Mac
   ```

5. **打基座 / 云包** — HBuilderX 打开工程（`src/manifest.json` 已勾选 **FaceUnity-Nama**）  
   - 开发：**制作自定义调试基座** → 运行到该基座  
   - 分发：**发行 → 原生 App-云打包**  
   更新 `.aar` / `.framework` 后须重打基座，热更新前端无效。
   注：打包前需注意包名与认证文件是否一致

6. **日常开发** — HBuilderX 运行到自定义基座；改前端可用 `npm run type-check` 做类型检查。

本地离线打包分别见 [Android](LOCAL_ANDROID_SETUP.md) 和 [iOS](docs/LOCAL_IOS_SETUP.md)。使用离线宿主前，需核对 DCloud npm 依赖、HBuilderX 与离线 SDK 的版本一致；不能直接混用不同版本的运行时。

## 常见问题

- **请运行到 App 自定义基座**：未选自定义基座，或基座未含本插件。
- **模型或美颜资源加载失败**：检查官方 Kit 依赖及其 bundle 资源是否完整，更新后重新制作基座。
- **缺少 authpack / fuSetup 失败**：确认 authpack 已放到上述 `scripts/` 路径，且与包名 / 签名匹配。

## 设备兼容性复测

### 华为 P20 Pro

旧实现曾出现导入视频无声音、预览卡顿、页面返回黑屏，以及录像保存后无法播放。切换到 Kit 后需在真机重新验证这些场景。

### vivo X9

旧实现曾出现录像失败、保存后无法播放或无声音，需在真机复测。

## 后续更新说明

两端需要重点验证拍照、长按录像、图片/视频美颜导出、滤镜独立强度、预览分辨率切换，以及相机与媒体页面往返。构建通过不代表已完成设备兼容性测试。
