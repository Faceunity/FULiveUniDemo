/** FaceUnity Nama（APP-PLUS） */

import {
  androidNamaReinitReason,
  androidNamaRelaunchReason,
  shouldMarkAndroidNamaSessionDirty,
  shouldReinitAndroidNamaSession,
  type JsNamaSession,
  type NamaSessionProbe,
} from '@/utils/android-nama-session'
import {
  BEAUTY_BASE_PARAMS,
  BEAUTY_SHAPE_EFFECTS,
  BEAUTY_SKIN_EFFECTS,
  DEFAULT_FILTER_ID,
  FILTER_LEVEL_ITEM,
  FILTER_PRESETS,
  defaultSliderValue,
  getEffectIconSrc,
  getFilterIconUrl,
  getFilterPresetById,
  getSliderMax,
  getSliderMin,
  isBeautyParamAllowed,
  isBidirectionalSlider,
  type BeautyEffectItem,
} from '@/config/beauty-effects'

const PLUGIN_ID = 'FaceUnity-Nama'
const NAMA_BUNDLE_DIR = '/static/nama-bundle/'
const AI_BUNDLE_FILE = 'ai_face_processor.bundle'
const BEAUTY_BUNDLE_FILE = 'face_beautification.bundle'
/** 完整 face AI（含皮肤分割/祛斑/ARMesh/丰盈）通常 >20MB */
const AI_BUNDLE_MIN_BYTES = 18 * 1024 * 1024

type NamaResult<T = unknown> = { code: number; data?: T; message?: string }
type NamaCallback = (res: NamaResult) => void

type NamaPlugin = {
  getVersion: (cb: NamaCallback) => void
  isSdkAlive?: (cb: NamaCallback) => void
  resetStaleOverlays?: (cb: NamaCallback) => void
  relaunchProcess?: (cb: NamaCallback) => void
  init: (opts: Record<string, unknown>, cb: NamaCallback) => void
  loadAIModel: (opts: Record<string, unknown>, cb: NamaCallback) => void
  loadBundle: (opts: Record<string, unknown>, cb: NamaCallback) => void
  setParam: (opts: Record<string, unknown>, cb: NamaCallback) => void
  drainSdkLog?: (cb: NamaCallback) => void
  bindMediaBeautyHandle?: (opts: Record<string, unknown>, cb: NamaCallback) => void
  showCamera: (opts: Record<string, unknown>, cb: NamaCallback) => void
  resizeCameraPreview?: (opts: Record<string, unknown>, cb: NamaCallback) => void
  hideCamera: (opts: Record<string, unknown> | NamaCallback, cb?: NamaCallback) => void
  pauseCameraPreview?: (cb: NamaCallback) => void
  resumeCameraPreview?: (cb: NamaCallback) => void
  destroyCameraPreview?: (cb: NamaCallback) => void
  setOverlayWindowsHidden?: (opts: Record<string, unknown>, cb: NamaCallback) => void
  getPreviewDiag?: (cb: NamaCallback) => void
  getPreviewStats?: (cb: NamaCallback) => void
  setBeautyEnabled?: (opts: Record<string, unknown>, cb: NamaCallback) => void
  setDualInput?: (opts: Record<string, unknown>, cb: NamaCallback) => void
  switchCamera?: (cb: NamaCallback) => void
  getDevicePerformanceLevel?: (cb: NamaCallback) => void
  requestPermissions?: (cb: NamaCallback) => void
  tapFocus?: (opts: Record<string, unknown>, cb: NamaCallback) => void
  showPreviewChrome?: (opts: Record<string, unknown>, cb: NamaCallback) => void
  hidePreviewChrome?: (cb: NamaCallback) => void
  showBeautyPanel?: (opts: Record<string, unknown>, cb: NamaCallback) => void
  hideBeautyPanel?: (cb: NamaCallback) => void
  updateBeautyPanelValues?: (opts: Record<string, unknown>, cb: NamaCallback) => void
  setBeautyPanelMode?: (opts: Record<string, unknown>, cb: NamaCallback) => void
  updatePreviewChromeStats?: (opts: Record<string, unknown>, cb: NamaCallback) => void
  setPreviewChromeRecording?: (opts: Record<string, unknown>, cb: NamaCallback) => void
  setCameraExposure?: (opts: Record<string, unknown>, cb: NamaCallback) => void
  setExposureBias?: (opts: Record<string, unknown>, cb: NamaCallback) => void
  hideFocusHud?: (cb: NamaCallback) => void
  hideFocusChrome?: (cb: NamaCallback) => void
  capturePhoto?: (cb: NamaCallback) => void
  startVideoRecord?: (cb: NamaCallback) => void
  stopVideoRecord?: (cb: NamaCallback) => void
  showToast?: (opts: Record<string, unknown>, cb: NamaCallback) => void
  showConfirm?: (opts: Record<string, unknown>, cb: NamaCallback) => void
  setPreviewResolution?: (opts: Record<string, unknown>, cb: NamaCallback) => void
  resetPreviewResolution?: (cb: NamaCallback) => void
  processImage?: (opts: Record<string, unknown>, cb: NamaCallback) => void
  showVideoPreview?: (opts: Record<string, unknown>, cb: NamaCallback) => void
  pauseVideoPreview?: (cb: NamaCallback) => void
  parkVideoForBackground?: (cb: NamaCallback) => void
  resetVideoToIdle?: (cb: NamaCallback) => void
  resumeVideoPreview?: (cb: NamaCallback) => void
  destroyVideoPreview?: (opts: Record<string, unknown> | NamaCallback, cb?: NamaCallback) => void
  processVideo?: (opts: Record<string, unknown>, cb: NamaCallback) => void
  resolveLocalMediaPath?: (opts: Record<string, unknown>, cb: NamaCallback) => void
  /** iOS：系统 UIImagePickerController（白底相册 + 左上角取消），对齐 FULiveDemo */
  pickMediaFromAlbum?: (opts: Record<string, unknown>, cb: NamaCallback) => void
  /** 分享 SDK fuOpenFileLog 写的 nama_log.log（系统面板可选钉钉等） */
  shareNamaSdkLog?: (cb: NamaCallback) => void
  getNamaSdkLogPath?: (cb: NamaCallback) => void
}

let nama: NamaPlugin | null = null
/** 相机管线 beauty item */
let cameraBeautyHandle = 0
/** 媒体管线 beauty item（图/视频） */
let mediaBeautyHandle = 0
/** 当前前端写参目标管线 */
let activePipeline: 'camera' | 'media' = 'camera'
let namaReady = false
/** iOS 媒体页复用了相机 beauty handle（参数已在 item 上，勿再全量串行写参） */
let mediaReusedCameraSession = false
/** SDK + AI 已加载 */
let sdkInited = false
/** 防止首页预热与进页初始化并发重复 loadAI */
let sdkInitPromise: Promise<void> | null = null
/** 防止首页 preload 与美颜页并发 loadBundle */
let beautyInitPromise: Promise<number> | null = null
/** 与 native MediaFuSetup 同步，供 JS 写参门禁 */
let cachedDevicePerfLevel = 1
/** 系统导航切换重建过 Activity：本 JS 生命周期内不得复用 parked overlay */
let androidNamaSessionDirty = false

function activeBeautyHandle() {
  return activePipeline === 'media' ? mediaBeautyHandle : cameraBeautyHandle
}

function invalidateCameraBeautyHandle() {
  cameraBeautyHandle = 0
  if (activePipeline === 'camera') {
    namaReady = false
  }
}

function invalidateMediaBeautyHandle() {
  mediaBeautyHandle = 0
  mediaReusedCameraSession = false
  if (activePipeline === 'media') {
    namaReady = false
  }
}

/** deviceLost 后整段会话失效：须重新 init + loadAI + loadBundle */
export function invalidateNamaSession() {
  invalidateCameraBeautyHandle()
  invalidateMediaBeautyHandle()
  sdkInited = false
  namaReady = false
  sdkInitPromise = null
  beautyInitPromise = null
}

