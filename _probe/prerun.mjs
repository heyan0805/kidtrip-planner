/**
 * 预跑 —— 把 Demo 那天要用的场景**提前**查一遍，让真实结果留在磁盘上。
 *
 * ══ 为什么需要它 ══════════════════════════════════════════════════
 * 实测过的上游脾气（2026-10-13）：
 *   并发 6 个 → 3 个失败（HTTP 429）；连续串行 6 个不间隔 → 后 3 个失败（HTTP 451 风控）；
 *   冷却 90 秒后，串行 + 间隔 7 秒的 4 次才全部成功。
 *
 * 也就是说 **"多查几次"这件事本身是有额度的**，而不是"慢一点总能查到"。
 * 一次 /generate 要发 6~8 次上游调用（宽查景点 + 酒店 + 最多 2 次补查 + 2 段车次），
 * 连起来点几次"生成"，就进到实测里那个会被 451 的区间了。
 *
 * 预跑做的事就是把额度**提前花掉**：那天要演的那几组参数，今天就查完、落到磁盘上。
 * 演示当天启动后端时配上同一份缓存文件，这些查询一次上游都不用发。
 *
 * ══ 用法 ═════════════════════════════════════════════════════════
 *   ① 启动后端时**必须**配上缓存文件（否则预跑结果留不下来，本脚本会直接报错并给出命令）：
 *
 *      cd backend && unset SERVER__PORT && \
 *        mvn spring-boot:run -Dspring-boot.run.arguments="\
 *          --kidtrip.flyai.cache-file=<仓库根>/_probe/flyai-cache.json \
 *          --kidtrip.flyai.cache-ttl-seconds=172800"
 *
 *      ⚠️ TTL 必须一起调长。它默认只有 600 秒，预跑和演示隔一晚就全过期了 —— 等于没预跑。
 *      ⚠️ 不要用 `KIDTRIP__FLYAI__CACHE_FILE` 这种环境变量写法：Spring Boot 的宽松绑定
 *         不认双下划线那种拼法，会**静默**地按"未配置"处理（这个坑已经踩过一次了）。
 *
 *   ② 预跑：`node _probe/prerun.mjs`
 *   ③ 本地检查缓存内容（不花任何额度）：`node _probe/prerun.mjs --check`
 *
 * ══ 它验收什么 ═══════════════════════════════════════════════════
 * 不是"跑完了没报错"，而是三条能证伪的事：
 *   · 缓存文件真的被创建了（—— 证明后端是带着 cache-file 启动的）
 *   · 每个场景的**车次查询**都进了文件（—— 这才是我最担心被限流的那一段）
 *   · 同一场景跑第二遍时，**车次条目数不再增加**（—— 证明现场真的不会再去查车次）
 *
 * 第三条是核心。前两条只说明"存下来了"，第三条才说明"现场真的省下了额度"。
 */
import { existsSync, readFileSync } from 'node:fs'
import { CACHE_FILE } from './_paths.mjs'

const APP = 'http://127.0.0.1:8003/api'
const CACHE = CACHE_FILE

/**
 * Demo 场景 —— **改这里就是改预跑清单**，也是"那天要演什么"的唯一出处。
 * 预跑和演示必须用同一组参数，否则缓存 key 对不上，等于白预跑（而且不会有任何报错）。
 */
const SCENARIOS = [
  {
    name: '广州出发 · 珠海 3 天',
    params: { origin: '广州', city: '珠海', days: 3, startDate: '2026-11-17', childAge: 6, pace: '慢' },
  },
  {
    name: '广州出发 · 珠海 5 天（带孩子最常问的天数）',
    params: { origin: '广州', city: '珠海', days: 5, startDate: '2026-11-17', childAge: 6, pace: '慢' },
  },
]

