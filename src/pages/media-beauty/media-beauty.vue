<template>
  <view class="page" :style="pageStyle">
    <!-- #ifdef APP-PLUS -->
    <view id="mediaPreviewHost" class="preview-area" :style="previewAreaStyle">
      <image
        v-if="mediaType === 'image' && displayPath"
        class="preview-image"
        :src="displayPath"
        mode="aspectFit"
      />
      <view v-else-if="mediaType === 'video'" class="preview-video-placeholder" />
    </view>

    <view
      v-if="processing && mediaType === 'video'"
      class="export-progress"
      @tap.stop
    >
      <text class="export-progress__text">{{ processingText }}</text>
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
import { onLoad, onUnload, onHide, onShow } from '@dcloudio/uni-app'
import { ref, reactive, computed, nextTick, getCurrentInstance } from 'vue'
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
  destroyVideoPreview,
  pauseCameraPreview,
  ensureStoragePermission,
  getDevicePerformanceLevel,
  getCachedDevicePerfLevel,
  initNamaForMedia,
  isMediaReusingCameraSession,
  isNamaReady,
  parkVideoForBackground,
  pauseVideoPreview,
  processStillImage,
  processStillVideo,
  resetVideoToIdle,
  setBeautyEnabled,
  setBeautyParam,
  setBeautyStringParam,
  setNamaPipeline,
  setOverlayWindowsHidden,
  showVideoPreview,
  buildBeautyPanelConfig,
  showBeautyPanel,
  hideBeautyPanel,
  updateBeautyPanelValues,
} from '@/utils/nama-app'
import { takePendingMedia } from '@/utils/pending-media'
import {
  applyStatusBarStyle,
  hideNativeTitleNView,
  applyTransparentWebViewStyle,
} from '@/utils/app-plus-style'

const sliderValues = reactive<Record<string, number>>({})
const initReady = ref(false)
const activeTab = ref<BeautyPanelTab | null>(null)
const selectedFilterId = ref(DEFAULT_FILTER_ID)
const selectedEffectKey = ref(BEAUTY_SKIN_EFFECTS[0].key)
const devicePerfLevel = ref(1)
const whiteningMode = ref<'global' | 'skin'>('global')
const comparing = ref(false)
const processing = ref(false)
const processingText = ref('导出中')
const mediaType = ref<'image' | 'video'>('image')
const mediaPath = ref('')
const beautyPath = ref('')
const windowHeight = ref(0)
const safeAreaTop = ref(0)
const safeAreaBottom = ref(0)
const videoPlaying = ref(false)
const videoMounted = ref(false)

let processSeq = 0
let imageBusy = false
let processQueued = false
let rawLocalPath = ''

ALL_SLIDER_EFFECTS.forEach((item) => {
  sliderValues[item.key] = defaultSliderValue(item)
})
migrateGlobalFilterLevel(sliderValues, DEFAULT_FILTER_ID)

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

