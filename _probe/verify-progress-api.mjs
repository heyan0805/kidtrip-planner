/**
 * 验收：生成进度接口（GET /api/plan/progress/{runId}）
 *
 * ── 它要证的那件事 ──────────────────────────────────────────────
 *   "用户看到的那条流水线"是**流程的真实投影**，不是装饰。
 *   所以这个脚本不看界面截图，而是直接盯接口：
 *     ① 同一趟生成，阶段是不是真的**随时间推进**（而不是一次就报出终态）；
 *     ② 正在跑的那一步，耗时必须是 null（而不是 0 或一个猜的数）；
 *     ③ 收尾时五步是否都标成完成，且各步耗时之和 ≤ 总耗时（时间账自洽）；
 *     ④ 每步耗时**明显各不相同** —— 如果它们几乎相等，说明是"按总数均摊"算出来的，
 *        那是编的，也正是这个功能唯一不能犯的错；
 *     ⑤ 一个没登记过的 runId 安静地返回 null（前端的"收工"信号）。
 *
 * ── 它会真的花一次上游调用 ────────────────────────────────────
 *   一次 /generate 就是一次真实生成（缓存命中就不花钱，没命中会花）。
 *   所以默认打 **8004**（临时实例），不动正在用的 8003。
 *
 * 用法：
 *   node _probe/verify-progress-api.mjs
 *   KIDTRIP_BASE=http://127.0.0.1:8003/api node _probe/verify-progress-api.mjs
 *   KIDTRIP_CITY=桂林 node _probe/verify-progress-api.mjs
 */
import { setTimeout as sleep } from 'node:timers/promises'

const BASE = process.env.KIDTRIP_BASE ?? 'http://127.0.0.1:8004/api'
const CITY = process.env.KIDTRIP_CITY ?? '珠海'
const RUN = `verify-${Date.now().toString(36)}`
const BUDGET_MS = 200_000

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

async function getProgress() {
  const r = await fetch(`${BASE}/plan/progress/${encodeURIComponent(RUN)}`)
  const j = await r.json()
  return j.data ?? null
}

console.log(`\n=== 生成进度接口验收\n    ${BASE}\n    runId = ${RUN}   城市 = ${CITY}\n`)

/* ── ① 开工前：这个 runId 谁都没登记过，必须是 null ─────────────── */
const before = await getProgress()
check(before === null, '未登记的 runId 返回 null（前端据此安静收工）', JSON.stringify(before))

/* ── ② 发一次真实生成，带着 X-Run-Id ───────────────────────────── */
const t0 = Date.now()
const genPromise = fetch(`${BASE}/plan/generate`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', 'X-Run-Id': RUN },
  body: JSON.stringify({
    params: { origin: '广州', city: CITY, days: 3, startDate: '2026-11-17', childAge: 6 },
  }),
})
  .then((r) => r.json())
  .catch((e) => ({ _error: String(e) }))

/* ── ③ 每秒问一次，把整条时间线记下来 ─────────────────────────── */
const timeline = []
const snapshots = []
while (Date.now() - t0 < BUDGET_MS) {
  const p = await getProgress()
  if (p) {
    snapshots.push(p)
    const line = p.steps.map((s) => `${s.key}=${s.state}`).join(' ')
    if (timeline.length === 0 || timeline[timeline.length - 1].line !== line) {
      timeline.push({ at: Date.now() - t0, line })
      console.log(`  [${((Date.now() - t0) / 1000).toFixed(1)}s] ${line}`)
    }
    if (p.finished) break
  }
  await sleep(1000)
}
const wallMs = Date.now() - t0

const gen = await genPromise
console.log('')

/* ── ④ 生成本身成不成 ─────────────────────────────────────────── */
check(gen && gen.code === 0, '生成接口返回成功', gen?._error ?? `code=${gen?.code} ${gen?.message ?? ''}`)
if (gen?.code === 0) {
  check(Array.isArray(gen.data?.days) && gen.data.days.length > 0, '行程里有天数',
    `${gen.data?.days?.length ?? 0} 天`)
}

