# iOS 接入方案：`FURenderKit` ↔ uni-app 桥接层对照表

## 1. 背景与目标

Android 侧已按「官方 AAR 提供能力 + uniapp 承接页面」的边界落地（见 `ANDROID_IMPLEMENTATION_COMPARISON.md`）。本文给出 iOS 侧同一套边界的落地方案：

- **核心能力全部来自官方 demo 包 `FURenderKit.framework`**，零二次开发。
- **页面、相册、选图、美颜面板 UI 全部由 uniapp 承接**，复用现有 `nama-app.ts` / Vue 页面。
- **唯一需要写的原生代码是 uni 桥接层 `NamaModule.m`**，把官方 API 薄薄包一层，不重写渲染逻辑。

原则：能用 demo 实现就用 demo 实现，避免二次开发；只有需要 uniapp 承接的才在 uniapp 侧做。

## 2. 官方 demo 能力包（iOS 侧现成物）

Android 需要三个 AAR 分开交付（`fu_core` / `fu_model` / `fu_beauty`），而 iOS 官方 demo `FULiveDemo` 的 **`FURenderKit` pod 把 framework + 模型 + 美颜 bundle 全打包在一起**：

| 能力块 | Android（三个 AAR） | iOS（`FURenderKit` 一个包） | 是否二次开发 |
|---|---|---|---|
| 核心渲染引擎 | `fu_core` | `FURenderKit.framework`（Camera/Photo/Video Renderer） | ❌ 直接用 |
| 相机采集 | 官方 `CameraRenderer` | `FUCaptureCamera` + `FUGLDisplayView` | ❌ 直接用 |
| 图片/视频处理 | `PhotoRenderer` / `VideoRenderer` | `FUVideoProcessor` / `UIImage+FURenderKit` | ❌ 直接用 |
| 人脸 AI 模型 | `fu_model` | `FURenderKit/Resources/model/ai_face_processor.bundle`（已内置） | ❌ 直接用 |
| 美颜 bundle | `fu_model` | `FURenderKit/Resources/graphics/face_beautification.bundle`（已内置） | ❌ 直接用 |
| **无界面美颜控制** | `fu_beauty`（`FaceBeautyKit.java`） | **`FUBeauty` 类（编译在 framework 内）** | ❌ 直接用 |
| 美颜 UI 面板 | 不接官方 UI | 官方 `FUBeautyComponent` **也不接**（避免绑定官方样式） | ✅ 由 uniapp 自己画面板 |
| uni 桥接层 | `NamaModule.java` | **新建 `NamaModule.m`** | ⚠️ **唯一二次开发点** |

> 架构提示：iOS 不需要像 Android 那样分别向官方要三个 AAR，一个 `FURenderKit` 就齐了。

## 3. 边界划分

```
uniapp 页面（nama-app.ts / beauty.vue / 选图页 / 美颜面板 UI）
   │  JS 侧接口不变，照 Android 同名方法调用
   ▼
NamaModule.m  ←── 唯一要新写的原生层（薄桥接）
   │  UNI_EXPORT_METHOD 翻译 + 生命周期编排
   ▼
FURenderKit.framework  ←── 官方 demo 现成包，零改动
   ├─ FUBeauty        美颜参数（下划线 key → 驼峰属性直映射）
   ├─ FUCaptureCamera 相机采集 / 切换 / 录像
   ├─ FUGLDisplayView 渲染显示（替代自建 GLKView）
   ├─ FUVideoProcessor 图片 / 视频处理
   └─ Resources        人脸模型 + 美颜 bundle（已内置）
```

## 4. 桥接层方法 ↔ `FURenderKit` API 对照

### 4.1 核心生命周期（鉴权 / 初始化）