export function setNamaPipeline(pipeline: 'camera' | 'media') {
  activePipeline = pipeline
  namaReady = activeBeautyHandle() > 0
}

export type NamaPluginDiag = {
  ok: boolean
  detail: string
  methods: string[]
}

/** 诊断插件是否真正打入基座（比 hasShowCamera 信息更完整） */
export function diagnoseNamaPlugin(): NamaPluginDiag {
  try {
    const fn = (uni as unknown as Record<string, unknown>)['requireNativePlugin'] as
      | ((id: string) => Record<string, unknown> | undefined)
      | undefined
    if (typeof fn !== 'function') {
      return { ok: false, detail: '当前不是 App 环境', methods: [] }
    }
    const mod = fn(PLUGIN_ID)
    if (!mod) {
      return {
        ok: false,
        detail: `插件 ${PLUGIN_ID} 未加载：请确认已制作自定义基座，且运行时选择了「自定义基座」`,
        methods: [],
      }
    }
    const methods = Object.keys(mod).filter((k) => typeof mod[k] === 'function')
    const hasShow = typeof mod.showCamera === 'function'
    if (hasShow) {
      return { ok: true, detail: 'ok', methods }
    }
    const hint =
      methods.some((m) => m === 'addEvent' || m === 'removeEvent') && !methods.includes('showCamera')
        ? '（仅含 addEvent/removeEvent 说明原生方法未注册，请重打 iOS 自定义基座）'
        : ''
    return {
      ok: false,
      detail:
        methods.length > 0
          ? `插件已加载但缺少 showCamera，已有方法: ${methods.join(', ')}${hint}`
          : `插件对象为空（基座未包含最新 libFaceUnityNamaPlugin.a）${hint}`,
      methods,
    }
  } catch (e) {
    return { ok: false, detail: (e as Error).message, methods: [] }
  }
}

function getNama(): NamaPlugin {
  if (!nama) {
    const fn = (uni as unknown as Record<string, unknown>)['requireNativePlugin'] as
      | ((id: string) => NamaPlugin)
      | undefined
    if (typeof fn !== 'function') {
      throw new Error('requireNativePlugin 不可用')
    }
    nama = fn(PLUGIN_ID)
  }
  if (!nama) {
    throw new Error(`未找到插件 ${PLUGIN_ID}，请制作自定义调试基座`)
  }
  return nama
}

function run<T>(method: keyof NamaPlugin, options?: Record<string, unknown>): Promise<T> {
  return new Promise((resolve, reject) => {
    const mod = getNama()
    const done: NamaCallback = (res) => {
      const data = res?.data as { pending?: number | boolean } | undefined
      // 原生 keepAlive 中间态：勿提前 resolve，否则 stopVideoRecord/switchCamera 会「假成功」或超时竞态
      if (data && (Number(data.pending) === 1 || data.pending === true)) {
        return
      }
      if (res && Number(res.code) === 0) {
        resolve(res.data as T)
        return
      }
      reject(new Error(res?.message || `${String(method)} 失败`))
    }
    try {
      const fn = mod[method] as (...args: unknown[]) => void
      if (typeof fn !== 'function') {
        reject(new Error(`${String(method)} 不是函数，基座 AAR 可能过旧`))
        return
      }
      if (options) {
        fn.call(mod, options, done)
      } else {
        fn.call(mod, done)
      }
    } catch (e) {
      reject(e instanceof Error ? e : new Error(String(e)))
    }
  })
}

function runWithTimeout<T>(
  method: keyof NamaPlugin,
  options?: Record<string, unknown>,
  ms = 8000,
): Promise<T> {
  return Promise.race([
    run<T>(method, options),
    new Promise<T>((_, reject) => {
      setTimeout(() => reject(new Error(`${String(method)} 超时 ${ms}ms`)), ms)
    }),
  ])
}

function pathExists(path: string): Promise<boolean> {
  return new Promise((resolve) => {
    plus.io.resolveLocalFileSystemURL(path, () => resolve(true), () => resolve(false))
  })
}

function getFileSize(path: string): Promise<number> {
  return new Promise((resolve) => {
    plus.io.resolveLocalFileSystemURL(
      path,
      (entry) => {
        entry.getMetadata(
          (meta) => resolve(Number(meta.size) || 0),
          () => resolve(0),
        )
      },
      () => resolve(0),
    )
  })
}

/** 从 App 包内 static/nama-bundle 读取（随云打包，不走网络） */
async function ensureStaticBundle(fileName: string, minBytes = 0): Promise<string> {
  const path = resolveStaticAssetUrl(`${NAMA_BUNDLE_DIR}${fileName}`)
  if (!path) {
    throw new Error(`bundle 路径无效: ${fileName}`)
  }
  const exists = await pathExists(path)
  if (!exists) {
    throw new Error(
      `缺少 ${fileName}，请放入 src/static/nama-bundle/ 后重新云打包`,
    )
  }
  if (minBytes > 0) {
    const size = await getFileSize(path)
    if (size > 0 && size < minBytes) {
      throw new Error(
        `${fileName} 过小(${size}B)，请确认 src/static/nama-bundle/ 内为完整 AI 包`,
      )
    }
  }
  return path
}

function parseNativeHandle(data: unknown): number {
  if (typeof data === 'number' && data > 0) {
    return data
  }
  if (typeof data === 'string') {
    const n = parseInt(data, 10)
    return n > 0 ? n : 0
  }
  if (data && typeof data === 'object') {
    const obj = data as Record<string, unknown>
    for (const key of ['handle', 'data', 'itemHandle']) {
      const n = parseNativeHandle(obj[key])
      if (n > 0) {
        return n
      }
    }
  }
  return 0
}

/** loadAIModel 回调里带的 deviceLevel，优先于过早的 getDevicePerformanceLevel */
function applyDeviceLevelFromNativeData(data: unknown) {
  if (!data || typeof data !== 'object') {
    return
  }
  const obj = data as Record<string, unknown>
  let level: unknown = obj.deviceLevel
  if (level == null && obj.data && typeof obj.data === 'object') {
    level = (obj.data as Record<string, unknown>).deviceLevel
  }
  if (level != null) {
    const n = Number(level)
    if (n >= 1 && n <= 4) {
      cachedDevicePerfLevel = n
    }
  }
}

export function isNamaReady() {
  return namaReady && activeBeautyHandle() > 0
}

export function getNamaVersion() {
  return run<string>('getVersion')
}

/** SDK 日志：关闭文件/控制台刷屏（DEBUG 写盘会卡预览） */
function printNamaSdkLog(_sdkLog: unknown) {
  // no-op
}

export async function drainNamaSdkLog() {
  // no-op
}

/** 弹出系统分享面板，发送 SDK DEBUG 文件日志 nama_log.log */
export function shareNamaSdkLog(): Promise<{ path?: string; size?: number }> {
  // #ifndef APP-PLUS
  return Promise.reject(new Error('仅 App 可用'))
  // #endif
  // #ifdef APP-PLUS
  const mod = getNama()
  if (typeof mod.shareNamaSdkLog !== 'function') {
    return Promise.reject(new Error('请使用含 shareNamaSdkLog 的自定义基座'))
  }
  return runWithTimeout<{ path?: string; size?: number }>('shareNamaSdkLog', undefined, 8000).then(
    (data) => data || {},
  )
  // #endif
}

export async function setBeautyParam(key: string, value: number, handle?: number) {
  // #ifdef APP-PLUS
  if (isAndroidApp() && !isBeautyParamAllowed(key, cachedDevicePerfLevel)) {
    value = 0
  }
  // #endif
  const data = await run<{
    ret?: number
    sdkLog?: string
  } | number>('setParam', {
    key,
    value,
    handle: handle ?? activeBeautyHandle(),
    pipeline: activePipeline,
  })
  if (data && typeof data === 'object') {
    if (isIOSApp() && data.sdkLog) {
      printNamaSdkLog(data.sdkLog)
    }
    return Number(data.ret ?? 0)
  }
  return Number(data ?? 0)
}