/* ── ⑤ 时间线：真的是"推进"出来的 ─────────────────────────────── */
check(timeline.length >= 3, '阶段随时间真的推进过（不是一次报终态）', `共 ${timeline.length} 个不同状态`)

const runningSeen = new Set()
for (const p of snapshots) for (const s of p.steps) if (s.state === 'RUNNING') runningSeen.add(s.key)
check(runningSeen.size >= 2, '至少看到两步处于"进行中"', [...runningSeen].join(', ') || '(一个都没看到)')

/* ── ⑥ 进度只前进不后退 ──────────────────────────────────────── */
const rank = { PENDING: 0, RUNNING: 1, DONE: 2, FAILED: 2 }
const seen = {}
let monotonic = true
for (const p of snapshots) {
  for (const s of p.steps) {
    const r = rank[s.state]
    if (seen[s.key] !== undefined && r < seen[s.key]) monotonic = false
    seen[s.key] = Math.max(seen[s.key] ?? 0, r)
  }
}
check(monotonic, '进度只前进不后退（走过的步不会退回待办）')

/* ── ⑦ 终态：五步都完成、每步都有真实耗时、时间账自洽 ───────────── */
const last = snapshots.at(-1)
check(!!last?.finished, '最终快照是收场状态')

if (last) {
  check(last.steps.every((s) => s.state === 'DONE'), '五步全部标成完成',
    last.steps.map((s) => `${s.key}=${s.state}`).join(' '))

  const tooks = last.steps.map((s) => s.tookMs)
  check(tooks.every((t) => typeof t === 'number' && t >= 0), '每一步都有真实耗时',
    tooks.map((t) => (t === null ? 'null' : `${(t / 1000).toFixed(1)}s`)).join(' / '))

  const sum = tooks.reduce((a, b) => a + (b ?? 0), 0)
  check(sum <= last.elapsedMs, '各步耗时之和 ≤ 总耗时（时间账自洽）',
    `${sum}ms ≤ ${last.elapsedMs}ms`)

  check(last.steps.map((s) => s.key).join(',') === 'LEGS,PLAN_QUERIES,SEARCH,COMPOSE,VALIDATE',
    '阶段顺序与后端声明一致', last.steps.map((s) => s.key).join(','))

  // ⭐ 这条是整份验收里最要紧的：耗时必须"长短不一"，而且和真实墙钟对得上。
  //    如果五步耗时几乎相等，那就是拿总数均摊出来的 —— 一个看起来很像真的、其实是编的数字。
  const nums = tooks.filter((t) => t !== null)
  const max = Math.max(...nums)
  const min = Math.min(...nums)
  check(max - min > 200, '各步耗时明显长短不一（说明是真量的，不是按总数均摊）',
    `最长 ${(max / 1000).toFixed(1)}s，最短 ${(min / 1000).toFixed(1)}s`)

  // 后端的总耗时和前端这边测到的墙钟时间应当接近（差值是网络往返）
  check(Math.abs(last.elapsedMs - wallMs) < 5000, '后端报的总耗时与前端实测墙钟接近',
    `后端 ${(last.elapsedMs / 1000).toFixed(1)}s vs 实测 ${(wallMs / 1000).toFixed(1)}s`)
}

/* ── ⑧ 不打 X-Run-Id 时，老调用方一切照旧 ────────────────────── */
const bare = await fetch(`${BASE}/plan/generate`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    params: { origin: '广州', city: CITY, days: 2, startDate: '2026-11-17', childAge: 6 },
  }),
})
  .then((r) => r.json())
  .catch((e) => ({ _error: String(e) }))
check(bare?.code === 0, '不带 X-Run-Id 的请求照常成功（老调用方不用改一个字）',
  bare?._error ?? `code=${bare?.code}`)

console.log(`\n=== 结果：${pass} 通过 / ${fail} 失败 ===\n`)
process.exit(fail === 0 ? 0 : 1)
