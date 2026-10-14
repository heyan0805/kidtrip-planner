/**
 * B（候选行标距离）+ C（兜底校验）—— 真实数据验收。
 *
 * 用法：
 *   1) 后端带缓存启动：
 *        cd backend && unset SERVER__PORT && mvn spring-boot:run -Dspring-boot.run.arguments="\
 *          --kidtrip.flyai.cache-file=<仓库根>/_probe/flyai-cache.json \
 *          --kidtrip.flyai.cache-ttl-seconds=172800"
 *      日志要能被读到（本脚本默认读系统临时目录下的 geo-backend.log，可用 GEO_LOG 覆盖）
 *   2) node _probe/verify-geo-api.mjs
 *
 * ── 为什么必须"独立复算"，而不能只看日志里那串数字 ────────────────
 *   如果只断言"酒店行里有 近：C3(1.7km)"，那么一个**乱序的、取了最远三个的、
 *   甚至把公里数写成随机数**的实现，照样能通过。断言只证明了"有这个形状"。
 *
 *   所以这里做的是：自己从飞猪原始缓存读坐标 → 自己算全部候选距离 →
 *   自己取最近的三个 → 和日志里渲染出来的那三个**逐个对**（编号要对、公里数要对）。
 *   只有"我自己重算一遍结果一样"才算验证过。
 *
 * ── 同时盯住"没有假警告" ──────────────────────────────────────────
 *   C 那一半最容易被做坏的**不是漏报，是误报**：阈值写小了、或者把第 1 天也
 *   套上"昨晚住哪"（第 1 天压根没有"昨晚"），界面上就会多出一堆看着很像真的、
 *   实际不成立的警告 —— 而警告一多，真问题就被淹了。
 *   所以下面**双向**验：有冲突的天必须有警告，没冲突的天必须**没有**警告。
 */
import { readFileSync } from 'node:fs'
import { CACHE_FILE, PROBE_LOG } from './_paths.mjs'

const API = 'http://127.0.0.1:8003/api'
const CACHE = CACHE_FILE
const LOG = PROBE_LOG

const ORIGIN = '深圳'
const CITY = '广州'
const START = '2026-11-17'
const DAYS = 5

/** PlanRules 里那条一直没人执行的阈值（公里）。本脚本用它做独立复算的门槛 */
const MAX_KM = 15

let pass = 0
let fail = 0
function check(ok, label, detail) {
  if (ok) {
    pass++
    console.log('  ✅', label)
  } else {
    fail++
    console.log('  ❌', label, detail ? '\n       ' + detail : '')
  }
}

/* ── 独立复算的距离公式：与后端 Geo.java / 前端 distanceKm 同一套近似 ── */
const R = (d) => (d * Math.PI) / 180
function km(a, b) {
  if (!a || !b) return null
  const dLat = R(b.lat - a.lat)
  const dLng = R(b.lng - a.lng)
  const x = dLng * Math.cos(R((a.lat + b.lat) / 2))
  const y = dLat
  return Math.sqrt(x * x + y * y) * 6371
}

/* ── 坐标索引：只认飞猪原始缓存 ───────────────────────────────── */
const cache = JSON.parse(readFileSync(CACHE, 'utf8'))
const entries = cache.entries ?? cache
function parseRaw(e) {
  try {
    return typeof e.raw === 'string' ? JSON.parse(e.raw) : e.raw
  } catch {
    return null
  }
}
function listOf(e) {
  const d = parseRaw(e)?.data
  return d ? d.itemList ?? d.list ?? d.items ?? [] : []
}
const poiByName = new Map()
const hotelByName = new Map()
for (const e of Object.values(entries)) {
  const kind = (e.args ?? [])[0]
  if (kind !== 'search-poi' && kind !== 'search-hotel') continue
  for (const x of listOf(e)) {
    if (!x.latitude || !x.longitude) continue
    const rec = { name: x.name, lat: +x.latitude, lng: +x.longitude }
    if (kind === 'search-poi') poiByName.set(x.name, rec)
    else hotelByName.set(x.name, rec)
  }
}
console.log(`坐标索引：景点 ${poiByName.size} 条 · 酒店 ${hotelByName.size} 条`)
console.log('')

