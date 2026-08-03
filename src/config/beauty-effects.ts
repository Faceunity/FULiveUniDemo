/** nama-web OSS 滤镜预览图（与 WebDemo 一致） */
export const NAMA_CDN_BASE = 'https://fu-sdk.oss-cn-hangzhou.aliyuncs.com/WebDemo/prod'

const ICON_DIR = '/static/beauty-icons'

/** 美颜参数项：UI 滑杆 [sliderMin, sliderMax] 映射到 SDK [min, max]（对齐 nama-web） */
export type BeautyEffectItem = {
  name: string
  key: string
  /** SDK 参数下限 */
  min: number
  /** SDK 参数上限 */
  max: number
  /** SDK 默认值（双向项中性点多为 0.5） */
  default: number
  /** nama-web mobIcons 前缀，如 MobMopi */
  iconBase: string
  /**
   * UI 滑杆下限，默认 0。
   * 双向项（下巴/额头/嘴型等）为 -50，对应 nama-web reflexType:2
   */
  sliderMin?: number
  /** UI 滑杆上限，默认 100；双向项为 50 */
  sliderMax?: number
  /** 已知未实现：点击提示，不写参 */
  unimplemented?: boolean
  /**
   * 机型门槛（对齐 FULiveDemo）：1=Low 2=High 3=VeryHigh 4=Excellent；
   * 设备 level < performanceLevel 时灰显
   */
  performanceLevel?: number
  /**
   * 双向滑杆无效果点（UI 值）。标准 BIDIRECTIONAL(-50~50) 为 0。
   */
  sliderZero?: number
}

/** 双向美型：UI -50~50，SDK 0~1（0.5 中性），对齐 nama-web reflexType 2 */
const BIDIRECTIONAL = { sliderMin: -50, sliderMax: 50 } as const

export type FilterPreset = {
  id: string
  name: string
  /** filter_name 参数值（必须带编号，如 ziran1） */
  key: string
  /** CDN demo_icon 文件名（不含扩展名） */
  cdnIconKey?: string
}

function iconPath(base: string) {
  return `${ICON_DIR}/${base}.png`
}

/**
 * 四态图标（对齐 nama-web MobIcons）：
 * default / changed（已调参） / active（选中） / changedActive（选中且已调参）
 */
export type EffectIconState = 'default' | 'changed' | 'active' | 'changedActive'

export function resolveEffectIconState(selected: boolean, changed: boolean): EffectIconState {
  if (selected && changed) return 'changedActive'
  if (selected) return 'active'
  if (changed) return 'changed'
  return 'default'
}

const ICON_STATE_SUFFIX: Record<EffectIconState, string> = {
  default: '',
  changed: 'Changes',
  active: 'Active',
  changedActive: 'ChangesActive',
}

/** 已从 nama-web 拷齐四态的 iconBase；其余仅普通态 */
const EFFECT_ICON_BASES_WITH_STATES = new Set([
  'MobMopi',
  'MobMeibai',
  'MobHongrun',
  'MobQingxi',
  'MobRuihua',
  'MobWglt',
  'MobLy',
  'MobMeiya',
  'MobQhyq',
  'MobQflw',
  'MobQbqd',
  'MobShenTiMoPi',
  'MobMianBuFengYing',
  'MobTongKongDaXiao',
  'MobShoulian',
  'MobVlian',
  'MobZhailian',
  'MobDuanlian',
  'MobXiaolian',
  'MobShoueg',
  'MobShouxeg',
  'MobDayan',
  'MobYuanyan',
  'MobXb',
  'MobEt',
  'MobSb',
  'MobZx',
  'MobZchd',
  'MobYjwz',
  'MobKyj',
  'MobYjxz',
  'MobYj',
  'MobYjjd',
  'MobCb',
  'MobSrz',
  'MobWxzj',
  'MobMmsx',
  'MobMjj',
  'MobMmcx',
])

/** 美颜/美型 icon（按选中 + 是否已调参切换四态） */
export function getEffectIconSrc(
  item: BeautyEffectItem,
  opts?: { selected?: boolean; changed?: boolean },
): string {
  const state = resolveEffectIconState(!!opts?.selected, !!opts?.changed)
  const suffix = ICON_STATE_SUFFIX[state]
  if (!suffix || !EFFECT_ICON_BASES_WITH_STATES.has(item.iconBase)) {
    return iconPath(item.iconBase)
  }
  return iconPath(`${item.iconBase}${suffix}`)
}

