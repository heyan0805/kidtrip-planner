/**
 * B5 端到端验收：**对话上下文传递** + **检索条件由模型规划**。
 *
 * 它验的不是"接口能通"，而是三件具体的事：
 *   ① 多轮对话里，画像能**逐轮累积**（每一轮都不能把之前问出来的字段弄丢）
 *   ② 上下文守卫是真的（以"助手"结尾的历史要被挡住）
 *   ③ /generate **不带 retrieval** 时，检索条件由模型规划出来，而且类别**全部合法**
 *
 * 用法：
 *   node verify-b5.mjs
 * 环境变量：
 *   COOLDOWN=90   跑之前先等几秒（上游限流 429/451 的冷却）
 *   GAP=7000      每次调用之间的间隔
 */

const BASE = 'http://127.0.0.1:8003/api'
const COOLDOWN = Number(process.env.COOLDOWN ?? 0)
const GAP = Number(process.env.GAP ?? 7000)

/** 飞猪 --category 的闭集（和后端 PoiCategories.ALL 必须一致） */
const CATEGORIES = new Set([
  '自然风光', '山湖田园', '森林丛林', '峡谷瀑布', '沙滩海岛',
  '沙漠草原', '人文古迹', '古镇古村', '历史古迹', '园林花园',
  '宗教场所', '公园乐园', '主题乐园', '水上乐园', '影视基地',
  '动物园', '植物园', '海洋馆', '体育场馆', '演出赛事',
  '剧院剧场', '博物馆', '纪念馆', '展览馆', '地标建筑',
  '市集', '文创街区', '城市观光', '户外活动', '滑雪',
  '漂流', '冲浪', '潜水', '露营', '温泉',
])

const results = []
function check(ok, label, detail) {
  results.push({ ok, label, detail })
  console.log(`${ok ? '✅' : '❌'} ${label}`)
  if (detail) console.log(`   ${detail}`)
}

async function post(path, body) {
  const res = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(150_000),
  })
  const text = await res.text()
  try {
    return { http: res.status, ...JSON.parse(text) }
  } catch {
    return { http: res.status, raw: text.slice(0, 400) }
  }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