export async function setBeautyStringParam(key: string, value: string, handle?: number) {
  const data = await run<{ ret?: number; sdkLog?: string } | number>('setParam', {
    key,
    stringValue: value,
    handle: handle ?? activeBeautyHandle(),
    pipeline: activePipeline,
  })
  if (data && typeof data === 'object') {
    if (isIOSApp() && data.sdkLog) {
      printNamaSdkLog(data.sdkLog)
    }
    return Number(data.ret ?? 0)
  }
  return Number(data ?? 0)
}

export function hasNamaModule() {
  return typeof getNama().init === 'function'
}

export function hasShowCamera() {
  return diagnoseNamaPlugin().ok
}

export type CameraRect = {
  x: number
  y: number
  width: number
  height: number
}

export function getPreviewDiag() {
  const mod = getNama()
  if (typeof mod.getPreviewDiag !== 'function') {
    return Promise.resolve({ mounted: false, diag: '' })
  }
  return runWithTimeout<{ mounted?: boolean; diag?: string }>('getPreviewDiag', undefined, 2000)
}

export function showCameraPreview(rect: CameraRect, extra?: Record<string, unknown>) {
  return runWithTimeout<Record<string, unknown>>('showCamera', { ...rect, ...extra }, 8000)
}

/** 仅改预览框尺寸，不 resume/重开相机（底栏展开收起用） */
export function resizeCameraPreview(rect: CameraRect) {
  const mod = getNama()
  if (typeof mod.resizeCameraPreview === 'function') {
    return runWithTimeout<Record<string, unknown>>('resizeCameraPreview', { ...rect }, 3000)
  }
  return showCameraPreview(rect, { resizeOnly: true })
}

/** 停相机叠层但保留 SDK/AI/bundle 会话（进出页、进媒体页） */
export function hideCameraPreview() {
  return runWithTimeout<number>('hideCamera', { keepSession: true }, 5000)
}

/** 拆除相机 overlay 视图（iOS 不 deviceLost）；离开美颜页用 */
export function detachCameraOverlay() {
  return runWithTimeout<number>('hideCamera', { keepSession: false }, 5000)
}

/**
 * 离开美颜页：普通会话 park；导航切换后的脏会话从窗口拆掉（保留 SDK）。
 * 脏会话若只 park，首页 reset 会停采集却拆不掉 Surface，暂停帧挡住下一页。
 */
export function hideOrDetachCameraPreview() {
  return hideCameraPreview()
}

export function pauseCameraPreview() {
  const mod = getNama()
  if (typeof mod.pauseCameraPreview !== 'function') {
    return Promise.resolve(0)
  }
  return runWithTimeout<number>('pauseCameraPreview', undefined, 2000)
}

export function resumeCameraPreview() {
  const mod = getNama()
  if (typeof mod.resumeCameraPreview !== 'function') {
    return Promise.resolve(0)
  }
  return runWithTimeout<number>('resumeCameraPreview', undefined, 2000)
}

/** 彻底拆相机并 deviceLost（仅进程退出级或强制重建时用） */
export function destroyCameraPreview() {
  const mod = getNama()
  if (typeof mod.destroyCameraPreview !== 'function') {
    return runWithTimeout<number>('hideCamera', { keepSession: false }, 8000).finally(() => {
      invalidateNamaSession()
    })
  }
  return runWithTimeout<number>('destroyCameraPreview', undefined, 8000).finally(() => {
    invalidateNamaSession()
  })
}

/** 弹系统 Alert 前临时藏原生 overlay（iOS 恢复确认用） */
export function setOverlayWindowsHidden(hidden: boolean) {
  const mod = getNama()
  if (typeof mod.setOverlayWindowsHidden !== 'function') {
    return Promise.resolve(0)
  }
  return runWithTimeout<number>('setOverlayWindowsHidden', { hidden }, 2000)
}

export type PreviewStats = {
  fps: number
  resolution: number
  renderTime?: number
  tracking: number
  frameWidth: number
  frameHeight: number
  frameCount?: number
  renderOk?: number
  previewStarted?: boolean
  label: string
}

export function getPreviewStats() {
  const mod = getNama()
  if (typeof mod.getPreviewStats !== 'function') {
    return Promise.resolve({
      fps: 0,
      resolution: 0,
      renderTime: 0,
      tracking: -1,
      frameWidth: 0,
      frameHeight: 0,
      label: '0.0.0',
    } satisfies PreviewStats)
  }
  return runWithTimeout<PreviewStats>('getPreviewStats', undefined, 2000)
}

export function getDevicePerformanceLevel() {
  const mod = getNama()
  if (typeof mod.getDevicePerformanceLevel !== 'function') {
    return Promise.resolve({ level: 4 })
  }
  return runWithTimeout<{ level?: number; ramGb?: number; cores?: number }>(
    'getDevicePerformanceLevel',
    undefined,
    2000,
  ).then((info) => {
    if (info?.level != null) {
      const n = Number(info.level)
      if (n >= 1 && n <= 4) {
        cachedDevicePerfLevel = n
      } else if (cachedDevicePerfLevel < 1 || cachedDevicePerfLevel > 4) {
        cachedDevicePerfLevel = 1
      }
    }
    return { ...info, level: cachedDevicePerfLevel }
  })
}

/** 首页统一申请相机+麦克风（iOS 走原生 requestPermissions，避免美颜页弹权） */
export function requestNativePermissions(): Promise<{ camera?: boolean; microphone?: boolean }> {
  const mod = getNama()
  if (typeof mod.requestPermissions !== 'function') {
    return Promise.resolve({})
  }
  return runWithTimeout<{ camera?: boolean; microphone?: boolean }>(
    'requestPermissions',
    undefined,
    5000,
  ).catch(() => ({}))
}

export function getCachedDevicePerfLevel() {
  return cachedDevicePerfLevel
}

export function setBeautyEnabled(enabled: boolean) {
  const mod = getNama()
  if (typeof mod.setBeautyEnabled !== 'function') {
    return Promise.resolve(0)
  }
  return runWithTimeout<number>('setBeautyEnabled', { enabled, pipeline: activePipeline }, 2000)
}

/** Android：双输入(fuDualInputToTexture) / 单输入(fuRenderToNV21Image) */
export function setDualInput(dual: boolean) {
  const mod = getNama()
  if (typeof mod.setDualInput !== 'function') {
    return Promise.resolve(0)
  }
  return runWithTimeout<number>('setDualInput', { dual }, 2000).catch(() => 0)
}

export function switchCameraFacing() {
  const mod = getNama()
  if (typeof mod.switchCamera !== 'function') {
    return Promise.reject(new Error('基座缺少 switchCamera'))
  }
  return runWithTimeout<number>('switchCamera', undefined, 5000)
}

export type TapFocusOpts = {
  localX: number
  localY: number
  previewX: number
  previewY: number
  previewW: number
  previewH: number
  exposure?: number
}

/** 安卓：原生对焦十字+曝光（盖住 ZOrderOnTop 取景）；iOS 同思路（overlay 窗） */
export function tapFocus(opts: TapFocusOpts) {
  const mod = getNama()
  if (typeof mod.tapFocus !== 'function') {
    return Promise.resolve(0)
  }
  return runWithTimeout<number>('tapFocus', { ...opts }, 3000)
}

/** iOS/Android：预览框内归一化坐标对焦并显示原生十字/曝光 */
export function tapFocusAt(nx: number, ny: number) {
  const mod = getNama()
  if (typeof mod.tapFocus !== 'function') {
    return Promise.resolve(0)
  }
  return runWithTimeout<number>('tapFocus', { nx, ny }, 3000).catch(() => 0)
}

