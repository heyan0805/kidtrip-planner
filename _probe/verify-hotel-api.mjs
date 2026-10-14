/**
 * 住宿那一层的端到端验收 —— 走真实的 /api/plan/generate。
 *
 * ── 它验的是"整条链"，不是"某个函数" ────────────────────────────
 *   单测（PlanServiceHotelAlternativeTest / RetrievalServiceTest）钉的是规则本身；
 *   这个脚本钉的是**真实的酒店数据能不能穿过"查询 → 解析 → 契约 → JSON → 界面"这一整条链**。
 *   两者不能互相替代：单测全绿而链路上少一个字段，是这类功能最常见的失败
 *   （字段名抄错一个字母不会报错，只会让那一行永远不出现）。
 *
 * ── 这次改动验三件事 ────────────────────────────────────────────
 *   ① **真的查了两档**（`--hotel-stars 3` 和 `--hotel-stars 4` 各一次）。
 *      为什么要两次：上游一次只回一档，多值不是并集（实测 "3,4"/"2,3"/"3,4,5" 回来都是
 *      清一色"舒适型"）。所以"让用户看到不同价位"只能靠查两次。
 *   ② **备选真的露出来了**，而且**跨档**。
 *      这一条是真跑一次才补上的：最初备选写成"按价格取前 3"，实测（广州→珠海）
 *      珠海最便宜的几家恰好全在舒适档，四星档**一家都没露出来** ——
 *      那样第二档那次调用、那份 30 秒预算就是纯浪费，而且没有任何信号。
 *   ③ **每家都有订房链接**（`bookUrl`）。在这之前前端一个字都没渲染这个字段，
 *      也就是说整份行程里唯一能直接把用户带到酒店的那条路一直是断的。
 *
 * ── 两条纪律（和这个工程其它验收脚本一样）───────────────────────
 *   · **独立复算**：脚本自己再调一次飞猪 CLI，把备选酒店逐项对一遍 ——
 *     后端说「鹏城·丽景酒店 ¥108」，脚本必须能从原始数据里也读出来。
 *   · **先证明抠取本身在工作**：读任何字段之前，先断言那条链的形状是对的
 *     （否则"字段名写错"会被读成"这个字段是空的"，然后用一句"通过"掩盖过去）。
 *
 * 用法：node verify-hotel-api.mjs             （默认打 8004 那台"假模型"实例）
 *       BASE=http://127.0.0.1:8003 node verify-hotel-api.mjs
 */
import { FLYAI_CLI } from './_paths.mjs'
import { execFile } from 'node:child_process'

const BASE = process.env.BASE ?? 'http://127.0.0.1:8004'
const CLI = FLYAI_CLI

let pass = 0
let fail = 0
function check(ok, label, detail) {
  if (ok) {
    pass++
    console.log(`  ✅ ${label}`)
  } else {
    fail++
    console.log(`  ❌ ${label}`)
    if (detail !== undefined) console.log(`     实际：${detail}`)
  }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const runCli = (args) =>
  new Promise((res) => {
    execFile('node', [CLI, ...args], { maxBuffer: 1 << 26 }, (e, so, se) =>
      res({ out: so, err: se, code: e ? e.code : 0 }))
  })

async function generate({ origin, city, days, startDate, categories }) {
  const t0 = Date.now()
  const res = await fetch(`${BASE}/api/plan/generate`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      params: { origin, city, days, startDate, pace: '中', partySize: 3, childAge: 8 },
      // 显式给检索条件 = 不问模型规划（省一次调用，也让这次跑的东西可复现）
      retrieval: { wantedCategories: categories, keywords: [], needHotel: true },
    }),
  })
  const body = await res.json()
  return { body, ms: Date.now() - t0 }
}

/** 取某一天的住宿与备选（顺带把"我抠的地方对不对"验掉） */
function stayOf(res, index) {
  const days = res?.data?.days
  if (!Array.isArray(days)) return null
  return days.at(index)
}

/** "¥108" / 108 → 108 */
const yuan = (v) => {
  if (typeof v === 'number') return v
  const m = /(\d+(?:\.\d+)?)/.exec(String(v ?? ''))
  return m ? Number(m[1]) : null
}

console.log(`\n目标后端：${BASE}\n`)

/* ══════════════════════════════════════════════════════════════
 * 广州 → 珠海 3 天：查两档酒店
 * ══════════════════════════════════════════════════════════════ */