/** 一轮对话：把上下文带全，拿回 reply + 累积后的 params */
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
  if (COOLDOWN > 0) {
    console.log(`先等 ${COOLDOWN} 秒（避开上游限流）…\n`)
    await sleep(COOLDOWN * 1000)
  }

  /* ── 第一段：上下文逐轮累积 ───────────────────────────── */
  console.log('══ 一、对话上下文传递（多轮）══\n')

  let history = []
  let params = {}

  const t1 = await turn(history, params, '想带孩子去珠海玩')
  history = t1.history
  params = t1.params
  console.log(`第 1 轮 → ${t1.reply}`)
  console.log(`   画像：${JSON.stringify(params)}\n`)
  check(params.city === '珠海', '第 1 轮提取出城市', `city=${params.city}`)
  check(!!t1.reply, '第 1 轮有回复话术')

  await sleep(GAP)

  const t2 = await turn(history, params, '大概 3 天吧，孩子 6 岁')
  history = t2.history
  params = t2.params
  console.log(`第 2 轮 → ${t2.reply}`)
  console.log(`   画像：${JSON.stringify(params)}\n`)
  check(params.city === '珠海', '⭐ 第 2 轮没有把第 1 轮的城市丢掉（上下文累积的核心）',
    `city=${params.city}`)
  check(params.days === 3, '第 2 轮提取出天数', `days=${params.days}`)
  check(params.childAge === 6, '第 2 轮提取出孩子年龄', `childAge=${params.childAge}`)

  await sleep(GAP)

  const t3 = await turn(history, params, '需要带推车，节奏慢一点')
  history = t3.history
  params = t3.params
  console.log(`第 3 轮 → ${t3.reply}`)
  console.log(`   画像：${JSON.stringify(params)}\n`)
  check(params.city === '珠海' && params.days === 3 && params.childAge === 6,
    '⭐ 第 3 轮前三轮的字段全部还在', JSON.stringify(params))
  check(params.stroller === true || params.stroller === false,
    '第 3 轮问出了推车（true/false 都算答了，null 才是还没问）', `stroller=${params.stroller}`)
  check(params.stroller === true, '推车被正确识别为「需要」', `stroller=${params.stroller}`)

  check(typeof params.stroller === 'boolean'
    ? true
    : params.stroller === null,
  'stroller 是三态：只有 true/false/null，不会变成 0 或 ""', `typeof=${typeof params.stroller}`)

  /* ── 第二段：上下文守卫 ───────────────────────────────── */
  console.log('\n══ 二、上下文守卫（该挡的要挡住）══\n')

  const empty = await post('/plan/chat', { messages: [] })
  check(empty.code === 4000, '空 messages → 4000（不是 5000「服务开小差了」）',
    `code=${empty.code} message=${empty.message}`)

  const endsWithAssistant = await post('/plan/chat', {
    messages: [
      { role: 'user', content: '想去珠海' },
      { role: 'assistant', content: '玩几天？' },
    ],
    currentParams: {},
  })
  check(endsWithAssistant.code === 4000,
    '⭐ 以「助手」结尾的历史 → 被挡住（否则会得到一次礼貌的答非所问）',
    `code=${endsWithAssistant.code} message=${endsWithAssistant.message}`)

  const noBody = await fetch(`${BASE}/plan/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: '{不是合法 JSON',
  })
  const noBodyJson = await noBody.json().catch(() => ({}))
  check(noBodyJson.code === 4000, '请求体不是合法 JSON → 4000（而不是 5000）',
    `http=${noBody.status} code=${noBodyJson.code} message=${noBodyJson.message}`)

  /* ── 第三段：检索条件由模型规划 ───────────────────────── */
  console.log('\n══ 三、/generate 不带 retrieval，检索条件由模型规划 ══\n')

  await sleep(GAP)

  const gen = await post('/plan/generate', { params })
  if (gen.code !== 0) {
    check(false, '/generate 成功', `code=${gen.code} message=${gen.message}`)
    return finish()
  }

  const plan = gen.data
  const rp = plan.provenance.retrievalPlan
  console.log(`行程摘要：${plan.summary}`)
  console.log(`天数：${plan.days.length}`)
  console.log(`检索条件：类别=${JSON.stringify(rp.wantedCategories)} 关键词=${JSON.stringify(rp.keywords)} 要酒店=${rp.needHotel}`)
  console.log(`查询命令：`)
  plan.provenance.queries.forEach((q) => console.log(`   ${q}`))
  console.log('')

  check(plan.days.length === params.days, '行程天数与画像一致',
    `排了 ${plan.days.length} 天，画像说 ${params.days} 天`)

  check(Array.isArray(rp.wantedCategories) && rp.wantedCategories.length > 0,
    '⭐ 模型确实规划出了检索类别（不再是空的 fallback）',
    JSON.stringify(rp.wantedCategories))

  const illegal = rp.wantedCategories.filter((c) => !CATEGORIES.has(c))
  check(illegal.length === 0, '⭐ 规划出的类别**全部在飞猪的 35 个闭集内**（白名单生效）',
    illegal.length > 0 ? `非法类别：${JSON.stringify(illegal)}` : '')

  // 更硬的一条：真正发出去的 --category 值也必须在闭集内
  const sentCats = plan.provenance.queries
    .flatMap((q) => {
      const m = q.match(/--category\s+(\S+)/)
      return m ? [m[1]] : []
    })
  const badSent = sentCats.filter((c) => !CATEGORIES.has(c))
  check(badSent.length === 0, '⭐ 真正发出去的 --category 值也都在闭集内',
    `发出去的类别：${JSON.stringify(sentCats)}`)

  check(rp.keywords.length <= 4, '关键词不超过 4 个', JSON.stringify(rp.keywords))

  // 景点事实必须可追溯（B4 的老要求，这里顺手回归一下）
  const spots = plan.days.flatMap((d) => d.spots)
  check(spots.length > 0, '排出了景点', `${spots.length} 个`)
  check(spots.every((s) => s.poiId && !s.poiId.startsWith('mock')),
    '每个景点都有真实的 poiId（事实来自飞猪，不是模型编的）',
    `${spots.filter((s) => s.poiId).length}/${spots.length} 条有 poiId`)

  const hasHotel = plan.days.some((d) => d.hotel)
  check(rp.needHotel === false || hasHotel, '要酒店的时候确实排了住宿',
    `needHotel=${rp.needHotel} 有住宿=${hasHotel}`)

  finish()
}

function finish() {
  const failed = results.filter((r) => !r.ok)
  console.log('\n' + '─'.repeat(60))
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
