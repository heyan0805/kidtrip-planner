/**
 * B4b 验收：**住宿落点**（TravelDay.hotel）
 *
 * 在 verify-b4 的基础上多问三个问题 —— 这三个问题都是"肉眼看不出"的那类：
 *
 *   ① 每一天的 hotel 能不能追回候选池里的真实酒店？（hotelId / 房价 / 名字逐字一致）
 *      注意房价要单独核对：它是**每晚房价**，和门票同量级，写错变量名完全看不出来。
 *
 *   ② 连住对不对？—— 相邻两天同一个 hotelId 是**合法且被鼓励**的（带娃少换酒店），
 *      但"同一家酒店在池子里只有一条"和"模型真的排了连住"是两件不同的事，
 *      只有把连住段数打出来才知道模型到底有没有在乱换。
 *
 *   ③ 最后一天该不该有住宿？—— 这里是"当天返程"和"多住一晚"的分界，
 *      按契约它可以是 null。脚本只报告，不判错 —— 因为这取决于模型怎么回答。
 *
 * 用法：先起后端（端口 8003），再 `node verify-b4b.mjs`
 */

const BASE = process.env.BASE ?? 'http://127.0.0.1:8003'

const PARAMS = {
  city: '深圳',
  days: 3,
  startDate: '2026-10-24',
  pace: '慢',
  childAge: 4,
  stroller: false,
  preferences: ['室内', '动物'],
}

const RETRIEVAL = { wantedCategories: ['动物园', '海洋馆'], keywords: ['室内'], needHotel: true }