/* ── 从日志里把那次 prompt 还原出来 ──────────────────────────── */
let logText
try {
  logText = readFileSync(LOG, 'utf8')
} catch (e) {
  console.log('读不到日志：' + LOG)
  console.log('先跑一单 /plan/generate，或用 GEO_LOG 指定路径')
  process.exit(1)
}
// 日志里换行是转义的 \\n，还原成真换行才好按行拆
const plain = logText.replace(/\\n/g, '\n')
function blockAfter(title) {
  const i = plain.lastIndexOf(title)
  if (i < 0) return null
  return plain.slice(i, i + 6000).split('\n')
}

/* ──【1】B：候选行的距离标注 ─────────────────────────────────── */
console.log('【1】B：酒店候选行的「最近的 3 个候选景点」')
const hotelLines = (blockAfter('【候选酒店】') ?? []).filter((l) => /^H\d+ \|/.test(l))
const poiLines = (blockAfter('【候选景点】') ?? []).filter((l) => /^C\d+ \|/.test(l))
check(hotelLines.length > 0, `日志里读到 ${hotelLines.length} 行候选酒店`, hotelLines[0] ?? '')
check(poiLines.length > 0, `日志里读到 ${poiLines.length} 行候选景点（即模型能看见的编号全集）`)

/** C 编号 → 景点名（只包含**进了 prompt** 的那批 —— 关键：不能拿全量池去比） */
const visiblePoi = new Map()
for (const l of poiLines) {
  const m = l.match(/^(C\d+) \| ([^|]+?) \|/)
  if (m) visiblePoi.set(m[1], m[2].trim())
}
console.log(`      模型能看见的景点编号：${visiblePoi.size} 个`)

let annotated = 0
let exact = 0
const problems = []
for (const line of hotelLines) {
  const m = line.match(/^(H\d+) \| ([^|]+?) \|/)
  if (!m) continue
  const [, code, name] = m
  const tail = line.match(/近：(.+)$/)
  if (tail) annotated++
  else problems.push(`${code} 这一行没有「近：」标注 —— ${line.slice(0, 80)}`)
  if (!tail) continue

  // 独立复算：拿这家酒店的坐标，和**所有可见 C 编号**逐个算，取最近的三个
  const h = hotelByName.get(name.trim())
  const recomputed = []
  for (const [c, poiName] of visiblePoi) {
    const p = poiByName.get(poiName)
    recomputed.push({ c, d: km(h, p) })
  }
  const usable = recomputed.filter((r) => r.d !== null)
  usable.sort((a, b) => a.d - b.d)
  const expect = usable.slice(0, 3)

  // 渲染出来的那三个
  const got = [...tail[1].matchAll(/(C\d+)\(([\d.]+)km\)/g)].map((x) => ({
    c: x[1],
    d: +x[2],
  }))

  if (expect.length === 0) {
    // 没坐标可算时，实现必须明说"未知"，不能编三个数字出来
    check(
      tail[1].includes('未知'),
      `${code} 无坐标可算 → 应标「未知」`,
      `实际渲染：${tail[1]}`
    )
    continue
  }

  const codesOk = expect.map((e) => e.c).join(',') === got.map((g) => g.c).join(',')
  const kmOk = expect.every((e, i) => Math.abs(e.d - got[i].d) <= 0.1)
  if (codesOk && kmOk) exact++
  else
    problems.push(
      `${code} 复算不等：\n         我算的 ${expect.map((e) => `${e.c}(${e.d.toFixed(1)})`).join(' ')}` +
        `\n         日志里 ${got.map((g) => `${g.c}(${g.d})`).join(' ')}`
    )
}

