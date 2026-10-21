# iOS 导入页返回美颜页黑屏 Spec

## 1. 背景

2026-08-10 排查 iOS 生命周期问题。已修复两项：
- 进美颜页首帧 9.6s 慢（`startInternalCamera` 上窗瞬间调用阻塞 → 延迟到 view 稳定后启动）。
- 按 Home 恢复画面卡住（onHide 真停相机 → 改软暂停 `kit.pause=YES`，保留采集会话）。

剩余问题：**从「导入」页（media-import）返回美颜页时，黑屏后才出现画面**。用户确认场景为「点导入进媒体导入页 → 返回美颜页」。

## 2. Demo 对比结论

Demo 位置：`~/eat_more/NAMA_PROJ/iOS/FULiveDemo`。

**Demo 的媒体选择流程**（`FURenderViewController.m:311-315`）：
- 点选图按钮 → `pushViewController: FUMediaPickerViewController`。
- push 触发当前 VC `viewWillDisappear` → `stopCamera`（`FURenderViewModel.m:68` `stopInternalCamera` + 清 glDisplayView）。
- pop 返回 → `viewWillAppear`（`FURenderViewController.m:69`）→ `startCameraWithRenderView:`（`FURenderViewModel.m:61`，`startInternalCamera` + 设 glDisplayView）。

**Demo 关键行为**：
- 每次进/返回都走 `startInternalCamera` 重启相机，但**复用同一个 `renderView`（FUGLDisplayView）**，不重建视图。
- `startInternalCamera` 在 `viewWillAppear`（view 已稳定）调用，秒开（实测首帧 488ms）。
- Demo **没有**「导入页」概念——媒体选择就是 push/pop，相机生命周期由 VC 的 appear/disappear 管理。

## 3. uniapp 侧现状与差异

**uniapp 导入流程**（`src/pages/beauty/beauty.vue` `goImportMedia`）：
```js
function goImportMedia() {
  collapseHeader()
  hideFocusChrome().catch(...)
  hidePreviewChrome().catch(...)
  needsCameraRemount = true          // ← 强制 remount
  setOverlayWindowsHidden(true).catch(...)  // 藏 overlay
  pauseCameraPreview().catch(...)    // 软暂停相机
  uni.navigateTo({ url: '/pages/media-import/media-import' })
}
```

导入页（`media-import.vue`）`onShow` 又 `setOverlayWindowsHidden(true)` + `pauseCameraPreview`。

返回美颜页触发 `onShow`（`beauty.vue:1403`）：
```js
if (needsCameraRemount || !cameraMounted) {
  // ← 走 remount 分支
  cameraMounted = false
  syncBeautyAfterCameraMount()
  setOverlayWindowsHidden(false)
  tryStartCamera()   // → mountNativeCameraPreview → 原生 showCamera
  return
}
```

**差异点**：
| 维度 | demo | uniapp |
|---|---|---|
| 媒体页 | push/pop（相机真停，返回重启） | navigateTo（软暂停，view 还在） |
| 返回恢复 | `viewWillAppear` → `startCameraWithRenderView` 复用 renderView | `onShow` → `needsCameraRemount` 强制 remount |
| 视图 | 复用同一 FUGLDisplayView | remount 重建 view + FUGLDisplayView |
| 黑屏 | 无（复用视图立即显示） | 有（remount 空窗黑屏） |

## 4. 根因

`goImportMedia` 设 `needsCameraRemount = true` 强制返回时走 remount 分支。但导入页只是**软暂停**相机（`pauseCameraPreview` → `hidePreview` → `kit.pause=YES`，**view 未销毁**），返回时本可走 resume 分支（`resumeCameraPreview` 软恢复）立即显示。

remount 分支的问题：
1. **异步空窗**：`tryStartCamera` → `setTimeout(0)` → `measurePreviewRect`（异步 query）→ `mountCameraPreview` → 原生 `showCamera`。mount 完成前视图是黑的。
2. **重建视图**：remount 重建 `FURenderKitCameraView` + `FUGLDisplayView`，需等新相机首帧，黑屏窗口更长。
3. **overlay 显示依赖**：remount 只 `setOverlayWindowsHidden(false)`（该 API 明确"不恢复相机"，只恢复视频叠层），相机显示依赖 mount 后 `bringOverlayToFront`，时序不可控。

日志证据（返回时）：`mount-call-showCamera` 秒过，但 mount 后 WILLRENDER 晚约 1 秒恢复（`FIRST_RENDER` 未触发因为 `_firstRenderLogged` 已置 YES）。

## 5. 修复方案

**方向：导入页返回走 resume 而非 remount**（对齐 demo 的「视图复用」）。

`goImportMedia` 不再设 `needsCameraRemount`，让返回时走 resume 分支（`setOverlayWindowsHidden(false)` + `resumeCameraPreview`，软恢复相机，view 复用，立即显示）。

风险：`needsCameraRemount` 原始用途是「避免视频 soft-hide/矩阵后 resume 花屏」——但该场景发生在 media-beauty（视频预览）页，不是 media-import（相册选择）页。media-import 返回走 resume 是安全的。

需验证：
1. 导入页返回不再黑屏（秒显画面）。
2. media-beauty（视频美颜）返回仍正常（remount 逻辑保留给 onUnload 场景）。
3. onUnload 后重进（页面真正销毁）仍能 remount 成功。

## 6. 验证口径

- 真机：进美颜页 → 点导入 → 返回，确认画面秒显、无黑屏。
- 回归：按 Home 恢复正常；首次进页 ~0.5s；全身磨皮有效。
- 边界：导入页选择媒体后进入 media-beauty，再返回美颜页正常。

## 7. 备注

