/**
 * 验收：一段「最快班次也超过上限」的路，会不会被整条丢掉
 *
 * ── 它要证的那件事 ──────────────────────────────────────────────
 *   用户报的现象是"生成出来的行程里没有车票信息"。根因不在界面，在**取数**：
 *   `search-train` 的时长粗筛窗口原来等于那条上限（180 分钟 → 3 小时），
 *   而深圳 → 上海最快的高铁约 6.5 小时 —— 上游按时长筛完返回"结果为空"，
 *   我们把它读成"这段路没有走法"。**一条真实存在的路线就这样被静默拿掉了**，
 *   不报错、不告警，只是行程里少了一整行。
 *
 *   所以这个脚本盯三件事：
 *     ① 去程 / 回程**都有** transfer（车票信息真的出现在行程里了）；
 *     ② 若耗时确实超限，警告说的是"最快的一班也要 X 小时"——
 *        而**不是**"在 180 分钟内没有直达车次"（那是一句听起来很确定的假话）；
 *     ③ 警告里那个耗时数字，脚本**自己复算一遍**对得上 —— 不是随口写的。
 *
 * ── 它会真的花上游调用 ──────────────────────────────────────────
 *   缓存里存的是旧窗口（3h）的结果，新窗口是 12h —— key 不同，
 *   所以这一趟**一定会真查上游**。这正好顺带证明了另一件事：
 *   "上游在 12 小时窗口下确实查得到深圳 → 上海的车"。要是它还是空的，
 *   那问题就不在窗口，得换思路。
 *
 * 用法：
 *   node _probe/verify-leg-overlimit.mjs
 *   KIDTRIP_BASE=http://127.0.0.1:8003/api node _probe/verify-leg-overlimit.mjs
 *   KIDTRIP_ORIGIN=深圳 KIDTRIP_CITY=上海 node _probe/verify-leg-overlimit.mjs
 */
const BASE = process.env.KIDTRIP_BASE ?? 'http://127.0.0.1:8003/api'
const ORIGIN = process.env.KIDTRIP_ORIGIN ?? '深圳'
const CITY = process.env.KIDTRIP_CITY ?? '上海'
const DAYS = Number(process.env.KIDTRIP_DAYS ?? 9)
const START = process.env.KIDTRIP_START ?? '2026-11-17'
const CHILD_AGE = Number(process.env.KIDTRIP_CHILD_AGE ?? 7)
/** 后端 kidtrip.flyai.max-leg-minutes 的默认值。改了配置就得跟着改这里 */
const LEG_LIMIT_MINUTES = Number(process.env.KIDTRIP_LEG_LIMIT ?? 180)

let pass = 0
let fail = 0
function check(ok, label, detail = '') {
  if (ok) {
    pass++
    console.log(`  \u2705 ${label}${detail ? '  \u2014\u2014 ' + detail : ''}`)
  } else {
    fail++
    console.log(`  \u274c ${label}${detail ? '  \u2014\u2014 ' + detail : ''}`)
  }
}

console.log(`\n=== 城际车次超限验收\n    ${BASE}\n    ${ORIGIN} → ${CITY}，${DAYS} 天，${START} 出发，上限 ${LEG_LIMIT_MINUTES} 分钟\n`)
console.log('    （这一趟会真的去查上游 —— 缓存里的旧结果是 3 小时窗口的，对不上新窗口）\n')

const t0 = Date.now()
const gen = await fetch(`${BASE}/plan/generate`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    params: { origin: ORIGIN, city: CITY, days: DAYS, startDate: START, childAge: CHILD_AGE, pace: '慢' },
  }),
})
  .then((r) => r.json())
  .catch((e) => ({ _error: String(e) }))
console.log(`  生成耗时 ${((Date.now() - t0) / 1000).toFixed(1)} 秒\n`)

/* ── ① 生成本身成不成 ─────────────────────────────────────────── */
check(gen?.code === 0, '生成接口返回成功', gen?._error ?? `code=${gen?.code} ${gen?.message ?? ''}`)
if (gen?.code !== 0) {
  console.log(`\n=== 结果：${pass} 通过 / ${fail} 失败 ===\n`)
  process.exit(1)
}

const days = gen.data?.days ?? []
const warnings = gen.data?.warnings ?? []

/* ── ② 行程里有没有那两段车票 ─────────────────────────────────── */
const outbound = days[0]?.transfer ?? null
const inbound = days[days.length - 1]?.transfer ?? null
// 同城出行本来就没有城际交通，那种情况下不该报这个断言失败 —— 但脚本的默认场景不是同城
const intercity = ORIGIN !== CITY

check(!intercity || outbound !== null,
  '⭐ 去程有车票信息（这就是用户报的"没有车票信息"）',
  outbound ? `${outbound.trainNo ?? '?'} ${outbound.depStation}→${outbound.arrStation}` : '(空 —— 又丢了)')
