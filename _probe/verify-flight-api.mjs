/**
 * 机票那一层的端到端验收 —— 走真实的 /api/plan/generate。
 *
 * ── 它验的是"整条链"，不是"某个函数" ────────────────────────────
 *   单测（PlanServiceFlightTest / LegServiceTest）钉的是判断本身；这个脚本钉的是
 *   **真实的机票数据能不能穿过"查询 → 解析 → 契约 → JSON → 界面"这一整条链**。
 *   两者不能互相替代：单测全绿而链路上少一个字段，是这类功能最常见、也最难发现的失败
 *   （字段名抄错一个字母不会报错，只会让那一行永远不出现）。
 *
 * ── 两个场景，一正一反 ──────────────────────────────────────────
 *   ① 深圳 → 上海 9 天（**长腿**）：火车最快也要 6.5 小时，远超 180 分钟那条上限
 *      → 应当补查一次航班，并把它作为 transfer 的**备选**摆出来（火车仍是主选）
 *   ② 广州 → 珠海 3 天（**短腿**）：一小时内的路
 *      → **一次航班都不该查**（这是"不浪费上游额度"那条规矩的落点）
 *
 *   ②和①同样重要：机票是"多花一次调用"换来的信息，触发条件一旦写松，
 *   每份行程都会多发一两次请求，而实测那套上游会限流（429/451）——
 *   演示当天最可能的表现就是"某个用户没见过的错"。
 *
 * ── 两条纪律（和这个工程其它验收脚本一样）───────────────────────
 *   · **独立复算**：脚本自己再调一次飞猪 CLI，把返回里的航班逐项对一遍 ——
 *     后端说"ZH9501 ¥610"，脚本必须能从原始数据里也读出来。
 *   · **先证明抠取本身在工作**：读任何字段之前，先断言那条链的形状是对的
 *     （否则"字段名写错"会被读成"这个字段是空的"，然后用一句"通过"掩盖过去）。
 *
 * 用法：node verify-flight-api.mjs            （默认打 8004 那台"假模型"实例）
 *       BASE=http://127.0.0.1:8003 node verify-flight-api.mjs
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

/** 从后端返回里取出那一天的交通（顺带把"我抠的地方对不对"验掉） */
function dayOf(res, index) {
  const days = res?.data?.days
  if (!Array.isArray(days)) return null
  return days.at(index)
}

console.log(`\n目标后端：${BASE}\n`)

/* ══════════════════════════════════════════════════════════════
 * ① 长腿：深圳 → 上海 9 天（火车超限 ⇒ 该补查机票）
 * ══════════════════════════════════════════════════════════════ */
console.log('① 深圳 → 上海 9 天（长腿：火车最快 6.5 小时，远超 180 分钟上限）')
const long = await generate({
  origin: '深圳',
  city: '上海',
  days: 9,
  startDate: '2026-11-17',
  categories: ['公园乐园'],
})
console.log(`   生成耗时 ${(long.ms / 1000).toFixed(1)}s  code=${long.body.code}`)
check(long.body.code === 0, '生成成功（code=0）', `${long.body.code} ${long.body.message}`)

const days = long.body?.data?.days
check(Array.isArray(days) && days.length === 9, '★ 自检：拿到了 9 天（抠取位置对不对）',
  `days=${Array.isArray(days) ? days.length : typeof days}`)

const d1 = dayOf(long.body, 0)
const dn = dayOf(long.body, days ? days.length - 1 : 0)
check(!!d1?.transfer, '★ 自检：第 1 天有 transfer（后面所有断言的地基）',
  JSON.stringify(d1?.transfer ?? null).slice(0, 160))

const train = d1?.transfer
check(train?.mode === 'TRAIN',
  '长腿的**主选仍然是火车**（超限只是超出默认上限，不是不能坐）', `mode=${train?.mode}`)
check(train?.durationMinutes > 180,
  '这一趟确实超了上限（否则这个场景根本没触发机票）', `${train?.durationMinutes} 分钟`)

const alts = d1?.alternatives
check(Array.isArray(alts), '★ self-check：alternatives 是一个数组（永远非 null）', typeof alts)
check(Array.isArray(alts) && alts.length >= 1, '⭐ 第 1 天多了一条备选走法',
  JSON.stringify(alts ?? null).slice(0, 200))

const flight = Array.isArray(alts) ? alts.find((a) => a.mode === 'FLIGHT') : null
check(!!flight, '⭐ 那条备选是**飞机**（mode=FLIGHT）', alts ? alts.map((a) => a.mode).join(',') : '无名')

