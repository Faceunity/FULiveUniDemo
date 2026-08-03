<template>
  <view class="page" :style="pageStyle">
    <!-- #ifdef APP-PLUS -->
    <view v-if="!useNativeBeautyPanel" class="header-bar" :style="headerBarStyle">
      <view class="top-bar">
        <view class="top-bar__left">
          <view class="home-btn" @click="goBack">
            <image class="home-btn__img" src="/static/global-icons/home.svg" mode="aspectFit" />
          </view>
        </view>
      </view>
    </view>

    <view id="mediaPreviewHost" class="preview-area" :style="previewAreaStyle" @tap="onPreviewTap">
      <image
        v-if="mediaType === 'image' && displayPath"
        class="preview-image"
        :src="displayPath"
        mode="aspectFit"
      />
      <!-- 视频由原生 BeautyVideoView 叠层；中心播放钮也在原生层 -->
      <view v-else-if="mediaType === 'video'" class="preview-video-placeholder" />
    </view>

    <view v-if="initReady && !useNativeBeautyPanel" class="floating-actions" :style="floatingActionsStyle">
      <view
        v-if="panelExpanded"
        class="compare-btn"
        @touchstart.prevent="onCompareStart"
        @touchend.prevent="onCompareEnd"
        @touchcancel.prevent="onCompareEnd"
      >
        <image class="compare-btn__img" src="/static/global-icons/compare.png" mode="aspectFit" />
      </view>
      <view class="download-btn" :class="{ 'download-btn--disabled': processing && mediaType === 'video' }" @click="onSave">
        <image class="download-btn__img" src="/static/global-icons/download.png" mode="aspectFit" />
      </view>
      <view v-if="processing && mediaType === 'video'" class="export-progress" @tap.stop>
        <text class="export-progress__text">{{ processingText }}</text>
      </view>
    </view>
    <view
      v-if="useNativeBeautyPanel && processing && mediaType === 'video'"
      class="export-progress export-progress--native"
      @tap.stop
    >
      <text class="export-progress__text">{{ processingText }}</text>
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
              :value="displaySliderValue"
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
    <view class="placeholder">
      <text>请在 App 自定义基座中打开</text>
    </view>
    <!-- #endif -->
  </view>
</template>

<script setup lang="ts">
import { onLoad, onUnload } from '@dcloudio/uni-app'
import { ref, reactive, computed, nextTick, getCurrentInstance, watch } from 'vue'
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
  destroyVideoPreview,
  pauseCameraPreview,
  ensureStoragePermission,
  getDevicePerformanceLevel,
  initNamaForMedia,
  isMediaReusingCameraSession,
  isNamaReady,
  pauseVideoPreview,
  processStillImage,
  processStillVideo,
  releaseMediaBeautyHandle,
  resumeVideoPreview,
  setBeautyEnabled,
  setBeautyParam,
  setBeautyStringParam,
  setNamaPipeline,
  setOverlayWindowsHidden,
  showVideoPreview,
  probeNativeBeautyPanel,
  buildBeautyPanelConfig,
  showBeautyPanel,
  hideBeautyPanel,
  updateBeautyPanelValues,
  showAppConfirm,
} from '@/utils/nama-app'
import { takePendingMedia } from '@/utils/pending-media'
import {
  applyStatusBarStyle,
  hideNativeTitleNView,
  applyTransparentWebViewStyle,
} from '@/utils/app-plus-style'

/** ?????? beauty ???? */
const HEADER_VH = 5
const TAB_BAR_VH = 5
const PANEL_BODY_VH = 13
const ACTION_GAP_VH = 1
const CAPTURE_SLOT_VH = 6.5
/** ????????????????????? */
const CAPTURE_BTN_EXPAND_NUDGE_PX = 14
const IOS_PREVIEW_SHRINK_VH = 6

