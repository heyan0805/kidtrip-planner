import { FLYAI_CLI } from './_paths.mjs'
/**
 * L1 验收：城际交通真的进了行程，而且数字是**独立复算**过的。
 *
 * 这个脚本和别的验收脚本最大的不同：它不只检查"接口返回里有个 transfer"，
 * 而是**自己再直接调一次飞猪 CLI**，把结果和接口返回的数字逐项对上。
 *
 * 为什么要这么麻烦：transfer 的字段全是"从另一门语言（JSON）抄过来再翻译一遍"的产物，
 * 而这类代码最典型的失败是**静默错位** —— 站名抄到了时刻栏、中转站当成了目的地、
 * 分钟当成了秒。这些都不会抛异常，只会让界面上显示一个"看起来正常但不对"的数字，
 * 而用户没有任何办法发现。
 *
 * ⚠️ 脚本会在不通过时 `exit 1`。判据必须能报错 ——
 *    一个永远 bless 的验收脚本等于没有验收（这条是 B4c 那次踩出来的）。
 */
import { execFile } from 'node:child_process'

const APP = 'http://127.0.0.1:8003/api'
const CLI = FLYAI_CLI

const START = '2026-11-17'
const DAYS = 3
const ORIGIN = '广州'
const CITY = '珠海'
/** 最后一天的日期 = 出发日 + (天数 - 1)。回程查的是这一天 */
const END = '2026-11-19'

let pass = 0
const failures = []

function check(ok, label, detail) {
  if (ok) {
    pass++
    console.log(`  ✓ ${label}`)
  } else {
    failures.push(label)
    console.log(`  ✗ ${label}${detail ? `\n      ${detail}` : ''}`)
  }
}