export type BeautyPanelTab = 'skin' | 'shape' | 'filter'

export const BEAUTY_PANEL_TABS: { id: BeautyPanelTab; label: string }[] = [
  { id: 'skin', label: '美肤' },
  { id: 'shape', label: '美型' },
  { id: 'filter', label: '滤镜' },
]

/**
 * 美肤默认值（UI 0~100 → SDK raw）
 * 磨皮55 美白40 红润30 锐化60 五官立体40 亮眼30 去黑眼圈80 去法令纹80
 */
export const BEAUTY_SKIN_EFFECTS: BeautyEffectItem[] = [
  { name: '磨皮', key: 'blur_level', min: 0, max: 6, default: 3.3, iconBase: 'MobMopi', performanceLevel: -1 },
  {
    name: '全身磨皮',
    key: 'body_blur_level',
    min: 0,
    max: 6,
    default: 0,
    iconBase: 'MobShenTiMoPi',
    performanceLevel: 4,
  },
  {
    name: '祛斑痘',
    key: 'delspot_level',
    min: 0,
    max: 1,
    default: 0,
    iconBase: 'MobQbqd',
    performanceLevel: 3,
  },
  {
    name: '面部丰盈',
    key: 'facial_plump',
    min: 0,
    max: 1,
    default: 0,
    iconBase: 'MobMianBuFengYing',
    performanceLevel: 3,
  },
  /** 美白：文档推荐 color_level_mode2；「仅皮肤」靠 enable_skinseg */
  { name: '美白', key: 'color_level_mode2', min: 0, max: 1, default: 0.4, iconBase: 'MobMeibai', performanceLevel: 1 },
  { name: '红润', key: 'red_level', min: 0, max: 1, default: 0.3, iconBase: 'MobHongrun', performanceLevel: 1 },
  { name: '清晰', key: 'clarity', min: 0, max: 1, default: 0, iconBase: 'MobQingxi', performanceLevel: 1 },
  { name: '锐化', key: 'sharpen', min: 0, max: 1, default: 0.6, iconBase: 'MobRuihua', performanceLevel: 1 },
  { name: '五官立体', key: 'face_threed', min: 0, max: 1, default: 0.4, iconBase: 'MobWglt', performanceLevel: 1 },
  { name: '亮眼', key: 'eye_bright', min: 0, max: 1, default: 0.3, iconBase: 'MobLy', performanceLevel: 1 },
  { name: '美牙', key: 'tooth_whiten', min: 0, max: 1, default: 0, iconBase: 'MobMeiya', performanceLevel: 1 },
  { name: '去黑眼圈', key: 'remove_pouch_strength_mode2', min: 0, max: 1, default: 0.8, iconBase: 'MobQhyq', performanceLevel: 1 },
  {
    name: '去法令纹',
    key: 'remove_nasolabial_folds_strength_mode2',
    min: 0,
    max: 1,
    default: 0.8,
    iconBase: 'MobQflw',
    performanceLevel: 1,
  },
]

/**
 * 美型默认值：V脸50 瘦下颌骨10 大眼40 瘦鼻50 微笑嘴角35；
 * 双向项（下巴/额头等）SDK 中性 0.5（UI 显示 0）；其余未列项均为 0
 */