const sliderValues = reactive<Record<string, number>>({})
const initReady = ref(false)
/** null = ??? Tab */
const activeTab = ref<BeautyPanelTab | null>(null)
const selectedFilterId = ref(DEFAULT_FILTER_ID)
const selectedEffectKey = ref(BEAUTY_SKIN_EFFECTS[0].key)
const scrollIntoViewId = ref('')
const devicePerfLevel = ref(1)
/** ????? / ???(enable_skinseg) */
const whiteningMode = ref<'global' | 'skin'>('global')
const restoreConfirmVisible = ref(false)
const restoreConfirmTabLabel = ref('美肤')
const showWhiteningMode = computed(
  () => activeTab.value === 'skin' && selectedEffectKey.value === 'color_level_mode2',
)
const comparing = ref(false)
const processing = ref(false)
const processingText = ref('导出中')
const mediaType = ref<'image' | 'video'>('image')
const mediaPath = ref('')
const beautyPath = ref('')
const windowHeight = ref(0)
const safeAreaTop = ref(0)
/** iOS??????????? Home Indicator */
const safeAreaBottom = ref(0)
const videoPlaying = ref(false)
const videoMounted = ref(false)
const sliderDragging = ref(false)

let processSeq = 0
/** ??????????? UI */
let imageBusy = false
/** ????????????????? */
let processQueued = false
let rawLocalPath = ''
let sliderHideTimer: ReturnType<typeof setTimeout> | null = null

ALL_SLIDER_EFFECTS.forEach((item) => {
  sliderValues[item.key] = defaultSliderValue(item)
})

const panelExpanded = computed(() => activeTab.value !== null)

/** iOS 试点：原生美颜面板；安卓继续 Vue */
const useNativeBeautyPanel = ref(false)
try {
  useNativeBeautyPanel.value = probeNativeBeautyPanel()
} catch {
  useNativeBeautyPanel.value = false
}

function isIOSPlatform() {
  const sys = uni.getSystemInfoSync()
  return sys.platform === 'ios' || (sys as UniApp.GetSystemInfoResult & { osName?: string }).osName === 'ios'
}

function syncLayoutMetrics() {
  const sys = uni.getSystemInfoSync()
  windowHeight.value = Math.round(sys.windowHeight)
  if (isIOSPlatform()) {
    safeAreaTop.value = 0
    const bottom = sys.safeAreaInsets?.bottom
    const base = typeof bottom === 'number' && bottom > 0 ? Math.round(bottom) : 10
    safeAreaBottom.value = base + 28
    return
  }
  safeAreaBottom.value = 0
  const bottom = sys.safeAreaInsets?.bottom
  if (typeof bottom === 'number' && bottom > 0) {
    safeAreaBottom.value = Math.round(bottom)
  } else {
    // 手势条机型 safeAreaInsets.bottom 常为 0：给底栏留出导航区
    const winH = Math.round(sys.windowHeight || 0)
    const screenH = Math.round(sys.screenHeight || 0)
    const gap = screenH > winH ? screenH - winH : 0
    safeAreaBottom.value = gap > 0 ? Math.min(gap, 48) : 16
  }
  const top = sys.safeAreaInsets?.top
  safeAreaBottom.value = Math.max(safeAreaBottom.value, 16)
  safeAreaTop.value =
    typeof top === 'number' && top > 0
      ? Math.round(top)
      : Math.round(sys.statusBarHeight || 0)
}

function vhToPx(vh: number) {
  const wh = windowHeight.value || uni.getSystemInfoSync().windowHeight
  return Math.round(wh * (vh / 100))
}