if (flight) {
  console.log(`     航班：${flight.serviceNo} ${flight.depDateTime} ${flight.depStation} → ` +
    `${flight.arrDateTime} ${flight.arrStation}  ${flight.durationMinutes} 分钟  ¥${flight.priceYuan}`)
  check(flight.serviceNo !== null && flight.serviceNo !== '',
    '航班号带出来了（"深航ZH9501" 这种拼好的形式）', String(flight.serviceNo))
  check(typeof flight.priceYuan === 'number' && flight.priceYuan > 0,
    '票价是**数字且在 ticketPrice 那一支上**（读错字段会得到 null，且不报错）',
    String(flight.priceYuan))
  check(typeof flight.durationMinutes === 'number' && flight.durationMinutes > 0,
    '纯飞行时长带出来了（界面要靠它显示"飞行 2 小时 5 分"）', String(flight.durationMinutes))
  check(flight.depDateTime?.startsWith('2026-11-17') && flight.arrDateTime?.startsWith('2026-11-17'),
    '⭐ 和火车**同一天**（两行并排才有意义；跨天的航班会让"同一天的备选"这句话变成假的）',
    `${flight.depDateTime} → ${flight.arrDateTime}`)
  check(flight.jumpUrl !== null, '有飞猪跳转链接（备选也要能直接买）', String(flight.jumpUrl))
  check(flight.seatClass !== null, '舱位带出来了（"经济舱"）', String(flight.seatClass))
}

/* ── 溯源与 warnings：花了哪一次调用、说了哪句话 ────────────────── */
const queries = long.body?.data?.provenance?.queries ?? []
const flightQueries = queries.filter((q) => q.includes('search-flight'))
// 这一段两个方向都超限（深圳↔上海高铁都要 6.5 小时以上），所以**两段各查一次** ——
// 断言写成"每段最多一次"，而不是拍一个数字：真正的规矩是"一段路只花一次调用"，
// 而"一共几次"是画像决定的（这次是 2）。
check(flightQueries.length <= 2,
  '⭐ 每段路最多查一次航班（一次 /api/plan/generate 的最坏成本是有上界的）',
  `实际 ${flightQueries.length} 条：${flightQueries.join(' | ')}`)
check(flightQueries.some((q) => q.includes('--origin 深圳') && q.includes('--destination 上海')),
  '去程那一节查了航班', flightQueries.join(' | '))
check(
  flightQueries.every((q) => !q.includes('--total-duration-hour')),
  '⭐ 发出去的航班命令里**没有** --total-duration-hour（上游对它 400 拒绝，help 里却列着）',
  flightQueries.join(' | '),
)
check(
  flightQueries.some((q) => q.includes('--journey-type 1')),
  '只留直达（不筛的话池子里会有 630 分钟的经停航班，而且最便宜的那条往往最慢）',
  flightQueries.join(' | '),
)

const warnings = long.body?.data?.warnings ?? []
const flightWarn = warnings.filter((w) => w.includes('可以飞') || w.includes('也能飞'))
check(flightWarn.length >= 1, '⭐ warnings 里说了这件事（否则界面多一行、却没有任何解释）',
  warnings.length ? warnings.join(' | ').slice(0, 300) : '一条警告都没有')
check(
  flightWarn.some((w) => w.includes('查询当时') || w.includes('浮动')),
  '⭐ 并且说了**价格是查询当时的**（机票浮动比火车大得多，不说就是把它当成确定数字）',
  flightWarn.join(' | '),
)
check(
  flightWarn.some((w) => w.includes('划不划算') || w.includes('由你定')),
  '★ 并且明确说"不替你判断划不划算"（价格会飘，任何自动结论都是拿飘的数替用户做决定）',
  flightWarn.join(' | '),
)