check(
  problems.length === 0,
  `${exact}/${hotelLines.length} 家酒店的「最近三个」与独立复算**逐个对得上**（编号与公里数）`,
  problems.slice(0, 3).join('\n       ')
)
check(
  annotated === hotelLines.length,
  `每一行候选酒店都带距离标注（${annotated}/${hotelLines.length}）`
)
// 这一条单独拎出来：它证明"取的是最近"而不是"取的是前三个"
const ascending = hotelLines.every((l) => {
  const g = [...l.matchAll(/\(([\d.]+)km\)/g)].map((x) => +x[1])
  return g.every((v, i) => i === 0 || v >= g[i - 1])
})
check(ascending, '标注的三个距离是**升序**（最近的排最前，而不是随手取前三条）')
console.log('')

/* ──【2】生成一次，做 C 的双向校验 ──────────────────────────── */
console.log(`【2】${ORIGIN} → ${CITY} ${DAYS} 天：兜底校验（有冲突必报，无冲突必不报）`)
const t0 = Date.now()
const res = await (
  await fetch(API + '/plan/generate', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      params: {
        origin: ORIGIN,
        city: CITY,
        days: DAYS,
        startDate: START,
        childAge: 7,
        preferences: ['户外活动', '看动物', '干农活'],
        pace: '慢节奏',
        stroller: false,
      },
    }),
  })
).json()
check(res?.code === 0, `生成成功（${((Date.now() - t0) / 1000).toFixed(1)} 秒）`, JSON.stringify(res).slice(0, 300))
if (res?.code !== 0) {
  console.log('\n生成失败，后面的检查没法做')
  process.exit(1)
}
const plan = res.data
const warnings = plan.warnings ?? []

/** 这一条警告是不是"距离类"。用「公里/km/以外/隔了」这类**只有距离警告会出现的词**识别 */
const isGeoWarning = (w) => /公里|km|以外|隔着|隔了/.test(w)
const geoWarnings = warnings.filter(isGeoWarning)

/** 一天里**按时刻**排好（读不出时刻的点排最后，和实现同语义） */
const timeKey = (s) => {
  const m = /(\d{1,2}):(\d{2})/.exec(s.time ?? '')
  return m ? +m[1] * 60 + +m[2] : Number.MAX_SAFE_INTEGER
}
const ordered = (day) => [...(day.spots ?? [])].sort((a, b) => timeKey(a) - timeKey(b))
const firstOf = (day) => {
  const o = ordered(day)
  return o.length ? o[0] : null
}

/** 我自己算出来的违规清单 */
const expectViolations = []
plan.days.forEach((day, i) => {
  // ① 当天相邻两点
  const o = ordered(day)
  for (let j = 1; j < o.length; j++) {
    const d = km({ lat: o[j - 1].lat, lng: o[j - 1].lng }, { lat: o[j].lat, lng: o[j].lng })
    if (d !== null && d > MAX_KM)
      expectViolations.push({ kind: 'spread', day: day.day, km: +d.toFixed(1), a: o[j - 1].name, b: o[j].name })
  }
  // ② 昨天住哪 → 今天第一个点（第 1 天没有"昨晚"，必须跳过）
  if (i > 0) {
    const stay = plan.days[i - 1].hotel
    const first = firstOf(day)
    if (stay && stay.lat && stay.lng && first && first.lat && first.lng) {
      const d = km({ lat: stay.lat, lng: stay.lng }, { lat: first.lat, lng: first.lng })
      if (d !== null && d > MAX_KM)
        expectViolations.push({ kind: 'commute', from: plan.days[i - 1].day, to: day.day, km: +d.toFixed(1), hotel: stay.name, spot: first.name })
    }
  }
})

console.log('      我独立复算出的违规：')
if (expectViolations.length === 0) console.log('        （无）')
for (const v of expectViolations) {
  console.log(
    v.kind === 'spread'
      ? `        第 ${v.day} 天内部：「${v.a}」↔「${v.b}」 ${v.km} km`
      : `        第 ${v.from} 晚「${v.hotel}」→ 第 ${v.to} 天首点「${v.spot}」 ${v.km} km`
  )
}
console.log('      后端给出的距离类警告：')
if (geoWarnings.length === 0) console.log('        （无）')
for (const w of geoWarnings) console.log('        ' + w)

