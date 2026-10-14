/**
 * B6 验收：**把用户在界面上真实撞到的那一段，原文重放一遍。**
 *
 * 用户当时的原话（三轮）：
 *   1. 我想11月17日带孩子在广东周边游玩6天
 *   2. 孩子7岁。我喜欢海边，动物园，室外多一些
 *   3. 慢慢玩吧，不带推车
 *
 * 结果是行程排不出来，因为两处模型输出没有被任何代码校验：
 *   · city = "广东周边"        —— 飞猪对这个值静默返回 0 条（status=0 / message="empty"，和"真没数据"一样）
 *   · startDate = 2025-11-17   —— 今天是 2026-10-13，模型填了**去年**（prompt 里从没告诉它今天几号）
 *   叠加第三处：上游对过去日期返回 status=1「入离时间校验失败」，被我们当成"没查到酒店"吞掉了。
 *
 * 所以这个脚本验三件事：
 *   ① 区域说法**绝不会**被当成城市收下（要么模型自己挑了一个具体城市，要么回退成"还没问过"）
 *   ② 日期落到**今天之后最近的那个 11 月 17 日**
 *   ③ 用重放出来的画像去 /generate，**能真的排出行程**；发出去的酒店查询带的是将来的日期
 *   另加一条廉价的负向用例：直接把"广东周边"喂给 /generate，必须返回**可行动**的 4000。
 *
 * 用法：node verify-b6.mjs
 *   COOLDOWN=90   跑之前先等几秒（上游 429/451 的冷却）
 *   GAP=3000      两次 LLM 调用之间的间隔
 */

const BASE = 'http://127.0.0.1:8003/api'
const COOLDOWN = Number(process.env.COOLDOWN ?? 0)
const GAP = Number(process.env.GAP ?? 3000)

const results = []
function check(ok, label, detail) {
  results.push({ ok, label, detail })
  console.log(`${ok ? '✅' : '❌'} ${label}`)
  if (detail) console.log(`   ${detail}`)
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function post(path, body, timeoutMs = 180_000) {
  const res = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(timeoutMs),
  })
  const text = await res.text()
  try {
    return { http: res.status, ...JSON.parse(text) }
  } catch {
    return { http: res.status, raw: text.slice(0, 400) }
  }
}

