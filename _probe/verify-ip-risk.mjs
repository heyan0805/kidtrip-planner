/**
 * 买机器前的一次性判据：换个公网出口 IP，飞猪还让不让我查？
 *
 * ── 为什么需要它 ────────────────────────────────────────────────
 *   上游对"访问来源"有风控。实测（见 backend 的 FlyaiRunner 注释）：
 *     并发 6 个        → 3 个 "MCP HTTP 429 Rate limit exceeded"
 *     连续串行不间隔   → 后 3 个 "MCP HTTP 451 Abnormal access behavior detected by risk control"
 *   于是留下一个没法拍脑袋回答的问题：
 *       把后端搬到云服务器（= 换一个公网出口 IP），飞猪会认吗？
 *   在花钱买机器之前必须先有答案，否则可能换来一堆 451。
 *
 * ── 怎么用（现在就能做，不花钱）──────────────────────────────────
 *   ① 先用**家里宽带**跑一次，记下结果：
 *          node _probe/verify-ip-risk.mjs
 *   ② 手机开热点 → 电脑连上热点（出口 IP 就换了）→ 再跑一次：
 *          node _probe/verify-ip-risk.mjs
 *   ③ 脚本把两次记录列在一起对比，并给出判定。
 *
 * ── 结果怎么读 ──────────────────────────────────────────────────
 *   两个不同 IP 都成功          → 换 IP 不触发风控，可以放心买机器
 *   家宽成功 / 热点 451         → 风控判的是访问环境，换 IP 这条路有风险
 *   两边都 429                  → 只是频率问题，隔 90 秒以上再跑
 *
 * ── ⚠️ 这个结论的边界 ───────────────────────────────────────────
 *   手机热点是运营商 NAT，和云机房的 IP 段不是一回事 —— 风控对机房 IP 通常更敏感。
 *   所以：**失败很有信息量，成功不构成保证**。它是一次必要条件检验，不是充分条件。
 *
 * ── 注意 ────────────────────────────────────────────────────────
 *   每次运行都会真实调用一次上游（消耗一次额度）。所以只发**一次**最小查询。
 */
import { FLYAI_CLI } from './_paths.mjs'
import { execFile } from 'node:child_process'
import { readFile, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'

const NODE = process.env.TEST_NODE || 'node'
const LOG = path.join(tmpdir(), 'kidtrip-ip-risk-log.json')
const CMD = ['search-poi', '--city-name', '深圳', '--category', '动物园']

/** 取出口 IP。用国内可达的服务，再退到国际的 */
async function publicIp() {
  for (const url of ['https://myip.ipip.net', 'https://api.ipify.org']) {
    try {
      const r = await fetch(url, { signal: AbortSignal.timeout(8000) })
      if (!r.ok) continue
      return (await r.text()).trim().replace(/\s+/g, ' ').slice(0, 100)
    } catch { /* 换下一个 */ }
  }
  return '(取不到出口 IP，不影响判定)'
}

function runOnce() {
  return new Promise((resolve) => {
    const t0 = Date.now()
    execFile(NODE, [FLYAI_CLI, ...CMD], {
      encoding: 'utf8',
      maxBuffer: 8 * 1024 * 1024,
      timeout: 130000,
    }, (err, stdout, stderr) => {
      let n = -1
      try {
        const j = JSON.parse(stdout)
        n = j.data ? (j.data.itemList || []).length : 0
      } catch { /* 非 JSON 就保持 -1 */ }
      const msg = (stderr || '').replace(/\s+/g, ' ')
      const code = /HTTP (\d{3})/.exec(msg)?.[1] ?? (err ? 'ERR' : '200')
      resolve({ ms: Date.now() - t0, n, code, ok: !err, msg: msg.slice(0, 200) })
    })
  })
}

const ip = await publicIp()
console.log('飞猪 CLI : ' + FLYAI_CLI)
console.log('出口 IP  : ' + ip)
console.log('查询     : ' + CMD.join(' '))
console.log('')

const r = await runOnce()
console.log((r.ok ? '✓ 成功' : '✗ 失败') + '  ' + r.ms + 'ms  上游码=' + r.code + '  条数=' + r.n)
if (!r.ok) console.log('   ↳ ' + r.msg)

let log = []
try { log = JSON.parse(await readFile(LOG, 'utf8')) } catch { /* 首次运行没有日志 */ }
log.push({
  at: new Date().toISOString().slice(0, 19).replace('T', ' '),
  ip,
  code: r.code,
  ok: r.ok,
  ms: r.ms,
})
await writeFile(LOG, JSON.stringify(log, null, 2))

console.log('\n── 历次记录（同一台机器、不同出口 IP 的对比）──')
for (const e of log) {
  console.log('  ' + e.at + '  ' + (e.ok ? '✓' : '✗') + '  码=' + String(e.code).padEnd(4) + '  ' + e.ip)
}

const ips = new Set(log.map((e) => e.ip))
const any451 = log.some((e) => e.code === '451')
const any429 = log.some((e) => e.code === '429')

console.log('')
if (log.length >= 2 && ips.size === 1) {
  console.log('提示：两次记录的出口 IP 相同 —— 热点没生效，这次"换 IP"其实没做成，再试一次。')
} else if (log.length >= 2 && log.every((e) => e.ok)) {
  console.log('判定：' + log.length + ' 个不同出口 IP 都成功 → 换 IP 没有触发风控，可以放心买机器。')
} else if (any451) {
  console.log('判定：出现过 451（风控）→ 上游会判访问环境。换 IP 这条路有风险，先别买机器。')
} else if (any429) {
  console.log('判定：出现过 429（限流）→ 这是频率问题、不是封 IP。隔 90 秒以上再跑一次。')
} else if (log.every((e) => e.ok)) {
  console.log('判定：当前出口 IP 成功。再连一次手机热点跑一遍 —— 两次都过才能说明换 IP 安全。')
} else {
  console.log('判定：出现失败，且码不是 429/451，看上面的报错原文。')
}
console.log('\n（日志：' + LOG + '  删掉它即可重新开始对比）')