| 桥接方法 | JS 调用参数 | iOS 官方 API | 说明 |
|---|---|---|---|
| `init` | authpack、bundle id | `[FURenderKit setupWithSetupConfig:]`（`FUSetupConfig` 填 `authPack` + `offLinePath`） | 对齐 Android `init`，用官方 setup，不碰旧 `fuSetup` |
| `loadAIModel` | 人脸 AI 路径 | `FUAIKit loadAIProcessor:type:` | 对齐 Android `loadAIModel` |
| `loadBundle` | 美颜 bundle 路径 | `[[FUBeauty alloc] initWithPath:name:]` → `[FURenderKit shareRenderKit].beauty = beauty` | 对齐 Android `loadBundle`（创建美颜对象并挂载） |
| `getVersion` | — | `[FURenderKit getVersion]` | 对齐 |

> `destroy`：Android 桥接有，但 JS 层未调用（资源释放走 `destroyCameraPreview` / `destroyVideoPreview`），iOS 桥接可省略。

### 4.2 相机渲染链路（核心）

| 桥接方法 | JS 调用参数 | iOS 官方 API | 说明 |
|---|---|---|---|
| `showCamera` | x/y/width/height | `FUCaptureCamera` 采集 + `FURenderKit` 渲染 + `FUGLDisplayView` 挂到 overlay | 用官方管线替代自建 `BeautyCameraView`（GLKView） |
| `resizeCameraPreview` | x/y/width/height | 更新 `FUGLDisplayView.frame` | 对齐 |
| `pauseCameraPreview` / `resumeCameraPreview` | — | `[FURenderKit shareRenderKit].pause = YES/NO` | 对齐 |
| `destroyCameraPreview` / `hideCamera` | — | 停采集 + 移除 `FUGLDisplayView` | 对齐 |
| `switchCamera` | — | `captureCamera` 切换前/后置 | 对齐 |
| `setPreviewResolution` / `resetPreviewResolution` | resolution | `internalCameraSetting.sessionPreset` | 对齐 |
| `setBeautyEnabled` | enabled | `[FURenderKit shareRenderKit].beauty.enable = YES/NO` | 对齐 |

### 4.3 美颜参数控制（核心）

| 桥接方法 | JS 调用参数 | iOS 官方 API | 说明 |
|---|---|---|---|
| `setParam` | pipeline/handle/key/value | `beauty.cheekThinning = value` 等（见 4.4 映射） | 对齐 Android 的 handle+pipeline 模型，落到底层就是设 `FUBeauty` 属性 |
| `setDualInput` | dual | `beauty.faceShape` / 渲染模式 | Android 有，iOS 旧桥接缺，**补上** |
| `showBeautyPanel` / `hideBeautyPanel` / `setBeautyPanelMode` / `updateBeautyPanelValues` | mode/values | 面板 UI 归 uniapp，桥接只透传参数到 `FUBeauty` | 对齐 |

### 4.4 美颜参数名映射（`setParam` 的 key → `FUBeauty` 属性）

`FUBeauty` 属性名与 JS/Android 的 key 几乎一一对应（驼峰 vs 下划线），`setParam` 桥接只需做一次「下划线 → 驼峰」翻译，无需手写每个参数。

| key（JS/Android） | `FUBeauty` 属性 | 范围 | 说明 |
|---|---|---|---|
| `color_level` | `colorLevel` | 0-1 美白 | ✅ 直接对应 |
| `blur_level` | `blurLevel` | 0-6 磨皮 | ✅ |
| `red_level` | `redLevel` | 0-1 红润 | ✅ |
| `eye_bright` | `eyeBright` | 0-1 亮眼 | ✅ |
| `tooth_whiten` | `toothWhiten` | 0-1 美牙 | ✅ |
| `cheek_thinning` | `cheekThinning` | 0-1 瘦脸 | ✅ |
| `cheek_narrow` | `cheekNarrow` | 0-1 窄脸 | ✅ |
| `cheek_small` | `cheekSmall` | 0-1 小脸 | ✅ |
| `eye_enlarging` | `eyeEnlarging` | 0-1 大眼 | ✅ |
| `intensity_nose` | `intensityNose` | 0-1 瘦鼻 | ✅ |
| `intensity_chin` | `intensityChin` | 0-1 下巴 | ✅ |
| `intensity_forehead` | `intensityForehead` | 0-1 额头 | ✅ |
| `intensity_mouth` | `intensityMouth` | 0-1 嘴型 | ✅ |
| `intensity_canthus` | `intensityCanthus` | 0-1 开眼角 | ✅ |
| 滤镜 | `filterName` + `filterLevel` | `FUFilter` | Android `setFilter` → iOS `filterName` |
| 皮肤分割 | `enableSkinSegmentation` | BOOL | Android `setSkinSegEnable` → iOS 属性 |

