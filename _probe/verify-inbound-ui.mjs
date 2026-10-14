import { PROBE_WORKSPACE } from './_paths.mjs'
/**
 * 用户那一单的前端验收：深圳 → 广州 5 天。
 *
 * 它要复核的是用户实际看到的那句质问 ——「要下午回去，怎么买了凌晨6点的车票？」
 * 所以在浏览器里要同时确认两件事：
 *   ① **车次本身**：最后一天那一行画的是下午的车，不是 06:18
 *   ② **和行程对得上**：第 1 天的点没有早于到站时间、最后一天的点没有晚于发车时间
 *
 * 为什么后端 15/15 过了还要在浏览器里再看一遍：
 *   后端证明"数据对了"，前端证明"人看得见"。中间隔着整整一层渲染，
 *   而那一层的失败方式全是静默的（字段名抄错、时刻切片切错位、行排错位置）。
 *   这一轮的 bug 恰恰就是"数据都对、拼起来不能用"——同一个道理，
 *   在渲染这一层会以另一种形式再犯一次。
 *
 * 用法：
 *   APP_URL=http://127.0.0.1:5174/ NODE_PATH=<workspace>/node_modules node verify-inbound-ui.mjs
 */

import { createRequire } from 'node:module'

const WORKSPACE = PROBE_WORKSPACE
  .replace(/\/+$/, '')
const require = createRequire(`${WORKSPACE}/noop.js`)
const { chromium } = require('playwright-core')