export const BEAUTY_SHAPE_EFFECTS: BeautyEffectItem[] = [
  { name: '瘦脸', key: 'cheek_thinning', min: 0, max: 1, default: 0, iconBase: 'MobShoulian', performanceLevel: -1 },
  { name: 'V脸', key: 'cheek_v', min: 0, max: 1, default: 0.5, iconBase: 'MobVlian', performanceLevel: 1 },
  { name: '窄脸', key: 'cheek_narrow_mode2', min: 0, max: 1, default: 0, iconBase: 'MobZhailian', performanceLevel: 1 },
  { name: '短脸', key: 'cheek_short', min: 0, max: 1, default: 0, iconBase: 'MobDuanlian', performanceLevel: 1 },
  { name: '小脸', key: 'cheek_small_mode2', min: 0, max: 1, default: 0, iconBase: 'MobXiaolian', performanceLevel: 1 },
  { name: '瘦颧骨', key: 'intensity_cheekbones', min: 0, max: 1, default: 0, iconBase: 'MobShoueg', performanceLevel: 1 },
  { name: '瘦下颌骨', key: 'intensity_lower_jaw', min: 0, max: 1, default: 0.1, iconBase: 'MobShouxeg', performanceLevel: 1 },
  { name: '大眼', key: 'eye_enlarging_mode3', min: 0, max: 1, default: 0.4, iconBase: 'MobDayan', performanceLevel: -1 },
  { name: '圆眼', key: 'intensity_eye_circle', min: 0, max: 1, default: 0, iconBase: 'MobYuanyan', performanceLevel: 1 },
  // 瞳孔大小：双向 -50~50，0 为无效果（SDK 0.5 中性）
  {
    name: '瞳孔大小',
    key: 'intensity_eye_pupil',
    min: 0,
    max: 1,
    default: 0.5,
    iconBase: 'MobTongKongDaXiao',
    performanceLevel: -1,
    ...BIDIRECTIONAL,
  },
  { name: '下巴', key: 'intensity_chin', min: 0, max: 1, default: 0.5, iconBase: 'MobXb', performanceLevel: 1, ...BIDIRECTIONAL },
  { name: '额头', key: 'intensity_forehead_mode2', min: 0, max: 1, default: 0.5, iconBase: 'MobEt', performanceLevel: 1, ...BIDIRECTIONAL },
  { name: '瘦鼻', key: 'intensity_nose_mode2', min: 0, max: 1, default: 0.5, iconBase: 'MobSb', performanceLevel: -1 },
  { name: '嘴型', key: 'intensity_mouth_mode3', min: 0, max: 1, default: 0.5, iconBase: 'MobZx', performanceLevel: 1, ...BIDIRECTIONAL },
  { name: '嘴唇厚度', key: 'intensity_lip_thick', min: 0, max: 1, default: 0.5, iconBase: 'MobZchd', performanceLevel: 2, ...BIDIRECTIONAL },
  { name: '眼睛位置', key: 'intensity_eye_height', min: 0, max: 1, default: 0.5, iconBase: 'MobYjwz', performanceLevel: 2, ...BIDIRECTIONAL },
  { name: '开眼角', key: 'intensity_canthus', min: 0, max: 1, default: 0, iconBase: 'MobKyj', performanceLevel: 1 },
  { name: '眼睑下至', key: 'intensity_eye_lid', min: 0, max: 1, default: 0, iconBase: 'MobYjxz', performanceLevel: 2 },
  { name: '眼距', key: 'intensity_eye_space', min: 0, max: 1, default: 0.5, iconBase: 'MobYj', performanceLevel: 1, ...BIDIRECTIONAL },
  { name: '眼睛角度', key: 'intensity_eye_rotate', min: 0, max: 1, default: 0.5, iconBase: 'MobYjjd', performanceLevel: 1, ...BIDIRECTIONAL },
  { name: '长鼻', key: 'intensity_long_nose', min: 0, max: 1, default: 0.5, iconBase: 'MobCb', performanceLevel: 1, ...BIDIRECTIONAL },
  { name: '缩人中', key: 'intensity_philtrum', min: 0, max: 1, default: 0.5, iconBase: 'MobSrz', performanceLevel: 1, ...BIDIRECTIONAL },
  { name: '微笑嘴角', key: 'intensity_smile', min: 0, max: 1, default: 0.35, iconBase: 'MobWxzj', performanceLevel: 1 },
  { name: '眉毛上下', key: 'intensity_brow_height', min: 0, max: 1, default: 0.5, iconBase: 'MobMmsx', performanceLevel: 2, ...BIDIRECTIONAL },
  { name: '眉间距', key: 'intensity_brow_space', min: 0, max: 1, default: 0.5, iconBase: 'MobMjj', performanceLevel: 2, ...BIDIRECTIONAL },
  { name: '眉毛粗细', key: 'intensity_brow_thick', min: 0, max: 1, default: 0.5, iconBase: 'MobMmcx', performanceLevel: 2, ...BIDIRECTIONAL },
]

export const FILTER_LEVEL_ITEM: BeautyEffectItem = {
  name: '滤镜强度',
  key: 'filter_level',
  min: 0,
  max: 1,
  /** 自然1 默认强度 40 */
  default: 0.4,
  iconBase: 'MobMopi',
}

export const FILTER_ORIGIN_ICON = `${ICON_DIR}/original.png`