console.log('① 广州 → 珠海 3 天（住宿：应当查两档，并把落选的几家摆出来）')
const CITY = '珠海'
const START = '2026-11-17'
const r = await generate({
  origin: '广州',
  city: CITY,
  days: 3,
  startDate: START,
  categories: ['海洋馆'],
})
console.log(`   生成耗时 ${(r.ms / 1000).toFixed(1)}s  code=${r.body.code}`)
check(r.body.code === 0, '生成成功（code=0）', `${r.body.code} ${r.body.message}`)

const days = r.body?.data?.days
check(Array.isArray(days) && days.length === 3, '★ 自检：拿到了 3 天（抠取位置对不对）',
  `days=${Array.isArray(days) ? days.length : typeof days}`)

const d1 = stayOf(r.body, 0)
check(!!d1, '★ 自检：拿得到第 1 天（后面所有断言的地基）', JSON.stringify(d1 ?? null).slice(0, 120))
check(!!d1?.hotel, '★ 自检：第 1 天有主选酒店（"排除主选"那条断言要有东西可排）',
  JSON.stringify(d1?.hotel ?? null).slice(0, 160))
check(Array.isArray(d1?.hotelAlternatives), '★ 自检：hotelAlternatives 是一个数组（永远非 null）',
  typeof d1?.hotelAlternatives)

/* ── ① 查询命令：两档、带日期 ─────────────────────────────────── */
const queries = r.body?.data?.provenance?.queries ?? []
const hotelQueries = queries.filter((q) => q.includes('search-hotel'))
console.log(`   酒店查询 ${hotelQueries.length} 条：`)
for (const q of hotelQueries) console.log(`     ${q}`)

check(hotelQueries.length === 2,
  '⭐ 查了**两次**（上游一次只回一档，多值不是并集 —— 想跨档只能查两次）',
  `${hotelQueries.length} 条：${hotelQueries.join(' | ')}`)
check(hotelQueries.some((q) => q.includes('--hotel-stars 3')),
  '⭐ 其中一次是舒适档（--hotel-stars 3，如家/维也纳那一档）', hotelQueries.join(' | '))
check(hotelQueries.some((q) => q.includes('--hotel-stars 4')),
  '⭐ 另一次是四星/高档档（--hotel-stars 4）', hotelQueries.join(' | '))
check(hotelQueries.every((q) => q.includes(`--check-in-date ${START}`)),
  '⭐ 两次都带了入离日期（不带日期上游**不回传 star**，档次那一栏会全是 null）',
  hotelQueries.join(' | '))
check(hotelQueries.every((q) => q.includes(`--dest-name ${CITY}`)),
  '两次查的都是目的地那一座城（出发地只在交通那一层出现）', hotelQueries.join(' | '))

/* ── ② 备选：露出来了、跨档、按价格、不含主选 ──────────────────── */
const allAlts = days.flatMap((d) => d?.hotelAlternatives ?? [])
console.log(`\n   备选合计 ${allAlts.length} 条；第 1 天：`)
for (const a of d1?.hotelAlternatives ?? []) {
  console.log(`     ¥${a.priceYuan}  ${a.star ?? '(档次未知)'}  ${a.name}`)
}

check(Array.isArray(d1?.hotelAlternatives) && d1.hotelAlternatives.length > 0,
  '⭐ 第 1 天摆出了备选（在这之前用户每天只看到一家，还不知道那是从 10 家里挑的）',
  JSON.stringify(d1?.hotelAlternatives ?? null).slice(0, 200))

const daysWithAlts = days.filter((d) => (d?.hotelAlternatives ?? []).length > 0).length
check(daysWithAlts === days.length,
  '⭐ 每一天都挂上了备选（连住时是同一份 —— "哪几天算连住"由前端合并显示）',
  `${daysWithAlts}/${days.length} 天有备选`)

check(d1.hotelAlternatives.every((a) => a.hotelId !== d1.hotel?.hotelId),
  '⭐ 备选里**没有**主选那一家（否则像是在复读"你也可以住你正住的那家"）',
  d1.hotelAlternatives.map((a) => `${a.name}(${a.hotelId})`).join(' | ') +
  `  主选=${d1.hotel?.name}(${d1.hotel?.hotelId})`)

check(d1.hotelAlternatives.length <= 3,
  '⭐ 最多 3 家（再多就从"一个决定"变成"一份列表"）',
  `${d1.hotelAlternatives.length} 家`)

const prices = d1.hotelAlternatives.map((a) => a.priceYuan)
const sortedPrices = [...prices].sort((x, y) => (x ?? Infinity) - (y ?? Infinity))
check(JSON.stringify(prices) === JSON.stringify(sortedPrices),
  '⭐ 按价格升序摆（用户看这一行就是为了找更便宜的，顺序乱了就白瞎了）',
  JSON.stringify(prices))