const APP_URL = process.env.APP_URL ?? 'http://127.0.0.1:5174/'
const SHOTS = new URL('./shots/', import.meta.url).pathname.replace(/^\//, '')

const ORIGIN = '深圳'
const CITY = '广州'
const OUT_TIME = '10:10'
const OUT_ARR = '10:39'
const IN_TIME = '14:16'      // ⭐ 上一轮这里是 06:18，就是用户投诉的那一趟
const BAD_TIME = '06:18'

const results = []
function check(ok, label, detail) {
  results.push({ ok, label, detail })
  console.log(`${ok ? '✓' : '✗'} ${label}`)
  if (detail) console.log(`      ${detail}`)
}

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage({ viewport: { width: 1280, height: 1100 } })
page.on('pageerror', (e) => console.log('   [页面错误]', e.message))
page.on('console', (m) => {
  if (m.type() === 'error') console.log('   [console.error]', m.text())
})

console.log(`\n═══ 用户那一单：${ORIGIN} → ${CITY} 5 天 · 回程还是凌晨车吗 ═══`)
console.log(`目标：${APP_URL}`)

try {
  await page.goto(APP_URL, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('h1', { timeout: 15_000 })

  async function readProfile() {
    const rows = await page.$$eval('aside dl > div', (els) =>
      els.map((e) => [
        // 标签后面可能跟一个必填标记（`出发地*`）—— 去掉它，否则这张表的 key 会随界面一起变。
        // 这不是"宽容"，是把两件事分开：界面怎么标必填会变（星号/颜色/图标），字段名是契约。
        (e.querySelector('dt')?.textContent?.trim() ?? '').replace(/\*+$/, ''),
        e.querySelector('dd')?.textContent?.trim() ?? '',
      ]),
    )
    return Object.fromEntries(rows)
  }

  async function send(text) {
    await page.fill('input', text)
    await page.click('button:has-text("发送")')
    await page.waitForSelector('text=正在想', { timeout: 8_000 }).catch(() => {})
    await page.waitForFunction(() => !document.body.innerText.includes('正在想'), null, {
      timeout: 120_000,
    })
  }

  /* ── 对话：照着用户当时说的原样走一遍 ───────────────────────── */
  console.log('\n【1】对话（原样复刻用户那几句）')
  await send(`想带孩子去${CITY}玩5天`)          // 用户原话
  await send(`${ORIGIN}，11月17日出发`)          // 用户原话
  await send('7岁，想带他户外，看看动物，干点农活啥的')   // 用户原话
  await send('好的，不要推车')                    // 用户原话

  const profile = await readProfile()
  console.log('      画像：', JSON.stringify(profile))
  check(profile['出发地'] === ORIGIN, `出发地是 ${ORIGIN}`, `出发地=${profile['出发地']}`)
  check(profile['目的地'] === CITY, `目的地是 ${CITY}`, `目的地=${profile['目的地']}`)
  check((profile['天数'] ?? '').includes('5'), '天数是 5 天', `天数=${profile['天数']}`)

  /* ── 生成 ───────────────────────────────────────────────────── */
  console.log('\n【2】点「生成行程」')
  const t0 = Date.now()
  await page.click('button:has-text("生成行程")')
  await page.waitForFunction(() => !document.body.innerText.includes('正在生成'), null, {
    timeout: 180_000,
  })
  console.log(`      实际等待 ${((Date.now() - t0) / 1000).toFixed(1)} 秒`)
  await page.screenshot({ path: `${SHOTS}inbound-ui-1-plan.png`, fullPage: true })

  const cards = await page.$$eval('article', (els) =>
    els.map((e) => ({
      title: e.querySelector('h3')?.textContent?.trim() ?? '',
      text: e.innerText.replace(/\s+/g, ' ').trim(),
    })),
  )
  check(cards.length === 5, '界面上出现 5 张「天」卡片', `实际 ${cards.length} 张`)

  const day1 = cards[0]
  const last = cards[cards.length - 1]

  /* ── 去程 ───────────────────────────────────────────────────── */
  console.log('\n【3】第 1 天：去程')
  check(!!day1?.text.includes('🚄'), '第 1 天有交通行', day1?.text.slice(0, 180))
  if (day1) {
    check(day1.text.includes(OUT_TIME) && day1.text.includes('深圳北'),
      `去程画的是 ${OUT_TIME} 深圳北 出发`, day1.text.match(/\d{2}:\d{2}[^ ]*/g)?.join(' ') ?? '')
    check(day1.text.includes(OUT_ARR) && day1.text.includes('广州南'), `到达是 ${OUT_ARR} 广州南`)

    // ⭐ 第 1 天的点不能早于到站 + 1 小时（到站 10:39 → 最早 11:39）
    //    先把车次那两个时刻滤掉，剩下的才是景点自己的时刻
    const spotTimes = (day1.text.match(/\d{2}:\d{2}/g) ?? [])
      .filter((t) => t !== OUT_TIME && t !== OUT_ARR)
    const earliest = spotTimes.sort()[0]
    check(earliest >= '11:39',
      '⭐ 第 1 天最早的点不早于 11:39（车 10:39 才到，出站进城要时间）',
      `第 1 天所有时刻：${(day1.text.match(/\d{2}:\d{2}/g) ?? []).join(' ')}`)
  }

  /* ── 回程：用户投诉的那一行 ─────────────────────────────────── */
  console.log('\n【4】最后一天：回程（用户投诉的就是这一行）')
  check(!!last?.text.includes('🚄'), '最后一天有交通行', last?.text.slice(0, 180))
  if (last) {
    check(last.text.includes(IN_TIME) && last.text.includes('广州南'),
      `⭐ 回程是 ${IN_TIME} 从广州南出发 —— 下午走`,
      last.text.match(/\d{2}:\d{2}[^ ]*/g)?.join(' ') ?? '')
    check(!last.text.includes(BAD_TIME),
      `⭐ 不再出现 ${BAD_TIME} 那趟凌晨车（就是用户投诉的那一班）`)
    // 方向必须是反的
    const iOut = last.text.indexOf('广州南')
    const iIn = last.text.indexOf('深圳北')
    check(iIn > iOut, `回程方向是反的（广州南 → 深圳北）`)

    const spotTimes = (last.text.match(/\d{2}:\d{2}/g) ?? []).filter((t) => t !== IN_TIME && t !== '14:45')
    const latest = spotTimes.sort().slice(-1)[0]
    check(!latest || latest <= '11:16',
      '⭐ 最后一天最晚的点不晚于 11:16（车 14:16 发，得留出赶车时间）',
      `最后一天所有时刻：${(last.text.match(/\d{2}:\d{2}/g) ?? []).join(' ')}`)
  }

  /* ── 溯源 ───────────────────────────────────────────────────── */
  await page.$$eval('details', (els) => els.forEach((e) => { e.open = true }))
  const body = await page.innerText('body')
  check(body.includes('search-train'), '溯源里能看到 search-train 命令')
  check(body.includes(`--origin ${CITY} --destination ${ORIGIN}`),
    `回程那条查询带着正确的方向（--origin ${CITY} --destination ${ORIGIN}）`)
  await page.screenshot({ path: `${SHOTS}inbound-ui-2-provenance.png`, fullPage: true })
} catch (e) {
  check(false, '脚本执行完成', `异常：${e.message}`)
} finally {
  await browser.close()
}

const failed = results.filter((r) => !r.ok)
console.log('\n' + '─'.repeat(60))
console.log(`共 ${results.length} 项，通过 ${results.length - failed.length}，失败 ${failed.length}`)
if (failed.length > 0) {
  console.log('\n失败项：')
  failed.forEach((f) => console.log(`  ✗ ${f.label}${f.detail ? ` —— ${f.detail}` : ''}`))
  process.exit(1)
}
console.log('全部通过。')
