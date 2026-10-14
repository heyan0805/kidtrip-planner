/**
 * 回程车那次修复的端到端验收（真实浏览器/HTTP，不是单测）。
 *
 * 要回答的是用户那句质问背后的问题：
 *   「这个车票，要下午回去，怎么买了凌晨6点的车票？」
 *
 * 三件事必须同时成立，缺一件都不算修好：
 *   ① 回程车落在"下午走、晚上到家"的窗口里（13:00 ≤ 发车，到达 ≤ 20:00）
 *   ② **独立复算**：脚本自己从缓存里的原始候选重挑一遍，逐项和响应对上
 *      —— 官方算法和"我以为的算法"不一致时，只有这一步能发现
 *   ③ 第 1 天和最后一天的安排和那两个时刻对得上；对不上时**必须有一句说明**
 *      （诚实比正确更重要：安排错了还说没事，才是真的坏了）
 *
 * 用法：
 *   node _probe/verify-inbound.mjs
 */

import { readFileSync } from 'node:fs'

const API = 'http://127.0.0.1:8003/api'
const CACHE = new URL('./flyai-cache.json', import.meta.url).pathname.replace(/^\//, '')

let pass = 0
let fail = 0

function check(ok, label, detail) {
  if (ok) {
    pass++
    console.log(`  ✅ ${label}`)
  } else {
    fail++
    console.log(`  ❌ ${label}${detail ? '\n       → ' + detail : ''}`)
  }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

/* ── 从缓存里取某条查询的**原始**候选（不经过任何我们的加工） ────── */
function rawCandidates(origin, destination, date) {
  const cache = JSON.parse(readFileSync(CACHE, 'utf8'))
  for (const entry of Object.values(cache.entries ?? {})) {
    const args = entry.args ?? []
    if (args[0] !== 'search-train') continue
    const at = (flag) => args[args.indexOf(flag) + 1]
    if (at('--origin') !== origin || at('--destination') !== destination) continue
    if (at('--dep-date') !== date) continue
    return JSON.parse(entry.raw).data?.itemList ?? []
  }
  return null
}

/* ── 复现 LegService.pick 的规则（**照抄产品意图，不是照抄代码**） ── */
const OUTBOUND_EARLIEST = '07:00'
const OUTBOUND_LATEST = '13:00'
const RETURN_EARLIEST = '13:00'
const RETURN_ARRIVE_BY = '20:00'

function hhmm(stamp) {
  if (!stamp || stamp.length < 16) return null
  return stamp.slice(11, 16)
}

function reproPick(origin, destination, date, direction) {
  const items = rawCandidates(origin, destination, date)
  if (!items) return null
  const within = items.filter((it) => Number(it.totalDuration) <= 180)
  for (const it of within) {
    const segs = it.journeys?.[0]?.segments ?? []
    if (!segs.length) continue
    const dep = hhmm(segs[0].depDateTime)
    if (!dep) continue
    if (direction === 'OUTBOUND') {
      if (dep >= OUTBOUND_EARLIEST && dep <= OUTBOUND_LATEST) return it
    } else {
      const arr = hhmm(segs[segs.length - 1].arrDateTime)
      if (arr && dep >= RETURN_EARLIEST && arr <= RETURN_ARRIVE_BY) return it
    }
  }
  return within[0] ?? null
}

function describe(item) {
  const segs = item.journeys[0].segments
  const f = segs[0]
  const l = segs[segs.length - 1]
  return {
    serviceNo: (f.marketingTransportName ?? '') + (f.marketingTransportNo ?? ''),
    dep: hhmm(f.depDateTime),
    arr: hhmm(l.arrDateTime),
    depStation: f.depStationShortName,
    arrStation: l.arrStationShortName,
    minutes: Number(item.totalDuration),
  }
}

const plusMinutes = (hhmmStr, n) => {
  const [h, m] = hhmmStr.split(':').map(Number)
  const total = h * 60 + m + n
  return String(Math.floor(total / 60)).padStart(2, '0') + ':' + String(total % 60).padStart(2, '0')
}

/* ══════════════════════════════════════════════════════════════ */

const ORIGIN = '深圳'
const CITY = '广州'
const DAYS = 5
const START = '2026-11-17'
const END = '2026-11-21'   // 11-17 + 4 天

console.log('\n【0】后端在不在')
let health
try {
  health = await (await fetch(`${API}/health`)).json()
} catch (e) {
  console.log('  ❌ 后端连不上，先启动它：', e.message)
  process.exit(1)
}
check(health?.code === 0, '健康检查通过')

console.log(`\n【1】${ORIGIN} → ${CITY} ${DAYS} 天（就是用户那一单）`)
const t0 = Date.now()
const res = await (await fetch(`${API}/plan/generate`, {
  method: 'POST',
  headers: { 'content-type': 'application/json' },
  body: JSON.stringify({ params: { origin: ORIGIN, city: CITY, days: DAYS, startDate: START, childAge: 7, pace: '慢' } }),
})).json()
const elapsed = ((Date.now() - t0) / 1000).toFixed(1)

check(res?.code === 0, '生成成功', res?.message)
const days = res?.data?.days ?? []
check(days.length === DAYS, `排出了 ${DAYS} 天`, `实际 ${days.length} 天`)

const outbound = days[0]?.transfer
const inbound = days[days.length - 1]?.transfer

console.log(`\n【2】回程车：要下午走、别太晚到家`)
check(!!inbound, '最后一班有交通信息', 'transfer 是 null')
if (inbound) {
  const dep = inbound.depDateTime.slice(11, 16)
  const arr = inbound.arrDateTime.slice(11, 16)
  console.log(`     实际拿到：${dep} ${inbound.depStation} → ${arr} ${inbound.arrStation}  ${inbound.serviceNo}  ${inbound.durationMinutes} 分钟`)

  check(dep >= RETURN_EARLIEST, `发车不早于 ${RETURN_EARLIEST}（用户要下午走）`, `实际 ${dep}`)
  check(arr <= RETURN_ARRIVE_BY, `到达不晚于 ${RETURN_ARRIVE_BY}（别把疲劳带回家）`, `实际 ${arr}`)
  check(dep !== '06:18', '⭐ 不再是那趟凌晨 06:18 的车')
  check(inbound.from === CITY && inbound.to === ORIGIN, `方向是 ${CITY} → ${ORIGIN}`,
    `实际 ${inbound.from} → ${inbound.to}`)
}

console.log(`\n【3】去程车：上午出发，别傍晚才到`)
if (outbound) {
  const dep = outbound.depDateTime.slice(11, 16)
  console.log(`     实际拿到：${dep} ${outbound.depStation} → ${outbound.arrDateTime.slice(11, 16)} ${outbound.arrStation}  ${outbound.serviceNo}`)
  check(dep >= OUTBOUND_EARLIEST && dep <= OUTBOUND_LATEST, `发车落在 ${OUTBOUND_EARLIEST}~${OUTBOUND_LATEST}`, `实际 ${dep}`)
} else {
  check(false, '第一天有交通信息')
}

console.log(`\n【4】⭐ 独立复算：脚本自己重挑一遍，逐项对账`)
for (const [label, direction, from, to, date, actual] of [
  ['去程', 'OUTBOUND', ORIGIN, CITY, START, outbound],
  ['回程', 'RETURN', CITY, ORIGIN, END, inbound],
]) {
  const expected = reproPick(from, to, date, direction)
  if (!expected) {
    check(false, `${label}：缓存里没找到 ${from}→${to} ${date} 的原始候选`,
      '预跑缓存没覆盖这一条 —— 现场会真去查上游')
    continue
  }
  const e = describe(expected)
  console.log(`     复算结果：${e.dep} ${e.depStation} → ${e.arr} ${e.arrStation}  ${e.serviceNo}  ${e.minutes} 分钟`)
  check(
    actual && actual.serviceNo === e.serviceNo && actual.depDateTime.slice(11, 16) === e.dep
      && actual.arrDateTime.slice(11, 16) === e.arr && actual.durationMinutes === e.minutes,
    `${label}：官方结果和独立复算完全一致`,
    actual ? `官方 ${actual.serviceNo} ${actual.depDateTime.slice(11, 16)} vs 复算 ${e.serviceNo} ${e.dep}`
      : '官方是 null'
  )
}

console.log(`\n【5】第一天和最后一天的安排，跟车次对得上吗`)
const spotsOf = (d) => (d?.spots ?? []).map((s) => s.time).filter(Boolean).sort()
const warnings = res?.data?.warnings ?? []

if (outbound && days[0]?.spots?.length) {
  const arrive = outbound.arrDateTime.slice(11, 16)
  const allowed = plusMinutes(arrive, 60)
  const earliest = spotsOf(days[0])[0]
  const ok = earliest >= allowed
  const explained = warnings.some((w) => w.includes('第 1 天') && w.includes('赶不上'))
  console.log(`     第一天最早的点 ${earliest}，车 ${arrive} 到站，允许的最早 ${allowed}`)
  check(ok || explained, '第 1 天要么排得合理，要么明确说了赶不上', ok ? '' : '既排得太早，又没有任何说明')
}

if (inbound && days[days.length - 1]?.spots?.length) {
  const depart = inbound.depDateTime.slice(11, 16)
  const allowed = plusMinutes(depart, -180)
  const latest = spotsOf(days[days.length - 1]).slice(-1)[0]
  const ok = latest <= allowed
  const explained = warnings.some((w) => w.includes('最后一天') && w.includes('赶不上'))
  console.log(`     最后一天最晚的点 ${latest}，车 ${depart} 发车，必须收尾于 ${allowed}`)
  check(ok || explained, '最后一天要么排得合理，要么明确说了赶不上', ok ? '' : '既排得太晚，又没有任何说明')
} else if (inbound) {
  check(true, '最后一天没有安排景点 —— 返程日不排点也是合理的（甚至更稳）')
}

console.log(`\n【6】溯源里能看见这两条真实查询`)
const queries = res?.data?.provenance?.queries ?? []
check(queries.some((q) => q.includes(`search-train --origin ${ORIGIN} --destination ${CITY}`)),
  `去程查询在溯源里`)
check(queries.some((q) => q.includes(`search-train --origin ${CITY} --destination ${ORIGIN}`)),
  `回程查询在溯源里`)

console.log(`\n${'═'.repeat(60)}`)
console.log(`  通过 ${pass} / 失败 ${fail}    （生成耗时 ${elapsed} 秒）`)
if (warnings.length) {
  console.log('\n  这次带回来的注意事项：')
  warnings.forEach((w) => console.log(`    · ${w}`))
}
console.log('')
process.exit(fail === 0 ? 0 : 1)