function layoutInsets() {
  const topPx = safeAreaTop.value
  const bottomInsetPx = safeAreaBottom.value
  const headerContentPx = vhToPx(HEADER_VH)
  const headerPx = topPx + headerContentPx
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
  const panelBodyPx = panelExpanded.value ? vhToPx(PANEL_BODY_VH) : 0
  const panelPx = tabBarPx + panelBodyPx + bottomInsetPx
  const actionGapPx = vhToPx(ACTION_GAP_VH)
  const captureSlotPx = vhToPx(CAPTURE_SLOT_VH)
  const previewShrinkPx = isIOSPlatform() ? vhToPx(IOS_PREVIEW_SHRINK_VH) : 0
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

const displayPath = computed(() => {
  if (mediaType.value !== 'image') {
    return mediaPath.value
  }
  if (comparing.value) {
    return mediaPath.value
  }
  return beautyPath.value || mediaPath.value
})

const pageStyle = computed(() => {
  const { previewShrinkPx } = layoutInsets()
  const wh = windowHeight.value
  if (wh <= 0) {
    return { height: '100vh' }
  }
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
  return {
    top: `${headerPx}px`,
    bottom: `${panelPx + actionGapPx + captureSlotPx}px`,
  }
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
  return !rulerItem.value.unimplemented
})

const rulerSliderMin = computed(() => getSliderMin(rulerItem.value))
const rulerSliderMax = computed(() => getSliderMax(rulerItem.value))
const rulerIsBipolar = computed(() => isBidirectionalSlider(rulerItem.value))

/** ?????????? :value ? native ??? */
const dragSliderValue = ref<number | null>(null)

const displaySliderValue = computed(() => {
  if (dragSliderValue.value != null) {
    return dragSliderValue.value
  }
  return sliderValues[rulerItem.value.key] ?? 0
})

const sliderDisplayValue = computed(() => {
  const item = rulerItem.value
  const def = defaultSliderValue(item)
  const v = Math.round(displaySliderValue.value ?? def)
  if (!rulerIsBipolar.value) {
    return String(v)
  }
  const offset = Math.round(v - getSliderZero(item))
  if (offset > 0) {
    return `+${offset}`
  }
  return String(offset)
})

const sliderTipStyle = computed(() => {
  const min = rulerSliderMin.value
  const max = rulerSliderMax.value
  const v = Number(displaySliderValue.value)
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
  const v = Number(displaySliderValue.value ?? center)
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

/** ?? setParam???????? */
let beautyParamChain: Promise<void> = Promise.resolve()
let latestParamPayload: { item: BeautyEffectItem; slider: number; value: number } | null = null
let paramFlushScheduled = false

function toDisplayablePath(path: string) {
  if (!path) {
    return ''
  }
  if (path.startsWith('file://') || path.startsWith('content://') || path.startsWith('http')) {
    return path
  }
  return `file://${path}`
}

function stripFileScheme(path: string) {
  return path.startsWith('file://') ? path.slice(7) : path
}

function collapsePanel() {
  if (comparing.value) {
    comparing.value = false
    if (mediaType.value === 'video') {
      setBeautyEnabled(true).catch(() => undefined)
    }
  }
  activeTab.value = null
}

function onPreviewTap() {
  if (panelExpanded.value) {
    collapsePanel()
  }
}

function onTabChange(tab: BeautyPanelTab) {
  if (activeTab.value === tab) {
    collapsePanel()
    return
  }
  activeTab.value = tab
  if (tab === 'filter') {
    if (selectedFilterId.value === FILTER_ORIGIN_PRESET.id) {
      scrollIntoViewId.value = ''
      return
    }
    scrollIntoViewId.value = `icon-${selectedFilterId.value}`
    return
  }
  const list = tab === 'shape' ? BEAUTY_SHAPE_EFFECTS : BEAUTY_SKIN_EFFECTS
  if (!list.some((i) => i.key === selectedEffectKey.value)) {
    selectedEffectKey.value = list[0].key
  }
  scrollIntoViewId.value = `icon-${selectedEffectKey.value}`
}

/** 仅恢复当前 Tab（美肤/美型）内功能到默认值 */
async function onRestoreTabDefaults() {
  if (!initReady.value || activeTab.value === 'filter' || activeTab.value === null) {
    return
  }
  const tabLabel = activeTab.value === 'shape' ? '美型' : '美肤'
  restoreConfirmTabLabel.value = tabLabel
  // 原生面板：确认与写参由原生完成
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
  if (mediaType.value === 'image') {
    scheduleProcessImage()
  }
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
  for (const item of list) {
    sliderValues[item.key] = defaultSliderValue(item)
  }
  if (activeTab.value === 'skin') {
    whiteningMode.value = 'global'
  }
  selectedEffectKey.value = list.find((i) => !i.unimplemented)?.key || list[0].key
  scrollIntoViewId.value = `icon-${selectedEffectKey.value}`
  try {
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
    if (mediaType.value === 'image') {
      scheduleProcessImage()
    }
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: 'none' })
  }
}

function onSelectEffect(item: BeautyEffectItem) {
  if (item.unimplemented) {
    uni.showToast({
      title: `${item.name} 当前机型不支持`,
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

function isEffectDisabled(item: BeautyEffectItem) {
  if (item.unimplemented) {
    return true
  }
  return !isBeautyParamAllowed(item.key, devicePerfLevel.value)
}

const canUseSkinWhitening = computed(() => devicePerfLevel.value >= 4)

async function setWhiteningMode(mode: 'global' | 'skin') {
  if (mode === 'skin' && !canUseSkinWhitening.value) {
    uni.showToast({
      title: '当前机型不支持仅皮肤美白',
      icon: 'none',
      duration: 1800,
    })
    return
  }
  whiteningMode.value = mode
  if (!initReady.value) {
    return
  }
  try {
    await setBeautyParam('enable_skinseg', mode === 'skin' ? 1 : 0)
    const item = BEAUTY_SKIN_EFFECTS.find((i) => i.key === 'color_level_mode2')
    if (item) {
      const slider = sliderValues[item.key] ?? defaultSliderValue(item)
      await setBeautyParam('color_level_mode2', sliderToValue(slider, item))
    }
    if (mediaType.value === 'image') {
      scheduleProcessImage()
    }
  } catch {
    // ignore
  }
}

function applySliderValue(slider: number, commit: boolean) {
  const item = rulerItem.value
  const sMin = getSliderMin(item)
  const sMax = getSliderMax(item)
  const clamped = Math.min(sMax, Math.max(sMin, Math.round(slider)))
  dragSliderValue.value = clamped
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

/** ???process ??????? */
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
  dragSliderValue.value = null
  if (sliderHideTimer) {
    clearTimeout(sliderHideTimer)
  }
  sliderHideTimer = setTimeout(() => {
    sliderDragging.value = false
    sliderHideTimer = null
  }, 400)
}

function queueBeautyChange(payload: { item: BeautyEffectItem; slider: number; value: number }) {
  latestParamPayload = payload
  if (paramFlushScheduled) {
    return
  }
  paramFlushScheduled = true
  beautyParamChain = beautyParamChain
    .then(async () => {
      while (latestParamPayload) {
        const next = latestParamPayload
        latestParamPayload = null
        await ensureMediaPipelineReady()
        if (BEAUTY_SHAPE_EFFECTS.some((i) => i.key === next.item.key)) {
          await setBeautyParam('face_shape', 4)
          await setBeautyParam('face_shape_level', 1)
        }
        await setBeautyParam(next.item.key, next.value)
        if (mediaType.value === 'image') {
          scheduleProcessImage()
        }
      }
    })
    .catch((e) => {
      const msg = (e as Error).message
      if (msg.includes('loadBundle')) {
        return
      }
      uni.showToast({ title: msg, icon: 'none' })
    })
    .finally(() => {
      paramFlushScheduled = false
      if (latestParamPayload) {
        queueBeautyChange(latestParamPayload)
      }
    })
}

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
    if (item.unimplemented || isEffectDisabled(item)) {
      if (!item.unimplemented) {
        await setBeautyParam(item.key, item.min ?? 0)
      }
      continue
    }
    await setBeautyParam(item.key, sliderToValue(sliderValues[item.key], item))
  }
}

/** ????? process/deviceLost ???? EGL */
async function ensureMediaPipelineReady() {
  setNamaPipeline('media')
  if (!isNamaReady()) {
    await initNamaForMedia()
    if (!isMediaReusingCameraSession()) {
      await applyBeautyParamsToSdk()
    }
  }
}

/** ?????????? */
function scheduleProcessImage() {
  if (mediaType.value !== 'image' || !mediaPath.value || !initReady.value) {
    return
  }
  if (imageBusy) {
    processQueued = true
    return
  }
  runProcessImage().catch(() => undefined)
}

async function runProcessImage() {
  if (mediaType.value !== 'image' || !mediaPath.value) {
    return
  }
  const seq = ++processSeq
  imageBusy = true
  try {
    await ensureMediaPipelineReady()
    const res = await processStillImage(stripFileScheme(mediaPath.value))
    if (seq !== processSeq) {
      return
    }
    beautyPath.value = toDisplayablePath(res.path)
  } catch (e) {
    if (seq !== processSeq) {
      return
    }
    uni.showToast({ title: (e as Error).message.slice(0, 80), icon: 'none', duration: 3000 })
  } finally {
    if (seq === processSeq) {
      imageBusy = false
      if (processQueued) {
        processQueued = false
        scheduleProcessImage()
      }
    }
  }
}

function measurePreviewRect() {
  const { headerPx, panelPx, actionGapPx, captureSlotPx } = layoutInsets()
  const sys = uni.getSystemInfoSync()
  const wh = Math.round(sys.windowHeight)
  const ww = Math.round(sys.windowWidth)
  const fallback = {
    x: 0,
    y: headerPx,
    width: ww,
    height: Math.max(wh - headerPx - panelPx - actionGapPx - captureSlotPx, 32),
  }
  return new Promise<{ x: number; y: number; width: number; height: number }>((resolve) => {
    const query = uni.createSelectorQuery()
    const instance = getCurrentInstance()
    if (instance) {
      query.in(instance)
    }
    query
      .select('#mediaPreviewHost')
      .boundingClientRect((rect) => {
        if (!rect || Array.isArray(rect) || !rect.width || !rect.height) {
          resolve(fallback)
          return
        }
        resolve({
          x: Math.round(rect.left ?? 0),
          y: Math.round(rect.top ?? fallback.y),
          width: Math.round(rect.width),
          height: Math.round(rect.height),
        })
      })
      .exec()
  })
}

async function mountVideoPreviewOverlay() {
  if (mediaType.value !== 'video' || !rawLocalPath) {
    return
  }
  // ? offscreen ??? GL?destroyVideoPreview idle ??
  if (videoMounted.value) {
    await destroyVideoPreview().catch(() => undefined)
  }
  
  videoMounted.value = false
  await ensureMediaPipelineReady()
  await nextTick()
  const box = await measurePreviewRect()
  await showVideoPreview({
    path: rawLocalPath,
    x: box.x,
    y: box.y,
    width: box.width,
    height: box.height,
  })
  videoMounted.value = true
  videoPlaying.value = false
  bindNamaVideoEvents()
}

function onNamaVideoEvent(e: Record<string, unknown>) {
  const action = String(e?.action || '')
  if (action === 'playing') {
    videoPlaying.value = true
    return
  }
  if (action === 'paused' || action === 'ended') {
    videoPlaying.value = false
  }
}

let namaVideoListening = false
function bindNamaVideoEvents() {
  if (namaVideoListening) {
    return
  }
  // #ifdef APP-PLUS
  try {
    plus.globalEvent.addEventListener('namaVideo', onNamaVideoEvent as any)
    namaVideoListening = true
  } catch {
    // ignore
  }
  // #endif
}

function unbindNamaVideoEvents() {
  if (!namaVideoListening) {
    return
  }
  // #ifdef APP-PLUS
  try {
    plus.globalEvent.removeEventListener('namaVideo', onNamaVideoEvent as any)
  } catch {
    // ignore
  }
  // #endif
  namaVideoListening = false
}

async function remountVideoForLayout() {
  if (mediaType.value !== 'video' || !videoMounted.value || !initReady.value) {
    return
  }
  await nextTick()
  try {
    // ?????? destroy/invalidate?????? loadAI/loadBundle
    const box = await measurePreviewRect()
    await showVideoPreview({
      path: rawLocalPath,
      x: box.x,
      y: box.y,
      width: box.width,
      height: box.height,
    })
  } catch {
    // ignore layout resize failures
  }
}

watch(panelExpanded, () => {
  // 原生面板叠在预览上，收起/展开不改 preview host 尺寸；重挂载视频会闪一下
  if (useNativeBeautyPanel.value) {
    return
  }
  remountVideoForLayout().catch(() => undefined)
})

async function onTogglePlay() {
  if (!videoMounted.value) {
    return
  }
  try {
    if (videoPlaying.value) {
      await pauseVideoPreview()
      videoPlaying.value = false
    } else {
      await resumeVideoPreview()
      videoPlaying.value = true
    }
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
    } else {
      const level = sliderToValue(sliderValues.filter_level, FILTER_LEVEL_ITEM)
      if (level <= 0) {
        sliderValues.filter_level = defaultSliderValue(FILTER_LEVEL_ITEM)
        await setBeautyParam('filter_level', FILTER_LEVEL_ITEM.default)
      } else {
        await setBeautyParam('filter_level', level)
      }
    }
    if (mediaType.value === 'image') {
      scheduleProcessImage()
    }
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: 'none' })
  }
}

