<template>
  <view class="page" :style="pageStyle">
    <!-- #ifdef APP-PLUS -->
    <view
      v-if="!useNativeTopChrome"
      class="header-bar"
      :style="headerBarStyle"
      @click.stop
    >
      <view v-if="!headerExpanded" class="top-bar">
        <view class="home-btn" @click="goHome">
          <image class="home-btn__img" src="/static/global-icons/home.svg" mode="aspectFit" />
        </view>
        <view class="top-bar__mid" @click.stop="toggleHeaderExpanded">
          <view class="io-tabs">
            <view
              class="io-tabs__item"
              :class="{ 'io-tabs__item--active': dualInputEnabled }"
              @click="setInputMode(true)"
            >
              <text class="io-tabs__text">双输入</text>
            </view>
            <view
              class="io-tabs__item"
              :class="{ 'io-tabs__item--active': !dualInputEnabled }"
              @click="setInputMode(false)"
            >
              <text class="io-tabs__text">单输入</text>
            </view>
          </view>
          <view v-if="!useNativePreviewChrome" class="stats-hud" @click.stop="toggleHeaderExpanded">
            <text class="stats-hud__item">分辨率:{{ statsResolution }}</text>
            <text class="stats-hud__item">帧率:{{ statsFps }}</text>
            <text class="stats-hud__item">rendertime:{{ statsRenderTime }}</text>
          </view>
        </view>
        <view class="header-switch-btn" @click.stop="onSwitchCamera">
          <image
            class="header-switch-btn__img"
            src="/static/global-icons/switch-camera.png"
            mode="aspectFit"
          />
        </view>
      </view>

      <view v-else class="top-bar top-bar--expanded">
        <view class="header-panel header-panel--left">
          <view
            v-for="preset in PREVIEW_RESOLUTION_PRESETS"
            :key="preset.id"
            class="resolution-item"
            :class="{ 'resolution-item--active': selectedResolutionId === preset.id }"
            @click.stop="onSelectResolution(preset)"
          >
            <text class="resolution-item__text">{{ preset.label }}</text>
          </view>
        </view>
        <view class="header-panel header-panel--right">
          <view class="import-btn" @click.stop="goImportMedia">
            <text class="import-btn__text">导入</text>
          </view>
          <view class="header-switch-btn" @click.stop="onSwitchCamera">
            <image
              class="header-switch-btn__img"
              src="/static/global-icons/switch-camera.png"
              mode="aspectFit"
            />
          </view>
        </view>
      </view>
    </view>

    <view
      v-if="headerExpanded"
      class="header-dismiss-mask"
      :style="headerDismissMaskStyle"
      @click="collapseHeader"
    />

    <view class="preview-area" :style="previewAreaStyle">
      <view id="cameraHost" class="camera camera--hole" />
    </view>

    <view class="preview-touch-layer" :style="previewAreaStyle" @tap="onPreviewTap" />

    <cover-view v-if="showNoFace && !useNativePreviewChrome" class="no-face" :style="noFaceStyle">
      <cover-view class="no-face__text">未检测到人脸</cover-view>
    </cover-view>

    <view v-if="initReady && !useNativePreviewChrome" class="floating-actions" :style="floatingActionsStyle">
      <view
        v-if="panelExpanded"
        class="compare-btn"
        @touchstart.prevent="onCompareStart"
        @touchend.prevent="onCompareEnd"
        @touchcancel.prevent="onCompareEnd"
      >
        <image class="compare-btn__img" src="/static/global-icons/compare.png" mode="aspectFit" />
      </view>
      <view
        class="capture-btn"
        :class="{ 'capture-btn--recording': recordingVideo, 'capture-btn--ios': isIOSPlatform() }"
        @touchstart.prevent="onCaptureTouchStart"
        @longpress.prevent="onCaptureLongPress"
        @touchend.prevent="onCaptureTouchEnd"
        @touchcancel.prevent="onCaptureTouchCancel"
      >
        <view class="capture-btn__inner" />
      </view>
    </view>

    <view v-if="!useNativeBeautyPanel" class="panel" :style="panelStyle" @tap.stop>
      <view v-if="panelExpanded" class="panel__body">
        <view
          v-if="showRuler && initReady"
          class="slider-row"
          :class="{
            'slider-row--bipolar': rulerIsBipolar,
            'slider-row--whitening': showWhiteningMode,
          }"
        >
          <view v-if="showWhiteningMode" class="whitening-seg">
            <view
              class="whitening-seg__item"
              :class="{ 'whitening-seg__item--active': whiteningMode === 'global' }"
              @click="setWhiteningMode('global')"
            >
              全局
            </view>
            <view
              class="whitening-seg__item"
              :class="{
                'whitening-seg__item--active': whiteningMode === 'skin',
                'whitening-seg__item--disabled': !canUseSkinWhitening,
              }"
              @click="setWhiteningMode('skin')"
            >
              仅皮肤
            </view>
          </view>
          <view class="slider-row__track">
            <view
              v-if="sliderDragging"
              class="slider-tip-bubble"
              :style="sliderTipStyle"
            >
              <image
                class="slider-tip-bubble__bg"
                src="/static/fu-chrome/slider_tip_background.png"
                mode="aspectFit"
              />
              <text class="slider-tip-bubble__text">{{ sliderDisplayValue }}</text>
            </view>
            <view
              v-if="rulerIsBipolar"
              class="slider-row__fill"
              :style="bipolarFillStyle"
            />
            <view v-if="rulerIsBipolar" class="slider-row__zero" />
            <slider
              class="beauty-slider"
              :class="{ 'beauty-slider--bipolar': rulerIsBipolar }"
              :key="rulerItem.key"
              :value="sliderValues[rulerItem.key]"
              :min="rulerSliderMin"
              :max="rulerSliderMax"
              :step="1"
              :activeColor="rulerIsBipolar ? 'rgba(255,255,255,0.25)' : '#5EC7FE'"
              backgroundColor="rgba(255,255,255,0.25)"
              block-color="#ffffff"
              block-size="18"
              @changing="onSliderChanging"
              @change="onSliderChange"
            />
          </view>
        </view>

        <view class="icon-bar-wrap">
          <view class="icon-bar-fixed">
            <view
              v-if="activeTab === 'filter'"
              class="icon-item"
              :class="{ 'icon-item--active': selectedFilterId === FILTER_ORIGIN_PRESET.id }"
              @click="onSelectFilter(FILTER_ORIGIN_PRESET)"
            >
              <image
                class="icon-item__img icon-item__img--filter"
                :src="getFilterIconUrl(FILTER_ORIGIN_PRESET)"
                mode="aspectFill"
              />
              <text class="icon-item__label">原图</text>
            </view>
            <view v-else class="icon-item" @click="onRestoreTabDefaults">
              <image
                class="icon-item__img icon-item__img--reset"
                src="/static/global-icons/reset.png"
                mode="aspectFit"
              />
              <text class="icon-item__label">恢复</text>
            </view>
            <view class="icon-bar-divider" />
          </view>

          <scroll-view class="icon-bar" scroll-x :scroll-into-view="scrollIntoViewId" scroll-with-animation>
            <view class="icon-bar__inner">
              <template v-if="activeTab === 'filter'">
                <view
                  v-for="filter in FILTER_SCROLL_PRESETS"
                  :id="`icon-${filter.id}`"
                  :key="filter.id"
                  class="icon-item"
                  :class="{ 'icon-item--active': selectedFilterId === filter.id }"
                  @click="onSelectFilter(filter)"
                >
                  <image
                    class="icon-item__img icon-item__img--filter"
                    :src="getFilterIconUrl(filter)"
                    mode="aspectFill"
                  />
                  <text class="icon-item__label">{{ filter.name }}</text>
                </view>
              </template>
              <template v-else>
                <view
                  v-for="item in currentEffectList"
                  :id="`icon-${item.key}`"
                  :key="item.key"
                  class="icon-item"
                  :class="{
                    'icon-item--active': selectedEffectKey === item.key,
                    'icon-item--disabled': isEffectDisabled(item),
                  }"
                  @click="onSelectEffect(item)"
                >
                  <image
                    class="icon-item__img"
                    :src="getEffectIconSrc(item, {
                      selected: selectedEffectKey === item.key,
                      changed: isEffectChanged(item, sliderValues[item.key] ?? 0),
                    })"
                    mode="aspectFit"
                  />
                  <text class="icon-item__label">{{ item.name }}</text>
                </view>
              </template>
            </view>
          </scroll-view>
        </view>
      </view>

      <view class="tabs">
        <view
          v-for="tab in BEAUTY_PANEL_TABS"
          :key="tab.id"
          class="tabs__item"
          :class="{ 'tabs__item--active': activeTab === tab.id }"
          @click="onTabChange(tab.id)"
        >
          <text class="tabs__text">{{ tab.label }}</text>
        </view>
      </view>

      <!-- 恢复确认：叠在底部功能区内，不藏其它 UI，双端共用 -->
      <view
        v-if="restoreConfirmVisible"
        class="restore-confirm"
        @tap.stop
      >
        <text class="restore-confirm__title">恢复默认</text>
        <text class="restore-confirm__desc">确定将当前「{{ restoreConfirmTabLabel }}」参数恢复为默认值？</text>
        <view class="restore-confirm__actions">
          <view class="restore-confirm__btn" @click="onRestoreConfirmCancel">
            <text class="restore-confirm__btn-text">取消</text>
          </view>
          <view class="restore-confirm__btn restore-confirm__btn--ok" @click="onRestoreConfirmOk">
            <text class="restore-confirm__btn-text restore-confirm__btn-text--ok">恢复</text>
          </view>
        </view>
      </view>
    </view>
    <!-- #endif -->

    <!-- #ifndef APP-PLUS -->
    <view class="camera camera--placeholder">
      <text>请在 App 自定义基座中打开</text>
    </view>
    <!-- #endif -->
  </view>