/** 滤镜系列：key 前缀 + 数量（与 nama-web filterList 一致） */
const FILTER_SERIES: { prefix: string; label: string; count: number }[] = [
  { prefix: 'ziran', label: '自然', count: 8 },
  { prefix: 'zhiganhui', label: '质感灰', count: 8 },
  { prefix: 'mitao', label: '蜜桃', count: 8 },
  { prefix: 'bailiang', label: '白亮', count: 7 },
  { prefix: 'fennen', label: '粉嫩', count: 8 },
  { prefix: 'lengsediao', label: '冷色调', count: 11 },
  { prefix: 'nuansediao', label: '暖色调', count: 3 },
  { prefix: 'gexing', label: '个性', count: 11 },
  { prefix: 'xiaoqingxin', label: '小清新', count: 6 },
  { prefix: 'heibai', label: '黑白', count: 5 },
]

function buildFilterPresets(): FilterPreset[] {
  const list: FilterPreset[] = [{ id: 'origin', name: '原图', key: 'origin' }]
  for (const series of FILTER_SERIES) {
    for (let i = 1; i <= series.count; i++) {
      const key = `${series.prefix}${i}`
      list.push({
        id: key,
        name: `${series.label}${i}`,
        key,
        cdnIconKey: key,
      })
    }
  }
  return list
}

/** 滤镜预设（filter_name 必须带编号，与 bundle / nama-web 一致） */
export const FILTER_PRESETS: FilterPreset[] = buildFilterPresets()

/** 固定在滤镜栏左侧、不参与横向滚动的「原图」 */
export const FILTER_ORIGIN_PRESET = FILTER_PRESETS[0]

/** 滤镜栏可滚动列表（不含原图） */
export const FILTER_SCROLL_PRESETS = FILTER_PRESETS.filter((f) => f.id !== 'origin')

export function getFilterIconUrl(filter: FilterPreset): string {
  if (filter.id === 'origin') {
    return FILTER_ORIGIN_ICON
  }
  const key = filter.cdnIconKey ?? filter.key
  return `${NAMA_CDN_BASE}/demo_icon_${key}.png`
}

/** 默认滤镜：nama-web 取 filterList[0] = ziran1 */
export const DEFAULT_FILTER_ID = 'ziran1'

export function getFilterPresetById(id: string) {
  return FILTER_PRESETS.find((f) => f.id === id) ?? FILTER_PRESETS[1]
}

/** 全部滑杆项（初始化用） */
export const ALL_SLIDER_EFFECTS: BeautyEffectItem[] = [
  ...BEAUTY_SKIN_EFFECTS,
  ...BEAUTY_SHAPE_EFFECTS,
  FILTER_LEVEL_ITEM,
]

/** @deprecated 使用 ALL_SLIDER_EFFECTS */
export const BEAUTY_EFFECTS = ALL_SLIDER_EFFECTS

/** 从滑杆配置取默认，避免 BASE / 原生硬编码与 config 漂移 */
function effectDefault(key: string, fallback = 0): number {
  const item = ALL_SLIDER_EFFECTS.find((e) => e.key === key)
  return item?.default ?? fallback
}

/** 加载 bundle 后必须先设的基础开关（运行时按机型再调 blur_type） */
export const BEAUTY_BASE_PARAMS: { key: string; value: number }[] = [
  { key: 'is_beauty_on', value: 1 },
  { key: 'face_shape', value: 4 },
  { key: 'face_shape_level', value: 1 },
  /** 默认均匀磨皮；高端机会在原生侧升到 blur_type=3 */
  { key: 'blur_type', value: 2 },
  { key: 'heavy_blur', value: 0 },
  { key: 'skin_detect', value: 0 },
  /** 默认全局美白；「仅皮肤」由 UI 分段切换（需 Excellent） */
  { key: 'enable_skinseg', value: 0 },
  // 与 BEAUTY_SKIN/SHAPE_EFFECTS.default 同源
  { key: 'blur_level', value: effectDefault('blur_level', 3.3) },
  { key: 'color_level_mode2', value: effectDefault('color_level_mode2', 0.4) },
  { key: 'red_level', value: effectDefault('red_level', 0.3) },
  { key: 'body_blur_level', value: effectDefault('body_blur_level') },
  { key: 'delspot_level', value: effectDefault('delspot_level') },
  { key: 'facial_plump', value: effectDefault('facial_plump') },
  { key: 'intensity_eye_pupil', value: effectDefault('intensity_eye_pupil') },
]

/** 机型档位中文名（对齐 FULiveDemo -1~4） */
export const DEVICE_PERF_LEVEL_LABELS: Record<number, string> = {
  [-1]: '超低',
  1: '低端',
  2: '中高端',
  3: '高端',
  4: '旗舰',
}

