<template>
  <view class="page" :style="pageStyle">
    <!-- #ifdef APP-PLUS -->
    <view class="preview-area" :style="previewAreaStyle">
      <view id="cameraHost" class="camera camera--hole" />
    </view>
    <view class="preview-touch-layer" :style="previewAreaStyle" @tap="onPreviewTap" />
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
import { ref, reactive, computed, nextTick, onBeforeUnmount, getCurrentInstance } from 'vue'
import {
  ALL_SLIDER_EFFECTS,
  BEAUTY_BASE_PARAMS,
  BEAUTY_SHAPE_EFFECTS,
  BEAUTY_SKIN_EFFECTS,
  DEFAULT_FILTER_ID,
  FILTER_LEVEL_ITEM,
  defaultSliderValue,
  getFilterLevelSliderUi,
  getFilterPresetById,
  migrateGlobalFilterLevel,
  setFilterLevelSliderUi,
  isBeautyParamAllowed,
  sliderToValue,
  valueToSlider,
  type BeautyPanelTab,
} from '@/config/beauty-effects'
import {
  captureCameraPhoto,
  startCameraVideoRecord,
  stopCameraVideoRecord,
  showAppToast,
  ensureCameraPermission,
  ensureMicrophonePermission,
  ensureStoragePermission,
  getDevicePerformanceLevel,
  getCachedDevicePerfLevel,
  getPreviewStats,
  getPreviewDiag,
  diagnoseNamaPlugin,
  hideCameraPreview,
  initNamaForBeauty,
  isNamaReady,
  pauseCameraPreview,
  resumeCameraPreview,
  setBeautyEnabled,
  setDualInput,
  setBeautyParam,
  setBeautyStringParam,
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
const activeTab = ref<BeautyPanelTab | null>('skin')
const selectedFilterId = ref(DEFAULT_FILTER_ID)
const selectedEffectKey = ref(BEAUTY_SKIN_EFFECTS[0].key)
const devicePerfLevel = ref(1)
const whiteningMode = ref<'global' | 'skin'>('global')
const statsLabel = ref('0.0.0')
const faceTracking = ref(-1)
const statsReady = ref(false)
const comparing = ref(false)
const recordingVideo = ref(false)
const dualInputEnabled = ref(true)
const selectedResolutionId = ref('720')
const windowHeight = ref(0)
const safeAreaTop = ref(0)
const safeAreaBottom = ref(0)

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

/** 原生 chrome / 美颜面板叠在预览上，H5 全屏占位 */
function layoutInsets() {
  const topPx = safeAreaTop.value
  return {
    topPx,
    headerPx: topPx,
    panelPx: 0,
    actionGapPx: 0,
    captureSlotPx: 0,
    previewShrinkPx: 0,
  }
}

const pageStyle = computed(() => {
  const wh = windowHeight.value
  if (wh <= 0) {
    return { height: '100vh' }
  }
  return { height: `${wh}px` }
})

const previewAreaStyle = computed(() => {
  const { headerPx } = layoutInsets()
  return {
    top: `${headerPx}px`,
    bottom: '0px',
  }
})

function isEffectDisabled(item: { key: string; unimplemented?: boolean }) {
  if (item.unimplemented) {
    return true
  }
  return !isBeautyParamAllowed(item.key, devicePerfLevel.value)
}

let cameraMounted = false
let cameraMounting = false
let pageReady = false
let pendingDestroy: Promise<void> = Promise.resolve()
let needsCameraRemount = false
let pageWasHidden = false
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
        box.width = ww
        if (isIOSPlatform()) {
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

  try {
    const info = (await showCameraPreview(box)) as Record<string, unknown>
    const cameraError = String(info.cameraError || '')
    if (cameraError) {
      throw new Error(cameraError)
    }

    cameraMounted = true
    statsReady.value = false
    faceTracking.value = -1
    startStatsPoll()
    await showPreviewChrome(chromeBoxOpts(box)).catch(() => undefined)
    bindPreviewChromeEvents()
    await mountNativeBeautyPanel().catch(() => undefined)
    syncBeautyAfterCameraMount().catch(() => undefined)
  } catch (e) {
    let detail = (e as Error).message
    try {
      const diag = await getPreviewDiag()
      if (diag.diag) {
        detail = `${detail}\n${diag.diag}`
      }
    } catch {
      // ignore
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

ALL_SLIDER_EFFECTS.forEach((item) => {
  sliderValues[item.key] = defaultSliderValue(item)
})
migrateGlobalFilterLevel(sliderValues, DEFAULT_FILTER_ID)

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
    if (item.unimplemented || item.key === 'filter_level') {
      continue
    }
    if (isEffectDisabled(item)) {
      await setBeautyParam(item.key, item.min ?? 0)
      continue
    }
    await setBeautyParam(item.key, sliderToValue(sliderValues[item.key], item))
  }
  await setBeautyParam(
    'filter_level',
    sliderToValue(getFilterLevelSliderUi(sliderValues, selectedFilterId.value), FILTER_LEVEL_ITEM),
  )
}

async function syncBeautyAfterCameraMount() {
  if (comparing.value) {
    return
  }
  await setBeautyEnabled(true)
  await applyBeautyParamsToSdk()
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
      const resText = fw > 0 && fh > 0 ? `${fw}*${fh}` : String(resolution || '-')
      statsLabel.value = `${resText}.${fps}.${renderTime}`
      if (stats.frameWidth > 0 && stats.fps >= 0) {
        statsReady.value = true
      }
      if (stats.tracking >= 0) {
        faceTracking.value = stats.tracking
      }
      updatePreviewChromeStats({
        resolution: resText,
        fps,
        renderTime,
      }).catch(() => undefined)
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
      await initNamaForBeauty()
      if (!isNamaReady()) {
        throw new Error('美颜 bundle 未加载成功')
      }
      return
    } catch (e) {
      lastErr = e instanceof Error ? e : new Error(String(e))
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
  pageWasHidden = false
  initReady.value = false
  selectedResolutionId.value = '720'
  resetPreviewResolution().catch(() => undefined)

  const pluginDiag = diagnoseNamaPlugin()
  if (!pluginDiag.ok) {
    uni.showToast({ title: pluginDiag.detail, icon: 'none', duration: 3500 })
    return
  }

  try {
    await pendingDestroy
    await ensureCameraPermission()
    try {
      await ensureMicrophonePermission()
    } catch {
      // ignore
    }
    try {
      await ensureStoragePermission()
    } catch {
      // ignore
    }
    await initBeautyPipelineWithRetry()

    if (!isNamaReady()) {
      throw new Error('美颜 bundle 未加载成功')
    }

    const perf = await getDevicePerformanceLevel().catch(() => ({ level: getCachedDevicePerfLevel() }))
    devicePerfLevel.value = Math.max(-1, Math.min(4, Number(perf?.level) || getCachedDevicePerfLevel() || 1))
    if (devicePerfLevel.value < 4 && whiteningMode.value === 'skin') {
      whiteningMode.value = 'global'
    }

    initReady.value = true
    tryStartCamera()
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: 'none' })
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
    pageWasHidden = false
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
        // ignore
      }
      startStatsPoll()
      const box = await measurePreviewRect()
      await showPreviewChrome(chromeBoxOpts(box)).catch(() => undefined)
      bindPreviewChromeEvents()
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
  pageWasHidden = true
  stopStatsPoll()
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
  // 安卓：softHide 卸 ZOrder，避免老机相机 Surface 盖住下一页；iOS pause 即可
  if (isIOSPlatform()) {
    pauseCameraPreview().catch(() => undefined)
  } else {
    hideCameraPreview().catch(() => undefined)
  }
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
  unbindPreviewChromeEvents()
  unbindBeautyPanelEvents()
  hideFocusChrome().catch(() => undefined)
  hidePreviewChrome().catch(() => undefined)
  hideBeautyPanel().catch(() => undefined)
  pendingDestroy = hideCameraPreview()
    .then(() => undefined)
    .catch(() => undefined)
})