</template>

<script setup lang="ts">
import { onLoad, onReady, onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { ref, reactive, computed, nextTick, onBeforeUnmount, getCurrentInstance, watch } from 'vue'
import {
  ALL_SLIDER_EFFECTS,
  BEAUTY_BASE_PARAMS,
  BEAUTY_PANEL_TABS,
  BEAUTY_SHAPE_EFFECTS,
  BEAUTY_SKIN_EFFECTS,
  DEFAULT_FILTER_ID,
  FILTER_LEVEL_ITEM,
  FILTER_ORIGIN_PRESET,
  FILTER_PRESETS,
  FILTER_SCROLL_PRESETS,
  defaultSliderValue,
  getEffectIconSrc,
  getFilterIconUrl,
  getFilterPresetById,
  getSliderMax,
  getSliderMin,
  getSliderZero,
  isBidirectionalSlider,
  isEffectChanged,
  isBeautyParamAllowed,
  performanceLimitToast,
  sliderToValue,
  valueToSlider,
  type BeautyEffectItem,
  type BeautyPanelTab,
  type FilterPreset,
} from '@/config/beauty-effects'
import {
  captureCameraPhoto,
  startCameraVideoRecord,
  stopCameraVideoRecord,
  showAppToast,
  showAppConfirm,
  ensureCameraPermission,
  ensureMicrophonePermission,
  ensureStoragePermission,
  getDevicePerformanceLevel,
  getPreviewStats,
  getPreviewDiag,
  diagnoseNamaPlugin,
  destroyCameraPreview,
  hideCameraPreview,
  detachCameraOverlay,
  initNamaForBeauty,
  invalidateNamaSession,
  isNamaReady,
  pauseCameraPreview,
  resizeCameraPreview,
  resumeCameraPreview,
  setBeautyEnabled,
  setDualInput,
  setBeautyParam,
  setBeautyStringParam,
  drainNamaSdkLog,
  setOverlayWindowsHidden,
  setPreviewResolution,
  resetPreviewResolution,
  showCameraPreview,
  switchCameraFacing,
  setNamaPipeline,
  tapFocusAt,
  showPreviewChrome,
  hidePreviewChrome,
  updatePreviewChromeStats,
  setPreviewChromeRecording,
  hideFocusChrome,
  probeNativeBeautyPanel,
  buildBeautyPanelConfig,
  showBeautyPanel,
  hideBeautyPanel,
  updateBeautyPanelValues,
  type PreviewResolutionPreset,
  PREVIEW_RESOLUTION_PRESETS,
} from '@/utils/nama-app'
import {
  applyStatusBarStyle,
  applyTransparentWebViewStyle,
  hideNativeTitleNView,
} from '@/utils/app-plus-style'

const sliderValues = reactive<Record<string, number>>({})
const initReady = ref(false)
/** null = 底部 Tab 收起，无选中 */
const activeTab = ref<BeautyPanelTab | null>('skin')
const selectedFilterId = ref(DEFAULT_FILTER_ID)
const selectedEffectKey = ref(BEAUTY_SKIN_EFFECTS[0].key)
/** 机型等级：1 Low … 4 Excellent；-1 未知按高端处理 */
const devicePerfLevel = ref(1)
/** 美白分段：全局 / 仅皮肤（enable_skinseg，对齐 FULiveDemo） */
const whiteningMode = ref<'global' | 'skin'>('global')
const showWhiteningMode = computed(
  () => activeTab.value === 'skin' && selectedEffectKey.value === 'color_level_mode2',
)
const scrollIntoViewId = ref('')
const statsLabel = ref('0.0.0')
const faceTracking = ref(-1)
const statsReady = ref(false)
const comparing = ref(false)
const recordingVideo = ref(false)
const headerExpanded = ref(false)
const dualInputEnabled = ref(true)
const selectedResolutionId = ref('720')
const windowHeight = ref(0)
const safeAreaTop = ref(0)
/** iOS：底部功能区上移，避开 Home Indicator（pages.json bottom offset=none） */
const safeAreaBottom = ref(0)
/** 恢复默认确认框（叠在底部功能区内） */
const restoreConfirmVisible = ref(false)
const restoreConfirmTabLabel = ref('美肤')

function logBeauty(_step: string, _extra?: unknown) {
  // 业务日志关闭；iOS boot 窗口 SDK 原文经 console.log 打到 HBuilderX
}
let cameraMountedAt = 0

/** 顶栏单行（含带标题的统计）；底部布局保持现状 */
const HEADER_VH = 5
const TAB_BAR_VH = 5
/** 展开区：刚好容纳滑杆 + 放大后的图标，减少顶部空档 */
const PANEL_BODY_VH = 13
/** 拍摄按钮与功能区间距（展开时再贴齐并略下移） */
const ACTION_GAP_VH = 1
/** 拍摄按钮占位，取景区 bottom 固定用此值，不随展开变化 */
const CAPTURE_SLOT_VH = 6.5
const CAPTURE_SLOT_VH_IOS = 8
/** 展开时拍摄按钮相对功能区上沿再下移（像素），仅动按钮、不动取景 */
const CAPTURE_BTN_EXPAND_NUDGE_PX = 14
const IOS_PREVIEW_SHRINK_VH = 6
const IOS_PREVIEW_SHRINK_COLLAPSED_VH = 8
const ACTION_GAP_VH_IOS = 2

const panelExpanded = computed(() => true)

function isIOSPlatform() {
  const sys = uni.getSystemInfoSync()
  return sys.platform === 'ios' || (sys as UniApp.GetSystemInfoResult & { osName?: string }).osName === 'ios'
}

/**
 * 拍摄/对比/顶栏走原生 PreviewChrome（与对焦层同挂 overlay，取景可放大）。
 * iOS：仅当基座已导出 showPreviewChrome 时启用；否则回退 Vue，避免旧基座无按钮。
 */
function probeNativePreviewChrome(): boolean {
  if (!isIOSPlatform()) {
    return true
  }
  try {
    return diagnoseNamaPlugin().methods.includes('showPreviewChrome')
  } catch {
    return false
  }
}
const useNativePreviewChrome = ref(probeNativePreviewChrome())
/** iOS 试点：原生美颜底栏（对齐 FULiveDemo）；旧基座无 API 时回退 Vue */
const useNativeBeautyPanel = ref(false)
try {
  useNativeBeautyPanel.value = probeNativeBeautyPanel()
} catch {
  useNativeBeautyPanel.value = false
}
/** 顶栏与拍摄/对比一致 */
const useNativeTopChrome = computed(() => useNativePreviewChrome.value)

function syncLayoutMetrics() {
  const sys = uni.getSystemInfoSync()
  windowHeight.value = Math.round(sys.windowHeight)
  if (isIOSPlatform()) {
    // pages.json 已设 safearea top/bottom offset=none，WebView 铺满，勿再叠状态栏高度
    safeAreaTop.value = 0
    const bottom = sys.safeAreaInsets?.bottom
    // 额外上抬：Tab/图标区离开 Home Indicator，更好点按（仅 iOS）
    const base = typeof bottom === 'number' && bottom > 0 ? Math.round(bottom) : 10
    safeAreaBottom.value = base + 28
    return
  }
  safeAreaBottom.value = 0
  const top = sys.safeAreaInsets?.top
  safeAreaTop.value =
    typeof top === 'number' && top > 0
      ? Math.round(top)
      : Math.round(sys.statusBarHeight || 0)
}

function readWebviewScreenOffset() {
  // #ifdef APP-PLUS
  try {
    const sys = uni.getSystemInfoSync() as UniApp.GetSystemInfoResult & {
      windowTop?: number
    }
    if (typeof sys.windowTop === 'number' && sys.windowTop >= 0) {
      return { x: 0, y: Math.round(sys.windowTop) }
    }
    const wv = plus.webview.currentWebview()
    if (wv && typeof wv.getPosition === 'function') {
      const pos = wv.getPosition() as { left?: number; top?: number }
      return {
        x: Math.round(pos.left ?? 0),
        y: Math.round(pos.top ?? 0),
      }
    }
  } catch {
    // ignore
  }
  // #endif
  return { x: 0, y: 0 }
}

/** 顶栏 + 底栏（Tab / 展开体）+ 拍摄区空隙；仅 iOS 再矮 previewShrink */
function layoutInsets() {
  const topPx = safeAreaTop.value
  const bottomInsetPx = safeAreaBottom.value
  // Android 原生顶栏叠在取景上：预览可顶到状态栏下（取景占满头部）
  const headerContentPx = useNativeTopChrome.value ? 0 : vhToPx(HEADER_VH)
  const headerPx = topPx + headerContentPx
  // iOS 原生面板叠在预览上：H5 不占底栏高度，取景全屏
  if (useNativeBeautyPanel.value) {
    return {
      topPx,
      bottomInsetPx: 0,
      headerPx,
      headerContentPx,
      tabBarPx: 0,
      panelBodyPx: 0,
      panelPx: 0,
      actionGapPx: 0,
      captureSlotPx: 0,
      previewShrinkPx: 0,
    }
  }
  const tabBarPx = vhToPx(TAB_BAR_VH)
  // 底栏长期展开；拍摄/对比/HUD 叠在取景上，预览不再为拍摄槽让位
  const panelBodyPx = vhToPx(PANEL_BODY_VH)
  const panelPx = tabBarPx + panelBodyPx + bottomInsetPx
  const actionGapPx = 0
  const captureSlotPx = 0
  const previewShrinkPx = isIOSPlatform() && !useNativeTopChrome.value ? vhToPx(IOS_PREVIEW_SHRINK_VH) : 0
  return {
    topPx,
    bottomInsetPx,
    headerPx,
    headerContentPx,
    tabBarPx,
    panelBodyPx,
    panelPx,
    actionGapPx,
    captureSlotPx,
    previewShrinkPx,
  }
}

function vhToPx(vh: number) {
  const wh = windowHeight.value || uni.getSystemInfoSync().windowHeight
  return Math.round(wh * (vh / 100))
}

const statsResolution = computed(() => {
  // 优先完整「宽*高」；兼容旧 label「短边.fps.rt」
  const raw = statsLabel.value || ''
  if (raw.includes('*')) {
    return raw.split('.')[0] || raw
  }
  const [res] = raw.split('.')
  return res || '0'
})

const statsFps = computed(() => {
  const parts = statsLabel.value.split('.')
  return parts[1] ?? '0'
})

const statsRenderTime = computed(() => {
  const parts = statsLabel.value.split('.')
  return parts[2] ?? '0'
})

const pageStyle = computed(() => {
  const { previewShrinkPx } = layoutInsets()
  const wh = windowHeight.value
  if (wh <= 0) {
    return { height: '100vh' }
  }
  // 仅 iOS 整页矮一截；安卓保持满屏
  return { height: `${Math.max(wh - previewShrinkPx, 0)}px` }
})

const headerBarStyle = computed(() => {
  const { topPx, headerContentPx } = layoutInsets()
  return {
    top: '0px',
    height: `${topPx + headerContentPx}px`,
    paddingTop: `${topPx}px`,
    boxSizing: 'border-box' as const,
  }
})

const previewAreaStyle = computed(() => {
  const { headerPx, panelPx, actionGapPx, captureSlotPx } = layoutInsets()
  // 预留拍摄按钮高度，原生取景不会盖住按钮
  return {
    top: `${headerPx}px`,
    bottom: `${panelPx + actionGapPx + captureSlotPx}px`,
  }
})

const headerDismissMaskStyle = computed(() => {
  const { headerPx } = layoutInsets()
  return { top: `${headerPx}px` }
})

const panelStyle = computed(() => {
  const { panelPx, bottomInsetPx } = layoutInsets()
  return {
    bottom: '0px',
    height: `${panelPx}px`,
    paddingBottom: `${bottomInsetPx}px`,
    boxSizing: 'border-box' as const,
  }
})

/** 拍摄/对比：展开时贴齐功能区并略下移；取景区占位不变 */
const floatingActionsStyle = computed(() => {
  const { panelPx, actionGapPx } = layoutInsets()
  if (panelExpanded.value) {
    return {
      bottom: `${Math.max(0, panelPx - CAPTURE_BTN_EXPAND_NUDGE_PX)}px`,
    }
  }
  return {
    bottom: `${panelPx + actionGapPx}px`,
  }
})

const noFaceStyle = computed(() => {
  const sys = uni.getSystemInfoSync()
  const { headerPx, panelPx, actionGapPx, captureSlotPx, previewShrinkPx } = layoutInsets()
  const wh = (windowHeight.value || Math.round(sys.windowHeight)) - previewShrinkPx
  const previewPx = wh - headerPx - panelPx - actionGapPx - captureSlotPx
  const centerY = headerPx + Math.max(previewPx, 0) / 2
  return { top: `${centerY}px` }
})

const showNoFace = computed(() => {
  // 未检测到人脸改由原生层盖住取景，避免与 cover-view 叠两层
  return false
})

const currentEffectList = computed(() =>
  activeTab.value === 'shape' ? BEAUTY_SHAPE_EFFECTS : BEAUTY_SKIN_EFFECTS,
)

const rulerItem = computed<BeautyEffectItem>(() => {
  if (activeTab.value === 'filter') {
    return FILTER_LEVEL_ITEM
  }
  const list = currentEffectList.value
  return list.find((i) => i.key === selectedEffectKey.value) ?? list[0]
})

const showRuler = computed(() => {
  if (!panelExpanded.value) {
    return false
  }
  if (activeTab.value === 'filter') {
    return selectedFilterId.value !== 'origin'
  }
  if (!selectedEffectKey.value) {
    return false
  }
  return !isEffectDisabled(rulerItem.value)
})

function isEffectDisabled(item: BeautyEffectItem) {
  if (item.unimplemented) {
    return true
  }
  return !isBeautyParamAllowed(item.key, devicePerfLevel.value)
}

const sliderDragging = ref(false)
let sliderHideTimer: ReturnType<typeof setTimeout> | null = null

const rulerSliderMin = computed(() => getSliderMin(rulerItem.value))
const rulerSliderMax = computed(() => getSliderMax(rulerItem.value))
const rulerIsBipolar = computed(() => isBidirectionalSlider(rulerItem.value))

const sliderDisplayValue = computed(() => {
  const item = rulerItem.value
  const def = defaultSliderValue(item)
  const v = Math.round(sliderValues[item.key] ?? def)
  if (!rulerIsBipolar.value) {
    return String(v)
  }
  const offset = Math.round(v - getSliderZero(item))
  if (offset > 0) {
    return `+${offset}`
  }
  return String(offset)
})

/** 滑杆气泡跟随拇指（对齐 FULiveDemo FUSlider） */
const sliderTipStyle = computed(() => {
  const min = rulerSliderMin.value
  const max = rulerSliderMax.value
  const v = Number(sliderValues[rulerItem.value.key] ?? 0)
  const span = Math.max(max - min, 1)
  const ratio = (v - min) / span
  const thumbInset = 9
  const pct = thumbInset + ratio * (100 - thumbInset * 2)
  return { left: `${pct}%` }
})

/** 双向滑杆：高亮从中点向当前值延伸（不超出拇指） */
const bipolarFillStyle = computed(() => {
  if (!rulerIsBipolar.value) {
    return {}
  }
  const item = rulerItem.value
  const center = getSliderZero(item)
  const v = Number(sliderValues[item.key] ?? center)
  const offset = v - center
  const span = Math.max(
    center - rulerSliderMin.value,
    rulerSliderMax.value - center,
    1,
  )
  const thumbInset = 9
  const halfPct = Math.min(50 - thumbInset, Math.abs(offset) / span * (50 - thumbInset))
  if (offset >= 0) {
    return {
      left: '50%',
      width: `${halfPct}%`,
    }
  }
  return {
    left: `${50 - halfPct}%`,
    width: `${halfPct}%`,
  }
})

function applySliderValue(slider: number, commit: boolean) {
  const item = rulerItem.value
  const sMin = getSliderMin(item)
  const sMax = getSliderMax(item)
  const clamped = Math.min(sMax, Math.max(sMin, Math.round(slider)))
  sliderValues[item.key] = clamped
  if (!commit || !initReady.value) {
    return
  }
  queueBeautyChange({
    item,
    slider: clamped,
    value: sliderToValue(clamped, item),
  })
}

/** 拖动过程中实时下发参数（最新值覆盖，不排队积压） */
function onSliderChanging(e: { detail: { value: number } }) {
  if (sliderHideTimer) {
    clearTimeout(sliderHideTimer)
    sliderHideTimer = null
  }
  sliderDragging.value = true
  applySliderValue(e.detail.value, true)
}

function onSliderChange(e: { detail: { value: number } }) {
  applySliderValue(e.detail.value, true)
  if (sliderHideTimer) {
    clearTimeout(sliderHideTimer)
  }
  sliderHideTimer = setTimeout(() => {
    sliderDragging.value = false
    sliderHideTimer = null
  }, 400)
}

let latestBeautyPayload: {
  item: BeautyEffectItem
  slider: number
  value: number
} | null = null
let beautyFlushRunning = false

function queueBeautyChange(payload: {
  item: BeautyEffectItem
  slider: number
  value: number
}) {
  latestBeautyPayload = payload
  if (beautyFlushRunning) {
    return
  }
  beautyFlushRunning = true
  const run = async () => {
    while (latestBeautyPayload) {
      const next = latestBeautyPayload
      latestBeautyPayload = null
      if (BEAUTY_SHAPE_EFFECTS.some((i) => i.key === next.item.key)) {
        await setBeautyParam('face_shape', 4)
        await setBeautyParam('face_shape_level', 1)
      }
      await setBeautyParam(next.item.key, next.value)
    }
  }
  run()
    .catch((e) => {
      const msg = (e as Error).message
      if (msg.includes('loadBundle')) {
        return
      }
      uni.showToast({ title: msg, icon: 'none' })
    })
    .finally(() => {
      beautyFlushRunning = false
      if (latestBeautyPayload) {
        queueBeautyChange(latestBeautyPayload)
      }
    })
}

function collapsePanel() {
  if (comparing.value) {
    comparing.value = false
    setBeautyParam('is_beauty_on', 1).catch(() => undefined)
  }
  activeTab.value = null
}

function onTabChange(tab: BeautyPanelTab) {
  if (activeTab.value === tab) {
    collapsePanel()
    return
  }
  activeTab.value = tab
  if (tab === 'filter') {
    // 原图固定在左侧，不在 scroll-view 内
    scrollIntoViewId.value =
      selectedFilterId.value === FILTER_ORIGIN_PRESET.id
        ? ''
        : `icon-${selectedFilterId.value}`
    return
  }
  const list = tab === 'shape' ? BEAUTY_SHAPE_EFFECTS : BEAUTY_SKIN_EFFECTS
  if (!list.some((i) => i.key === selectedEffectKey.value)) {
    selectedEffectKey.value = list[0].key
  }
  scrollIntoViewId.value = `icon-${selectedEffectKey.value}`
}

function onSelectEffect(item: BeautyEffectItem) {
  if (item.unimplemented) {
    uni.showToast({
      title: `${item.name}功能暂未实现`,
      icon: 'none',
      duration: 1800,
    })
    return
  }
  if (isEffectDisabled(item)) {
    uni.showToast({
      title: performanceLimitToast(item),
      icon: 'none',
      duration: 2000,
      mask: true,
    })
    return
  }
  selectedEffectKey.value = item.key
  scrollIntoViewId.value = `icon-${item.key}`
}

const canUseSkinWhitening = computed(() => devicePerfLevel.value >= 4)

async function setWhiteningMode(mode: 'global' | 'skin') {
  if (mode === 'skin' && !canUseSkinWhitening.value) {
    uni.showToast({
      title: '皮肤美白仅支持旗舰及以上机型',
      icon: 'none',
      duration: 2000,
      mask: true,
    })
    return
  }
  whiteningMode.value = mode
  if (!initReady.value) {
    return
  }
  // 对齐 Demo：同一 color_level，切换分段只改 enable_skinseg，并再推一次美白强度以即时生效
  try {
    await setBeautyParam('enable_skinseg', mode === 'skin' ? 1 : 0)
    const item = BEAUTY_SKIN_EFFECTS.find((i) => i.key === 'color_level_mode2')
    if (item) {
      const slider = sliderValues[item.key] ?? defaultSliderValue(item)
      await setBeautyParam('color_level_mode2', sliderToValue(slider, item))
    }
  } catch {
    // ignore
  }
}

let cameraMounted = false
let cameraMounting = false
let pageReady = false
let pendingDestroy: Promise<void> = Promise.resolve()
let needsCameraRemount = false
let statsTimer: ReturnType<typeof setInterval> | null = null

function tryStartCamera() {
  if (!initReady.value || !pageReady || !isNamaReady()) {
    return
  }
  if (cameraMounted || cameraMounting) {
    return
  }
  setTimeout(() => mountNativeCameraPreview(0), 0)
}

function calcPreviewRect() {
  const sys = uni.getSystemInfoSync()
  const { headerPx, panelPx, actionGapPx, captureSlotPx, previewShrinkPx } = layoutInsets()
  const wh = (windowHeight.value || Math.round(sys.windowHeight)) - previewShrinkPx
  const previewPx = wh - headerPx - panelPx - actionGapPx - captureSlotPx
  const offset = readWebviewScreenOffset()
  return {
    x: offset.x,
    y: offset.y + headerPx,
    width: Math.round(sys.windowWidth),
    height:
      previewPx > 0
        ? previewPx
        : Math.max(wh - headerPx - panelPx - actionGapPx - captureSlotPx, 0),
  }
}

function domRectToNativeBox(
  rect: { left?: number; top?: number; width?: number; height?: number },
  fallback: { x: number; y: number; width: number; height: number },
) {
  // iOS 原生侧会再按 WKWebView 做 convertRect；这里只传 Web 视口坐标，勿重复加 offset
  // #ifdef APP-PLUS
  if (typeof plus !== 'undefined' && plus.os.name === 'iOS') {
    return {
      x: Math.round(rect.left ?? 0),
      y: Math.round(rect.top ?? fallback.y),
      width: Math.round(rect.width ?? fallback.width),
      height: Math.round(rect.height ?? fallback.height),
    }
  }
  // #endif
  const offset = readWebviewScreenOffset()
  return {
    x: Math.round((rect.left ?? 0) + offset.x),
    y: Math.round((rect.top ?? fallback.y) + offset.y),
    width: Math.round(rect.width ?? fallback.width),
    height: Math.round(rect.height ?? fallback.height),
  }
}

function measurePreviewRect() {
  const fallback = calcPreviewRect()
  return new Promise<{
    x: number
    y: number
    width: number
    height: number
  }>((resolve) => {
    const query = uni.createSelectorQuery()
    const instance = getCurrentInstance()
    if (instance) {
      query.in(instance)
    }
    query
      .select('#cameraHost')
      .boundingClientRect((rect) => {
        const sys = uni.getSystemInfoSync()
        const ww = Math.round(sys.windowWidth)
        if (!rect || Array.isArray(rect) || !rect.width || !rect.height) {
          resolve({ ...fallback, width: ww })
          return
        }
        const box = domRectToNativeBox(rect, fallback)
        // 取景宽度强制跟屏幕一致，避免量到偏窄导致右侧黑条
        box.width = ww
        if (isIOSPlatform()) {
          // iOS 偶发 boundingClientRect.left / convertRect 把整层拽偏：全宽取景贴左
          box.x = 0
        } else {
          box.x = readWebviewScreenOffset().x
        }
        resolve(box)
      })
      .exec()
  })
}

async function mountNativeCameraPreview(retry = 0) {
  if (cameraMounted && retry === 0) {
    return
  }
  if (cameraMounting && retry === 0) {
    return
  }
  if (retry === 0) {
    cameraMounting = true
  }

  await nextTick()
  const box = await measurePreviewRect()
  logBeauty('mountNativeCameraPreview', { retry, box })

  try {
    const info = (await showCameraPreview(box)) as Record<string, unknown>
    logBeauty('showCameraPreview ok', info)
    const cameraError = String(info.cameraError || '')
    if (cameraError) {
      throw new Error(cameraError)
    }

    // 先标记已挂载并出 chrome，写参异步跟上，避免首屏黑等全量 setParam
    cameraMounted = true
    cameraMountedAt = Date.now()
    statsReady.value = false
    faceTracking.value = -1
    startStatsPoll()
    if (useNativePreviewChrome.value) {
      await showPreviewChrome(chromeBoxOpts(box)).catch(() => undefined)
      bindPreviewChromeEvents()
    }
    await mountNativeBeautyPanel().catch(() => undefined)
    syncBeautyAfterCameraMount().catch(() => undefined)
  } catch (e) {
    let detail = (e as Error).message
    try {
      const diag = await getPreviewDiag()
      if (diag.diag) {
        detail = `${detail}\n${diag.diag}`
      }
      logBeauty('mountNativeCameraPreview failed', detail)
    } catch {
      logBeauty('mountNativeCameraPreview failed', detail)
    }
    if (retry < 4) {
      setTimeout(() => mountNativeCameraPreview(retry + 1), 500 + retry * 200)
    } else {
      uni.showToast({ title: detail.slice(0, 120), icon: 'none', duration: 4000 })
    }
  } finally {
    if (retry === 0) {
      cameraMounting = false
    }
  }
}

async function remountCameraForLayout() {
  if (!cameraMounted || cameraMounting || !initReady.value) {
    return
  }
  await nextTick()
  try {
    const box = await measurePreviewRect()
    await resizeCameraPreview(box)
    if (useNativePreviewChrome.value) {
      showPreviewChrome(chromeBoxOpts(box)).catch(() => undefined)
    }
  } catch {
    // ignore layout resize failures
  }
}

// 底栏常开，不再因展开/收起 resize 跳画面

ALL_SLIDER_EFFECTS.forEach((item) => {
  sliderValues[item.key] = defaultSliderValue(item)
})

async function applyBeautyParamsToSdk() {
  if (!isNamaReady()) {
    return
  }
  for (const p of BEAUTY_BASE_PARAMS) {
    await setBeautyParam(p.key, p.value)
  }
  await setBeautyParam('enable_skinseg', whiteningMode.value === 'skin' ? 1 : 0)
  await setBeautyParam('is_beauty_on', 1)
  await setBeautyEnabled(true)

  const filter = getFilterPresetById(selectedFilterId.value)
  await setBeautyStringParam('filter_name', filter.key)
  for (const item of ALL_SLIDER_EFFECTS) {
    if (item.unimplemented) {
      continue
    }
    // 灰显项写 0，勿写 default（去黑眼圈/法令纹 default=0.8 会误开）
    if (isEffectDisabled(item)) {
      await setBeautyParam(item.key, item.min ?? 0)
      continue
    }
    await setBeautyParam(item.key, sliderToValue(sliderValues[item.key], item))
  }
}

/** 相机挂载后刷一次参数；拖动滑杆 / 对比中跳过，避免把对比状态冲掉 */
async function syncBeautyAfterCameraMount() {
  if (sliderDragging.value || comparing.value) {
    return
  }
  await setBeautyEnabled(true)
  await applyBeautyParamsToSdk()
  // 首帧前后可能还有 boot SDK 日志尾巴，捞到 HBuilderX 控制台
  await drainNamaSdkLog().catch(() => undefined)
}

function startStatsPoll() {
  stopStatsPoll()
  statsTimer = setInterval(async () => {
    if (!cameraMounted) {
      return
    }
    try {
      const stats = await getPreviewStats()
      const renderTime = Math.max(0, Math.round(Number(stats.renderTime) || 0))
      const fps = Math.max(0, Math.round(Number(stats.fps) || 0))
      const resolution = Math.max(0, Math.round(Number(stats.resolution) || 0))
      const fw = Math.max(0, Math.round(Number(stats.frameWidth) || 0))
      const fh = Math.max(0, Math.round(Number(stats.frameHeight) || 0))
      // debug：分辨率用「宽*高」（如 720*1280）
      const resText = fw > 0 && fh > 0 ? `${fw}*${fh}` : String(resolution || '-')
      statsLabel.value = `${resText}.${fps}.${renderTime}`
      if (stats.frameWidth > 0 && stats.fps >= 0) {
        statsReady.value = true
      }
      if (stats.tracking >= 0) {
        faceTracking.value = stats.tracking
      }
      if (useNativePreviewChrome.value) {
        updatePreviewChromeStats({
          resolution: resText,
          fps,
          renderTime,
        }).catch(() => undefined)
      }
    } catch {
      // ignore
    }
  }, 500)
}

function stopStatsPoll() {
  if (statsTimer) {
    clearInterval(statsTimer)
    statsTimer = null
  }
}

function isTransientBeautyInitError(message: string): boolean {
  return (
    message.includes('请先 init') ||
    message.includes('activity null') ||
    message.includes('SDK 未就绪') ||
    message.includes('fuIsLibraryInit') ||
    message.includes('超时') ||
    message.includes('执行出错')
  )
}

async function initBeautyPipelineWithRetry(maxAttempts = 3): Promise<void> {
  let lastErr: Error | null = null
  for (let attempt = 0; attempt < maxAttempts; attempt++) {
    try {
      if (attempt > 0) {
        await new Promise((r) => setTimeout(r, 400 * attempt))
      }
      await initNamaForBeauty((step) => logBeauty('init', step))
      if (!isNamaReady()) {
        throw new Error('美颜 bundle 未加载成功')
      }
      return
    } catch (e) {
      lastErr = e instanceof Error ? e : new Error(String(e))
      logBeauty('init attempt failed', { attempt: attempt + 1, message: lastErr.message })
      if (!isTransientBeautyInitError(lastErr.message) || attempt >= maxAttempts - 1) {
        throw lastErr
      }
    }
  }
  if (lastErr) {
    throw lastErr
  }
}

onLoad(async () => {
  // #ifndef APP-PLUS
  return
  // #endif

  syncLayoutMetrics()
  hideNativeTitleNView()
  applyTransparentWebViewStyle()
  applyStatusBarStyle()

  cameraMounted = false
  cameraMounting = false
  initReady.value = false
  selectedResolutionId.value = '720'
  resetPreviewResolution().catch(() => undefined)

  logBeauty('onLoad start')

  const pluginDiag = diagnoseNamaPlugin()
  logBeauty('pluginDiag', pluginDiag)
  useNativePreviewChrome.value = !isIOSPlatform()
    || pluginDiag.methods.includes('showPreviewChrome')
  useNativeBeautyPanel.value = pluginDiag.methods.includes('showBeautyPanel')
  if (!pluginDiag.ok) {
    uni.showToast({ title: pluginDiag.detail, icon: 'none', duration: 3500 })
    return
  }

  try {
    await pendingDestroy
    logBeauty('ensureCameraPermission')
    await ensureCameraPermission()
    // 首进就申请麦克风/存储，避免首次拍摄/录像弹权导致黑屏、存相册异常
    try {
      await ensureMicrophonePermission()
    } catch {
      // 拒绝仍可拍照/无声录像
    }
    try {
      await ensureStoragePermission()
    } catch {
      // 拒绝后点击拍摄再提示
    }
    logBeauty('initNamaForBeauty')
    // 首页已 preload 时这里应秒过；未完成则 join 同一条，不要另起一套
    await initBeautyPipelineWithRetry()

    if (!isNamaReady()) {
      throw new Error('美颜 bundle 未加载成功')
    }

    // 先出相机，性能档/写参放到挂载后，避免黑屏干等几十次 setParam
    initReady.value = true
    logBeauty('init ready, start camera')
    tryStartCamera()

    getDevicePerformanceLevel()
      .then(async (perf) => {
        devicePerfLevel.value = Math.max(-1, Math.min(4, Number(perf?.level) || 1))
        if (devicePerfLevel.value < 4 && whiteningMode.value === 'skin') {
          whiteningMode.value = 'global'
        }
        if (useNativeBeautyPanel.value && initReady.value) {
          mountNativeBeautyPanel().catch(() => undefined)
        }
      })
      .catch(() => {
        devicePerfLevel.value = 1
      })
  } catch (e) {
    const msg = (e as Error).message
    logBeauty('onLoad failed', msg)
    uni.showToast({ title: msg, icon: 'none' })
    setTimeout(() => uni.navigateBack(), 1200)
  }
})

onReady(() => {
  hideNativeTitleNView()
  applyTransparentWebViewStyle()
  pageReady = true
  tryStartCamera()
})

onShow(() => {
  syncLayoutMetrics()
  pageReady = true
  hideNativeTitleNView()
  applyStatusBarStyle()
  applyTransparentWebViewStyle()
  comparing.value = false
  setNamaPipeline('camera')
  if (!initReady.value) {
    return
  }
  const resume = async () => {
    if (!isNamaReady()) {
      await initNamaForBeauty()
      await applyBeautyParamsToSdk()
    }
    if (needsCameraRemount || !cameraMounted) {
      needsCameraRemount = true
      cameraMounted = false
      cameraMounting = false
      statsReady.value = false
      syncBeautyAfterCameraMount().catch(() => undefined)
      tryStartCamera()
      return
    }
    syncBeautyAfterCameraMount().catch(() => undefined)
    try {
      await setOverlayWindowsHidden(false).catch(() => undefined)
      await resumeCameraPreview()
      // soft-hide 后若原生层已丢，resume 空转会一直黑：探测后强制 remount
      try {
        const diag = await getPreviewDiag().catch(() => null)
        const mounted = diag && (diag as { mounted?: boolean }).mounted
        if (mounted === false) {
          cameraMounted = false
          statsReady.value = false
          tryStartCamera()
          return
        }
      } catch {
        // ignore diag
      }
      startStatsPoll()
      if (useNativePreviewChrome.value) {
        const box = await measurePreviewRect()
        await showPreviewChrome(chromeBoxOpts(box)).catch(() => undefined)
        bindPreviewChromeEvents()
      }
      await mountNativeBeautyPanel().catch(() => undefined)
    } catch {
      cameraMounted = false
      statsReady.value = false
      tryStartCamera()
    }
  }
  resume().catch(() => undefined)
})

onHide(() => {
  stopStatsPoll()
  // 勿在 onHide 卸 chrome 监听：进导入页等仍在栈内，回来还要同一套回调
  // 美颜面板事件必须卸掉，避免与 media-beauty 双监听抢同一事件
  unbindBeautyPanelEvents()
  hideFocusChrome().catch(() => undefined)
  hidePreviewChrome().catch(() => undefined)
  hideBeautyPanel().catch(() => undefined)
  if (comparing.value) {
    comparing.value = false
    setBeautyEnabled(true).catch(() => undefined)
  }
  if (recordingVideo.value || recordIntent) {
    stopVideoRecording().catch(() => undefined)
  }
  // 冻帧停采集；勿 park 出屏，否则上滑多任务缩略图黑屏
  pauseCameraPreview().catch(() => {
    // ignore
  })
})

onUnload(() => {
  initReady.value = false
  needsCameraRemount = true
  cameraMounted = false
  cameraMounting = false
  pageReady = false
  statsReady.value = false
  comparing.value = false
  stopStatsPoll()
  // 真正离开相机页：卸掉唯一监听
  unbindPreviewChromeEvents()
  unbindBeautyPanelEvents()
  hideFocusChrome().catch(() => undefined)
  hidePreviewChrome().catch(() => undefined)
  hideBeautyPanel().catch(() => undefined)
  // 离开页只 soft-hide 相机叠层，保留 GL/SDK；禁止拆掉后回页黑屏
  pendingDestroy = hideCameraPreview()
    .then(() => undefined)
    .catch(() => undefined)
})

onBeforeUnmount(() => {
  stopStatsPoll()
})

/**
 * 模块级唯一监听：plus.globalEvent 跨页面实例不会自动清。
 * 进页只挂一份 dispatcher，回调指向当前页；离页（onUnload）再卸。
 */
let chromeListening = false
let chromePageHandler: ((e: {
  action?: string
  longPress?: boolean
  dual?: boolean
  id?: string
  visible?: boolean
}) => void) | null = null

function chromeEventDispatcher(e: {
  action?: string
  longPress?: boolean
  dual?: boolean
  id?: string
  visible?: boolean
}) {
  chromePageHandler?.(e)
}

function bindPreviewChromeEvents() {
  chromePageHandler = onPreviewChromeEvent
  if (chromeListening) {
    return
  }
  // #ifdef APP-PLUS
  try {
    plus.globalEvent.addEventListener('namaPreviewChrome', chromeEventDispatcher as any)
    chromeListening = true
  } catch {
    // ignore
  }
  // #endif
}

function unbindPreviewChromeEvents() {
  chromePageHandler = null
  if (!chromeListening) {
    return
  }
  // #ifdef APP-PLUS
  try {
    plus.globalEvent.removeEventListener('namaPreviewChrome', chromeEventDispatcher as any)
  } catch {
    // ignore
  }
  // #endif
  chromeListening = false
}

let beautyPanelListening = false
let beautyPanelPageHandler: ((e: Record<string, unknown>) => void) | null = null

function beautyPanelEventDispatcher(e: Record<string, unknown>) {
  beautyPanelPageHandler?.(e)
}

function bindBeautyPanelEvents() {
  beautyPanelPageHandler = onBeautyPanelEvent
  if (beautyPanelListening) {
    return
  }
  // #ifdef APP-PLUS
  try {
    plus.globalEvent.addEventListener('namaBeautyPanel', beautyPanelEventDispatcher as any)
    beautyPanelListening = true
  } catch {
    // ignore
  }
  // #endif
}

function unbindBeautyPanelEvents() {
  beautyPanelPageHandler = null
  if (!beautyPanelListening) {
    return
  }
  // #ifdef APP-PLUS
  try {
    plus.globalEvent.removeEventListener('namaBeautyPanel', beautyPanelEventDispatcher as any)
  } catch {
    // ignore
  }
  // #endif
  beautyPanelListening = false
}

async function mountNativeBeautyPanel() {
  if (!useNativeBeautyPanel.value || !initReady.value) {
    return
  }
  bindBeautyPanelEvents()
  const cfg = buildBeautyPanelConfig({
    mode: 'camera',
    values: { ...sliderValues },
    filterId: selectedFilterId.value,
    whiteningMode: whiteningMode.value,
    selectedKey: selectedEffectKey.value,
  })
  try {
    await showBeautyPanel(cfg)
  } catch {
    useNativeBeautyPanel.value = false
  }
}

function onBeautyPanelEvent(e: Record<string, unknown>) {
  const action = String(e?.action || '')
  if (action === 'tab') {
    const tab = String(e.tab || '')
    const expanded = !!e.expanded
    if (!expanded || !tab) {
      activeTab.value = null
    } else if (tab === 'skin' || tab === 'shape' || tab === 'filter') {
      activeTab.value = tab
    }
    return
  }
  if (action === 'selectEffect') {
    const key = String(e.key || '')
    if (key) {
      selectedEffectKey.value = key
    }
    return
  }
  if (action === 'slider') {
    const key = String(e.key || '')
    const sdk = Number(e.value)
    if (!key || Number.isNaN(sdk)) {
      return
    }
    const item =
      ALL_SLIDER_EFFECTS.find((i) => i.key === key) ||
      (key === 'filter_level' ? FILTER_LEVEL_ITEM : null)
    if (item) {
      sliderValues[key] = valueToSlider(sdk, item)
    }
    return
  }
  if (action === 'filter') {
    const id = String(e.id || e.key || '')
    if (id) {
      selectedFilterId.value = id
    }
    return
  }
  if (action === 'whiteningMode') {
    const mode = String(e.mode || 'global') === 'skin' ? 'skin' : 'global'
    whiteningMode.value = mode
    return
  }
  if (action === 'recover') {
    const tab = String(e.tab || '')
    if (tab === 'skin' || tab === 'shape') {
      activeTab.value = tab
      // 原生已确认并写参：只同步 JS 状态
      if (Number(e.confirmed) === 1 || e.confirmed === true) {
        void syncRestoreStateFromDefaults()
      } else {
        void onRestoreTabDefaults()
      }
    }
    return
  }
  if (action === 'compareStart') {
    onCompareStart()
    return
  }
  if (action === 'compareEnd') {
    onCompareEnd()
  }
}

function chromeBoxOpts(box: { x: number; y: number; width: number; height: number }) {
  return {
    ...box,
    resolutionId: selectedResolutionId.value,
    dualInput: dualInputEnabled.value,
  }
}

function onPreviewChromeEvent(e: {
  action?: string
  longPress?: boolean
  dual?: boolean
  id?: string
  visible?: boolean
}) {
  const action = e?.action || ''
  if (action === 'compareStart') {
    onCompareStart()
    return
  }
  if (action === 'compareEnd') {
    onCompareEnd()
    return
  }
  if (action === 'captureLongPress') {
    onCaptureLongPress()
    return
  }
  if (action === 'captureUp') {
    if (e.longPress) {
      onCaptureTouchEnd()
    } else {
      onCaptureTouchStart()
      onCaptureTouchEnd()
    }
    return
  }
  if (action === 'home') {
    goHome()
    return
  }
  if (action === 'switchCamera') {
    onSwitchCamera()
    return
  }
  if (action === 'recordAutoStopped') {
    clearRecordMaxTimer()
    recordIntent = false
    recordingVideo.value = false
    setPreviewChromeRecording(false).catch(() => undefined)
    const ok = Number((e as { ok?: number | boolean }).ok) === 1 || (e as { ok?: boolean }).ok === true
    if (ok) {
      showAppToast('视频已保存', { icon: 'success' })
    }
    captureLongPressTriggered = false
    return
  }
  if (action === 'dualInput') {
    setInputMode(!!e.dual)
    return
  }
  if (action === 'resolution') {
    const preset = PREVIEW_RESOLUTION_PRESETS.find((p) => p.id === e.id)
    if (preset) {
      onSelectResolution(preset)
    }
    return
  }
  if (action === 'importMedia') {
    goImportMedia()
    return
  }
}

function goHome() {
  headerExpanded.value = false
  uni.navigateBack({
    fail: () => {
      uni.reLaunch({ url: '/pages/index/index' })
    },
  })
}

function toggleHeaderExpanded() {
  headerExpanded.value = !headerExpanded.value
}

function collapseHeader() {
  headerExpanded.value = false
}

function setInputMode(dual: boolean) {
  if (dualInputEnabled.value === dual) {
    return
  }
  dualInputEnabled.value = dual
  setDualInput(dual).catch(() => undefined)
}

function onPreviewTap(e: { detail: { x: number; y: number } }) {
  if (headerExpanded.value) {
    collapseHeader()
    return
  }
  // 底栏常开：预览点击只对焦，不收面板

  uni
    .createSelectorQuery()
    .select('.preview-touch-layer')
    .boundingClientRect((rect) => {
      if (!rect || Array.isArray(rect)) {
        return
      }
      const left = rect.left ?? 0
      const top = rect.top ?? 0
      const width = Math.max(1, rect.width ?? 1)
      const height = Math.max(1, rect.height ?? 1)
      const nx = Math.min(1, Math.max(0, (e.detail.x - left) / width))
      const ny = Math.min(1, Math.max(0, (e.detail.y - top) / height))
      // 原生 overlay 画十字/曝光（WebView UI 会被相机窗盖住）
      tapFocusAt(nx, ny).catch(() => undefined)
    })
    .exec()
}

async function onSelectResolution(preset: PreviewResolutionPreset) {
  selectedResolutionId.value = preset.id
  headerExpanded.value = false
  try {
    await setPreviewResolution(preset.width, preset.height)
    statsLabel.value = `${preset.label}.0.0`
    // iOS/Android 原生侧已 restartPreview，勿再 remount，避免取景黑闪
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: 'none' })
  }
}

