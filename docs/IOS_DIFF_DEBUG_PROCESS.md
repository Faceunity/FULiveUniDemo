# iOS/Android 差异问题排查流程

> 适用范围：任何「官方 demo 正常、uniapp 表现异常」的差异性问题（首帧慢、后台恢复黑屏、返回黑屏、参数无效等）。
> 核心方法论：**先在官方 demo 里确认"官方正确行为"是什么，再回 uniapp 对比找出差异点**，绝不在不了解 demo 实现前改 uniapp。

## 排查总纲

```
1. 现象描述（哪个页面、什么操作、表现是什么）
2. 官方 demo 对照（~/eat_more/NAMA_PROJ/iOS/FULiveDemo）
   ├─ 找到对应功能页的源码（ViewController / ViewModel / Component）
   ├─ 确认 demo 在这个场景下的正确行为
   ├─ 必要时真机实测 demo（加临时埋点，测完回退）
   └─ 记录 demo 的实现方式（API 顺序、生命周期、视图复用等）
3. uniapp 侧对照
   ├─ 找对应 JS 页面 + 原生桥接代码
   ├─ 对比 demo 与我们的差异点（API 调用顺序/参数/生命周期/视图管理）
   └─ 定位差异点
4. 验证假设（改 uniapp 复现 demo 行为 → 真机测）
5. 结论 + 修复
```

## 关键：官方 demo 的位置与结构

- demo 根目录：`~/eat_more/NAMA_PROJ/iOS/`
- demo 工程：`FULiveDemo/`（workspace `FULiveDemo.xcworkspace`，scheme `FULiveDemo`）
- 相机/美颜核心：
  - `FULiveDemo/Render/ViewModel/FURenderViewModel.m` — `startCameraWithRenderView:` / `stopCamera`（相机生命周期）
  - `FULiveDemo/Render/ViewController/FURenderViewController.m` — `viewWillAppear`/`viewWillDisappear` 里 start/stop
  - `FULiveDemo/Helper/FURenderKitManager.m` — setup、磨皮 blurType 动态调整
  - `FULiveDemo/Modules/Style/Component/ViewModel/FUStyleListViewModel.m` / `FUCustomizeSkinViewModel.m` — 美颜参数写参（含 setParam:forName: 底层 C 参数）
- 官方 SDK 头文件（本机）：`~/NAMA_PROJ/ios-offline-sdk/SDK/HBuilder-Hello/FaceUnityNama/FURenderKit.framework/Headers/`

## 常用对比点（iOS 相机/美颜）

| 场景 | demo 做法 | uniapp 对应 |
|---|---|---|
| 进美颜页启动相机 | `viewWillAppear` → `startCameraWithRenderView:`（view 已稳定） | `onLoad` → `mountNativeCameraPreview`（addSubview→didMoveToWindow 触发） |
| 按 Home | 相机**不停**（viewWillDisappear 不触发） | `onHide` → `pauseCameraPreview`（软暂停，保留采集会话） |
| 从子页面返回 | `viewWillAppear` → 重新 `startCameraWithRenderView`（复用 renderView） | `onShow` → remount 或 resumeCameraPreview |
| 写 C 参数 | `[beauty setParam:forName:paramType:]` | `FURenderKitCameraView.m applyPendingBeautyParams` |
| 磨皮 blurType | 按设备性能 + 人脸置信度动态 | `loadBundle` 固定 + JS blur_type |

## 常见根因模式（已确认）

1. **进页首帧 9.6s 慢**：`startInternalCamera` 在 view 上窗瞬间（didMoveToWindow 期间）调用，FURenderKit 主线程阻塞约 9s。demo 是 view 稳定后（viewWillAppear）才 start。修复：延迟到下一 runloop。
2. **按 Home 恢复黑屏**：onHide 真停相机（stopInternalCamera）→ 恢复时重启渲染失败。demo 相机不停。修复：软暂停（kit.pause=YES，保留采集）。
3. **返回子页面黑屏**：remount 重建视图后 overlay 未恢复显示。demo 复用 renderView + viewWillAppear 重启。
4. **参数无效**（全身磨皮等）：iOS 无 OC 属性，需 `setParam:forName:` 写 C 参数。

## 真机实测 demo 的方法

demo 编译装真机（复用 build-dd 增量编译）：

```bash
cd ~/eat_more/NAMA_PROJ/iOS
xcodebuild -workspace FULiveDemo.xcworkspace -scheme FULiveDemo \
  -configuration Debug -destination 'id=<真机UUID>' -derivedDataPath build-dd build
xcrun devicectl device install app --device <CoreDeviceID> build-dd/Build/Products/Debug-iphoneos/FULiveDemo.app
xcrun devicectl device process launch --device <CoreDeviceID> com.fu.fulivedemo
```

加临时埋点（诊断完成必须回退，保持 demo 纯净）：在 `FURenderViewModel.m` 的 `startCameraWithRenderView:` / `renderKitWillRenderFromRenderInput:` 写 `demo_diag.log`，或直接 NSLog + `devicectl device copy from` 拉。

> 注意：demo 与 uniapp 宿主 bundle id 相同（`com.fu.fulivedemo`），装 demo 会覆盖 uniapp App，测完要重装回 uniapp。

## 本机真机信息

- 设备：Dev 的 iPhone（iPhone 12）
- xcodebuild 真机 UUID：`00008101-000571AE2685001E`
- devicectl CoreDevice ID：`1BF5BEE8-9E07-5C83-A731-B167713D63FC`
- 签名 Team：`35A6KD62ZJ`