export function setCameraExposure(exposure: number) {
  const mod = getNama()
  if (typeof mod.setCameraExposure === 'function') {
    return runWithTimeout<number>('setCameraExposure', { exposure }, 2000)
  }
  if (typeof mod.setExposureBias === 'function') {
    const value = exposure > 1 ? exposure / 100 : exposure
    return runWithTimeout<number>('setExposureBias', { value }, 2000).catch(() => exposure)
  }
  return Promise.resolve(exposure)
}

export function setExposureBias(value: number) {
  const mod = getNama()
  if (typeof mod.setExposureBias === 'function') {
    return runWithTimeout<number>('setExposureBias', { value }, 3000).catch(() => 0)
  }
  return Promise.resolve(0)
}

export function hideFocusHud() {
  const mod = getNama()
  if (typeof mod.hideFocusHud === 'function') {
    return runWithTimeout<number>('hideFocusHud', undefined, 2000)
  }
  if (typeof mod.hideFocusChrome === 'function') {
    return runWithTimeout<number>('hideFocusChrome', undefined, 2000).catch(() => 0)
  }
  return Promise.resolve(0)
}

export function hideFocusChrome() {
  return hideFocusHud()
}

export type PreviewResolutionPreset = {
  id: string
  label: string
  width: number
  height: number
}

export const PREVIEW_RESOLUTION_PRESETS: PreviewResolutionPreset[] = [
  { id: '480', label: '480', width: 640, height: 480 },
  { id: '720', label: '720', width: 1280, height: 720 },
  { id: '1080', label: '1080', width: 1920, height: 1080 },
]

export function setPreviewResolution(width: number, height: number) {
  const mod = getNama()
  if (typeof mod.setPreviewResolution !== 'function') {
    return Promise.reject(new Error('基座缺少 setPreviewResolution，请更新自定义基座'))
  }
  return runWithTimeout<number>('setPreviewResolution', { width, height }, 8000)
}

/** 进相机页重置原生静态分辨率为 720，不跨页记忆 */
export function resetPreviewResolution() {
  const mod = getNama()
  if (typeof mod.resetPreviewResolution !== 'function') {
    // 旧基座：直接 set 720
    return setPreviewResolution(1280, 720).catch(() => 0)
  }
  return runWithTimeout<number>('resetPreviewResolution', undefined, 2000).catch(() => 0)
}

export function captureCameraPhoto() {
  const mod = getNama()
  if (typeof mod.capturePhoto !== 'function') {
    return Promise.reject(new Error('基座缺少 capturePhoto'))
  }
  return runWithTimeout<{ path: string }>('capturePhoto', undefined, 8000)
}

export function startCameraVideoRecord() {
  const mod = getNama()
  if (typeof mod.startVideoRecord !== 'function') {
    return Promise.reject(new Error('基座缺少 startVideoRecord，请重新制作自定义基座'))
  }
  return runWithTimeout<number>('startVideoRecord', undefined, 5000)
}

export function stopCameraVideoRecord() {
  const mod = getNama()
  if (typeof mod.stopVideoRecord !== 'function') {
    return Promise.reject(new Error('基座缺少 stopVideoRecord，请重新制作自定义基座'))
  }
  return runWithTimeout<{ path: string }>('stopVideoRecord', undefined, 30000)
}

/**
 * 原生选图/视频：
 * - iOS：UIImagePickerController（SavedPhotosAlbum）
 * - Android：ACTION_OPEN_DOCUMENT（对齐 FULiveDemoDroid，避免 uni.choose 空相册）
 */
export function pickMediaFromAlbum(type: 'image' | 'video'): Promise<string> {
  const mod = getNama()
  if (typeof mod.pickMediaFromAlbum !== 'function') {
    return Promise.reject(new Error('基座缺少 pickMediaFromAlbum，请重新制作自定义基座'))
  }
  return new Promise((resolve, reject) => {
    const done: NamaCallback = (res) => {
      const data = (res?.data || {}) as {
        pending?: number | boolean
        path?: string
        type?: string
      }
      if (data.pending) {
        return
      }
      if (res && Number(res.code) === 0 && data.path) {
        resolve(String(data.path))
        return
      }
      reject(new Error(res?.message || '选择媒体失败'))
    }
    try {
      mod.pickMediaFromAlbum!({ type }, done)
    } catch (e) {
      reject(e instanceof Error ? e : new Error(String(e)))
    }
  })
}

/**
 * iOS 相机原生叠层 windowLevel 很高，会盖住 uni.showToast。
 * 优先走插件叠层 toast；失败再回退 uni / plus.nativeUI。
 */
export function showAppToast(
  title: string,
  opts?: { icon?: 'success' | 'none' | 'error'; duration?: number },
) {
  const text = (title || '').trim()
  if (!text) {
    return
  }
  const duration = opts?.duration ?? 2000
  try {
    const mod = getNama()
    if (typeof mod.showToast === 'function') {
      run('showToast', { title: text, duration }).catch(() => undefined)
      return
    }
  } catch {
    // ignore
  }
  try {
    if (typeof plus !== 'undefined' && plus.nativeUI) {
      plus.nativeUI.toast(text, {
        duration: duration <= 1500 ? 'short' : 'long',
        verticalAlign: 'bottom',
      })
      return
    }
  } catch {
    // ignore
  }
  uni.showToast({
    title: text,
    icon: opts?.icon === 'success' ? 'success' : 'none',
    duration,
  })
}

/**
 * App 确认框：原生面板会盖住 uni.showModal，优先走插件 showConfirm（双端）。
 */
export function showAppConfirm(opts: {
  title?: string
  content?: string
  confirmText?: string
  cancelText?: string
}): Promise<boolean> {
  const title = opts.title || '提示'
  const content = opts.content || ''
  const confirmText = opts.confirmText || '确定'
  const cancelText = opts.cancelText || '取消'
  try {
    const mod = getNama()
    if (typeof mod.showConfirm === 'function') {
      return new Promise<boolean>((resolve) => {
        const done: NamaCallback = (res) => {
          const data = (res?.data || {}) as {
            pending?: number | boolean
            confirm?: number | boolean
          }
          if (data.pending) {
            return
          }
          if (res && Number(res.code) === 0) {
            resolve(Number(data.confirm) === 1 || data.confirm === true)
            return
          }
          resolve(false)
        }
        try {
          mod.showConfirm!({ title, content, confirmText, cancelText }, done)
        } catch {
          resolve(false)
        }
      })
    }
  } catch {
    // fall through
  }
  return new Promise((resolve) => {
    uni.showModal({
      title,
      content,
      confirmText,
      cancelText,
      success: (res) => resolve(!!res.confirm),
      fail: () => resolve(false),
    })
  })
}

/** 对相册静图做 Nama 美颜，返回缓存目录下的结果图路径 */
export function processStillImage(path: string, opts?: { maxSide?: number }) {
  const mod = getNama()
  if (typeof mod.processImage !== 'function') {
    return Promise.reject(new Error('基座缺少 processImage，请重新制作自定义基座'))
  }
  let local = path
  if (local.startsWith('file://')) {
    local = local.slice(7)
  }
  // iOS 预览边长：960 足够清晰且比 1280 快一截；导出可显式传更大
  let maxSide = opts?.maxSide
  if (maxSide == null && isIOSApp()) {
    maxSide = 960
  }
  const payload: Record<string, unknown> = { path: local }
  if (typeof maxSide === 'number' && maxSide > 0) {
    payload.maxSide = maxSide
  }
  // 静图处理不再 deviceLost；会话保持，避免每次重载导致无美颜
  return runWithTimeout<{ path: string }>('processImage', payload, 20000)
}

export type VideoPreviewRect = {
  path: string
  x: number
  y: number
  width: number
  height: number
}