async function post(path, body) {
  const res = await fetch(BASE + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  return res.json()
}

function line(char = '─', n = 74) {
  return char.repeat(n)
}

const money = (v) => (v == null ? '(无房价)' : '¥' + v)

const t0 = Date.now()

console.log(line('═'))
console.log('① 候选池（事实基准）')
console.log(line('═'))

const poolRes = await post('/api/retrieval/pool', {
  city: PARAMS.city,
  days: PARAMS.days,
  startDate: PARAMS.startDate,
  wantedCategories: RETRIEVAL.wantedCategories,
  keywords: RETRIEVAL.keywords,
  needHotel: RETRIEVAL.needHotel,
})
if (poolRes.code !== 0) {
  console.log('候选池调用失败：', poolRes.code, poolRes.message)
  process.exit(1)
}

const pool = poolRes.data.candidates
const byId = new Map()
for (const c of pool) byId.set(c.kind === 'poi' ? c.poiId : c.hotelId, c)

const pois = pool.filter((c) => c.kind === 'poi')
const hotels = pool.filter((c) => c.kind === 'hotel')
console.log(`候选池：${pool.length} 条（景点 ${pois.length} / 酒店 ${hotels.length}）`)
console.log(`上游调用 ${poolRes.data.provenance.upstreamCalls} 次，缓存命中 ${poolRes.data.provenance.cacheHits} 次`)
console.log()
console.log('  酒店候选：')
for (const h of hotels) {
  console.log(`    ${h.hotelId.padEnd(16)} ${h.name}  ${money(h.priceYuan)}/晚  ${h.star ?? '星级未知'}  ${h.rating ?? '-'}`)
}

const poolMs = Date.now() - t0

console.log()
console.log(line('═'))
console.log('② 生成行程（同一组条件 → 命中缓存，不额外花上游额度）')
console.log(line('═'))

const t1 = Date.now()
const gen = await post('/api/plan/generate', { params: PARAMS, retrieval: RETRIEVAL })
const genMs = Date.now() - t1

if (gen.code !== 0) {
  console.log(`生成失败：code=${gen.code}  message=${gen.message}`)
  process.exit(1)
}

const plan = gen.data
console.log(`耗时 ${(genMs / 1000).toFixed(1)} 秒（检索阶段单独 ${(poolMs / 1000).toFixed(1)} 秒）`)
console.log(`摘要：${plan.summary}`)

// ── ③ 逐天核对 ────────────────────────────────────────────────────────
const problems = []
let spotChecked = 0
let stayChecked = 0
const staySeries = [] // 每天住的 hotelId（null 表示不住）

console.log()
console.log(line('═'))
console.log('③ 逐天核对：白天的点 + 晚上的住处')
console.log(line('═'))

for (const day of plan.days) {
  const isLast = day.day === plan.days.length
  console.log()
  console.log(`第 ${day.day} 天 · ${day.theme}`)

  for (const s of day.spots) {
    const src = byId.get(s.poiId)
    spotChecked++
    if (!src) problems.push(`${s.name}: poiId ${s.poiId} 不在候选池里`)
    else if (src.name !== s.name) problems.push(`${s.name}: 名字与池子不一致（池里是「${src.name}」）`)
    else if (src.priceYuan !== s.priceYuan) problems.push(`${s.name}: 票价与池子不一致（池里 ${src.priceYuan}，行程 ${s.priceYuan}）`)
    console.log(`    ${s.time}  ${s.name}  ${money(s.priceYuan)}  ${src ? '✅ 可追溯' : '❌ 查不到'}`)
  }
  if (day.spots.length === 0) console.log('    （无景点）')

  // 住宿
  const h = day.hotel
  staySeries.push(h ? h.hotelId : null)
  if (h == null) {
    console.log(`    🛏 今晚不住 ${isLast ? '（最后一天 → 当天返程，合法）' : '（⚠️ 非最后一天却没有住宿安排）'}`)
    if (!isLast) problems.push(`第 ${day.day} 天不是最后一天，却没有住宿落点`)
    continue
  }

  stayChecked++
  const src = byId.get(h.hotelId)
  const nameOk = src ? src.name === h.name : false
  const priceOk = src ? src.priceYuan === h.priceYuan : false
  const urlOk = typeof h.bookUrl === 'string' && h.bookUrl.length > 0

  if (!src) problems.push(`住宿 ${h.name}: hotelId ${h.hotelId} 不在候选池里（模型编造的？）`)
  else if (!nameOk) problems.push(`住宿 ${h.name}: 名字与池子不一致（池里是「${src.name}」）`)
  else if (!priceOk) problems.push(`住宿 ${h.name}: 房价与池子不一致（池里 ${src.priceYuan}，行程 ${h.priceYuan}）`)
  if (!urlOk) problems.push(`住宿 ${h.name}: bookUrl 为空`)

  console.log(`    🛏 ${h.name}  ${money(h.priceYuan)}/晚  ${h.star ?? '星级未知'}  ${src ? '✅ 可追溯' : '❌ 查不到'}`)
  if (h.tips) console.log(`       理由：${h.tips}`)
}

// ── ④ 连住分析 ────────────────────────────────────────────────────────
console.log()
console.log(line('═'))
console.log('④ 连住分析（带娃旅行的关键指标）')
console.log(line('═'))

const runs = []
for (let i = 0; i < staySeries.length; i++) {
  const id = staySeries[i]
  if (id == null) continue
  const last = runs[runs.length - 1]
  if (last && last.id === id) last.nights++
  else runs.push({ id, nights: 1, from: i + 1 })
}
console.log(`  住宿序列：${staySeries.map((s) => s ?? '不住').join(' → ')}`)
console.log(`  换酒店次数：${Math.max(0, runs.length - 1)}   共 ${runs.length} 家`)
for (const r of runs) {
  const h = byId.get(r.id)
  console.log(`    ${h ? h.name : r.id}  住 ${r.nights} 晚（从第 ${r.from} 天起）`)
}

const hotelAsserts = []
if (stayChecked === 0) {
  hotelAsserts.push('这份行程一个住宿落点都没有（检查 needHotel 和 warnings）')
} else {
  const distinctPool = new Set(hotels.map((h) => h.hotelId)).size
  if (runs.length > distinctPool) hotelAsserts.push('用到的酒店比池子里的还多，不可能')
  // 连住段数 = 用的不同酒店数；如果模型每天都换，会等于住宿晚数
  if (runs.length === stayChecked && stayChecked > 2) {
    hotelAsserts.push(`每天都换了一家酒店（${runs.length} 晚 ${runs.length} 家）—— 带娃行程里这通常是坏答案，值得看一眼 prompt`)
  }
}

console.log()
console.log(line('═'))
console.log('⑤ 告警（我们的 + 模型的）')
console.log(line('═'))
plan.warnings.forEach((w, i) => console.log(`  ${i + 1}. ${w}`))

console.log()
console.log(line('═'))
console.log('⑥ 溯源')
console.log(line('═'))
const pv = plan.provenance
console.log('  真实发出的命令：')
pv.queries.forEach((q) => console.log(`    $ ${q}`))
console.log(`  检索轮次 ${pv.searchRounds} / 候选回来 ${pv.candidatesFound} 条 / 可用 ${pv.candidatesKept} 条`)
console.log(`  放宽过规则：${pv.relaxed}   被筛掉：${pv.rejected.length} 条`)
pv.rejected.slice(0, 6).forEach((r) => console.log(`    - ${r.name}：${r.reason}`))
console.log(`  总耗时 ${(pv.elapsedMs / 1000).toFixed(1)} 秒`)

console.log()
console.log(line('═'))
console.log('⑦ 结论')
console.log(line('═'))
console.log(`  核对：景点 ${spotChecked} 个 / 住宿 ${stayChecked} 晚`)
const all = [...problems, ...hotelAsserts]
if (all.length === 0) {
  console.log('  ✅ 景点与住宿都能追回飞猪候选池里的真实商品（名字、价格逐字一致）')
} else {
  console.log(`  ❌ 有 ${all.length} 处问题：`)
  all.forEach((p) => console.log(`     - ${p}`))
  process.exit(1)
}
console.log(`  全流程 ${((Date.now() - t0) / 1000).toFixed(1)} 秒`)