async function onCompareStart() {
  comparing.value = true
  try {
    await setBeautyEnabled(false)
    await setBeautyParam('is_beauty_on', 0)
  } catch {
    // ignore
  }
}

async function onCompareEnd() {
  comparing.value = false
  try {
    await setBeautyEnabled(true)
    await setBeautyParam('is_beauty_on', 1)
  } catch {
    // ignore
  }
}

async function onSave() {
  if (processing.value) {
    return
  }
  try {
    await ensureStoragePermission()
    if (mediaType.value === 'video') {
      processing.value = true
      processingText.value = '导出中'
      const wasPlaying = videoPlaying.value
      if (wasPlaying) {
        try {
          await pauseVideoPreview()
          videoPlaying.value = false
        } catch {
          // ignore
        }
      }
      const res = await processStillVideo(rawLocalPath || stripFileScheme(mediaPath.value))
      await new Promise<void>((resolve, reject) => {
        uni.saveVideoToPhotosAlbum({
          filePath: toDisplayablePath(res.path),
          success: () => resolve(),
          fail: (err) => reject(new Error((err as { errMsg?: string }).errMsg || '保存失败')),
        })
      })
      uni.showToast({ title: '已保存到相册', icon: 'success' })
      videoPlaying.value = false
      try {
        await setOverlayWindowsHidden(false).catch(() => undefined)
        // 导出走冻帧，解码器仍在；回到暂停 + 中心播放钮
        await pauseVideoPreview()
      } catch (e) {
        console.warn('resume after save failed', e)
      }
      return
    }

    const path = beautyPath.value || mediaPath.value
    if (!path) {
      return
    }
    await new Promise<void>((resolve, reject) => {
      uni.saveImageToPhotosAlbum({
        filePath: path,
        success: () => resolve(),
        fail: (err) => reject(new Error((err as { errMsg?: string }).errMsg || '保存失败')),
      })
    })
    uni.showToast({ title: '已保存到相册', icon: 'success' })
  } catch (e) {
    uni.showToast({ title: (e as Error).message.slice(0, 80), icon: 'none', duration: 3000 })
  } finally {
    processing.value = false
  }
}

