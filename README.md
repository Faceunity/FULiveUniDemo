# FULiveUniDemo

基于 **uni-app（Vue 3）** 的 FaceUnity Nama 美颜演示应用（SDK **9.0.1**）。

前端为 uni-app 页面；美颜通过本地原生插件 **FaceUnity-Nama** 接入。开发需 **自定义调试基座**；分发用 **云打包 / 正式包**。

## 目录结构

```
FULiveUniDemo/
├── src/
│   ├── static/nama-bundle/          # AI/美颜 bundle（本地放入，随 App 打包）
│   └── utils/nama-app.ts            # 从 static 加载 bundle，不走 OSS
├── nativeplugins/FaceUnity-Nama/    # 原生插件产物（AAR / framework）
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

2. **准备 bundle** — 确认 `src/static/nama-bundle/` 下已有 `ai_face_processor.bundle`、`face_beautification.bundle`（随 App 打包，不走 OSS）。

3. **放入 authpack**（仓库不含，从证书方获取，仅本机；各平台只维护 **一处**）  
   - Android：`scripts/build-bridge/src/com/faceunity/app/authpack.java`  
   - iOS：`scripts/build-ios-framework/src/ios/auth/authpack.h`  

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

## 常见问题

- **请运行到 App 自定义基座**：未选自定义基座，或基座未含本插件。
- **bundle 无法解析**：将两个 `.bundle` 放入 `src/static/nama-bundle/` 后重新云打包。
- **缺少 authpack / fuSetup 失败**：确认 authpack 已放到上述 `scripts/` 路径，且与包名 / 签名匹配。

## 部分设备已知问题

### 华为 P20 Pro

1. 导入视频美颜无声音且预览卡顿。
2. 导入视频后切页面回到软件显示黑屏或卡死。
3. 录制视频到相册无法播放。

### vivo X9

1. 存在无法录制，或录制后无法播放、无声音的问题。

## 后续更新说明

受当前 uni-app 实现方式影响，部分场景（尤其视频美颜、录制）性能表现不够理想，上述设备问题也可能与此相关。后续版本将持续优化体验与兼容性。