- 本 spec 依排查流程 `docs/IOS_DIFF_DEBUG_PROCESS.md` 编写（先 demo 后 uniapp 对照）。
- 修复实施前需用户确认此方案。

## 8. 2026-08-10 补充：选照片后返回仍黑屏（media-beauty 场景）

**现象**：导入页返回已修复（不再黑屏），但**选照片进入 media-beauty 后再返回 beauty 页仍黑屏一段**。

**链路**：beauty → media-import（选类型）→ media-beauty（选具体照片）→ 返回 beauty（两次返回）。

**media-beauty 进页时对相机**（`media-beauty.vue:1463-1464`）：
```js
void pauseCameraPreview()   // 软暂停相机（view 仍在）
void destroyVideoPreview()  // 销毁视频预览
```

**返回 beauty 时 onShow resume 分支**（`beauty.vue:1391-1398`）：
```js
await joinWithTimeout(waitForMediaPreviewCleanup(), 800, '媒体预览清理等待超时')
```
`waitForMediaPreviewCleanup` 等媒体预览销毁完成，最多 800ms。media-beauty 返回时若媒体销毁慢，resume 被阻塞最多 0.8s。

**待确认**：
- media-beauty 返回时 beauty.onShow 走 resume 还是 remount 分支。
- `waitForMediaPreviewCleanup` 是否阻塞导致黑屏。
- overlay 是否在 media-beauty 期间被隐藏、返回时是否恢复。

**下一步**：在 beauty.onShow resume 分支 + `waitForMediaPreviewCleanup` 加埋点，真机复现测量黑屏窗口。

## 9. 2026-08-10 埋点结果：resume 链路秒过，但 beauty 可能被重建（onLoad）

加埋点（`resume-enter` / `resume-after-cleanup` / `resume-resume-branch` / `resume-resumeCameraPreview-done`）后实测：

```
06:30:27 resume-enter
06:30:28 resume-after-cleanup
06:30:28 resume-resume-branch          ← 走 resume 分支（非 remount）
06:30:28 resume-resumeCameraPreview-done  ← resume 完成
06:30:28 WILLRENDER #61                 ← 渲染立即恢复 ✅
```

**resume 链路秒过、渲染秒恢复**——黑屏不在 resume 内部。

但发现：**beauty 可能被重建**（06:30:20 `onload-start`，含完整 mount 流程；06:19:34 还出现 `init ok`/`loadAIModel`/`loadBundle` 重新初始化，container 变化 `16DA50B8`）。若 beauty 被 onUnload 重建，`needsCameraRemount=true` → 走 remount 分支 → 黑屏（与导入页根因相同）。

**需确认**：media-beauty 返回 beauty 时，beauty 是 onShow 还是 onLoad（是否被销毁重建）。若被重建，根因 = remount 黑屏，修复方向同导入页（避免 remount，或 remount 时快速恢复 overlay）。

**日志干扰**：06:19:34 出现 SDK 重新初始化，疑似 app 中途被杀/重启，需纯净复现确认时间线。

## 10. 2026-08-10 二次复现：resume 秒过、渲染恢复，但 overlay 可见性未恢复

纯净复现（进美颜页 → 导入 → 选照片 → media-beauty → 返回）：

```
06:46:26 WILLRENDER #27781    ← 进入媒体前最后渲染
06:46:35 resume-enter          ← 返回触发
06:46:35 resume-resume-branch  ← 走 resume 分支（非 remount）
06:46:35 后 5 秒 14 个 WILLRENDER ← 渲染立即恢复 ✅
```

**resume 秒过、渲染立即恢复**——黑屏不在渲染层（WILLRENDER 连续）。

**新发现**：media-beauty 进页（`media-beauty.vue:1499-1500` Android 分支；iOS 分支 1463 只 `pauseCameraPreview`）会隐藏 overlay。media-beauty `onUnload`（1564）**只清理媒体资源、不恢复 overlay**。

**假设**：黑屏在**返回过渡动画期间**——navigateBack 动画时 beauty 页面滑入，但相机 overlay 尚未显示（onShow 的 resume 在动画后执行），动画期间页面是黑的。等 resume 恢复 overlay 才出画面。

**待验证**：media-beauty 返回时 overlay 何时恢复可见（动画前 or 动画后）。修复候选：media-beauty `onUnload` 补 `setOverlayWindowsHidden(false)`，让 overlay 在销毁时恢复。

## 11. 根因确认：media-import 隐藏相机后从未恢复，返回转场期间黑屏

**黑屏时长**：0.5-2 秒（用户确认），超过单次转场动画，但 resume 埋点全部秒过（06:46:35 同秒完成）。

**页面栈**：beauty → media-import → media-beauty。选照片返回要**两次 navigateBack**（media-beauty → media-import → beauty）。

**media-import 的行为**（`media-import.vue`）：
- `onLoad`/`onShow`（75-76, 83-84）：`setOverlayWindowsHidden(true)` + `pauseCameraPreview` —— **持续隐藏相机**
- **没有 onUnload**：从未恢复 overlay/相机

**根因**：media-import 隐藏相机后从不恢复。返回 beauty 时，靠 beauty.onShow 的 resume 恢复，但 resume 在**两次转场动画之后**才执行 → 转场期间 beauty 页面显示但相机 overlay 隐藏 → 黑屏 0.5-2s。

**修复方案**：media-import 补 `onUnload`，返回时恢复 overlay（`setOverlayWindowsHidden(false)`），让相机在转场前可见。同时 media-beauty 的 `onUnload` 也应恢复 overlay（它进页时 Android 分支隐藏了）。

**风险**：media-import onUnload 恢复 overlay 后，若转场动画期间 beauty 未完全就绪，可能短暂露出相机。需真机验证。