function goImportMedia() {
  collapseHeader()
  hideFocusChrome().catch(() => undefined)
  hidePreviewChrome().catch(() => undefined)
  // 导入后回相机：强制按需 remount/探测，避免视频 soft-hide/矩阵后 resume 花屏
  needsCameraRemount = true
  // 立刻跳转；异步藏叠层即可（勿 await，否则导入选择页卡很久）
  // 冻帧靠原生 softHide 整窗 + 导入页 onShow 再藏一次，不挡导航
  setOverlayWindowsHidden(true).catch(() => undefined)
  pauseCameraPreview().catch(() => undefined)
  uni.navigateTo({ url: '/pages/media-import/media-import' })
}

/** 仅恢复当前 Tab（美肤/美型）内功能到默认值 */
async function onRestoreTabDefaults() {
  if (!initReady.value || activeTab.value === 'filter' || activeTab.value === null) {
    return
  }
  const tabLabel = activeTab.value === 'shape' ? '美型' : '美肤'
  restoreConfirmTabLabel.value = tabLabel
  hideFocusChrome().catch(() => undefined)
  // 原生面板：确认与写参由原生 beautyPanelRecoverTab 完成，这里不再二次弹窗
  if (useNativeBeautyPanel.value) {
    return
  }
  restoreConfirmVisible.value = true
}