/** 挂载原生视频美颜预览叠层（循环播放） */
export function showVideoPreview(rect: VideoPreviewRect) {
  const mod = getNama()
  if (typeof mod.showVideoPreview !== 'function') {
    return Promise.reject(new Error('基座缺少 showVideoPreview，请重新制作自定义基座'))
  }
  let local = rect.path
  if (local.startsWith('file://')) {
    local = local.slice(7)
  }
  return runWithTimeout<Record<string, unknown>>(
    'showVideoPreview',
    { ...rect, path: local },
    15000,
  )
}

export function pauseVideoPreview() {
  const mod = getNama()
  if (typeof mod.pauseVideoPreview !== 'function') {
    return Promise.resolve(0)
  }
  return runWithTimeout<number>('pauseVideoPreview', undefined, 3000)
}

/** 锁屏/回桌面：成对 GL onPause，保住 Nama */
export function parkVideoForBackground() {
  const mod = getNama()
  if (typeof mod.parkVideoForBackground !== 'function') {
    return pauseVideoPreview()
  }
  return runWithTimeout<number>('parkVideoForBackground', undefined, 3000)
}

/** 回前台：首帧 + Play（不 invalidate） */
export function resetVideoToIdle() {
  const mod = getNama()
  if (typeof mod.resetVideoToIdle !== 'function') {
    return Promise.resolve({ ok: 0 })
  }
  return runWithTimeout<{ ok?: number }>('resetVideoToIdle', undefined, 5000)
}

export function resumeVideoPreview() {
  const mod = getNama()
  if (typeof mod.resumeVideoPreview !== 'function') {
    return Promise.resolve(0)
  }
  return runWithTimeout<number>('resumeVideoPreview', undefined, 3000)
}

export function destroyVideoPreview() {
  const mod = getNama()
  if (typeof mod.destroyVideoPreview !== 'function') {
    return Promise.resolve(0)
  }
  // 相机/视频已共享 EGL（对齐 iOS sharegroup）：默认 keepSession，避免回相机久黑
  return runWithTimeout<{ resourcesLost?: boolean | number }>(
    'destroyVideoPreview',
    { keepSession: true },
    8000,
  )
    .then((data) => {
      const lost =
        !!data &&
        (Number((data as { resourcesLost?: number }).resourcesLost) === 1 ||
          (data as { resourcesLost?: boolean }).resourcesLost === true)
      if (lost) {
        invalidateNamaSession()
      }
      return 0
    })
    .catch(() => 0)
}

/** 离线导出美颜视频，返回缓存目录下的 mp4 路径 */
export async function processStillVideo(path: string) {
  const mod = getNama()
  if (typeof mod.processVideo !== 'function') {
    return Promise.reject(new Error('基座缺少 processVideo，请重新制作自定义基座'))
  }
  let local = path
  if (local.startsWith('file://')) {
    local = local.slice(7)
  }
  // 最长约 60s 视频，给足导出时间；导出后不 invalidate，保留会话
  return runWithTimeout<{ path: string }>('processVideo', { path: local }, 600000)
}

/** Android API Level（不可用 plus.os.version，那是系统版本号如 13/14） */
function getAndroidApiLevel(): number {
  if (typeof plus === 'undefined' || plus.os.name !== 'Android') {
    return 0
  }
  try {
    const Version = plus.android.importClass('android.os.Build$VERSION') as unknown as {
      SDK_INT: number
    }
    const level = Number(Version?.SDK_INT)
    if (Number.isFinite(level) && level > 0) {
      return level
    }
  } catch {
    // fall through
  }
  try {
    const Build = plus.android.importClass('android.os.Build') as unknown as {
      VERSION: { SDK_INT: number }
    }
    const level = Number(Build?.VERSION?.SDK_INT)
    if (Number.isFinite(level) && level > 0) {
      return level
    }
  } catch {
    // fall through
  }
  // 回退：把 13/14 这类版本号映射到 API（仅粗略）
  const ver = parseInt(String(plus.os.version || '0'), 10)
  if (ver >= 13) {
    return 33
  }
  if (ver >= 10) {
    return 29
  }
  return Number.isFinite(ver) ? ver : 0
}

export function ensureStoragePermission(): Promise<void> {
  if (typeof plus === 'undefined' || plus.os.name !== 'Android') {
    return Promise.resolve()
  }
  const api = getAndroidApiLevel()
  // 拍照/录像写入相册：低版本需 WRITE；13+ 用 READ_MEDIA_*（部分机型写 MediaStore 也会查）
  const permissions =
    api >= 33
      ? ['android.permission.READ_MEDIA_IMAGES', 'android.permission.READ_MEDIA_VIDEO']
      : ['android.permission.WRITE_EXTERNAL_STORAGE', 'android.permission.READ_EXTERNAL_STORAGE']
  return ensurePermissions(permissions, '存储权限被拒绝，无法保存到相册')
}

/** 从相册读取图片/视频 */
export function ensureAlbumReadPermission(): Promise<void> {
  if (typeof plus === 'undefined') {
    return Promise.resolve()
  }
  if (plus.os.name === 'iOS') {
    return Promise.resolve()
  }
  const api = getAndroidApiLevel()
  // Android 13+：系统 Photo Picker 可不申请 READ_MEDIA_*；仍声明并请求，兼容旧选择器回退
  // Android 10-12：分区存储下读相册仍需 READ_EXTERNAL_STORAGE
  // Android 9 及以下：READ_EXTERNAL_STORAGE
  const permissions =
    api >= 33
      ? ['android.permission.READ_MEDIA_IMAGES', 'android.permission.READ_MEDIA_VIDEO']
      : api >= 29
        ? ['android.permission.READ_EXTERNAL_STORAGE']
        : ['android.permission.READ_EXTERNAL_STORAGE', 'android.permission.WRITE_EXTERNAL_STORAGE']
  return ensurePermissions(permissions, '相册权限被拒绝，无法导入媒体')
}

/**
 * 将相册返回的路径复制到应用私有目录，保证原生 processImage / MediaExtractor 能用 File 打开。
 * 安卓系统选择器常返回 content://，必须先拷贝，否则会「无法解码 / Failed to instantiate extractor」。
 */
export async function ensureLocalMediaFile(srcPath: string, extHint = '.jpg'): Promise<string> {
  if (typeof plus === 'undefined') {
    return srcPath
  }
  const src = (srcPath || '').trim()
  if (!src) {
    return srcPath
  }
  const stripFile = (p: string) => (p.startsWith('file://') ? p.slice(7) : p)
  const extMatch = /\.(mp4|mov|m4v|3gp|jpg|jpeg|png|webp|heic)$/i.exec(src)
  const ext = extMatch?.[0] || (extHint.startsWith('.') ? extHint : `.${extHint}`)

  // 安卓：优先原生 ContentResolver 拷贝（含 content://）
  if (plus.os.name === 'Android') {
    const mod = getNama()
    if (typeof mod.resolveLocalMediaPath === 'function') {
      try {
        const data = await runWithTimeout<{ path: string }>(
          'resolveLocalMediaPath',
          { path: src, ext },
          20000,
        )
        if (data?.path) {
          return stripFile(data.path)
        }
      } catch {
        // fall through
      }
    }
    const viaAndroid = copyAndroidUriSync(src, ext)
    if (viaAndroid) {
      return viaAndroid
    }
  }

  // 已是可读本地文件
  const plain = stripFile(src)
  if (plain.startsWith('/') && !src.startsWith('content://')) {
    return plain
  }

  return new Promise((resolve) => {
    const resolveUrl = src.startsWith('content://') || src.startsWith('file://') ? src : src
    const destName = `nama_import_${Date.now()}${ext}`
    plus.io.resolveLocalFileSystemURL(
      resolveUrl,
      (entry) => {
        plus.io.resolveLocalFileSystemURL(
          '_doc/',
          (dir) => {
            entry.copyTo(
              dir,
              destName,
              (copied) => {
                const local =
                  (copied as { fullPath?: string }).fullPath ||
                  (typeof (copied as { toLocalURL?: () => string }).toLocalURL === 'function'
                    ? (copied as { toLocalURL: () => string }).toLocalURL()
                    : '')
                resolve(local ? stripFile(local) : stripFile(src))
              },
              () => resolve(stripFile(src)),
            )
          },
          () => resolve(stripFile(src)),
        )
      },
      () => resolve(stripFile(src)),
    )
  })
}

