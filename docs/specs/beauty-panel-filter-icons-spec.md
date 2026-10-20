# Beauty Panel Filter Icons Spec

## 1. 背景

2026-09-04：Uniapp-NAMA 美颜面板滤镜预览图原先对齐 **nama-web WebDemo**，运行时从 OSS 拉取 `demo_icon_{key}.png`。这带来：

- 首次进滤镜栏需网络，弱网 / 低端机（如 iPhone XR）易卡。
- 与 **iOS FULiveDemo / Android Kotlin Demo** 不一致：官方 Demo 滤镜图标全部本地打包。

本次改为对齐官方 Demo：**滤镜预览图随 App 打进 `www/static`，原生面板与 Vue 页均读本地路径**。

## 2. Demo 对照

| 平台 | Demo 路径 | 加载方式 |
|------|-----------|----------|
| iOS | `iOS/FUBeautyComponent/.../FUBeautyComponent.xcassets/Filter/{filterKey}.imageset` | `FUBeautyImageNamed(filter.filterName)` |
| Android | `fu_ui/.../mipmap-xxhdpi/icon_beauty_filter_*.png` | `R.mipmap.icon_beauty_filter_*` |
| UniApp（改后） | `src/static/filter-icons/{filterKey}.png` | `getFilterIconUrl()` → `panelAssetUrl()` |

滤镜列表与 `beauty_filter.json` / `FaceBeautySource` 一致，共 **76 项**（含 `origin` + 75 款滤镜）。

### 命名约定

- **UniApp 文件名**：与 SDK `filter_name` 一致，如 `ziran1.png`、`origin.png`。
- **Demo 内部 PNG 名**可能不同（如 `demo_icon_natural_1.png`、`cancel.png`），拷贝脚本按 imageset 名输出为 `{filterKey}.png`，运行时只认 filter key。

## 3. 工程约定

### 3.1 资源目录

```text
src/static/filter-icons/
  origin.png
  ziran1.png
  ziran2.png
  ...
  heibai5.png
```

### 3.2 配置代码

`src/config/beauty-effects.ts`：

```ts
const FILTER_ICON_DIR = '/static/filter-icons'

export function getFilterIconUrl(filter: FilterPreset): string {
  return `${FILTER_ICON_DIR}/${filter.key}.png`
}
```

**禁止**再使用 OSS CDN URL 作为滤镜栏 icon（`NAMA_CDN_BASE/demo_icon_*` 已废弃）。

### 3.3 原生面板

`buildBeautyPanelConfig()` 中所有滤镜 `iconUrl` 统一经 `panelAssetUrl(getFilterIconUrl(f))` 转为 `file://` 绝对路径，供 iOS `FuBeautyPanelView` / Android `FuBeautyPanelView` 的 `loadImage` 读本地文件。

### 3.4 拷贝脚本

从 iOS Demo xcassets 同步资源：

```bash
# 默认 Demo 根：../../eat_more/NAMA_PROJ/iOS/FUBeautyComponent
node scripts/copy-beauty-icons-from-demo.mjs

# 或指定路径
FU_BEAUTY_DEMO_ROOT=/path/to/FUBeautyComponent node scripts/copy-beauty-icons-from-demo.mjs
```

脚本同时维护：

- `src/static/beauty-icons/`（美肤/美型 Mob* 四态）
- `src/static/filter-icons/`（滤镜预览图）

## 4. 性能要求（iOS 原生面板）

对齐 Demo「本地读图、切换不重载」：

- 滤镜 tab **切换选中**时：`refreshIconSelectionOnly` 只更新边框/高亮，**不**对全部 icon 再调 `loadImage`。
- 首次构建 icon 条或切 tab 重建时：允许 `loadImage`，但 URL 必须为本地路径；配合 `NSCache` / `iconBmpCache` 避免重复解码。

## 5. 验收

- [ ] `src/static/filter-icons/` 含 76 张 PNG，与 `FILTER_PRESETS` 一一对应。
- [ ] 断网状态下打开相机/媒体页美颜面板，滤镜栏图标正常显示。
- [ ] 快速连点切换滤镜，无明显卡顿（XR 真机）。
- [ ] iOS / Android 原生面板与 Vue `media-beauty` 页图标一致。
- [ ] 无对 `fu-sdk.oss-cn-hangzhou.aliyuncs.com/.../demo_icon_` 的网络请求。

## 6. 变更记录

| 日期 | 说明 |
|------|------|
| 2026-09-04 | 滤镜 icon 从 OSS 改为本地 `static/filter-icons`，对齐 FULiveDemo xcassets/Filter |