/** 原生 Alert 确认后：只同步 JS 内存状态（滑杆/写参已由原生完成） */
async function syncRestoreStateFromDefaults() {
  if (!initReady.value || activeTab.value === 'filter' || activeTab.value === null) {
    return
  }
  const list = activeTab.value === 'shape' ? BEAUTY_SHAPE_EFFECTS : BEAUTY_SKIN_EFFECTS
  for (const item of list) {
    sliderValues[item.key] = defaultSliderValue(item)
  }
  if (activeTab.value === 'skin') {
    whiteningMode.value = 'global'
  }
  selectedEffectKey.value = list.find((i) => !i.unimplemented)?.key || list[0].key
  scrollIntoViewId.value = `icon-${selectedEffectKey.value}`
}

function onRestoreConfirmCancel() {
  restoreConfirmVisible.value = false
}

async function onRestoreConfirmOk() {
  restoreConfirmVisible.value = false
  if (!initReady.value || activeTab.value === 'filter' || activeTab.value === null) {
    return
  }
  const list = activeTab.value === 'shape' ? BEAUTY_SHAPE_EFFECTS : BEAUTY_SKIN_EFFECTS
  // 先刷新滑杆 UI，再写 SDK
  for (const item of list) {
    sliderValues[item.key] = defaultSliderValue(item)
  }
  if (activeTab.value === 'skin') {
    whiteningMode.value = 'global'
  }
  selectedEffectKey.value = list.find((i) => !i.unimplemented)?.key || list[0].key
  scrollIntoViewId.value = `icon-${selectedEffectKey.value}`
  try {
    setNamaPipeline('camera')
    await setBeautyParam('is_beauty_on', 1)
    if (activeTab.value === 'shape') {
      await setBeautyParam('face_shape', 4)
      await setBeautyParam('face_shape_level', 1)
    }
    for (const item of list) {
      if (item.unimplemented) {
        continue
      }
      await setBeautyParam(item.key, item.default)
    }
    if (activeTab.value === 'skin') {
      await setBeautyParam('enable_skinseg', 0)
    }
    if (useNativeBeautyPanel.value) {
      updateBeautyPanelValues({
        values: { ...sliderValues },
        whiteningMode: whiteningMode.value,
        selectedKey: selectedEffectKey.value,
      }).catch(() => undefined)
    }
    uni.showToast({ title: '已恢复默认', icon: 'none', duration: 1200 })
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: 'none' })
  }
}