check(
  expectViolations.length === geoWarnings.length,
  `违规条数一致（我算 ${expectViolations.length} 条 / 后端报 ${geoWarnings.length} 条）`,
  '少了 = 漏报（真问题没说出来）；多了 = 误报（假警告淹没重点）'
)
// 逐条对：每条我算出来的违规，都必须能在后端警告里找到对应的两个地名
for (const v of expectViolations) {
  const needle = v.kind === 'spread' ? [v.a, v.b] : [v.hotel, v.spot]
  const hit = geoWarnings.find((w) => needle.every((n) => w.includes(n)))
  check(!!hit, `「${needle[0]}」↔「${needle[1]}」${
    v.kind === 'spread' ? '（同日跨度）' : '（跨夜通勤）'} 有对应警告，且公里数写对 ${v.km} km`,
    hit ? '' : '后端没报这一条')
  if (hit) check(hit.includes(v.km.toFixed(1)) || hit.includes(String(Math.round(v.km))),
    `  警告里的公里数与复算一致（${v.km} km）`, hit)
}
// 反向：没有违规时必须一条都不报（第 1 天尤其容易误报）
const spreadDays = new Set(expectViolations.filter((v) => v.kind === 'spread').map((v) => v.day))
const commuteTargets = new Set(expectViolations.filter((v) => v.kind === 'commute').map((v) => v.to))
const falsePositives = geoWarnings.filter((w) => {
  if (/隔了/.test(w)) return ![...spreadDays].some((d) => w.includes(`第 ${d} 天`))
  return ![...commuteTargets].some((d) => w.includes(`第 ${d} 天第一个点`))
})
check(falsePositives.length === 0, '没有假警告（没冲突的天一条都没多报）', falsePositives.join('\n       '))
// 第 1 天不该出现"昨晚住哪"式警告 —— 第 1 天早上是从家里/车站出发的
check(
  !geoWarnings.some((w) => w.includes('第 1 天晚上住')),
  '第 1 天没有被硬套「昨晚住哪」（那天没有昨晚）'
)
console.log('')

/* ──【3】B 有没有真的被模型用上 ───────────────────────────────── */
console.log('【3】模型有没有用上我们给的距离（而不是继续编）')
const tips = plan.days.map((d) => d.hotel?.tips ?? '').filter(Boolean)
const cited = tips.filter((t) => /[\d.]+\s*公里|C\d+|\d+\.\d+km/.test(t))
console.log(`      hotelTips 里引用了距离/编号的：${cited.length}/${tips.length} 条`)
cited.slice(0, 4).forEach((t) => console.log('        · ' + t))
check(
  cited.length > 0,
  '至少有一条 hotelTips 引用了我们给的距离或编号（证明那段标注进了模型视野并被采纳）'
)
// 引用了编号的话，编号和公里数必须和候选行里的一致（防"抄错行"）
let mismatch = []
for (const t of cited) {
  for (const m of t.matchAll(/C(\d+)[^0-9]{0,8}([\d.]+)\s*公里/g)) {
    const code = 'C' + m[1]
    const said = +m[2]
    const src = hotelLines.find((l) => l.includes(`${code}(`))
    if (!src) {
      mismatch.push(`${code} 不在候选行里，模型凭空引用`)
      continue
    }
    const real = +(new RegExp(`${code}\\(([\\d.]+)km\\)`).exec(src)?.[1] ?? NaN)
    if (Number.isFinite(real) && Math.abs(real - said) > 0.15)
      mismatch.push(`${code}：模型说 ${said}，候选行里是 ${real}`)
  }
}
check(mismatch.length === 0, '模型引用的编号↔距离与候选行里的完全一致（没有抄错行/编数字）', mismatch.join('; '))

console.log('')
console.log(`─────────────────────────────────────`)
console.log(`通过 ${pass} · 失败 ${fail}`)
process.exit(fail === 0 ? 0 : 1)
