/**
 * 一个**假的 OpenAI 兼容接口**，只回写死的 JSON。
 *
 * ── 为什么需要它 ────────────────────────────────────────────────
 *   验收"机票"这条链路要跑一次真实的 /api/plan/generate，而这一步里唯一无法离线的东西
 *   是大模型。它有两个毛病：**要密钥**、**同一份画像两次给的不一样**。
 *   前者让脚本没法在干净环境里跑，后者让"这次到底验了什么"说不清。
 *
 *   所以在 HTTP 这一层把模型换成一个只会回固定 JSON 的东西 —— 和单测里的 FakeChatModel
 *   是同一个思路，只是换了个层面。换掉之后：
 *     · 行程点是固定的（脚本可以逐条断言）；
 *     · 剩下的**全是真数据**：车次、航班、价格、酒店都来自飞猪，走的还是那份真代码。
 *
 * ── 为什么不干脆把网络调用也 mock 掉 ────────────────────────────
 *   那这一整套验收就没有意义了 —— 它要验的正是"真实的机票数据能不能穿过整条链路落到界面上"。
 *   假掉的只能是"不该由我们决定的那一步"（模型怎么排），不能是"要验的那一步"。
 *
 * ── 2026-10-15 起有三个分支（用 prompt 的第一句分辨，那是三类请求各不相同的开场白）──
 *   ① 对话（ChatComposer，"通过对话把出行信息问清楚"）→ {reply, params, persona}。
 *      persona 依据**逐字取自 prompt 里渲染的对话历史**（"用户：xxx" 那几行）——
 *      这样 PersonaGuard 的逐字回查在真后端里真的会被走到且真的通过，
 *      而不是"假模型和真闸门各说各话"。年份从 prompt 里的【今天的日期】抠，别写死。
 *   ② 检索计划（RetrievalPlanner，"检索条件规划助手"）→ {wantedCategories, keywords, needHotel}。
 *      没有这一分支它也能活（那侧有降级宽查的兜底），但降级会烧一次重试并留一条 warning，
 *      而验收脚本盯的正是 warnings —— 让它干净比让它兜底好。
 *   ③ 行程草稿（PlanComposer，"亲子行程编排助手"）→ 原来的固定草稿。
 *      同时把**收到的 prompt 原文**落盘到 out/fake-llm-last-draft.json ——
 *      验收脚本读它就能断言"persona 真的进了排程 prompt"（前端带回来 → 后端没丢），
 *      这是链路上任何一处静默丢字段都逃不过的验法。
 *
 * 用法：node fake-llm.mjs [端口]   （默认 8899）
 */
import { createServer } from 'node:http'
import { writeFileSync } from 'node:fs'

const PORT = Number(process.argv[2] ?? 8899)
const OUT_DIR = new URL('./out/', import.meta.url)

/** 一份**结构合法**的行程草稿：景点用 C1..C8（脚本不校验它们，只校验交通那一行）。 */
function draft(days) {
  const items = (start, n) =>
    Array.from({ length: n }, (_, i) => ({
      candidateId: `C${start + i}`,
      time: i === 0 ? '10:00' : `${9 + i}:30`,
      tips: '（假模型写的提示，用来占位）',
    }))
  return {
    summary: '（假模型的 summary）这份行程用来验收交通那一行。',
    days: Array.from({ length: days }, (_, i) => ({
      day: i + 1,
      theme: `第 ${i + 1} 天（假模型）`,
      hotelCandidateId: i === days - 1 ? 'none' : 'H1',
      hotelTips: null,
      items: items(i * 2 + 1, 2),
    })),
    warnings: [],
  }
}

/* ── ① 对话分支 ────────────────────────────────────────────────────
 * 和前端 mock 是同一条规矩：**只做关键词 → 字段的粗暴映射，不假装理解语义**。
 * 它模拟的是"一个很守规矩的模型"：字段从原话抠、persona 依据原样照抄 ——
 * 恰好是 PersonaGuard 逐字回查设计里"应该放行"的那类输出。 */
const PERSONA_RULES = [
  { key: 'walking', value: '少走路', hit: /放松|轻松|不想折腾|怕累|少走/ },
  { key: 'rest', value: '要午睡', hit: /午睡|午觉/ },
  { key: 'crowd', value: '避开人多', hit: /人多|排队|人挤/ },
  { key: 'budget', value: '想省着花', hit: /省钱|预算|便宜|划算/ },
]