const stars = d1.hotelAlternatives.map((a) => a.star).filter(Boolean)
check(new Set(stars).size >= 2,
  '⭐⭐ 备选**跨档**（全是一个档次的话，第二次查询就是纯浪费 —— 这条是真跑一次才补上的）',
  `档次：${stars.join(' / ')}`)

check(d1.hotelAlternatives.every((a) => typeof a.bookUrl === 'string' && a.bookUrl.length > 0),
  '⭐ 每条备选都有订房链接（备选只能看不能订就只是个装饰）',
  d1.hotelAlternatives.map((a) => String(a.bookUrl).slice(0, 40)).join(' | '))

/* ── ③ 主选也有订房链接（这是这次补上的另一个洞）────────────────── */
check(typeof d1?.hotel?.bookUrl === 'string' && d1.hotel.bookUrl.includes('feizhu'),
  '⭐ 主选酒店带飞猪订房链接（实测 302 落到带入离日期的详情页；前端以前一个字都没渲染它）',
  String(d1?.hotel?.bookUrl))
check(d1.hotel.bookUrl !== d1.hotelAlternatives[0]?.bookUrl,
  '主选和备选指向的是不同的酒店（链接要跟着酒店走，不能是同一个通用页）',
  `${String(d1.hotel.bookUrl).slice(0, 60)}  vs  ${String(d1.hotelAlternatives[0]?.bookUrl).slice(0, 60)}`)

/* ── ④ 哨兵：星级筛没有失效 ───────────────────────────────────── */
const warnings = r.body?.data?.warnings ?? []
const budgetWarn = warnings.filter((w) => w.includes('经济型'))
check(budgetWarn.length === 0,
  '星级筛生效（结果里没有重新冒出一堆"经济型" —— 那说明筛选被上游忽略了）',
  budgetWarn.join(' | '))
check(!warnings.some((w) => w.includes('没有查到可用的酒店')),
  '查到了酒店（不是"放宽筛选"那条降级路）', warnings.join(' | ').slice(0, 200))

/* ── ⑤ 独立复算：脚本自己再调一次 CLI，逐项对 ──────────────────── */
console.log('\n   ── 独立复算（脚本自己再调两次 CLI，不信后端的转述）──')
const rawByName = new Map()
for (const tier of ['3', '4']) {
  const args = ['search-hotel', '--dest-name', CITY, '--check-in-date', START,
    '--check-out-date', '2026-11-20', '--hotel-stars', tier]
  const { out, code } = await runCli(args)
  let raw = null
  try { raw = JSON.parse(out) } catch { }
  const items = raw?.data?.itemList ?? []
  console.log(`     flyai ${args.join(' ')}   [exit ${code}]  ${items.length} 条`)
  for (const h of items) rawByName.set(h.name, h)
  await sleep(3000)
}

check(rawByName.size > 0, '★ 自检：复算拿到了原始酒店数据（否则下面的"对不上"全是噪声）',
  `${rawByName.size} 家`)

// 主选 + 备选，逐家去原始数据里对名字和价格
const toCheck = [d1.hotel, ...(d1.hotelAlternatives ?? [])].filter(Boolean)
let matched = 0
for (const h of toCheck) {
  const raw = rawByName.get(h.name)
  if (!raw) {
    console.log(`     ⚠️  原始数据里没有「${h.name}」（可能是那两次查询被上游限流/截断）`)
    continue
  }
  matched++
  check(yuan(raw.price) === h.priceYuan,
    `⭐ 「${h.name}」的房价逐字相同`,
    `原始 ${raw.price} vs 后端 ${h.priceYuan}`)
  check((raw.star ?? '').trim() === (h.star ?? ''),
    `⭐ 「${h.name}」的档次词逐字相同（读错字段会得到 null，且不报错）`,
    `原始 ${JSON.stringify(raw.star)} vs 后端 ${JSON.stringify(h.star)}`)
}
check(matched > 0, '⭐ 至少对上了一家（对不上说明候选池和原始响应不是同一批数据）',
  `对上 ${matched}/${toCheck.length} 家`)

/* ══════════════════════════════════════════════════════════════
 * 汇总
 * ══════════════════════════════════════════════════════════════ */
console.log(`\n${'═'.repeat(60)}`)
console.log(`共 ${pass + fail} 项：✅ ${pass}  ❌ ${fail}`)
if (fail > 0) {
  console.log('失败项见上面的 ❌')
  process.exitCode = 1
}