function onBeautyChange(payload: {
  item: BeautyEffectItem
  slider: number
  value: number
}) {
  if (!initReady.value) {
    return
  }
  queueBeautyChange(payload)
}

async function onSelectFilter(filter: FilterPreset) {
  selectedFilterId.value = filter.id
  scrollIntoViewId.value = `icon-${filter.id}`
  try {
    await setBeautyParam('face_shape', 4)
    await setBeautyParam('face_shape_level', 1)
    await setBeautyStringParam('filter_name', filter.key)
    if (filter.id === 'origin') {
      sliderValues.filter_level = 0
      await setBeautyParam('filter_level', 0)
      return
    }
    const level = sliderToValue(sliderValues.filter_level, FILTER_LEVEL_ITEM)
    if (level <= 0) {
      sliderValues.filter_level = defaultSliderValue(FILTER_LEVEL_ITEM)
      await setBeautyParam('filter_level', FILTER_LEVEL_ITEM.default)
    } else {
      await setBeautyParam('filter_level', level)
    }
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: 'none' })
  }
}

function onCompareStart() {
  comparing.value = true
  setBeautyEnabled(false).catch(() => undefined)
  setBeautyParam('is_beauty_on', 0).catch(() => undefined)
}

function onCompareEnd() {
  comparing.value = false
  setBeautyEnabled(true).catch(() => undefined)
  setBeautyParam('is_beauty_on', 1).catch(() => undefined)
}