export function performanceLevelLabel(level: number): string {
  return DEVICE_PERF_LEVEL_LABELS[level] ?? '更高'
}

export function performanceLimitToast(item: BeautyEffectItem): string {
  const need = item.performanceLevel
  if (need == null || need < 0) {
    return `${item.name}当前不可用`
  }
  return `${item.name}仅支持${performanceLevelLabel(need)}及以上机型`
}

/** 某 param key 最低档位（与 Android FuBeautyPerfGate 对齐） */
export function minPerformanceLevelForKey(key: string): number {
  if (key === 'enable_skinseg') {
    return 4
  }
  const item = ALL_SLIDER_EFFECTS.find((e) => e.key === key)
  if (item?.performanceLevel != null) {
    return item.performanceLevel
  }
  return -1
}

export function isBeautyParamAllowed(key: string, deviceLevel: number): boolean {
  const need = minPerformanceLevelForKey(key)
  if (need < 0) {
    return true
  }
  return deviceLevel >= need
}

export function getSliderMin(item: BeautyEffectItem): number {
  return item.sliderMin ?? 0
}

export function getSliderMax(item: BeautyEffectItem): number {
  return item.sliderMax ?? 100
}

/** 是否双向滑杆（UI 可为负值，或 0~100 以 sliderZero 为中点） */
export function isBidirectionalSlider(item: BeautyEffectItem): boolean {
  return getSliderMin(item) < 0 || item.sliderZero != null
}

export function getSliderZero(item: BeautyEffectItem): number {
  if (item.sliderZero != null) {
    return item.sliderZero
  }
  if (getSliderMin(item) < 0) {
    return 0
  }
  return 0
}

/**
 * UI 滑杆 → SDK 值。
 * 单向 0~100 → [min,max]；双向 -50~50 → [min,max]（0 对应中性，多为 0.5）
 */
export function sliderToValue(slider: number, item: BeautyEffectItem): number {
  const sMin = getSliderMin(item)
  const sMax = getSliderMax(item)
  const clamped = Math.min(sMax, Math.max(sMin, slider))
  if (item.sliderZero != null) {
    const z = item.sliderZero
    if (Math.abs(clamped - z) < 0.01) {
      return item.default
    }
    if (clamped > z) {
      const ratio = (clamped - z) / (sMax - z)
      return Math.round((item.default + ratio * (item.max - item.default)) * 100) / 100
    }
    const ratio = (clamped - z) / (z - sMin)
    return Math.round((item.default + ratio * (item.min - item.default)) * 100) / 100
  }
  const ratio = sMax === sMin ? 0 : (clamped - sMin) / (sMax - sMin)
  const raw = item.min + ratio * (item.max - item.min)
  return Math.round(raw * 100) / 100
}

export function valueToSlider(value: number, item: BeautyEffectItem): number {
  const sMin = getSliderMin(item)
  const sMax = getSliderMax(item)
  if (item.sliderZero != null) {
    const z = item.sliderZero
    if (Math.abs(value - item.default) < 0.01) {
      return z
    }
    if (value > item.default) {
      const ratio = (value - item.default) / (item.max - item.default)
      return Math.round(z + ratio * (sMax - z))
    }
    const ratio = (value - item.default) / (item.min - item.default)
    return Math.round(z + ratio * (z - sMin))
  }
  if (item.max === item.min) return sMin
  const ratio = (value - item.min) / (item.max - item.min)
  return Math.round(sMin + Math.min(1, Math.max(0, ratio)) * (sMax - sMin))
}

export function defaultSliderValue(item: BeautyEffectItem): number {
  return valueToSlider(item.default, item)
}

/** 图标「已调参」：滑杆相对默认值有偏移（双向项默认常为 -50 等） */
export function isEffectChanged(item: BeautyEffectItem, slider: number): boolean {
  const def = defaultSliderValue(item)
  const cur = slider ?? def
  return Math.abs(cur - def) > 0.01
}

/** 归一化显示值 0~1（刻度尺顶部数值） */
export function normalizedValue(slider: number, item: BeautyEffectItem): number {
  const v = sliderToValue(slider, item)
  if (item.max === item.min) return 0
  const n = (v - item.min) / (item.max - item.min)
  return Math.round(Math.min(1, Math.max(0, n)) * 100) / 100
}

export function normalizedToSlider(n: number, item: BeautyEffectItem): number {
  const ratio = Math.min(1, Math.max(0, n))
  const value = item.min + ratio * (item.max - item.min)
  return valueToSlider(value, item)
}
