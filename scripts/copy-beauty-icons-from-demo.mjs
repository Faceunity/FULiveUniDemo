import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const projectRoot = path.resolve(__dirname, '..')

const demoRoot =
  process.env.FU_BEAUTY_DEMO_ROOT ||
  path.resolve(projectRoot, '../iOS/FUBeautyComponent')

const xcassets = path.join(
  demoRoot,
  'FUBeautyComponent/Resource/FUBeautyComponent.xcassets',
)
const beautyOutDir = path.join(projectRoot, 'src/static/beauty-icons')
const filterOutDir = path.join(projectRoot, 'src/static/filter-icons')

const effects = [
  { name: '磨皮', base: 'MobMopi', folder: 'Skin' },
  { name: '全身磨皮', base: 'MobShenTiMoPi', folder: 'Skin' },
  { name: '祛斑痘', base: 'MobQbqd', folder: 'Skin' },
  { name: '面部丰盈', base: 'MobMianBuFengYing', folder: 'Skin' },
  { name: '美白', base: 'MobMeibai', folder: 'Skin' },
  { name: '红润', base: 'MobHongrun', folder: 'Skin' },
  { name: '清晰', base: 'MobQingxi', folder: 'Skin' },
  { name: '锐化', base: 'MobRuihua', folder: 'Skin' },
  { name: '五官立体', base: 'MobWglt', folder: 'Skin' },
  { name: '亮眼', base: 'MobLy', folder: 'Skin' },
  { name: '美牙', base: 'MobMeiya', folder: 'Skin' },
  { name: '去黑眼圈', base: 'MobQhyq', folder: 'Skin' },
  { name: '去法令纹', base: 'MobQflw', folder: 'Skin' },
  { name: '瘦脸', base: 'MobShoulian', folder: 'Shape' },
  { name: 'V脸', base: 'MobVlian', folder: 'Shape' },
  { name: '窄脸', base: 'MobZhailian', folder: 'Shape' },
  { name: '短脸', base: 'MobDuanlian', folder: 'Shape' },
  { name: '小脸', base: 'MobXiaolian', folder: 'Shape' },
  { name: '瘦颧骨', base: 'MobShoueg', folder: 'Shape' },
  { name: '瘦下颌骨', base: 'MobShouxeg', folder: 'Shape' },
  { name: '大眼', base: 'MobDayan', folder: 'Shape' },
  { name: '圆眼', base: 'MobYuanyan', folder: 'Shape' },
  { name: '瞳孔大小', base: 'MobTongKongDaXiao', folder: 'Shape' },
  { name: '下巴', base: 'MobXb', folder: 'Shape' },
  { name: '额头', base: 'MobEt', folder: 'Shape' },
  { name: '瘦鼻', base: 'MobSb', folder: 'Shape' },
  { name: '嘴型', base: 'MobZx', folder: 'Shape' },
  { name: '嘴唇厚度', base: 'MobZchd', folder: 'Shape' },
  { name: '眼睛位置', base: 'MobYjwz', folder: 'Shape' },
  { name: '开眼角', base: 'MobKyj', folder: 'Shape' },
  { name: '眼睑下至', base: 'MobYjxz', folder: 'Shape' },
  { name: '眼距', base: 'MobYj', folder: 'Shape' },
  { name: '眼睛角度', base: 'MobYjjd', folder: 'Shape' },
  { name: '长鼻', base: 'MobCb', folder: 'Shape' },
  { name: '缩人中', base: 'MobSrz', folder: 'Shape' },
  { name: '微笑嘴角', base: 'MobWxzj', folder: 'Shape' },
  { name: '眉毛上下', base: 'MobMmsx', folder: 'Shape' },
  { name: '眉间距', base: 'MobMjj', folder: 'Shape' },
  { name: '眉毛粗细', base: 'MobMmcx', folder: 'Shape' },
]

const suffixMap = {
  0: '',
  1: 'Changes',
  2: 'Active',
  3: 'ChangesActive',
}

function pickPng(imagesetDir) {
  const jsonPath = path.join(imagesetDir, 'Contents.json')
  if (!fs.existsSync(jsonPath)) return null
  const json = JSON.parse(fs.readFileSync(jsonPath, 'utf8'))
  for (const scale of ['2x', '3x', '1x']) {
    const entry = json.images?.find((img) => img.scale === scale && img.filename)
    if (!entry) continue
    const p = path.join(imagesetDir, entry.filename)
    if (fs.existsSync(p)) return p
  }
  return null
}

function findImageset(folder, effectName, state) {
  const direct = path.join(xcassets, folder, `${effectName}-${state}.imageset`)
  if (fs.existsSync(direct)) return direct
  const dir = path.join(xcassets, folder)
  if (!fs.existsSync(dir)) return null
  const suffix = `-${state}.imageset`
  const hit = fs.readdirSync(dir).find((name) => name.endsWith(suffix) && name.startsWith(effectName))
  return hit ? path.join(dir, hit) : null
}

function ensureDir(dir) {
  fs.mkdirSync(dir, { recursive: true })
}

let copied = 0
const missing = []

ensureDir(beautyOutDir)
ensureDir(filterOutDir)

for (const fx of effects) {
  for (const [state, outSuffix] of Object.entries(suffixMap)) {
    const imageset = findImageset(fx.folder, fx.name, state)
    if (!imageset) {
      missing.push(`${fx.folder}/${fx.name}-${state}`)
      continue
    }
    const src = pickPng(imageset)
    if (!src) {
      missing.push(`${imageset} (no png)`)
      continue
    }
    const out = path.join(beautyOutDir, `${fx.base}${outSuffix}.png`)
    fs.copyFileSync(src, out)
    copied++
  }
}

// 滤镜预览图：imageset 名 = filter key（ziran1、origin…）
const filterDir = path.join(xcassets, 'Filter')
let filterCopied = 0
if (fs.existsSync(filterDir)) {
  for (const name of fs.readdirSync(filterDir)) {
    if (!name.endsWith('.imageset')) continue
    const key = name.replace(/\.imageset$/, '')
    const src = pickPng(path.join(filterDir, name))
    if (!src) {
      missing.push(`Filter/${name} (no png)`)
      continue
    }
    fs.copyFileSync(src, path.join(filterOutDir, `${key}.png`))
    filterCopied++
  }
} else {
  missing.push('Filter/ (missing)')
}

console.log(`Demo root: ${demoRoot}`)
console.log(`Copied ${copied} beauty icons to ${beautyOutDir}`)
console.log(`Copied ${filterCopied} filter icons to ${filterOutDir}`)
if (missing.length) {
  console.log('Missing:')
  for (const m of missing) console.log('  -', m)
}
