/**
 * B4 验收：POST /api/plan/generate
 *
 * 它验的不是"有没有返回"，而是**返回的每一个点能不能追回飞猪上的真实商品**：
 *   ① 先调 /api/retrieval/pool 拿到候选池（记下每条 poiId 和价格）
 *   ② 再调 /api/plan/generate（同一组条件 → 后端会命中缓存，不额外花上游额度）
 *   ③ 逐条断言：行程里每个点的 poiId 都在候选池里，且价格与池子里的**逐字相同**
 *
 * 第 ③ 步是这个脚本存在的全部理由。
 * 一个"看起来很像"的行程和一个"每个点都能追溯"的行程，肉眼分不出来 ——
 * 只有断言能分。
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

const t0 = Date.now()

console.log(line('═'))
console.log('① 先取候选池（作为"事实的基准"）')
console.log(line('═'))

const poolRes = await post('/api/retrieval/pool', { city: PARAMS.city, days: PARAMS.days, startDate: PARAMS.startDate, wantedCategories: RETRIEVAL.wantedCategories, keywords: RETRIEVAL.keywords, needHotel: RETRIEVAL.needHotel })
if (poolRes.code !== 0) {
  console.log('候选池调用失败：', poolRes.code, poolRes.message)
  process.exit(1)
}

const pool = poolRes.data.candidates
const byId = new Map()
for (const c of pool) byId.set(c.kind === 'poi' ? c.poiId : c.hotelId, c)

const pois = pool.filter((c) => c.kind === 'poi')
console.log(`候选池：${pool.length} 条（景点 ${pois.length} / 酒店 ${pool.length - pois.length}）`)
console.log(`上游调用 ${poolRes.data.provenance.upstreamCalls} 次，缓存命中 ${poolRes.data.provenance.cacheHits} 次`)
for (const p of pois) {
  console.log(`   ${p.poiId.padEnd(16)} ${p.name}  ${p.priceYuan == null ? '(无票价)' : '¥' + p.priceYuan}`)
}

const poolMs = Date.now() - t0

console.log()
console.log(line('═'))
console.log('② 生成行程（同一组条件，应当命中缓存）')
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
console.log()
console.log(`摘要：${plan.summary}`)
console.log()

// ── ③ 逐条核对：行程里的每个点是不是真的来自候选池 ──────────────────
let checked = 0
const problems = []

for (const day of plan.days) {
  console.log(`第 ${day.day} 天 · ${day.theme}`)
  if (day.spots.length === 0) {
    console.log('    （空）')
  }
  for (const s of day.spots) {
    const src = byId.get(s.poiId)
    checked++

    const priceOk = src ? src.priceYuan === s.priceYuan : false
    const nameOk = src ? src.name === s.name : false
    const urlOk = typeof s.bookUrl === 'string' && s.bookUrl.includes('feizhu')

    if (!src) problems.push(`${s.name}: poiId ${s.poiId} 不在候选池里（模型编造的？）`)
    else if (!nameOk) problems.push(`${s.name}: 名字与候选池不一致（池里是「${src.name}」）`)
    else if (!priceOk) problems.push(`${s.name}: 价格与候选池不一致（池里是 ${src.priceYuan}，行程里是 ${s.priceYuan}）`)
    if (!urlOk) problems.push(`${s.name}: bookUrl 不像飞猪真实链接（${s.bookUrl}）`)

    console.log(`    ${s.time}  ${s.name}  ${s.priceYuan == null ? '(无票价)' : '¥' + s.priceYuan}  ${src ? '✅ 可追溯' : '❌ 查不到'}  ${s.tips ?? ''}`)
  }
}

console.log()
console.log(line('═'))
console.log('③ 告警（我们的 + 模型的）')
console.log(line('═'))
plan.warnings.forEach((w, i) => console.log(`  ${i + 1}. ${w}`))

console.log()
console.log(line('═'))
console.log('④ 溯源')
console.log(line('═'))
const pv = plan.provenance
console.log(`  真实发出的命令：`)
pv.queries.forEach((q) => console.log(`    $ ${q}`))
console.log(`  检索轮次 ${pv.searchRounds} / 候选回来 ${pv.candidatesFound} 条 / 可用景点 ${pv.candidatesKept} 条`)
console.log(`  放宽过规则：${pv.relaxed}    被筛掉的：${pv.rejected.length} 条`)
pv.rejected.slice(0, 6).forEach((r) => console.log(`    - ${r.name}：${r.reason}`))
console.log(`  总耗时 ${(pv.elapsedMs / 1000).toFixed(1)} 秒`)

console.log()
console.log(line('═'))
console.log('⑤ 结论')
console.log(line('═'))
console.log(`  核对点数：${checked}`)
if (problems.length === 0) {
  console.log('  ✅ 每一个点都能追回飞猪候选池里的真实商品（名字、价格逐字一致）')
} else {
  console.log(`  ❌ 有 ${problems.length} 处对不上：`)
  problems.forEach((p) => console.log(`     - ${p}`))
  process.exit(1)
}
console.log(`  全流程 ${((Date.now() - t0) / 1000).toFixed(1)} 秒`)