async function post(path, body) {
  const res = await fetch(`${APP}${path}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
  })
  return res.json()
}

function cli(args) {
  return new Promise((resolve) => {
    execFile('node', [CLI, ...args], { maxBuffer: 1 << 26 }, (e, so) =>
      resolve(JSON.parse(so || '{}')),
    )
  })
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

/** "2026-11-17T08:36" → "08:36" */
const hhmm = (iso) => (iso && iso.length >= 16 ? iso.slice(11, 16) : iso)

console.log('\n══════ L1 验收：城际交通进入行程 ══════')

/* ── 0. 健康检查 ───────────────────────────────────────────── */
const health = await (await fetch(`${APP}/health`)).json()
check(health?.code === 0, '后端在线', JSON.stringify(health))

/* ── 1. 正例：广州 → 珠海 3 天 ──────────────────────────────── */
console.log('\n【1】正例：广州 → 珠海 3 天')
const ok = await post('/plan/generate', {
  params: { origin: ORIGIN, city: CITY, days: DAYS, startDate: START, childAge: 6, pace: '慢' },
})
check(ok?.code === 0, '/generate 返回成功', JSON.stringify(ok).slice(0, 300))

const plan = ok?.data
const days = plan?.days ?? []
check(days.length === DAYS, `行程有 ${DAYS} 天`)

const outbound = days[0]?.transfer
const inbound = days[days.length - 1]?.transfer

check(!!outbound, '第 1 天有去程交通', JSON.stringify(days[0]).slice(0, 200))
check(!!inbound, '最后一天有回程交通', JSON.stringify(days.at(-1)).slice(0, 200))

if (outbound) {
  check(outbound.from === ORIGIN && outbound.to === CITY,
    `去程方向是 ${ORIGIN} → ${CITY}`, `实际 ${outbound.from} → ${outbound.to}`)
  check(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(outbound.depDateTime ?? ''),
    'depDateTime 是 ISO 到分钟', outbound.depDateTime)
  check((outbound.depDateTime ?? '').startsWith(START),
    `去程发车日是出发日 ${START}`, outbound.depDateTime)
  check(typeof outbound.durationMinutes === 'number' && outbound.durationMinutes > 0,
    '耗时是正整数（分钟）', String(outbound.durationMinutes))
  check(outbound.durationMinutes <= 180, '耗时在上限 180 分钟内（这是产品红线）',
    `${outbound.durationMinutes} 分钟`)
  check(!!outbound.depStation && !!outbound.arrStation, '有上/下车车站名',
    `${outbound.depStation} / ${outbound.arrStation}`)
}

if (inbound) {
  check(inbound.from === CITY && inbound.to === ORIGIN,
    `回程方向是 ${CITY} → ${ORIGIN}（方向读 from/to，不靠天数推断）`,
    `实际 ${inbound.from} → ${inbound.to}`)
  check((inbound.depDateTime ?? '').startsWith(END),
    `回程发车日是最后一天 ${END}`, inbound.depDateTime)
}

/* ── 2. 溯源里有真实命令 ─────────────────────────────────────── */
console.log('\n【2】溯源：发出去的查询命令')
const queries = plan?.provenance?.queries ?? []
const trainQueries = queries.filter((q) => q.includes('search-train'))
check(trainQueries.length === 2, '有两条车次查询（去 + 回）', `实际 ${trainQueries.length} 条`)
check(trainQueries.some((q) => q.includes(`--origin ${ORIGIN}`) && q.includes(`--destination ${CITY}`)),
  '去程命令的起终点正确')
check(trainQueries.some((q) => q.includes(`--origin ${CITY}`) && q.includes(`--destination ${ORIGIN}`)),
  '回程命令的起终点正确')
check(trainQueries.every((q) => q.includes('--total-duration-hour 3')),
  '都带上了 3 小时上限（180 分钟）',
  trainQueries.join(' | '))
check(trainQueries.some((q) => q.includes(`--dep-date ${START}`)), '去程带了出发日')
check(trainQueries.some((q) => q.includes(`--dep-date ${END}`)), '回程带了最后一天')

/* ── 3. ⭐ 独立复算：自己再调一次飞猪，逐项对 ─────────────────── */
console.log('\n【3】⭐ 独立复算（防止字段静默错位）')
if (outbound) {
  await sleep(4000) // 让后端那边的限流窗口过去，我们自己的调用别撞上去
  const raw = await cli([
    'search-train', '--origin', ORIGIN, '--destination', CITY,
    '--dep-date', START, '--total-duration-hour', '3', '--sort-type', '4',
  ])
  const items = raw?.data?.itemList ?? []
  check(items.length > 0, '独立查询也拿到了候车', `命中 ${items.length}`)

  // 复现 LegService.pick 的去程规则：时长升序里第一条"发车在 07:00~13:00"的
  const pickOutbound = (list) => {
    for (const t of list) {
      const dt = t.journeys?.[0]?.segments?.[0]?.depDateTime
      if (!dt) continue
      const hh = Number(dt.slice(11, 13))
      if (hh >= 7 && hh <= 13) return t
    }
    return list[0]
  }
  const expected = pickOutbound(items)
  const expSeg = expected?.journeys?.[0]?.segments?.[0]

  check(String(outbound.durationMinutes) === String(expected?.totalDuration),
    '耗时和独立查询一致',
    `接口 ${outbound.durationMinutes} vs 直查 ${expected?.totalDuration}`)
  check(outbound.depStation === expSeg?.depStationShortName,
    '上车站在独立查询里能对上',
    `接口 ${outbound.depStation} vs 直查 ${expSeg?.depStationShortName}`)
  check(outbound.arrStation === expSeg?.arrStationShortName,
    '下车站在独立查询里能对上',
    `接口 ${outbound.arrStation} vs 直查 ${expSeg?.arrStationShortName}`)
  check(hhmm(outbound.depDateTime) === expSeg?.depDateTime?.slice(11, 16),
    '发车时刻能对上',
    `接口 ${hhmm(outbound.depDateTime)} vs 直查 ${expSeg?.depDateTime?.slice(11, 16)}`)
  check(Number(outbound.priceYuan) === Number(expected?.price),
    '票价能对上',
    `接口 ${outbound.priceYuan} vs 直查 ${expected?.price}`)
}

/* ── 4. 反例 A：两地之间没有够快的直达 ────────────────────────── */
console.log('\n【4】反例 A：珠海 → 桂林（实测无 3 小时内直达）')
await sleep(4000)
const noEdge = await post('/plan/generate', {
  params: { origin: '珠海', city: '桂林', days: 3, startDate: START, childAge: 6 },
})
check(noEdge?.code === 0, '仍然生成了行程（交通缺失不该让整份行程失败）')
const noEdgeWarnings = noEdge?.data?.warnings ?? []
const edgeWarn = noEdgeWarnings.find((w) => w.includes('带娃偏累'))
check(!!edgeWarn, '⚠️ 说出了"这段路带娃偏累"这个**结论**',
  JSON.stringify(noEdgeWarnings))
check(!noEdgeWarnings.some((w) => w.includes('没查成')),
  '⚠️ 且**没有**误报成"查询失败"（两者结论相反，混了会让用户永远放弃一条存在的路线）',
  JSON.stringify(noEdgeWarnings))
check(noEdge?.data?.days?.[0]?.transfer === null, '第 1 天没有交通信息（而不是编一个）')

/* ── 5. 反例 B：同城（本地游）────────────────────────────────── */
console.log('\n【5】反例 B：origin === city（本地游，不应该去查城际交通）')
const local = await post('/plan/generate', {
  params: { origin: '珠海', city: '珠海', days: 3, startDate: START, childAge: 6 },
})
check(local?.code === 0, '本地游能正常生成')
const localQueries = local?.data?.provenance?.queries ?? []
check(!localQueries.some((q) => q.includes('search-train')),
  '⚠️ 一条车次查询都没发（同城还去查，就是在白花额度）', localQueries.join(' | '))
check(local?.data?.days?.[0]?.transfer === null, '第 1 天没有交通信息')
check(!(local?.data?.warnings ?? []).some((w) => w.includes('车次')),
  '⚠️ 也没有报"没有车次"（本地出行不是故障）',
  JSON.stringify(local?.data?.warnings))

/* ── 6. 反例 C：没说出发地 ──────────────────────────────────── */
console.log('\n【6】反例 C：画像里没有 origin')
await sleep(2000)
const noOrigin = await post('/plan/generate', {
  params: { city: CITY, days: 3, startDate: START, childAge: 6 },
})
check(noOrigin?.code === 0, '⚠️ 没有出发地也能生成行程（缺信息 ≠ 出错）')
const noOriginQueries = noOrigin?.data?.provenance?.queries ?? []
check(!noOriginQueries.some((q) => q.includes('search-train')), '没查车次')
check(noOrigin?.data?.days?.[0]?.transfer === null, '第 1 天安静地空着，不编一段交通')

/* ── 7. 反例 D：区域说法当出发地 ─────────────────────────────── */
console.log('\n【7】反例 D：出发地是"广东周边"（区域说法）')
await sleep(2000)
const badOrigin = await post('/plan/generate', {
  params: { origin: '广东周边', city: CITY, days: 3, startDate: START },
})
// 注意：/generate 这条路**不经过对话层的 sanitize**（脚本会直接调它），
// 所以这里要的是"别把一条注定空的查询发出去"，而不是"静默改成某个城市"。
//
// ⚠️ 光检查"没发查询"是不够的 —— 500 的时候 data 是 undefined，
//    下面那句 `?? []` 会把它兜成空数组，`.some()` 就成了 false，**检查照样通过**。
//    一个会在真故障上放行的验收项，比没有这条验收更危险：它给人"验过了"的错觉。
//    所以必须先钉住 code === 0 和 data 的存在，再谈查询。
check(badOrigin?.code === 0, '⚠️ 非法出发地不该让 /generate 崩（它只是查不了车次）',
  JSON.stringify(badOrigin).slice(0, 300))
check(badOrigin?.data?.days?.length === DAYS, '行程本身照样排出来了',
  `实际 ${badOrigin?.data?.days?.length} 天`)
const badOriginQueries = badOrigin?.data?.provenance?.queries ?? []
check(!badOriginQueries.some((q) => q.includes('search-train --origin 广东周边')),
  '⚠️ 没有把"广东周边"当出发地发出去',
  badOriginQueries.filter((q) => q.includes('search-train')).join(' | '))
const badOriginWarnings = badOrigin?.data?.warnings ?? []
check(badOriginWarnings.some((w) => w.includes('车次没能查')),
  '⚠️ 说清了"车次没能查"（而不是假装没有车次）',
  JSON.stringify(badOriginWarnings))
check(!badOriginWarnings.some((w) => w.includes('带娃偏累')),
  '⚠️ **没有**误报成"这段路带娃偏累" —— 我们根本没查成，无权下这个结论',
  JSON.stringify(badOriginWarnings))
check(badOrigin?.data?.days?.[0]?.transfer === null, '第 1 天安静地空着，不编一段交通')

/* ── 结果 ─────────────────────────────────────────────────── */
console.log(`\n══════ 结果：${pass} 项通过，${failures.length} 项失败 ══════`)
if (failures.length > 0) {
  console.log('失败项：')
  for (const f of failures) console.log(`  · ${f}`)
  process.exit(1)
}
console.log('全部通过。')