function chatAnswer(prompt) {
  // prompt 里渲染的历史是「用户：xxx」/「助手：xxx」各占一行
  const userLines = (prompt.match(/^用户：.*$/gm) ?? []).map((l) => l.slice('用户：'.length))
  const all = userLines.join('\n')
  // 年份从【今天的日期】抠 —— 写死的话明年这份假模型就开始输出过去的日期了
  const today = /(\d{4})-(\d{2})-(\d{2})/.exec(prompt)
  const year = today ? today[1] : String(new Date().getFullYear())

  const origin = /(?:我们|从)([^，。,.\s]{1,6}?)出发/.exec(all)?.[1] ?? null
  const city = /去([^，。,.\s]{1,6}?)玩/.exec(all)?.[1] ?? null
  const days = /(\d+)\s*天/.exec(all)?.[1] ?? null
  const childAge = /(\d+)\s*岁/.exec(all)?.[1] ?? null
  const mon = /(\d{1,2})\s*月\s*(\d{1,2})\s*[号日]/.exec(all)
  const startDate = mon ? `${year}-${mon[1].padStart(2, '0')}-${mon[2].padStart(2, '0')}` : null

  // persona：哪句命中就抄哪句（整句照抄 = 逐字回查必过）。同 key 只留第一次出现。
  const persona = []
  for (const line of userLines) {
    for (const r of PERSONA_RULES) {
      if (r.hit.test(line) && !persona.some((p) => p.key === r.key)) {
        persona.push({ key: r.key, value: r.value, evidence: line })
      }
    }
  }

  return {
    reply: persona.length > 0
      ? `（假模型）记下了，你说的偏好我也记下来了。`
      : '（假模型）记下了。',
    // 注意：这里给的是"这一轮的补丁"，字段全给也没关系 —— ChatComposer 的合并
    // 规则是"null 保留原值"，和真模型只报新信息是同一条路。
    params: { origin, city, days: days ? Number(days) : null, startDate,
      childAge: childAge ? Number(childAge) : null, preferences: [], pace: null, stroller: null },
    persona,
  }
}

const server = createServer((req, res) => {
  let body = ''
  req.on('data', (c) => (body += c))
  req.on('end', () => {
    let prompt = body
    try {
      prompt = JSON.parse(body).messages?.[0]?.content ?? body
    } catch { /* 不是 JSON 的请求按原文处理 */ }

    let content
    if (prompt.includes('通过对话把出行信息问清楚')) {
      content = JSON.stringify(chatAnswer(prompt))
    } else if (prompt.includes('检索条件规划助手')) {
      content = JSON.stringify({ wantedCategories: ['动物园', '海洋馆'], keywords: [], needHotel: true })
    } else {
      // 天数从 prompt 里猜一下（"共 N 天"），猜不到就按 9 天 —— 只影响假数据的条数，
      // 不影响任何被验收的东西（交通那一行挂的是第 1 天和最后一天）。
      const m = /(\d+)\s*天/.exec(prompt)
      const days = m ? Math.min(Math.max(Number(m[1]), 1), 15) : 9
      // 落盘：验收脚本读它断言"persona 进了排程 prompt"（链路任何一处丢字段都跑不掉）
      try {
        writeFileSync(new URL('fake-llm-last-draft.json', OUT_DIR), JSON.stringify({ prompt }))
      } catch { /* 落盘失败不挡正常回包 */ }
      content = JSON.stringify(draft(days))
    }
    res.writeHead(200, { 'content-type': 'application/json' })
    res.end(
      JSON.stringify({
        id: 'fake-1',
        object: 'chat.completion',
        created: Math.floor(Date.now() / 1000),
        model: 'fake',
        choices: [{ index: 0, message: { role: 'assistant', content }, finish_reason: 'stop' }],
        usage: { prompt_tokens: 0, completion_tokens: 0, total_tokens: 0 },
      }),
    )
  })
})

server.listen(PORT, '127.0.0.1', () => {
  console.log(`假模型在 http://127.0.0.1:${PORT}/v1 上等着（对话 / 检索计划 / 草稿三个分支）`)
})