/** 安卓 ContentResolver 同步拷贝 content:// / file:// 到 cache */
function copyAndroidUriSync(uriStr: string, ext: string): string | null {
  if (typeof plus === 'undefined' || plus.os.name !== 'Android') {
    return null
  }
  try {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const plusAndroid = plus.android as any
    plusAndroid.importClass('android.net.Uri')
    plusAndroid.importClass('java.io.File')
    plusAndroid.importClass('java.io.FileOutputStream')
    plusAndroid.importClass('java.lang.reflect.Array')
    plusAndroid.importClass('java.lang.Byte')
    const Uri = plusAndroid.importClass('android.net.Uri')
    const File = plusAndroid.importClass('java.io.File')
    const FileOutputStream = plusAndroid.importClass('java.io.FileOutputStream')
    const ArrayCls = plusAndroid.importClass('java.lang.reflect.Array')
    const Byte = plusAndroid.importClass('java.lang.Byte')
    const main = plusAndroid.runtimeMainActivity()

    let uri = uriStr
    if (!uri.startsWith('content://') && !uri.startsWith('file://')) {
      if (uri.startsWith('/')) {
        const f = new File(uri)
        if (f.exists() && f.isFile() && f.length() > 0) {
          return f.getAbsolutePath()
        }
      }
      uri = uri.startsWith('/') ? `file://${uri}` : uri
    }
    const parsed = Uri.parse(uri)
    const resolver = main.getContentResolver()
    const input = resolver.openInputStream(parsed)
    if (!input) {
      return null
    }
    const dir = new File(main.getCacheDir(), 'nama_import')
    if (!dir.exists()) {
      dir.mkdirs()
    }
    const dest = new File(dir, `import_${Date.now()}${ext}`)
    const fos = new FileOutputStream(dest)
    try {
      try {
        const FileUtils = plusAndroid.importClass('android.os.FileUtils')
        FileUtils.copy(input, fos)
      } catch {
        const buf = ArrayCls.newInstance(Byte.TYPE, 8192)
        let n = 0
        // eslint-disable-next-line no-cond-assign
        while ((n = input.read(buf)) > 0) {
          fos.write(buf, 0, n)
        }
      }
      fos.flush()
    } finally {
      try {
        fos.close()
      } catch {
        // ignore
      }
      try {
        input.close()
      } catch {
        // ignore
      }
    }
    if (!dest.exists() || dest.length() <= 0) {
      return null
    }
    return String(dest.getAbsolutePath())
  } catch {
    return null
  }
}

function ensureIOSMediaPermission(mediaType: 'vide' | 'soun', deniedMessage: string): Promise<void> {
  return new Promise((resolve, reject) => {
    if (typeof plus === 'undefined' || plus.os.name !== 'iOS' || !plus.ios) {
      resolve()
      return
    }

    try {
      const AVCaptureDevice = plus.ios.importClass('AVCaptureDevice') as {
        authorizationStatusForMediaType: (type: string) => number
        requestAccessForMediaTypecompletionHandler: (
          type: string,
          handler: (granted: boolean) => void,
        ) => void
      }
      if (!AVCaptureDevice) {
        resolve()
        return
      }

      const status = AVCaptureDevice.authorizationStatusForMediaType(mediaType)

      if (status === 3) {
        plus.ios.deleteObject(AVCaptureDevice)
        resolve()
        return
      }
      if (status === 2 || status === 1) {
        plus.ios.deleteObject(AVCaptureDevice)
        reject(new Error(deniedMessage))
        return
      }

      AVCaptureDevice.requestAccessForMediaTypecompletionHandler(mediaType, (granted) => {
        plus.ios.deleteObject(AVCaptureDevice)
        if (granted) {
          resolve()
        } else {
          reject(new Error(deniedMessage))
        }
      })
    } catch (e) {
      reject(e instanceof Error ? e : new Error(String(e)))
    }
  })
}

function ensureIOSCameraPermission(): Promise<void> {
  return ensureIOSMediaPermission('vide', '相机权限被拒绝，请在系统设置中开启')
}

function ensureIOSMicrophonePermission(): Promise<void> {
  return ensureIOSMediaPermission('soun', '麦克风权限被拒绝，请在系统设置中开启')
}

export function ensureCameraPermission(): Promise<void> {
  if (typeof plus === 'undefined') {
    return Promise.resolve()
  }
  if (plus.os.name === 'iOS') {
    return ensureIOSCameraPermission()
  }
  return ensurePermissions(['android.permission.CAMERA'], '相机权限被拒绝')
}

export function ensureMicrophonePermission(): Promise<void> {
  if (typeof plus === 'undefined') {
    return Promise.resolve()
  }
  if (plus.os.name === 'iOS') {
    return ensureIOSMicrophonePermission()
  }
  return ensurePermissions(['android.permission.RECORD_AUDIO'], '麦克风权限被拒绝')
}

/** 首页进入时申请相机 + 麦克风 + 存储/相册（避免拍摄时弹权导致相机黑屏、存相册失败） */
export async function ensureMediaPermissions(): Promise<void> {
  await ensureCameraPermission()
  try {
    await ensureMicrophonePermission()
  } catch {
    // 麦克风非美颜页必需；拒绝后仍可使用相机
  }
  try {
    await ensureStoragePermission()
  } catch {
    // 拒绝后拍摄/录像时再提示，勿阻断进首页
  }
  try {
    await ensureAlbumReadPermission()
  } catch {
    // 拒绝后导入媒体时再提示
  }
}

function isAndroidPermissionGranted(permission: string): boolean {
  if (typeof plus === 'undefined' || plus.os.name !== 'Android') {
    return true
  }
  try {
    const main = plus.android.runtimeMainActivity()
    const PackageManager = plus.android.importClass('android.content.pm.PackageManager') as {
      PERMISSION_GRANTED: number
    }
    const granted = main.checkSelfPermission(permission)
    return granted === PackageManager.PERMISSION_GRANTED
  } catch {
    try {
      const state = plus.android.checkPermission(permission)
      // 未知状态视为未授权，避免直接打开自建空相册
      if (state === undefined || state === null) {
        return false
      }
      const text = String(state).toLowerCase()
      return text === 'authorized' || text.includes('grant')
    } catch {
      return false
    }
  }
}

function ensurePermissions(permissions: string[], deniedMessage: string): Promise<void> {
  return new Promise((resolve, reject) => {
    if (typeof plus === 'undefined' || plus.os.name !== 'Android') {
      resolve()
      return
    }

    const missing = permissions.filter((p) => !isAndroidPermissionGranted(p))
    if (missing.length === 0) {
      resolve()
      return
    }

    plus.android.requestPermissions(
      missing,
      () => {
        const stillMissing = permissions.filter((p) => !isAndroidPermissionGranted(p))
        if (stillMissing.length > 0) {
          reject(new Error(deniedMessage))
          return
        }
        resolve()
      },
      (err) => reject(err instanceof Error ? err : new Error(String(err))),
    )
  })
}

export async function initNamaSdk() {
  const diag = await run<Record<string, unknown>>('init', {})
  if (isIOSApp() && diag && typeof diag === 'object') {
    printNamaSdkLog(diag.sdkLog)
  }
  return diag
}