check(!intercity || inbound !== null,
  '⭐ 回程有车票信息',
  inbound ? `${inbound.trainNo ?? '?'} ${inbound.depStation}→${inbound.arrStation}` : '(空 —— 又丢了)')

/* ── ③ 两段各自：耗时是真的、方向是对的 ───────────────────────── */
for (const [role, t] of [['去程', outbound], ['回程', inbound]]) {
  if (!t) continue
  console.log(`\n  [${role}] ${t.trainNo ?? '?'}  ${t.depDateTime} ${t.depStation} → ${t.arrDateTime} ${t.arrStation}  ${t.durationMinutes} 分钟  ¥${t.priceYuan ?? '未知'}`)

  check(t.from === (role === '去程' ? ORIGIN : CITY) && t.to === (role === '去程' ? CITY : ORIGIN),
    `${role} from/to 方向对得上画像`,
    `${t.from} → ${t.to}`)

  check(typeof t.durationMinutes === 'number' && t.durationMinutes > 0,
    `${role}有耗时数字`, String(t.durationMinutes))

  // ⭐ 自己复算：dep → arr 的差值就该是整体耗时（totalDuration 含候车）。
  //    不自己算一遍的话，"durationMinutes 是上游随手给的某个数"这件事就没人管 ——
  //    而下面那条超限判断完全建立在它身上。
  if (t.depDateTime && t.arrDateTime && typeof t.durationMinutes === 'number') {
    const dep = new Date(`${t.depDateTime}:00`)
    const arr = new Date(`${t.arrDateTime}:00`)
    const diff = Math.round((arr - dep) / 60000)
    check(Math.abs(diff - t.durationMinutes) <= 2,
      `${role}耗时经独立复算对得上（dep→arr 差值）`,
      `自己算 ${diff} 分钟 vs 接口给的 ${t.durationMinutes} 分钟`)
  }
}

/* ── ④ 警告有没有说成"没有车次"这句假话 ───────────────────────── */
const overLimitLines = warnings.filter((w) => w.includes('最快的一班也要'))
const noEdgeLine = warnings.find((w) => w.includes('没有直达') || w.includes('没有查到直达走法'))

const exceeded = [outbound, inbound].filter((t) => t && t.durationMinutes > LEG_LIMIT_MINUTES)

check(exceeded.length === 0 || overLimitLines.length >= exceeded.length,
  '⭐ 每一段超限都有自己的「最快的一班也要 X」警告',
  overLimitLines.length > 0
    ? overLimitLines.map((l) => l.slice(0, 48) + '…').join(' ｜ ')
    : '(一条都没有)')

check(!noEdgeLine,
  '⭐ 没有把"有车但太长"说成"没有直达车次"（这是本次修的那句假话）',
  noEdgeLine ? noEdgeLine.slice(0, 70) + '…' : '(干净)')

if (exceeded.length > 0) {
  console.log('')
  console.log(`  超限的两段：${exceeded.map((t) => `${t.from}→${t.to} ${t.durationMinutes} 分钟`).join('；')}`)
  console.log(`  （上限是 ${LEG_LIMIT_MINUTES} 分钟 —— 这正是原来会让整行消失的原因）`)
}

/* ── ⑤ 每条超限段各自对账：警告里的耗时必须和 transfer 算出来的一模一样 ── */
//
// ⚠️ 这一段第一版写错了，记下来当反例：它拿"所有超限段里最长的那个"去对
//    "第一条警告"—— 而超限的往往是两段（去程 + 回程），两次警告各说各的数字，
//    于是它必然失败。**"只找到第一条就下结论"，正是这个脚本本身在防的错法。**
for (const t of exceeded) {
  const pair = `${t.from} 到 ${t.to}`
  const line = warnings.find((w) => w.includes('最快的一班也要') && w.includes(pair))
  check(Boolean(line), `${pair} 有它自己那条超限警告`,
    line ? line.slice(0, 60) + '…' : '(没找到这一段的)')
  if (!line) continue

  // 把 "X 小时 Y 分" 解析回分钟，和 transfer 的 durationMinutes 比。
  // 差一分钟都不行 —— 这句警告的全部价值就在于它那个数字是准的。
  const m = line.match(/最快的一班也要\s*(\d+)\s*小时(?:\s*(\d+)\s*分)?/)
  const warned = m ? Number(m[1]) * 60 + Number(m[2] ?? 0) : null
  check(warned === t.durationMinutes,
    `${pair} 警告里的耗时和 transfer 逐字对得上`,
    `警告说 ${m ? `${m[1]} 小时 ${m[2] ?? '0'} 分（${warned} 分钟）` : '读不出来'} vs transfer ${t.durationMinutes} 分钟`)
}

console.log(`\n=== 结果：${pass} 通过 / ${fail} 失败 ===\n`)
process.exit(fail === 0 ? 0 : 1)