async function onCapture() {
  if (recordingVideo.value || captureLongPressTriggered || recordIntent) {
    return
  }
  try {
    await ensureStoragePermission()
    await captureCameraPhoto()
    showAppToast('已保存至相册', { icon: 'success' })
  } catch (e) {
    showAppToast((e as Error).message, { icon: 'none' })
  }
}

let captureLongPressTimer: ReturnType<typeof setTimeout> | null = null
let captureLongPressTriggered = false
/** 已发起开始录制但原生尚未返回：松手时要能取消/停录 */
let recordIntent = false
/** 最长 10 秒自动停录 */
let recordMaxTimer: ReturnType<typeof setTimeout> | null = null
const RECORD_MAX_MS = 10_000

function clearRecordMaxTimer() {
  if (recordMaxTimer) {
    clearTimeout(recordMaxTimer)
    recordMaxTimer = null
  }
}

function clearCaptureLongPressTimer() {
  if (captureLongPressTimer) {
    clearTimeout(captureLongPressTimer)
    captureLongPressTimer = null
  }
}

function onCaptureTouchStart() {
  captureLongPressTriggered = false
  clearCaptureLongPressTimer()
  // 兜底：部分机型 longpress 不稳，仍用 320ms 定时器
  captureLongPressTimer = setTimeout(() => {
    captureLongPressTimer = null
    onCaptureLongPress()
  }, 320)
}