function goBack() {
  uni.navigateBack()
}

let mediaBeautyPanelListening = false
let mediaBeautyPanelHandler: ((e: Record<string, unknown>) => void) | null = null

function mediaBeautyPanelDispatcher(e: Record<string, unknown>) {
  mediaBeautyPanelHandler?.(e)
}

function bindMediaBeautyPanelEvents() {
  mediaBeautyPanelHandler = onMediaBeautyPanelEvent
  if (mediaBeautyPanelListening) {
    return
  }
  // #ifdef APP-PLUS
  try {
    plus.globalEvent.addEventListener('namaBeautyPanel', mediaBeautyPanelDispatcher as any)
    mediaBeautyPanelListening = true
  } catch {
    // ignore
  }
  // #endif
}

function unbindMediaBeautyPanelEvents() {
  mediaBeautyPanelHandler = null
  if (!mediaBeautyPanelListening) {
    return
  }
  // #ifdef APP-PLUS
  try {
    plus.globalEvent.removeEventListener('namaBeautyPanel', mediaBeautyPanelDispatcher as any)
  } catch {
    // ignore
  }
  // #endif
  mediaBeautyPanelListening = false
}

async function mountNativeBeautyPanel() {
  if (!useNativeBeautyPanel.value || !initReady.value) {
    return
  }
  bindMediaBeautyPanelEvents()
  if (!activeTab.value) {
    activeTab.value = 'skin'
  }
  const mode = mediaType.value === 'video' ? 'video' : 'image'
  const cfg = buildBeautyPanelConfig({
    mode,
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

function onMediaBeautyPanelEvent(e: Record<string, unknown>) {
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
    if (key) selectedEffectKey.value = key
    return
  }
  if (action === 'slider') {
    const key = String(e.key || '')
    const sdk = Number(e.value)
    if (!key || Number.isNaN(sdk)) return
    const item =
      ALL_SLIDER_EFFECTS.find((i) => i.key === key) ||
      (key === 'filter_level' ? FILTER_LEVEL_ITEM : null)
    if (item) {
      sliderValues[key] = valueToSlider(sdk, item)
      if (mediaType.value === 'image') {
        scheduleProcessImage()
      }
    }
    return
  }
  if (action === 'filter') {
    const id = String(e.id || e.key || '')
    if (id) {
      selectedFilterId.value = id
      if (mediaType.value === 'image') {
        scheduleProcessImage()
      }
    }
    return
  }
  if (action === 'whiteningMode') {
    whiteningMode.value = String(e.mode || 'global') === 'skin' ? 'skin' : 'global'
    if (mediaType.value === 'image') {
      scheduleProcessImage()
    }
    return
  }
  if (action === 'recover') {
    const tab = String(e.tab || '')
    if (tab === 'skin' || tab === 'shape') {
      activeTab.value = tab
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
    return
  }
  if (action === 'save') {
    onSave()
    return
  }
  if (action === 'back') {
    goBack()
  }
}

onLoad(async (query) => {
  // #ifndef APP-PLUS
  return
  // #endif

  // #ifdef APP-PLUS
  syncLayoutMetrics()
  hideNativeTitleNView()
  applyTransparentWebViewStyle()
  applyStatusBarStyle()

  const pending = takePendingMedia()
  const typeFromQuery = String(query?.type || '')
  const type = pending?.type || (typeFromQuery === 'video' ? 'video' : 'image')
  mediaType.value = type === 'video' ? 'video' : 'image'
  const rawPath =
    pending?.path ||
    decodeURIComponent(String(query?.path || ''))
  rawLocalPath = stripFileScheme(rawPath)
  mediaPath.value = toDisplayablePath(rawPath)
  beautyPath.value = ''

  if (!rawPath) {
    uni.showToast({ title: '未找到媒体文件', icon: 'none' })
    return
  }

  initReady.value = true

  // ========== iOS：不阻塞首屏；初始化完成后挂原生面板 ==========
  if (isIOSPlatform()) {
    useNativeBeautyPanel.value = probeNativeBeautyPanel()
    void pauseCameraPreview().catch(() => undefined)
    void destroyVideoPreview().catch(() => undefined)
    void (async () => {
      try {
        setNamaPipeline('media')
        await initNamaForMedia()
        await setBeautyEnabled(true).catch(() => undefined)
        await setBeautyParam('is_beauty_on', 1).catch(() => undefined)
        if (!isMediaReusingCameraSession()) {
          await applyBeautyParamsToSdk().catch(() => undefined)
        }
        if (mediaType.value === 'image') {
          setOverlayWindowsHidden(false).catch(() => undefined)
          await runProcessImage().catch(() => undefined)
          if (!beautyPath.value) {
            await runProcessImage().catch(() => undefined)
          }
          await mountNativeBeautyPanel()
        } else {
          await mountVideoPreviewOverlay()
          setOverlayWindowsHidden(false).catch(() => undefined)
          await mountNativeBeautyPanel()
        }
      } catch (e) {
        uni.showToast({
          title: String((e as Error).message || e).slice(0, 100),
          icon: 'none',
          duration: 3500,
        })
      }
    })()
    return
  }

  // ========== Android：原生面板可用则全屏叠层，否则 Vue 底栏 ==========
  useNativeBeautyPanel.value = probeNativeBeautyPanel()
  await Promise.all([
    setOverlayWindowsHidden(true).catch(() => undefined),
    pauseCameraPreview().catch(() => undefined),
    destroyVideoPreview().catch(() => undefined),
  ])

  try {
    setNamaPipeline('media')
    const initP = initNamaForMedia()
    getDevicePerformanceLevel()
      .then((perf) => {
        devicePerfLevel.value = Math.max(-1, Math.min(4, Number(perf?.level) || 1))
        if (devicePerfLevel.value < 4 && whiteningMode.value === 'skin') {
          whiteningMode.value = 'global'
          setBeautyParam('enable_skinseg', 0).catch(() => undefined)
        }
        if (useNativeBeautyPanel.value) {
          mountNativeBeautyPanel().catch(() => undefined)
        }
      })
      .catch(() => {
        /* 保持默认；勿在失败时强行升到 4 */
      })

    await initP
    const reused = isMediaReusingCameraSession()
    const warmParams = reused
      ? Promise.resolve()
      : applyBeautyParamsToSdk().catch(() => undefined)
    if (mediaType.value === 'image') {
      setOverlayWindowsHidden(false).catch(() => undefined)
      await warmParams
      await runProcessImage().catch(() => undefined)
      await mountNativeBeautyPanel()
    } else {
      await warmParams
      await mountVideoPreviewOverlay()
      setOverlayWindowsHidden(false).catch(() => undefined)
      await mountNativeBeautyPanel()
    }
  } catch (e) {
    setOverlayWindowsHidden(false).catch(() => undefined)
    uni.showToast({ title: (e as Error).message.slice(0, 100), icon: 'none', duration: 3500 })
  }
  // #endif
})

onUnload(() => {
  latestParamPayload = null
  paramFlushScheduled = false
  if (sliderHideTimer) {
    clearTimeout(sliderHideTimer)
    sliderHideTimer = null
  }
  processSeq += 1
  imageBusy = false
  processQueued = false
  unbindMediaBeautyPanelEvents()
  unbindNamaVideoEvents()
  hideBeautyPanel().catch(() => undefined)
  destroyVideoPreview().catch(() => undefined)
  // ?? AI/bundle?????????
  setNamaPipeline('camera')
})
</script>

<style scoped src="./media-beauty.css"></style>