/* ── 独立复算：脚本自己再问一次飞猪，逐项对 ──────────────────────── */
if (flight) {
  console.log('\n   ── 独立复算（脚本自己再调一次 CLI，不信后端的转述）──')
  const args = [
    'search-flight', '--origin', '深圳', '--destination', '上海',
    '--dep-date', '2026-11-17', '--journey-type', '1', '--sort-type', '4',
  ]
  const { out, code } = await runCli(args)
  let raw = null
  try { raw = JSON.parse(out) } catch { }
  const items = raw?.data?.itemList ?? []
  console.log(`     命令：flyai ${args.join(' ')}   [exit ${code}]  ${items.length} 条`)
  check(items.length > 0, '★ 自检：复算这次也拿到了航班数据（否则下面的"对不上"全是噪声）',
    `itemList=${items.length} status=${raw?.status}`)

  const hit = items.find((it) => {
    const seg = it.journeys?.[0]?.segments?.[0]
    return seg && `${seg.marketingTransportName ?? ''}${seg.marketingTransportNo ?? ''}`.trim() === flight.serviceNo
  })
  check(!!hit, `⭐ 后端给的航班号「${flight.serviceNo}」在原始数据里找得到`, 
    items.slice(0, 3).map((it) => {
      const s = it.journeys?.[0]?.segments?.[0] ?? {}
      return `${s.marketingTransportName ?? ''}${s.marketingTransportNo ?? ''}`
    }).join(' / '))

  if (hit) {
    const seg = hit.journeys[0].segments[0]
    const price = Number(hit.ticketPrice ?? hit.price)
    check(Number(hit.totalDuration) === flight.durationMinutes,
      '⭐ 耗时逐字相同',
      `原始 ${hit.totalDuration} vs 后端 ${flight.durationMinutes}`)
    check(price === flight.priceYuan,
      '⭐ 票价逐字相同（读的是 ticketPrice 那一支）',
      `原始 ${price} vs 后端 ${flight.priceYuan}`)
    check(seg.depDateTime.slice(11, 16) === flight.depDateTime.slice(11, 16),
      '⭐ 起飞时刻逐字相同', `${seg.depDateTime} vs ${flight.depDateTime}`)
    check(seg.depStationShortName === flight.depStation,
      '机场短名逐字相同', `${seg.depStationShortName} vs ${flight.depStation}`)
  }
}

/* ══════════════════════════════════════════════════════════════
 * ② 短腿：广州 → 珠海 3 天（一小时的路 ⇒ 一次航班都不该查）
 * ══════════════════════════════════════════════════════════════ */
console.log('\n② 广州 → 珠海 3 天（短腿：53 分钟，火车完全够用）')
const short = await generate({
  origin: '广州',
  city: '珠海',
  days: 3,
  startDate: '2026-11-17',
  categories: ['海洋馆'],
})
console.log(`   生成耗时 ${(short.ms / 1000).toFixed(1)}s  code=${short.body.code}`)
check(short.body.code === 0, '生成成功（code=0）', `${short.body.code} ${short.body.message}`)

const s1 = dayOf(short.body, 0)
check(!!s1?.transfer, '★ 自检：短腿这次也有 transfer', JSON.stringify(s1?.transfer ?? null).slice(0, 120))
check(s1?.transfer?.mode === 'TRAIN', '主选是火车', `mode=${s1?.transfer?.mode}`)
check(s1?.transfer?.durationMinutes <= 180, '这趟车在上限之内（所以没有理由去问飞机）',
  `${s1?.transfer?.durationMinutes} 分钟`)
check(Array.isArray(s1?.alternatives) && s1.alternatives.length === 0,
  '备选为空 —— 不是 null（前端不用判空）', JSON.stringify(s1?.alternatives))

const shortQueries = short.body?.data?.provenance?.queries ?? []
check(
  !shortQueries.some((q) => q.includes('search-flight')),
  '⭐ 一次航班都没查（这是"不浪费上游额度"那条规矩的落点）',
  shortQueries.join(' | '),
)
check(shortQueries.some((q) => q.includes('search-train')),
  '★ 自检：但车次**确实查了**（否则上面那条会因为"什么都没查"而假通过）',
  shortQueries.join(' | '),
)

// 最后一天（回程）也是长腿，所以它同样该有备选 —— 这一条验的是"两段是对称的"，
// 只挂去程不挂回程是一个很容易漏、而且不报错的写法（复制粘贴时少改一行）
const dnAlts = dn?.alternatives ?? []
check(dnAlts.some((a) => a.mode === 'FLIGHT'),
  '⭐ 回程那一节也查到了航班（两段对称 —— 只给去程挂备选是复制粘贴漏改一行的典型症状）',
  JSON.stringify(dnAlts).slice(0, 200))

const shortWarnings = short.body?.data?.warnings ?? []
check(
  !shortWarnings.some((w) => w.includes('可以飞') || w.includes('也能飞')),
  'warnings 里也没有航班的话（没说却查了、或查了却不说，两头都是错的）',
  shortWarnings.join(' | ').slice(0, 200),
)

console.log(`\n${fail === 0 ? '✅' : '❌'} 通过 ${pass} / ${pass + fail}\n`)
process.exit(fail === 0 ? 0 : 1)