/** "11月17日" 指的是**接下来的**那个 11/17 —— 用它算出期望值，脚本就不会过一年自己变红 */
function nextOccurrence(month, day) {
  const now = new Date()
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  let d = new Date(now.getFullYear(), month - 1, day)
  if (d < today) d = new Date(now.getFullYear() + 1, month - 1, day)
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

/** 和 CityNames.REGION_MARKERS 保持一致 —— 这一侧独立列一遍，才能真的验到"没漏" */
const REGION_MARKERS = ['周边', '附近', '一带', '沿线', '地区', '周边游',
  '三角', '湾区', '城市群', '全省', '省内', '省市']
const SEPARATORS = ['、', '，', ',', '/', '+', '＋']
const looksLikeRegion = (city) =>
  REGION_MARKERS.some((m) => city.includes(m)) || SEPARATORS.some((s) => city.includes(s))

/** 一轮对话：带上全部历史与当前画像，拿回 reply + 累积后的 params */
async function turn(history, params, userText) {
  const messages = [...history, { role: 'user', content: userText }]
  const r = await post('/plan/chat', { messages, currentParams: params })
  if (r.code !== 0) {
    throw new Error(`chat 失败 code=${r.code} message=${r.message}`)
  }
  return {
    history: [...messages, { role: 'assistant', content: r.data.reply }],
    params: r.data.params,
    reply: r.data.reply,
  }
}

async function main() {
  const EXPECTED_DATE = nextOccurrence(11, 17)
  console.log(`期望的出发日期（今天之后最近的那个 11 月 17 日）：${EXPECTED_DATE}\n`)

  if (COOLDOWN > 0) {
    console.log(`先等 ${COOLDOWN} 秒（避开上游限流）…\n`)
    await sleep(COOLDOWN * 1000)
  }

  /* ── 一、原文重放那三轮 ─────────────────────────────────────── */
  console.log('══ 一、原文重放用户那三轮对话 ══\n')

  let history = []
  let params = {}

  const t1 = await turn(history, params, '我想11月17日带孩子在广东周边游玩6天')
  history = t1.history
  params = t1.params
  console.log(`第 1 轮 → ${t1.reply}`)
  console.log(`   画像：${JSON.stringify(params)}\n`)
  check(!!t1.reply, '第 1 轮有回复')
  check(params.days === 6, '第 1 轮听出天数 6', `days=${params.days}`)
  check(params.startDate === EXPECTED_DATE,
    '⭐ 日期落在「今天之后最近的那个 11 月 17 日」（原来填的是去年的 2025-11-17）',
    `startDate=${params.startDate}，期望 ${EXPECTED_DATE}`)
  check(params.city == null || !looksLikeRegion(params.city),
    '⭐ 「广东周边」没有被当成城市收下（要么挑了个具体城市，要么回退成没答）',
    `city=${JSON.stringify(params.city)}`)

  await sleep(GAP)

  const t2 = await turn(history, params, '孩子7岁。我喜欢海边，动物园，室外多一些')
  history = t2.history
  params = t2.params
  console.log(`第 2 轮 → ${t2.reply}`)
  console.log(`   画像：${JSON.stringify(params)}\n`)
  check(params.childAge === 7, '第 2 轮听出孩子年龄 7', `childAge=${params.childAge}`)
  check(params.days === 6 && params.startDate === EXPECTED_DATE,
    '⭐ 第 2 轮没把第 1 轮的天数和日期丢掉', JSON.stringify(params))
  check(params.city == null || !looksLikeRegion(params.city),
    '⭐ 第 2 轮城市依然是合法的', `city=${JSON.stringify(params.city)}`)

  await sleep(GAP)

  const t3 = await turn(history, params, '慢慢玩吧，不带推车')
  history = t3.history
  params = t3.params
  console.log(`第 3 轮 → ${t3.reply}`)
  console.log(`   画像：${JSON.stringify(params)}\n`)
  check(params.pace === '慢', '第 3 轮听出节奏「慢」', `pace=${params.pace}`)
  check(params.stroller === false, '第 3 轮听出「不带推车」= false（不是 null）',
    `stroller=${params.stroller}`)
  check(params.city == null || !looksLikeRegion(params.city),
    '⭐ 三轮下来城市始终没有被区域说法污染', `city=${JSON.stringify(params.city)}`)

  /* 如果模型选择"先问一句哪个城市"，那是对的行为 —— 补一句再往下，把链路走完 */
  if (params.city == null) {
    console.log('\n（模型选择了先问城市 —— 这是允许的行为，补一句继续把链路走完）\n')
    await sleep(GAP)
    const t4 = await turn(history, params, '那就珠海吧')
    history = t4.history
    params = t4.params
    console.log(`第 4 轮 → ${t4.reply}`)
    console.log(`   画像：${JSON.stringify(params)}\n`)
  }
  check(!!params.city && !looksLikeRegion(params.city),
    '最终拿到一个**具体城市**，可以往下走了', `city=${JSON.stringify(params.city)}`)

  /* ── 二、负向用例：非法城市名必须被挡住，而且话要能照着做 ────── */
  console.log('\n══ 二、直接把「广东周边」喂给 /generate（不花上游额度）══\n')

  const bad = await post('/plan/generate', {
    params: { city: '广东周边', days: 6, startDate: EXPECTED_DATE },
  })
  console.log(`   code=${bad.code}  message=${bad.message}\n`)
  check(bad.code === 4000, '非法城市名 → 4000（而不是排出一份空行程、或 5000「服务开小差了」）',
    `code=${bad.code}`)
  check(typeof bad.message === 'string' && bad.message.includes('具体'),
    '⭐ 报错必须说清「要换成具体城市」', bad.message)
  check(typeof bad.message === 'string' && /珠海|广州|深圳/.test(bad.message),
    '⭐ 报错里要有例子（否则用户不知道合法值长什么样）', bad.message)

  /* ── 三、用重放出来的画像真的生成一份行程 ───────────────────── */
  console.log('\n══ 三、用重放出来的画像生成行程（真调上游 + 模型）══\n')

  const gen = await post('/plan/generate', { params })
  if (gen.code !== 0) {
    check(false, '/generate 成功', `code=${gen.code} message=${gen.message}`)
    return finish()
  }

  const plan = gen.data
  const p = plan.provenance
  console.log(`城市：${params.city}    摘要：${plan.summary}`)
  console.log(`天数：${plan.days.length}    查询：${p.queries.length} 次（上游调用 ${p.upstreamCalls ?? '-'}）`)
  console.log('发的命令：')
  p.queries.forEach((q) => console.log(`   ${q}`))
  console.log('')

  check(plan.days.length > 0, '⭐ 行程排出来了（原来这里是"没能在广东周边找到可用的景点"）',
    `排了 ${plan.days.length} 天`)
  check(plan.days.flatMap((d) => d.spots).length > 0, '⭐ 排出了真实景点',
    `${plan.days.flatMap((d) => d.spots).length} 个`)
  check(plan.days.length === params.days, '天数与画像一致',
    `画像 ${params.days} 天，排了 ${plan.days.length} 天`)

  const hotelQueries = p.queries.filter((q) => q.includes('search-hotel'))
  const badDate = hotelQueries.filter((q) => /20\d\d-\d\d-\d\d/.test(q)
    && q.match(/--check-in-date\s+(\S+)/)?.[1] !== params.startDate)
  check(hotelQueries.length === 0 || badDate.length === 0,
    '⭐ 发出去的酒店查询用的是画像里那个（将来的）入离日期',
    hotelQueries.length ? hotelQueries.join(' | ') : '（这次没查酒店）')

  const hasHotel = plan.days.some((d) => d.hotel)
  check(hotelQueries.length === 0 || hasHotel,
    '⭐ 要酒店的时候，酒店真的落到了某一天上（原来酒店也是 0 条）',
    `有住宿=${hasHotel}`)

  /* ── 四、第二道日期兜底：绕过对话、直接送一个过去的日期 ──────── */
  // 为什么要单独验：这一段走的是**和上面完全不同的入口** ——
  // 不经过 ChatComposer（那里是第一道），是"前端传回一份旧画像"和"脚本直接调接口"的那条路。
  // 第一道验过了不等于第二道也在 —— 两道守卫各自都要有一条用例，
  // 否则删掉其中一道，测试照样全绿。
  console.log('\n══ 四、旧画像里的过去日期（走 PlanService 那一道兜底）══\n')

  const stale = await post('/plan/generate', {
    params: { city: params.city, days: 2, startDate: '2020-01-01' },
  })
  if (stale.code !== 0) {
    check(false, '带过去日期的 /generate 成功（应被自动顺延，而不是报错）',
      `code=${stale.code} message=${stale.message}`)
    return finish()
  }

  const want = nextOccurrence(1, 1)
  const staleWarnings = stale.data.warnings ?? []
  const saidIt = staleWarnings.find((w) => w.includes(want))
  const staleHotel = stale.data.provenance.queries.find((q) => q.includes('search-hotel'))
  console.log(`   期望顺延到：${want}`)
  console.log(`   提醒：${saidIt ?? '（没找到相关提醒）'}`)
  console.log(`   酒店查询：${staleHotel ?? '（没查酒店）'}\n`)

  check(!!saidIt, '⭐ 顺延过日期就必须说出来 —— 用户看到的日期和他说的不一样，得有地方解释',
    JSON.stringify(staleWarnings))
  check(!staleHotel || staleHotel.includes(want),
    '⭐ 真正发出去的入离日期是顺延后的（原来那个过去日期会被上游以 status=1 拒绝）',
    staleHotel ?? '')

  finish()
}

function finish() {
  const failed = results.filter((r) => !r.ok)
  console.log('\n' + '─'.repeat(64))
  console.log(`共 ${results.length} 项，通过 ${results.length - failed.length}，失败 ${failed.length}`)
  if (failed.length > 0) {
    console.log('\n失败项：')
    failed.forEach((f) => console.log(`  ❌ ${f.label}${f.detail ? ` —— ${f.detail}` : ''}`))
    process.exit(1)
  }
}

main().catch((e) => {
  console.error('\n脚本异常：', e)
  process.exit(1)
})