export async function loadNamaAIModel() {
  const aiPath = await ensureStaticBundle(AI_BUNDLE_FILE, AI_BUNDLE_MIN_BYTES)
  const raw = await run<unknown>('loadAIModel', { path: aiPath })
  if (raw && typeof raw === 'object') {
    printNamaSdkLog((raw as Record<string, unknown>).sdkLog)
  }
  applyDeviceLevelFromNativeData(raw)
  const handle = parseNativeHandle(raw)
  if (handle <= 0) {
    throw new Error(`loadAIModel 无效 handle: ${JSON.stringify(raw)}`)
  }
  return handle
}

export async function loadNamaBeautyBundle(pipeline: 'camera' | 'media' = 'camera') {
  const beautyPath = await ensureStaticBundle(BEAUTY_BUNDLE_FILE)
  const raw = await run<unknown>('loadBundle', { path: beautyPath, pipeline })
  if (raw && typeof raw === 'object') {
    printNamaSdkLog((raw as Record<string, unknown>).sdkLog)
  }
  const handle = parseNativeHandle(raw)
  if (handle <= 0) {
    throw new Error(`loadBundle 无效 handle: ${JSON.stringify(raw)}`)
  }

  for (const p of BEAUTY_BASE_PARAMS) {
    await setBeautyParam(p.key, p.value, handle)
  }
  const defaultFilter = getFilterPresetById(DEFAULT_FILTER_ID)
  await setBeautyStringParam('filter_name', defaultFilter.key, handle)
  await setBeautyParam('filter_level', FILTER_LEVEL_ITEM.default, handle)

  if (pipeline === 'media') {
    mediaBeautyHandle = handle
  } else {
    cameraBeautyHandle = handle
  }
  activePipeline = pipeline
  namaReady = true
  await drainNamaSdkLog()
  return handle
}

async function ensureSdkAndAi(onProgress?: (step: string) => void) {
  if (sdkInited) {
    return
  }
  if (sdkInitPromise) {
    onProgress?.('等待初始化...')
    await sdkInitPromise
    return
  }
  sdkInitPromise = (async () => {
    onProgress?.('初始化授权...')
    // 跳过单独 getVersion 往返，init 内已含授权
    await initNamaSdk()

    onProgress?.('加载 AI 模型...')
    await loadNamaAIModel()
    sdkInited = true
  })()
  try {
    await sdkInitPromise
  } finally {
    sdkInitPromise = null
  }
}

/** 首页预热：SDK+AI+相机 beauty bundle，后续进页只启停预览 */
export async function preloadNamaSdk() {
  try {
    if (!diagnoseNamaPlugin().ok) {
      return
    }
    await initNamaForBeauty()
  } catch {
    // 预热失败不打断首页
  }
}

/** 美颜相机管线 */
export async function initNamaForBeauty(
  onProgress?: (step: string) => void,
): Promise<number> {
  activePipeline = 'camera'
  if (cameraBeautyHandle > 0 && sdkInited) {
    namaReady = true
    onProgress?.('已初始化')
    await getDevicePerformanceLevel().catch(() => undefined)
    return cameraBeautyHandle
  }
  if (beautyInitPromise) {
    onProgress?.('等待初始化...')
    return beautyInitPromise
  }
  beautyInitPromise = (async () => {
    await ensureSdkAndAi(onProgress)
    if (cameraBeautyHandle > 0 && sdkInited) {
      namaReady = true
      onProgress?.('已初始化')
      await getDevicePerformanceLevel().catch(() => undefined)
      return cameraBeautyHandle
    }
    onProgress?.('加载美颜资源...')
    const handle = await loadNamaBeautyBundle('camera')
    await getDevicePerformanceLevel().catch(() => undefined)
    onProgress?.('初始化完成')
    return handle
  })()
  try {
    return await beautyInitPromise
  } finally {
    beautyInitPromise = null
  }
}

function isIOSApp(): boolean {
  try {
    const sys = uni.getSystemInfoSync() as UniApp.GetSystemInfoResult & { osName?: string }
    return sys.platform === 'ios' || sys.osName === 'ios'
  } catch {
    return false
  }
}

function isAndroidApp(): boolean {
  try {
    const sys = uni.getSystemInfoSync() as UniApp.GetSystemInfoResult & { osName?: string }
    return sys.platform === 'android' || sys.osName === 'android'
  } catch {
    return false
  }
}

type SdkAliveInfo = NamaSessionProbe & {
  alive?: boolean
  aiLoaded?: boolean
}

function currentJsNamaSession(): JsNamaSession {
  return {
    sdkInited,
    cameraHandle: cameraBeautyHandle,
    sessionDirty: androidNamaSessionDirty,
  }
}

/** 切导航后半死：整进程冷启动，不修旧 overlay */
function relaunchAndroidApp() {
  try {
    const mod = getNama()
    if (typeof mod.relaunchProcess === 'function') {
      void run('relaunchProcess').catch(() => undefined)
      return
    }
  } catch {
    // 旧基座无此方法
  }
  try {
    if (typeof plus !== 'undefined' && plus.runtime && typeof plus.runtime.restart === 'function') {
      plus.runtime.restart()
    }
  } catch {
    // ignore
  }
}

/**
 * 首页核对 native 会话。Activity 已死 / JS 重载残留 overlay → 整进程重启。
 * 仅库或 handle 丢了才就地 reinit。
 */
export async function ensureAndroidNamaSessionFresh(): Promise<boolean> {
  // #ifndef APP-PLUS
  return false
  // #endif
  // #ifdef APP-PLUS
  if (!isAndroidApp() || !diagnoseNamaPlugin().ok) {
    return false
  }
  const mod = getNama()
  if (typeof mod.isSdkAlive !== 'function') {
    return false
  }
  if (sdkInitPromise || beautyInitPromise) {
    try {
      await (sdkInitPromise || beautyInitPromise)
    } catch {
      // 预热失败仍继续探测
    }
  }
  let info: SdkAliveInfo = {}
  try {
    info = (await run<SdkAliveInfo>('isSdkAlive')) || {}
  } catch {
    info = {}
  }
  if (shouldMarkAndroidNamaSessionDirty(info, currentJsNamaSession())) {
    androidNamaSessionDirty = true
  }
  const jsState = currentJsNamaSession()
  const relaunchReason = androidNamaRelaunchReason(info, jsState)
  if (relaunchReason) {
    console.log('[FU] relaunch Android process', relaunchReason, {
      overlayMounted: info.overlayMounted,
      overlayActDead: info.overlayActDead,
      overlaySurfaceValid: info.overlaySurfaceValid,
      contextAlive: info.contextAlive,
    })
    relaunchAndroidApp()
    return true
  }
  if (!shouldReinitAndroidNamaSession(info, jsState)) {
    return false
  }
  const reason = androidNamaReinitReason(info, jsState) || 'unknown'
  console.log('[FU] reinit Nama session on home check', reason, {
    overlayMounted: info.overlayMounted,
    overlayActDead: info.overlayActDead,
    overlaySurfaceValid: info.overlaySurfaceValid,
    jsHandle: cameraBeautyHandle,
    nativeHandle: info.cameraHandle,
    sdkInited,
    sessionDirty: androidNamaSessionDirty,
  })
  androidNamaSessionDirty = true
  try {
    await run('resetStaleOverlays')
  } catch {
    // 旧基座可能没有此方法
  }
  invalidateNamaSession()
  androidNamaSessionDirty = true
  try {
    await initNamaForBeauty()
  } catch {
    // 首页静默失败，进美颜页再 init
  }
  return true
  // #endif
}

/** App.onShow：与首页同一套探测，避免只在冷启动 reset 却不重新 init */
export async function ensureAndroidNamaAlive(): Promise<void> {
  await ensureAndroidNamaSessionFresh()
}