function onCaptureLongPress() {
  if (recordingVideo.value || recordIntent) {
    return
  }
  captureLongPressTriggered = true
  clearCaptureLongPressTimer()
  startVideoRecording().catch(() => undefined)
}

function onCaptureTouchEnd() {
  const shortTap = captureLongPressTimer != null
  clearCaptureLongPressTimer()
  if (recordingVideo.value || recordIntent) {
    stopVideoRecording().catch(() => undefined)
    return
  }
  if (captureLongPressTriggered) {
    captureLongPressTriggered = false
    return
  }
  if (shortTap) {
    onCapture().catch(() => undefined)
  }
}

function onCaptureTouchCancel() {
  clearCaptureLongPressTimer()
  if (recordingVideo.value || recordIntent) {
    stopVideoRecording().catch(() => undefined)
  }
  captureLongPressTriggered = false
}

async function startVideoRecording() {
  if (recordingVideo.value || recordIntent) {
    return
  }
  recordIntent = true
  captureLongPressTriggered = true
  try {
    await ensureStoragePermission()
    try {
      await ensureMicrophonePermission()
    } catch {
      // 无麦克风权限仍允许录画面
    }
    await startCameraVideoRecord()
    if (!recordIntent) {
      // 手指已松开：立刻停掉刚开的录制
      try {
        await stopCameraVideoRecord()
      } catch {
        // ignore
      }
      captureLongPressTriggered = false
      return
    }
    recordingVideo.value = true
    setPreviewChromeRecording(true).catch(() => undefined)
    clearRecordMaxTimer()
    recordMaxTimer = setTimeout(() => {
      recordMaxTimer = null
      if (recordingVideo.value || recordIntent) {
        stopVideoRecording().catch(() => undefined)
      }
    }, RECORD_MAX_MS)
  } catch (e) {
    recordIntent = false
    captureLongPressTriggered = false
    recordingVideo.value = false
  setPreviewChromeRecording(false).catch(() => undefined)
    clearRecordMaxTimer()
    showAppToast((e as Error).message, { icon: 'none', duration: 2500 })
  }
}