### 4.5 拍照 / 录像 / 媒体处理

| 桥接方法 | iOS 官方 API | 说明 |
|---|---|---|
| `capturePhoto` | `captureCamera` 读帧 + `UIImage+FURenderKit` | 对齐 Android `capturePhoto` |
| `startVideoRecord` / `stopVideoRecord` | `FUCaptureCamera startRecord/stopRecordWithCompletionHandler:` | 对齐 |
| `processImage` | `UIImage+FURenderKit`（官方图片美颜） | 对齐 |
| `processVideo` / `showVideoPreview` / `pauseVideoPreview` / `resumeVideoPreview` / `destroyVideoPreview` | `FUVideoProcessor` | 对齐 |

### 4.6 原生 UI 能力（边界：归 uniapp，桥接尽量不建）

| 桥接方法 | 处理 | 边界 |
|---|---|---|
| `showPreviewChrome` / `hidePreviewChrome` / `updatePreviewChromeStats` / `setPreviewChromeRecording` | 预览操作栏 | Android 走原生；iOS 建议**归 uniapp Vue 顶栏**，桥接不再建 |
| `tapFocus` / `setExposureBias` / `setCameraExposure` / `hideFocusChrome` / `hideFocusHud` | 对焦 / 曝光 | 底层相机 API 官方有，桥接薄封装 |
| `showToast` / `showConfirm` | 提示 / 确认 | 归 uniapp，桥接可省 |
| `pickMediaFromAlbum` | 选图 | **归 uniapp**（页面选图），桥接可省 |
| `getPreviewDiag` / `getPreviewStats` | 调试 | 可省，仅调试用 |

## 5. 关键确认项

1. **真机可用**：`FURenderKit.framework` 为 arm64-only（`lipo -info` 验证），只跑真机则无需向官方要 xcframework。若日后要跑模拟器，需官方出 xcframework 合并真机+模拟器切片。
2. **渲染承载差异**：iOS 无 Android 那种独立 Surface 层级问题，`FUGLDisplayView` 是普通 UIView，叠加在 WKWebView 上即普通 view 层级。自建 `BeautyCameraView`（GLKView）应整体替换为官方管线。
3. **输入格式差异**：Android 官方 Renderer 走 RGBA/OES 链路；iOS 采集为 `kCVPixelFormatType_32BGRA`（CMSampleBuffer）。这些由官方 SDK 内部处理，桥接层无需关心像素格式。
4. **`FURenderKit` 资源内置**：人脸模型与美颜 bundle 已随 pod 打包，无需单独交付 `fu_model`。

## 6. 待办（写 `NamaModule.m` 的蓝本）

按 4.1–4.5 的对照表实现桥接层：

- [ ] `init`：`setupWithSetupConfig:` 注入 authpack + offLinePath
- [ ] `loadAIModel` / `loadBundle`：加载 AI 模型与美颜 bundle
- [ ] `showCamera` / `resizeCameraPreview` / `pause` / `resume` / `destroy` / `switchCamera`：官方相机 + `FUGLDisplayView`
- [ ] `setParam`：下划线 → 驼峰翻译，落到 `FUBeauty` 属性
- [ ] `setDualInput`：补齐 Android 已有能力
- [ ] `capturePhoto` / `startVideoRecord` / `stopVideoRecord`：官方录像/拍照
- [ ] `processImage` / `processVideo` / `showVideoPreview`：官方媒体处理
- [ ] 鉴权用官方 `setupWithSetupConfig:`，不碰旧 `fuSetup`
