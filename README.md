# nama-surpport

基于 **uni-app（Vue 3）** 的 FaceUnity Nama 美颜演示应用（SDK **9.0.1**）。

前端为 uni-app 页面；美颜通过本地原生插件 **FaceUnity-Nama** 接入。开发需 **自定义调试基座**；分发用 **云打包 / 正式包**。

## 目录结构

```
nama-surpport/
├── src/
│   ├── static/nama-bundle/          # AI/美颜 bundle（本地放入，随 App 打包）
│   └── utils/nama-app.ts            # 从 static 加载 bundle，不走 OSS
├── nativeplugins/FaceUnity-Nama/    # 原生插件（AAR / framework）
├── scripts/build-bridge/            # Android 桥接（编译前本地放 authpack.java）
└── scripts/build-ios-framework/     # iOS 桥接（编译前本地放 authpack.h）
```

## 环境要求

- Node.js、`npm install`
- [HBuilderX](https://www.dcloud.io/hbuilderx.html)
- Android：JDK 8+、`adb`
- iOS：macOS、Xcode（仅编译 framework 时需要）

## 常用命令

```bash
npm install
npm run type-check

# Android 桥接 → nativeplugins/.../FaceUnity-Nama.aar
npm run build:native-bridge:win    # Windows
npm run build:native-bridge        # macOS / Linux（bash）

# iOS framework（仅 Mac）
npm run build:ios-framework
```

| 命令 | 说明 |
|------|------|
| `npm run build:native-bridge:win` | 编译 Android AAR（需本地 authpack.java） |
| `npm run build:ios-framework` | 编译 iOS framework（需本地 authpack.h） |
| `npm run type-check` | TS 类型检查 |

## 鉴权文件（authpack）

仓库**不含** authpack。从证书方获取后，**仅在本机**放入以下路径再编译原生插件：

| 平台 | 本地路径 |
|------|----------|
| Android | `scripts/build-bridge/src/com/faceunity/app/authpack.java` |
| iOS | `nativeplugins/FaceUnity-Nama/ios/authpack.h` |

**不要**放到 `src/static/`。替换后须重打 AAR / framework 并重做基座。`fuSetup` 失败多为包名 / Bundle ID 与证书不匹配。

## Bundle 资源

- 路径：`src/static/nama-bundle/`
  - `ai_face_processor.bundle`
  - `face_beautification.bundle`
- 运行时从 static 读取，**不访问 OSS**（滤镜预览图 CDN 除外）
- 两个 `.bundle` 随仓库提交，打云包前确认目录下文件齐全

## 打基座 / 云打包

1. `src/manifest.json` 已勾选本地插件 `FaceUnity-Nama`
2. 确认 `src/static/nama-bundle/` 下两个 bundle 已存在
3. HBuilderX → **制作自定义调试基座** 或 **发行 → 原生 App-云打包**
4. 更新 `.aar` / `.framework` 后必须重打基座，热更新前端无效

## 原生插件更新（简要）

**Android**：本地放入 authpack.java → 改桥接源码 → `npm run build:native-bridge:win` → 重打基座。

**iOS**：本地放入 authpack.h → 改桥接源码 → Mac 上 `npm run build:ios-framework` → 重打基座。

## 常见问题

- **请运行到 App 自定义基座**：未选自定义基座，或基座未含本插件。
- **bundle 无法解析**：将两个 `.bundle` 放入 `src/static/nama-bundle/` 后重新云打包。
- **缺少 authpack / fuSetup 失败**：检查本地 authpack 路径与包名 / 签名是否匹配。
- **HBuilderX 搜不到安卓机**：可用 `adb connect` 无线调试。
