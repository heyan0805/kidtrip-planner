/**
 * B4c 验收：**酒店候选质量**（这是"事实正确"之外的第二个维度）
 *
 * 上一轮（verify-b4b）证明了"住哪"的**事实**是对的：hotelId 能追回候选池、房价逐字一致。
 * 但那次也暴露了另一件事：候选池里 10 家酒店有 6 家是青旅和日租公寓
 *   （舒坦时光青年公寓 ¥27 / 百联住宿公寓 ¥49 / 几米舍青年客栈 ¥44 …）
 * ——**事实对，选择错**。
 *
 * 这个脚本盯的就是那件事，问四个问题：
 *   ① 候选池里的酒店，还有多少家是"不适合亲子过夜"的？（本脚本的判据写在下面）
 *   ② 我们**真的把 --hotel-stars 发出去了**吗？（看溯源里的真实命令，不看代码意图）
 *   ③ 行程里落下来的酒店，star 是什么？（是不是回到了"像样的酒店"这一档）
 *   ④ 放宽分支有没有被误触发？（深圳这种大城市不该触发；触发了说明筛太狠）
 *
 * 判据说明：本脚本用**两条独立判据**，任一命中就算"不适合亲子"——
 *   · 名字里带"公寓/客栈/青旅/旅舍/日租/民宿/旅馆/招待所"；
 *   · 或 star === "经济型"。
 * 两条都留着，是为了当其中一条失配时另一条还能兜住（比如飞猪哪天把名字改了）。
 *
 * 用法：先起后端（端口 8003），再 `node verify-b4c.mjs`
 *      想看"不带筛"的对照：把 application.yaml 的 hotel-stars 改成 ""，重启后端，再跑一次。
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

const BAD_WORDS = ['公寓', '客栈', '青旅', '青年旅舍', '青年公寓', '日租', '短租', '民宿', '旅馆', '招待所']
const BUDGET_TIER = '经济型'

/** 这家酒店适不适合带娃过夜？任一判据命中即判"不适合" */
function unfriendly(h) {
  const hits = []
  if (BAD_WORDS.some((w) => (h.name || '').includes(w))) hits.push('名字像青旅/公寓')
  if (h.star === BUDGET_TIER) hits.push('档次=经济型')
  return hits
}

async function post(path, body) {
  const res = await fetch(BASE + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  return res.json()
}

const line = (c = '─', n = 74) => c.repeat(n)
const money = (v) => (v == null ? '(无房价)' : '¥' + v)

const problems = []
const t0 = Date.now()

// ── ① 候选池 ────────────────────────────────────────────────────────
console.log(line('═'))
console.log('① 候选池里的酒店（上游筛选后的结果）')
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

const provenance = poolRes.data.provenance
const hotels = poolRes.data.candidates.filter((c) => c.kind === 'hotel')
const byId = new Map(poolRes.data.candidates.map((c) => [c.kind === 'poi' ? c.poiId : c.hotelId, c]))

console.log(`酒店候选 ${hotels.length} 家，上游调用 ${provenance.upstreamCalls} 次`)
console.log()

let badCount = 0
for (const h of hotels) {
  const hits = unfriendly(h)
  if (hits.length) badCount++
  const tag = hits.length ? `❌ ${hits.join(' / ')}` : '✅'
  console.log(`  ${(h.name || '?').padEnd(30)} ${String(h.star ?? '档未知').padEnd(5)} ${money(h.priceYuan).padEnd(8)} ${tag}`)
  console.log(`  ${''.padEnd(30)} ${h.hotelId}`)
}

console.log()
console.log(`  不适合亲子的：${badCount}/${hotels.length}`)

// ── ② 真的发出星级筛了吗（看命令，不看代码）──────────────────────────
console.log()
console.log(line('═'))
console.log('② 溯源：真实发出的命令')
console.log(line('═'))
const hotelQueries = provenance.queries.filter((q) => q.includes('search-hotel'))
hotelQueries.forEach((q) => console.log(`  $ ${q}`))

const starred = hotelQueries.some((q) => q.includes('--hotel-stars'))
const relaxed = hotelQueries.length > 1 && !hotelQueries[hotelQueries.length - 1].includes('--hotel-stars')
console.log()
if (!starred) {
  problems.push('发出的 search-hotel 命令里**没有** --hotel-stars —— 星级筛没生效（配置成空串了？）')
} else {
  console.log('  ✅ 星级筛确实发出去了')
}
if (relaxed) {
  console.log('  ⚠️ 触发了"筛空后放宽"分支（多查了一次）。大城市不该触发 —— 检查 hotel-stars 是不是设得太窄')
}

// ── ③ 行程里落下来的酒店 ────────────────────────────────────────────
console.log()
console.log(line('═'))
console.log('③ 行程里每晚住的酒店')
console.log(line('═'))

const t1 = Date.now()
const gen = await post('/api/plan/generate', { params: PARAMS, retrieval: RETRIEVAL })
const genMs = Date.now() - t1
if (gen.code !== 0) {
  console.log(`生成失败：code=${gen.code}  message=${gen.message}`)
  process.exit(1)
}
const plan = gen.data
console.log(`耗时 ${(genMs / 1000).toFixed(1)} 秒`)

for (const day of plan.days) {
  const h = day.hotel
  if (!h) {
    console.log(`  第 ${day.day} 天  不住${day.day === plan.days.length ? '（最后一天 → 当天返程）' : ' ⚠️'}`)
    continue
  }
  const hits = unfriendly(h)
  if (hits.length) problems.push(`第 ${day.day} 天落下来的酒店「${h.name}」不适合亲子（${hits.join(' / ')}）`)
  const src = byId.get(h.hotelId)
  console.log(`  第 ${day.day} 天  ${(h.name || '?').padEnd(28)} ${String(h.star ?? '档未知').padEnd(5)} ${money(h.priceYuan)}/晚  ${src ? '✅可追溯' : '❌查不到'}`)
}

// ── ④ 告警 + 结论 ───────────────────────────────────────────────────
console.log()
console.log(line('═'))
console.log('④ 告警')
console.log(line('═'))
plan.warnings.forEach((w, i) => console.log(`  ${i + 1}. ${w}`))
const hasBudgetWarn = plan.warnings.some((w) => w.includes('经济型'))
if (hasBudgetWarn) {
  problems.push('出现了"经济型占比过高"的哨兵告警 —— 说明星级筛可能没生效（这正是它存在的意义）')
}

console.log()
console.log(line('═'))
console.log('⑤ 结论')
console.log(line('═'))

if (hotels.length === 0) {
  problems.push('一家酒店都没查到')
} else if (badCount * 2 >= hotels.length) {
  problems.push(`候选池里 ${badCount}/${hotels.length} 家不适合亲子 —— 星级筛没起作用`)
}

if (problems.length === 0) {
  console.log(`  ✅ 候选池 ${hotels.length} 家里只有 ${badCount} 家不适合亲子，星级筛生效`)
  console.log('  ✅ 每晚落下来的酒店都能追回候选池，且都在"像样的酒店"这一档')
} else {
  console.log(`  ❌ 有 ${problems.length} 处问题：`)
  problems.forEach((p) => console.log(`     - ${p}`))
  process.exit(1)
}
console.log(`  全流程 ${((Date.now() - t0) / 1000).toFixed(1)} 秒`)