async function stopVideoRecording() {
  clearRecordMaxTimer()
  const shouldStop = recordingVideo.value || recordIntent
  recordIntent = false
  if (!shouldStop) {
    return
  }
  const wasRecording = recordingVideo.value
  recordingVideo.value = false
  setPreviewChromeRecording(false).catch(() => undefined)
  if (!wasRecording) {
    // 仅 intent、原生可能还没 start 成功
    captureLongPressTriggered = false
    return
  }
  try {
    await stopCameraVideoRecord()
    showAppToast('视频已保存', { icon: 'success' })
  } catch (e) {
    const msg = (e as Error).message || ''
    // 幂等空 stop / 竞态：不当作失败打扰用户
    if (!/未在录制|photos:\/\/noop|超时/.test(msg)) {
      showAppToast(msg, { icon: 'none', duration: 2500 })
    }
  } finally {
    setTimeout(() => {
      captureLongPressTriggered = false
    }, 300)
  }
}

let switchCameraBusy = false
let switchCameraAt = 0

async function onSwitchCamera() {
  const now = Date.now()
  if (switchCameraBusy || now - switchCameraAt < 600) {
    return
  }
  switchCameraBusy = true
  switchCameraAt = now
  try {
    await switchCameraFacing()
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: 'none' })
  } finally {
    setTimeout(() => {
      switchCameraBusy = false
    }, 500)
  }
}
</script>

<style scoped src="./beauty.css"></style>
