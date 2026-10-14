/**
 * 一个**假的 OpenAI 兼容接口**，只回一句写死的行程 JSON。
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
 * 用法：node fake-llm.mjs [端口]   （默认 8899）
 */
import { createServer } from 'node:http'

const PORT = Number(process.argv[2] ?? 8899)

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

const server = createServer((req, res) => {
  let body = ''
  req.on('data', (c) => (body += c))
  req.on('end', () => {
    // 天数从 prompt 里猜一下（"共 N 天"），猜不到就按 9 天 —— 只影响假数据的条数，
    // 不影响任何被验收的东西（交通那一行挂的是第 1 天和最后一天）。
    const m = /(\d+)\s*天/.exec(body)
    const days = m ? Math.min(Math.max(Number(m[1]), 1), 15) : 9
    const content = JSON.stringify(draft(days))
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
  console.log(`假模型在 http://127.0.0.1:${PORT}/v1 上等着（回固定行程 JSON）`)
})