function layoutInsets() {
  const topPx = safeAreaTop.value
  return {
    topPx,
    headerPx: topPx,
    panelPx: 0,
    actionGapPx: 0,
    captureSlotPx: 0,
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

function isEffectDisabled(item: { key: string; unimplemented?: boolean }) {
  if (item.unimplemented) {
    return true
  }
  return !isBeautyParamAllowed(item.key, devicePerfLevel.value)
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
    if (item.unimplemented || item.key === 'filter_level') {
      if (!item.unimplemented && item.key !== 'filter_level' && isEffectDisabled(item)) {
        await setBeautyParam(item.key, item.min ?? 0)
      }
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

async function ensureMediaPipelineReady() {
  setNamaPipeline('media')
  if (!isNamaReady()) {
    await initNamaForMedia()
    if (!isMediaReusingCameraSession()) {
      await applyBeautyParamsToSdk()
    }
  }
}

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

async function onCompareStart() {
  comparing.value = true
  try {
    await setBeautyEnabled(false)
    // 视频对比只切显示层；勿改 is_beauty_on，否则松手会跳变
    if (mediaType.value !== 'video') {
      await setBeautyParam('is_beauty_on', 0)
    }
  } catch {
    // ignore
  }
}

async function onCompareEnd() {
  comparing.value = false
  try {
    await setBeautyEnabled(true)
    if (mediaType.value !== 'video') {
      await setBeautyParam('is_beauty_on', 1)
    }
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
        await pauseVideoPreview()
      } catch {
        // ignore
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
    const msg = ((e as Error).message || '').trim()
    if (msg.includes('取消')) {
      uni.showToast({ title: '已取消导出', icon: 'none' })
    } else {
      uni.showToast({ title: msg.slice(0, 80), icon: 'none', duration: 3000 })
    }
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
  if (!initReady.value) {
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
    devicePerfLevel: devicePerfLevel.value,
  })
  await showBeautyPanel(cfg)
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
  if (mediaType.value === 'image') {
    scheduleProcessImage()
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

async function bootstrapMediaPage() {
  setNamaPipeline('media')

  // 安卓保持原串行路径，避免影响已验证行为；仅 iOS 走快速出面板
  if (!isIOSPlatform()) {
    await initNamaForMedia()
    const perf = await getDevicePerformanceLevel().catch(() => ({
      level: getCachedDevicePerfLevel(),
    }))
    devicePerfLevel.value = Math.max(
      -1,
      Math.min(4, Number(perf?.level) || getCachedDevicePerfLevel() || 1),
    )
    if (devicePerfLevel.value < 4 && whiteningMode.value === 'skin') {
      whiteningMode.value = 'global'
      setBeautyParam('enable_skinseg', 0).catch(() => undefined)
    }
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
    return
  }

  // —— iOS：先出返回/滑杆（对齐 1925fc1 快路径），init / 写参 / 出画后台跑 ——
  setOverlayWindowsHidden(false).catch(() => undefined)

  devicePerfLevel.value = Math.max(
    -1,
    Math.min(4, getCachedDevicePerfLevel() || devicePerfLevel.value || 1),
  )
  if (devicePerfLevel.value < 4 && whiteningMode.value === 'skin') {
    whiteningMode.value = 'global'
    setBeautyParam('enable_skinseg', 0).catch(() => undefined)
  }

  const initP = initNamaForMedia()

  // 勿 await init：面板立刻挂；原生 showBeautyPanel 会再查 SDK 档位
  await mountNativeBeautyPanel()

  getDevicePerformanceLevel()
    .then((perf) => {
      const level = Math.max(
        -1,
        Math.min(4, Number(perf?.level) || getCachedDevicePerfLevel() || 1),
      )
      devicePerfLevel.value = level
      if (level < 4 && whiteningMode.value === 'skin') {
        whiteningMode.value = 'global'
        setBeautyParam('enable_skinseg', 0).catch(() => undefined)
      }
      updateBeautyPanelValues({
        values: { ...sliderValues },
        whiteningMode: whiteningMode.value,
        selectedKey: selectedEffectKey.value,
      }).catch(() => undefined)
    })
    .catch(() => undefined)

  void initP
    .then(() => {
      const reused = isMediaReusingCameraSession()
      const warmParams = reused
        ? Promise.resolve()
        : applyBeautyParamsToSdk().catch(() => undefined)
      if (mediaType.value === 'image') {
        void warmParams.then(() => runProcessImage().catch(() => undefined))
      } else {
        void warmParams.then(() => mountVideoPreviewOverlay().catch(() => undefined))
      }
    })
    .catch(() => undefined)
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

  const cleanupOverlay = Promise.all([
    setOverlayWindowsHidden(true).catch(() => undefined),
    pauseCameraPreview().catch(() => undefined),
    destroyVideoPreview().catch(() => undefined),
  ])

  // iOS：清理叠层勿挡 bootstrap（否则选完图还要干等 pause/destroy）
  if (!isIOSPlatform()) {
    await cleanupOverlay
  } else {
    void cleanupOverlay
  }

  try {
    await bootstrapMediaPage()
  } catch (e) {
    setOverlayWindowsHidden(false).catch(() => undefined)
    uni.showToast({ title: (e as Error).message.slice(0, 100), icon: 'none', duration: 3500 })
  }
  // #endif
})

onHide(() => {
  // #ifdef APP-PLUS
  // 安卓：成对 GL onPause（Demo 同款），不拆 Nama
  if (!isIOSPlatform() && mediaType.value === 'video') {
    videoPlaying.value = false
    parkVideoForBackground().catch(() => undefined)
  }
  // #endif
})

onShow(() => {
  // #ifdef APP-PLUS
  // 回前台：首帧静帧 + Play（不 invalidate，避免回相机无美颜）
  if (!isIOSPlatform() && mediaType.value === 'video' && initReady.value && videoMounted.value) {
    videoPlaying.value = false
    resetVideoToIdle().catch(() => undefined)
  }
  // #endif
})

onUnload(() => {
  processSeq += 1
  imageBusy = false
  processQueued = false
  unbindMediaBeautyPanelEvents()
  unbindNamaVideoEvents()
  hideBeautyPanel().catch(() => undefined)
  destroyVideoPreview().catch(() => undefined)
  setNamaPipeline('camera')
})
</script>

<style scoped src="./media-beauty.css"></style>