const CHECK_ONLY = process.argv.includes('--check')
const START_COMMAND =
  'cd backend && unset SERVER__PORT && mvn spring-boot:run -Dspring-boot.run.arguments="'
  + `--kidtrip.flyai.cache-file=${CACHE} --kidtrip.flyai.cache-ttl-seconds=172800"`

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

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function post(path, body) {
  const res = await fetch(`${APP}${path}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
  })
  return res.json()
}

/**
 * 读缓存文件。
 *
 * 每次读都重新解析 —— 因为后端是**后台异步**落盘的（1 秒一次），
 * 文件内容会在我们眼前变化。缓存一份反而会读到旧的。
 */
function readCache() {
  if (!existsSync(CACHE)) {
    return null
  }
  try {
    return JSON.parse(readFileSync(CACHE, 'utf8'))
  } catch (e) {
    return { version: -1, entries: [], broken: e.message }
  }
}

/** 按子命令归类条目 —— "查了几次什么"比"总共几条"有用得多 */
function byCommand(cache) {
  const counts = {}
  for (const e of cache?.entries ?? []) {
    const cmd = (e.args ?? [])[0] ?? '(空)'
    counts[cmd] = (counts[cmd] ?? 0) + 1
  }
  return counts
}

function trainQueries(cache) {
  return (cache?.entries ?? [])
    .filter((e) => (e.args ?? [])[0] === 'search-train')
    .map((e) => (e.args ?? []).join(' '))
}

console.log('\n══════ 预跑：把 Demo 的额度提前花掉 ══════')
if (CHECK_ONLY) {
  console.log('（--check 模式：只审计缓存文件，不发任何请求、不花任何额度）')
}

/* ── 0. 后端在线 ─────────────────────────────────────────────── */
const health = await (await fetch(`${APP}/health`)).json().catch(() => null)
check(health?.code === 0, '后端在线', JSON.stringify(health))

/* ── 1. 逐场景预跑 ───────────────────────────────────────────── */
const results = []

if (!CHECK_ONLY) {
  for (const [i, s] of SCENARIOS.entries()) {
    console.log(`\n【${i + 1}/${SCENARIOS.length}】${s.name}`)
    const t0 = Date.now()
    const res = await post('/plan/generate', { params: s.params })
    const ms = Date.now() - t0

    const days = res?.data?.days ?? []
    // 回程挂在最后一天，去程挂在第一天 —— 两天相同（1 天行程）时它就是同一条
    const outbound = days[0]?.transfer ?? null
    const inbound = days.at(-1)?.transfer ?? null
    const trainQ = (res?.data?.provenance?.queries ?? []).filter((q) => q.includes('search-train'))

    check(res?.code === 0, '生成成功', JSON.stringify(res).slice(0, 250))
    check(days.length === s.params.days, `行程 ${s.params.days} 天`)
    check(!!outbound, '第 1 天有真实去程')
    check(!!inbound, '最后一天有真实回程')
    console.log(`      耗时 ${(ms / 1000).toFixed(1)} 秒 · 车次查询 ${trainQ.length} 条`);
    if (outbound) {
      console.log(`      ${outbound.from} → ${outbound.to} ${outbound.serviceNo ?? ''} `
        + `${(outbound.depDateTime ?? '').slice(11, 16)}→${(outbound.arrDateTime ?? '').slice(11, 16)} `
        + `${outbound.durationMinutes} 分 ¥${outbound.priceYuan}`);
    }
    results.push({ scenario: s, ms, transfer: outbound })
  }

  // 落盘是 1 秒一次的后台任务，等它写完再审计文件
  await sleep(2500)
}

/* ── 2. 缓存文件真的存在吗 ────────────────────────────────────── */
console.log('\n【审计】缓存文件')
let cache = readCache()

if (cache === null) {
  // 这是**最可能的一种搞错**：后端没带 cache-file 起来，于是所有预跑都只留在内存里，
  // 一重启就没了 —— 而我们此刻还以为"预跑好了"。必须在检测到的那一刻就说清楚怎么办。
  check(false, '⚠️ 缓存文件不存在 —— 后端多半没带 cache-file 启动',
    `路径：${CACHE}\n      用一个**带缓存文件**的后端重启，然后重跑本脚本：\n      ${START_COMMAND}`)
  console.log(`\n══════ 结果：${pass} 项通过，${failures.length} 项失败 ══════`)
  process.exit(1)
}

check(cache.version === 1, `缓存文件格式版本是 1（实际 ${cache.version}）`,
  cache.broken ? `解析失败：${cache.broken}` : undefined)

const counts = byCommand(cache)
const total = cache.entries.length
console.log(`      共 ${total} 条：${Object.entries(counts).map(([k, v]) => `${k}×${v}`).join(' · ')}`)

const trains = trainQueries(cache)
check(trains.length > 0, '⭐ 车次查询已经进了缓存（这是最怕被限流的那一段）')
for (const q of trains) {
  console.log(`      ${q}`)
}

/* ── 3. 每个场景的车次都在里面吗 ──────────────────────────────── */
if (!CHECK_ONLY) {
  console.log('\n【逐场景核对】车次查询有没有落到缓存里')
  for (const r of results) {
    if (!r.transfer) {
      console.log(`  – ${r.scenario.name}：这个场景没有车次（可能是"两地之间没有够快的直达"，本身就正常）`)
      continue
    }
    const { origin, city } = r.scenario.params
    // 判据用 origin/city 直接搜，不依赖我在这里复现 LegService 的参数拼法 ——
    // 复现一遍就等于把被测逻辑抄进测试里，抄错了会两边一起错、而测试照样绿。
    const has = trains.some((q) => q.includes(`--origin ${origin}`) && q.includes(`--destination ${city}`))
    check(has, `${r.scenario.name}：有 ${origin} → ${city} 的车次缓存`)
  }
}

/* ── 4. ⭐ 同一个场景再跑一遍，车次条目数不该增加 ──────────────── */
// 这一条才是"现场真的省下额度"的证据。
// 前三条只说明"存下来了"；而存下来却不生效（比如 key 对不上）会安静地浪费掉整个预跑，
// 现场照样每次都去查车次 —— 于是预跑这件事看起来做了，其实一点用没有。
if (!CHECK_ONLY) {
  console.log('\n【关键验证】同一场景跑第二遍 —— 车次条目数不该再增加')
  const before = trainQueries(readCache()).length
  const t0 = Date.now()
  await post('/plan/generate', { params: SCENARIOS[0].params })
  const ms = Date.now() - t0
  await sleep(2500)
  const after = trainQueries(readCache()).length

  check(after === before,
    `⭐ 车次条目数没变（${before} → ${after}）：现场再点一次生成，不会再去查车次`,
    `新增了 ${after - before} 条 —— 说明缓存 key 没对上，预跑等于白做`)
  console.log(`      第二遍耗时 ${(ms / 1000).toFixed(1)} 秒（上游调用已经省掉，但大模型排程还在跑）`)
}

/* ── 现场清单 ────────────────────────────────────────────────── */
console.log('\n══════ 演示当天 ══════')
console.log(`  缓存文件：${CACHE}`)
console.log(`  已备 ${total} 条真实上游结果（其中车次 ${trains.length} 条）`)
console.log('  用**同一份配置**启动后端：')
console.log(`    ${START_COMMAND}`)
console.log('  现场如果连不上飞猪：缓存命中的那些查询照常返回，不会退化成"查不到车次"。')
console.log('')
console.log('  ── 万一整条链路都不通（会场网络 / 上游全挂 / 后端起不来）──────────────')
console.log('  前端有一条**明确的**降级路：用一个开着 Mock 的 dev server 顶上去。')
console.log('    cd frontend && VITE_USE_MOCK=true npx vite --host 127.0.0.1 --port 5175')
console.log('  它不依赖后端、不依赖任何上游，界面上到处写着"（假数据）"、顶上还有提示条 ——')
console.log('  **看起来像降级**，不会把编的东西讲成真的。')
console.log('  这条路由 `_probe/verify-mock-ui.mjs` 守着（10 项，含"必须看起来像降级"）。')
console.log('  ⚠️ 测它的时候要**把后端停掉**，否则"没有后端也能用"这句话是没验证过的。')

console.log(`\n══════ 结果：${pass} 项通过，${failures.length} 项失败 ══════`)
if (failures.length > 0) {
  console.log('失败项：')
  for (const f of failures) console.log(`  · ${f}`)
  process.exit(1)
}
console.log('全部通过。')