/** 媒体图/视频管线；复用首页/相机已 load 的 beauty handle，避免二次 loadBundle */
export async function initNamaForMedia(
  onProgress?: (step: string) => void,
): Promise<number> {
  activePipeline = 'media'
  mediaReusedCameraSession = false

  // 首页 preload / 相机页正在 init：必须 join，禁止另起一条 loadBundle
  if (beautyInitPromise && !(cameraBeautyHandle > 0 && sdkInited)) {
    onProgress?.('等待首页预热...')
    try {
      await beautyInitPromise
    } catch {
      // 预热失败再走自己的初始化
    }
  }

  if (mediaBeautyHandle > 0) {
    namaReady = true
    if (cameraBeautyHandle > 0 && mediaBeautyHandle === cameraBeautyHandle) {
      mediaReusedCameraSession = true
    }
    onProgress?.('媒体管线已初始化')
    // 安卓保留机型刷新；iOS 媒体页用缓存档位，避免多一次桥接拖慢进页
    if (!isIOSApp()) {
      await getDevicePerformanceLevel().catch(() => undefined)
    }
    return mediaBeautyHandle
  }

  // 复用相机会话：双端都 await bind，避免媒体页首帧写参/出画早于原生 handle
  if (cameraBeautyHandle > 0 && sdkInited) {
    mediaBeautyHandle = cameraBeautyHandle
    namaReady = true
    mediaReusedCameraSession = true
    try {
      const mod = getNama()
      if (typeof mod.bindMediaBeautyHandle === 'function') {
        const bindP = run('bindMediaBeautyHandle', { handle: cameraBeautyHandle }).catch(
          () => undefined,
        )
        // iOS 媒体页先出面板：bind 后台完成即可，勿多挡一轮桥接
        if (isIOSApp()) {
          void bindP
        } else {
          await bindP
        }
      }
    } catch {
      // 旧基座无此方法时仍用 JS handle 写参
    }
    onProgress?.('复用相机美颜会话')
    if (!isIOSApp()) {
      await getDevicePerformanceLevel().catch(() => undefined)
    }
    return mediaBeautyHandle
  }

  await ensureSdkAndAi(onProgress)
  onProgress?.('加载媒体美颜资源...')
  const handle = await loadNamaBeautyBundle('media')
  await getDevicePerformanceLevel().catch(() => undefined)
  mediaReusedCameraSession = false
  onProgress?.('初始化完成')
  return handle
}

/** iOS：媒体页是否复用了首页/相机已写好参数的 beauty 会话 */
export function isMediaReusingCameraSession() {
  return mediaReusedCameraSession
}

/** 销毁媒体预览后可选清理媒体 handle（默认保留以便返回再进） */
export function releaseMediaBeautyHandle() {
  invalidateMediaBeautyHandle()
}


export function showPreviewChrome(opts: Record<string, unknown>) {
  const mod = getNama()
  if (typeof mod.showPreviewChrome !== 'function') return Promise.resolve(0)
  return run<number>('showPreviewChrome', opts)
}

export function hidePreviewChrome() {
  const mod = getNama()
  if (typeof mod.hidePreviewChrome !== 'function') return Promise.resolve(0)
  return run<number>('hidePreviewChrome')
}

export function updatePreviewChromeStats(opts: Record<string, unknown>) {
  const mod = getNama()
  if (typeof mod.updatePreviewChromeStats !== 'function') return Promise.resolve(0)
  return run<number>('updatePreviewChromeStats', opts)
}

export function setPreviewChromeRecording(recording: boolean) {
  const mod = getNama()
  if (typeof mod.setPreviewChromeRecording !== 'function') return Promise.resolve(0)
  return run<number>('setPreviewChromeRecording', { recording })
}

/** 把 /static/... 转成原生 UIImage 可读的 file:// 绝对路径 */
function resolveStaticAssetUrl(relPath: string): string {
  if (!relPath) return ''
  if (relPath.startsWith('http://') || relPath.startsWith('https://') || relPath.startsWith('file://')) {
    return relPath
  }
  // #ifdef APP-PLUS
  try {
    const local = `_www${relPath.startsWith('/') ? relPath : `/${relPath}`}`
    const abs = plus.io.convertLocalFileSystemURL(local)
    if (abs) {
      return abs.startsWith('file://') ? abs : `file://${abs}`
    }
  } catch {
    // ignore
  }
  // #endif
  return relPath
}

/** 面板图标：优先转绝对路径；安卓原生仍可扫 www/static 兜底 */
function panelAssetUrl(relPath: string): string {
  if (!relPath) return ''
  if (relPath.startsWith('http://') || relPath.startsWith('https://')) {
    return relPath
  }
  const resolved = resolveStaticAssetUrl(relPath)
  if (resolved && resolved !== relPath) {
    return resolved
  }
  return relPath.startsWith('/') ? relPath : `/${relPath}`
}

function serializePanelEffect(item: BeautyEffectItem) {
  return {
    key: item.key,
    name: item.name,
    min: item.min,
    max: item.max,
    default: item.default,
    defaultSlider: defaultSliderValue(item),
    sliderMin: getSliderMin(item),
    sliderMax: getSliderMax(item),
    sliderZero: item.sliderZero,
    bidirectional: isBidirectionalSlider(item),
    unimplemented: !!item.unimplemented,
    performanceLevel: item.performanceLevel ?? -1,
    iconUrl: panelAssetUrl(getEffectIconSrc(item)),
    iconUrlChanges: panelAssetUrl(getEffectIconSrc(item, { changed: true })),
    iconUrlActive: panelAssetUrl(getEffectIconSrc(item, { selected: true })),
    iconUrlChangesActive: panelAssetUrl(
      getEffectIconSrc(item, { selected: true, changed: true }),
    ),
  }
}

export type BeautyPanelMode = 'camera' | 'image' | 'video'

/** iOS 原生美颜面板配置（由 beauty-effects 序列化，避免原生硬编码漂移） */
export function buildBeautyPanelConfig(opts: {
  mode: BeautyPanelMode
  values: Record<string, number>
  filterId: string
  whiteningMode?: 'global' | 'skin'
  selectedKey?: string
  devicePerfLevel?: number
}) {
  return {
    mode: opts.mode,
    devicePerfLevel: opts.devicePerfLevel,
    skin: BEAUTY_SKIN_EFFECTS.map(serializePanelEffect),
    shape: BEAUTY_SHAPE_EFFECTS.map(serializePanelEffect),
    filters: FILTER_PRESETS.map((f) => ({
      id: f.id,
      name: f.name,
      key: f.key,
      iconUrl:
        f.id === 'origin' ? panelAssetUrl(getFilterIconUrl(f)) : getFilterIconUrl(f),
    })),
    values: { ...opts.values },
    filterId: opts.filterId,
    whiteningMode: opts.whiteningMode ?? 'global',
    selectedKey: opts.selectedKey,
  }
}

export function probeNativeBeautyPanel(): boolean {
  try {
    // #ifdef APP-PLUS
    return diagnoseNamaPlugin().methods.includes('showBeautyPanel')
    // #endif
  } catch {
    return false
  }
  return false
}

export function showBeautyPanel(opts: Record<string, unknown>) {
  const mod = getNama()
  if (typeof mod.showBeautyPanel !== 'function') return Promise.resolve(0)
  return run<number | { height?: number }>('showBeautyPanel', opts)
}

export function hideBeautyPanel() {
  const mod = getNama()
  if (typeof mod.hideBeautyPanel !== 'function') return Promise.resolve(0)
  return run<number>('hideBeautyPanel')
}

export function updateBeautyPanelValues(opts: {
  values?: Record<string, number>
  filterId?: string
  whiteningMode?: string
  selectedKey?: string
}) {
  const mod = getNama()
  if (typeof mod.updateBeautyPanelValues !== 'function') return Promise.resolve(0)
  return run<number>('updateBeautyPanelValues', opts as Record<string, unknown>)
}

export function setBeautyPanelMode(mode: BeautyPanelMode) {
  const mod = getNama()
  if (typeof mod.setBeautyPanelMode !== 'function') return Promise.resolve(0)
  return run<number>('setBeautyPanelMode', { mode })
}
