# Android FaceUnity Special Beauty Spec

## 1. 背景

2026-07-31 这一轮收尾，目标是把 Android / UniApp 美颜链路和 FaceUnity Demo 对齐，重点处理这些问题：

- `111单输入` 文案错误。
- 按下对比按钮后预览卡帧。
- 页面退出后资源没有完整释放。
- `面部丰盈` 点击或拖动后页面容易卡住。
- `瞳孔大小` 调节依旧无效，需要补日志确认资源和底层写参。
- 机型等级不满足时，按钮需要像 Demo 一样置灰并提示。
- NAMA 官方库需要使用 `all_feature`，不能继续使用 `face_beauty_sticker` 分版本 core/model。

这份 spec 记录本次改动过程和当前验收口径，方便后续继续测真机。

## 2. Demo 对比结论

Demo 位置：

```text
/path/to/Kotlin_FaceUnity_Demo
```

关键结论：

- Demo 是多版本工程，同时包含 `all_feature`、`face_beauty_sticker`、`face_all`、`body_all` 等版本。
- Demo 的机型等级计算使用 CPU 60% + GPU 40%，阈值是 `64 / 69 / 78 / 85`。
- Demo 的等级配置在加载 face processor 前执行。
- Demo 启动时会打开 FaceUnity Kit 和 Core 的 DEBUG 日志。
- Demo 产物里 `all_feature` 的 model 包包含更多资源，例如 `body_slim.bundle`、`face_makeup.bundle`、`ai_human_processor.bundle`。
- UniApp 之前放的是 `face_beauty_sticker` 分版本 core/model，不符合这次排查要求。

## 3. AAR 改动过程

原状态：

```text
nativeplugins/FaceUnity-Nama/android/libs/fu_core_face_beauty_sticker_release.aar
nativeplugins/FaceUnity-Nama/android/libs/fu_model_face_beauty_sticker_release.aar
nativeplugins/FaceUnity-Nama/android/libs/fu_beauty_face_beauty_sticker_release.aar
```

调整后：

```text
nativeplugins/FaceUnity-Nama/android/libs/fu_core_all_feature_release.aar
nativeplugins/FaceUnity-Nama/android/libs/fu_model_all_feature_release.aar
nativeplugins/FaceUnity-Nama/android/libs/fu_beauty_face_beauty_sticker_release.aar
```

说明：

- `core/model` 已切到 `all_feature`。
- `fu_beauty` 仍保留 `face_beauty_sticker` 控制层，因为 Demo 的 `all_feature` 结果目录中没有单独产出的 beauty AAR。
- 旧的 `fu_core_face_beauty_sticker_release.aar` 和 `fu_model_face_beauty_sticker_release.aar` 已删除，避免新旧库同时进包。
- `FaceUnity-Nama.aar` 已重新打包，已放在 UniApp 插件目录内。

## 4. 机型等级策略

对齐 Demo 的等级逻辑：

```text
level -1 / 1: 关闭遮挡、皮肤分割、祛斑、ARMeshV2、人种分类、高精遮挡点
level 2: 关闭皮肤分割、祛斑、ARMeshV2、人种分类
level 3: 关闭皮肤分割
level 4: 全部开启
```

功能门槛：

```text
facial_plump: 3 级以上
delspot_level: 3 级以上
body_blur_level: 4 级以上
enable_skinseg: 4 级以上
intensity_eye_pupil: 无等级门槛，默认 0.5
```

UniApp 补齐的表现：

- 不满足等级的功能按钮置灰。
- 点击置灰项时弹出提示。
- 滑杆禁用，避免用户误以为已经生效。
- 默认选中项和恢复默认项会跳过不可用功能。

滤镜预览图（2026-09-04 对齐 Demo）：

- 不再从 OSS 拉 `demo_icon_{key}.png`；详见 `docs/specs/beauty-panel-filter-icons-spec.md`。
- 资源位于 `src/static/filter-icons/{filterKey}.png`，与 iOS `FUBeautyComponent.xcassets/Filter` 一致。

## 5. 日志与瞳孔大小

本次已确认瞳孔图标资源存在，不是前端图标缺失。

已补的日志和调试开关：

```text
FURenderManager.setKitDebug(FULogger.LogLevel.DEBUG)
FURenderManager.setCoreDebug(FULogger.LogLevel.DEBUG)
FaceBeautySdk.setDebug(true)
faceunity.fuSetLogLevel(1)
faceunity.fuOpenFileLog(...)
```

当前判断：

- `瞳孔大小` 不是机型等级限制项。
- 默认值已从 `0` 改成 `0.5`，对齐 Demo 的中性值。
- 原生层已增加特殊参数缓存和回写，避免底层 getter 返回 0 后把用户设置吞掉。
- 如果真机仍无效果，下一步看日志里 `intensity_eye_pupil` 的写入值、缓存值、底层返回值是否一致。

## 6. 对比按钮与资源释放

对比按钮：

- Android 对比逻辑改成 Demo 同款渲染开关。
- 按下时临时关闭美颜渲染，松开恢复。
- 避免通过大量参数反复重写造成卡帧。

页面退出：

- 媒体美颜页退出时按顺序暂停预览、隐藏面板、销毁视频预览、释放媒体 handle。
- `destroyVideoPreview(true)` 会要求原生侧释放媒体页资源。
- 相机页仍保持原有会话策略，避免返回相机时黑屏。

## 7. 本次提交

已提交：

```text
1bfd8ef fix: align android faceunity beauty aar
```

本次提交包含：

- `all_feature` 官方 core/model AAR 切换。
- 旧分版本 core/model AAR 删除。
- Android 原生桥 AAR 重新打包。
- 机型等级和置灰提示逻辑。
- 对比按钮卡帧处理。
- 资源释放处理。
- 特殊美颜参数缓存和日志。
- 本地 Android 联调脚本。

未提交内容：

```text
LOCAL_ANDROID_SETUP.md
android-local-host-5.15/
package-lock.json
```

这些属于本地环境或额外产物，不放进这次提交。

## 8. 验证结果

已通过：

```text
git diff --check
npm run type-check
./scripts/build-bridge/build-aar.sh
```

当前需要真机继续确认：

- 重新做自定义基座后，`瞳孔大小` 是否出现实际变化。
- 导出的 adb 日志里，`intensity_eye_pupil` 是否成功写到底层。
- 低等级机型上，`面部丰盈` 等功能是否正确置灰并提示。

## 9. 后续排查口径

如果 `瞳孔大小` 仍然没效果，优先看三件事：

1. 当前安装包是否确实包含 `all_feature` core/model。
2. 日志里 `intensity_eye_pupil` 写入值是否不是 0。
3. 底层是否接受该参数，还是当前 Android SDK 版本虽然有参数名但无实际效果。

如果日志显示写入成功但画面无变化，需要继续和 FaceUnity Demo 用同一张脸、同一光照、同一参数值做对照，判断是资源版本问题还是 SDK 行为差异。
