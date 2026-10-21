# Android 预览分辨率切换 Spec

## 1. 背景

Kit 相机路径（`KitBeautyCameraGLView` + 官方 `CameraRenderer` / `FUCamera`）在 PreviewChrome 切换 480P / 720P / 1080P 时，曾出现：

- 切换卡顿（同一操作触发两次 `changeResolution`，且多余 `resumePreview` 重绑 Kit）；
- 首次 1080→480 画面横向拉伸后恢复（过渡帧仍用旧 MVP 裁剪矩阵渲染新尺寸 buffer）。

官方 Demo 参考：`Kotlin_FaceUnity_Demo` → `BaseFaceUnityActivity` → `FUCamera.changeResolution`。

## 2. 目标行为（对齐 Demo）

| 步骤 | Demo | Uniapp-NAMA |
| --- | --- | --- |
| 用户点分辨率 | Activity RadioGroup | 原生 `PreviewChromeView` |
| 改采集尺寸 | `fuCamera.changeResolution(w,h)` 一次 | `applyPreviewResolutionById` → `setPreviewResolution` → 同上 |
| GL / Kit | 不 pause、不 rebind | **禁止** `resumePreview()` |
| Vue 状态 | 无 JS 层 | `resolution` 事件**仅**同步 `selectedResolutionId` / stats |
| 过渡帧 | `mIsStopPreview` + 新帧更新 MVP | `openCameraIgnoreFrame = 6` |

映射（与 Demo / iOS 一致）：

- 480 → 640×480
- 720 → 1280×720
- 1080 → 1920×1080

## 3. 禁止项

- 原生 `onSelectResolution` 改完相机后，JS **不得**再调 `setPreviewResolution`（避免双重 close/open）。
- 分辨率切换路径 **不得**调用 `resumePreview()`（该 API 仅用于 showCamera 恢复、视频页交还相机）。
- **不得**在分辨率切换时整段移植 dev 分支自研 `BeautyCameraGLView.restartPreview`（Kit 路径走官方 `FUCamera`）。

## 4. 保留的 JS API

- `setPreviewResolution(width, height)`：非 PreviewChrome 菜单触发的程序化切换（如调试）。
- `resetPreviewResolution()`：进相机页重置为 720，不跨页记忆。

## 5. 涉及文件

- `scripts/build-bridge/.../NamaModule.java` — `onSelectResolution`、`applyPreviewResolutionSize`
- `scripts/build-bridge/.../KitBeautyCameraGLView.java` — `setPreviewResolution`、`RESOLUTION_SWITCH_IGNORE_FRAMES`
- `src/pages/beauty/beauty.vue` — `resolution` 事件只更新 UI

## 6. 验收

按 `.cursor/skills/nama-build-run` 重编桥接 AAR 并装真机：

1. 480 ↔ 720 ↔ 1080 连切，无明显「卡两下」；
2. 首次 1080→480 无横向拉伸闪一下；
3. 切换后拍照 / 长按录像、从视频页返回后再切分辨率仍正常。

未覆盖：dev 分支上自研 Camera1 的机型特例（导航栏重建、`DeviceQuirk` 等），Kit 路径需单独评估移植。