onBeforeUnmount(() => {
  stopStatsPoll()
})

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
  if (!initReady.value) {
    return
  }
  bindBeautyPanelEvents()
  const cfg = buildBeautyPanelConfig({
    mode: 'camera',
    values: { ...sliderValues },
    filterId: selectedFilterId.value,
    whiteningMode: whiteningMode.value,
    selectedKey: selectedEffectKey.value,
    devicePerfLevel: devicePerfLevel.value,
  })
  await showBeautyPanel(cfg)
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
      if (key === 'filter_level') {
        setFilterLevelSliderUi(sliderValues, selectedFilterId.value, valueToSlider(sdk, item))
      } else {
        sliderValues[key] = valueToSlider(sdk, item)
      }
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
    whiteningMode.value = String(e.mode || 'global') === 'skin' ? 'skin' : 'global'
    return
  }
  if (action === 'recover') {
    const tab = String(e.tab || '')
    if (tab === 'skin' || tab === 'shape') {
      activeTab.value = tab
      if (Number(e.confirmed) === 1 || e.confirmed === true) {
        void syncRestoreStateFromDefaults()
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
  }
}

function goHome() {
  uni.navigateBack({
    fail: () => {
      uni.reLaunch({ url: '/pages/index/index' })
    },
  })
}

function setInputMode(dual: boolean) {
  if (dualInputEnabled.value === dual) {
    return
  }
  dualInputEnabled.value = dual
  setDualInput(dual).catch(() => undefined)
}

function onPreviewTap(e: { detail: { x: number; y: number } }) {
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
      tapFocusAt(nx, ny).catch(() => undefined)
    })
    .exec()
}

async function onSelectResolution(preset: PreviewResolutionPreset) {
  selectedResolutionId.value = preset.id
  try {
    await setPreviewResolution(preset.width, preset.height)
    statsLabel.value = `${preset.label}.0.0`
  } catch (e) {
    uni.showToast({ title: (e as Error).message, icon: 'none' })
  }
}

async function goImportMedia() {
  hideFocusChrome().catch(() => undefined)
  hidePreviewChrome().catch(() => undefined)
  needsCameraRemount = true
  if (isIOSPlatform()) {
    // iOS：先跳转，再异步藏叠层，避免 await hide 卡住进选择页
    uni.navigateTo({ url: '/pages/media-import/media-import' })
    hideBeautyPanel().catch(() => undefined)
    setOverlayWindowsHidden(true).catch(() => undefined)
    pauseCameraPreview().catch(() => undefined)
    hideCameraPreview().catch(() => undefined)
    return
  }
  // 安卓：先藏/拆相机层，避免 Surface 盖住导入页
  try {
    await hideCameraPreview()
  } catch {
    setOverlayWindowsHidden(true).catch(() => undefined)
    pauseCameraPreview().catch(() => undefined)
  }
  uni.navigateTo({ url: '/pages/media-import/media-import' })
}

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
  updateBeautyPanelValues({
    values: { ...sliderValues },
    whiteningMode: whiteningMode.value,
    selectedKey: selectedEffectKey.value,
  }).catch(() => undefined)
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
let recordIntent = false
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
      // ignore
    }
    await startCameraVideoRecord()
    if (!recordIntent) {
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
    captureLongPressTriggered = false
    return
  }
  try {
    await stopCameraVideoRecord()
    showAppToast('视频已保存', { icon: 'success' })
  } catch (e) {
    const msg = (e as Error).message || ''
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
