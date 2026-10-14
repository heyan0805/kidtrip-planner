import { PROBE_WORKSPACE } from './_paths.mjs'
/**
 * L1 前端验收：在**真实浏览器**里确认"今天怎么到的"这一行真的画出来了。
 *
 * 为什么后端 42/42 全过了还要再做一遍：
 *   后端验收证明的是**数据对了**，前端验收证明的是**人看得见**。
 *   这两件事之间隔着整整一层渲染，而那一层的失败方式全都是静默的：
 *     · `travelDay.transfer` 字段名抄错一个字母 → 这一行永远不出现，控制台不报错
 *     · 把 `day.transfer` 写成 `plan.transfer` → 同上
 *     · 时刻切片切错位（ISO 里取 11..16 却写成 0..5）→ 画出 "2026-" 这种"看起来像数据"的东西
 *     · 交通行排在住宿行后面 → 逻辑上没错，但"到之前"和"到之后"的顺序读起来是反的
 *   后端一个都测不到这些。而 L1 的**全部意义**就是这一行 —— 它没画出来，L1 等于没做。
 *
 * 用本机 Edge（playwright-core + channel:'msedge'），省掉几百 MB 的 Chromium 下载。
 *
 * 用法：
 *   NODE_PATH=<node workspace>/node_modules node verify-l1-ui.mjs
 *   APP_URL=http://127.0.0.1:5174/ node verify-l1-ui.mjs   # dev server 不在默认端口时
 *
 * ⚠️ 这个脚本会真的点一次"生成行程"，等待 20~90 秒，并消耗一次 LLM 调用。
 *    车次查询不会消耗上游额度（预跑缓存命中），但**大模型排程每次都跑**。
 */

import { createRequire } from 'node:module'

// ESM 的 import 不认 NODE_PATH，用 createRequire 从隔离目录解析（见 verify-b5-ui.mjs）
const WORKSPACE = PROBE_WORKSPACE
  .replace(/\/+$/, '')
const require = createRequire(`${WORKSPACE}/noop.js`)
const { chromium } = require('playwright-core')

const APP_URL = process.env.APP_URL ?? 'http://127.0.0.1:5173/'
const SHOTS = new URL('./shots/', import.meta.url).pathname.replace(/^\//, '')

/** 和后端探针保持同一组参数，这样两边说的"同一个场景"是真的同一个 */
const ORIGIN = '广州'
const CITY = '珠海'
const TRAIN_NO = '动车D947'
const OUT_STATION = '广州南'
const IN_STATION = '珠海'
const DURATION_TEXT = '53 分钟'
const PRICE_TEXT = '¥70'

const results = []
function check(ok, label, detail) {
  results.push({ ok, label, detail })
  console.log(`${ok ? '✓' : '✗'} ${label}`)
  if (detail) console.log(`      ${detail}`)
}

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage({ viewport: { width: 1280, height: 1000 } })
page.on('pageerror', (e) => console.log('   [页面错误]', e.message))
page.on('console', (m) => {
  if (m.type() === 'error') console.log('   [console.error]', m.text())
})

console.log(`\n═══ L1 前端验收：${ORIGIN} → ${CITY} · 交通行进得了界面吗 ═══`)
console.log(`目标：${APP_URL}`)

try {
  await page.goto(APP_URL, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('h1', { timeout: 15_000 })

  async function readProfile() {
    const rows = await page.$$eval('aside dl > div', (els) =>
      els.map((e) => [
        e.querySelector('dt')?.textContent?.trim() ?? '',
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

  /* ── 对话：必须把 origin 问出来 ─────────────────────────────── */
  console.log('\n【1】对话：把出发地和目的地都说出来')
  await send(`我们${ORIGIN}出发，想带孩子去${CITY}玩`)   // 出发地和目的地同一句里：逼模型两个字段都要填
  await send('大概 3 天吧，孩子 6 岁')
  await send('11 月 17 号出发')

  const profile = await readProfile()
  console.log('      画像：', JSON.stringify(profile))

  check(profile['出发地'] === ORIGIN,
    `⭐ 画像卡上有「出发地」且是 ${ORIGIN} —— 没有它，整条"每段不能太长"的约束落不了地`,
    `出发地=${profile['出发地']}（这一行是 L1 新加的字段）`)
  check(profile['目的地'] === CITY, `目的地是 ${CITY}`, `目的地=${profile['目的地']}`)
  await page.screenshot({ path: `${SHOTS}l1-ui-1-profile.png`, fullPage: true })

  /* ── 生成 ───────────────────────────────────────────────────── */
  console.log('\n【2】点「生成行程」，等 20~90 秒')
  const t0 = Date.now()
  await page.click('button:has-text("生成行程")')
  await page.waitForFunction(() => !document.body.innerText.includes('正在生成'), null, {
    timeout: 180_000,
  })
  console.log(`      实际等待 ${((Date.now() - t0) / 1000).toFixed(1)} 秒`)
  await page.screenshot({ path: `${SHOTS}l1-ui-2-plan.png`, fullPage: true })

  /* ── 第 1 天：去程 ──────────────────────────────────────────── */
  const cards = await page.$$eval('article', (els) =>
    els.map((e) => ({
      title: e.querySelector('h3')?.textContent?.trim() ?? '',
      text: e.innerText.replace(/\s+/g, ' ').trim(),
    })),
  )
  check(cards.length === 3, '界面上出现 3 张「天」卡片', `实际 ${cards.length} 张`)

  const day1 = cards[0]
  const last = cards[cards.length - 1]

  check(!!day1?.text.includes('🚄'),
    '⭐ 第 1 天卡片里出现了 🚄 这一行（L1 的全部意义就在这里）',
    day1 ? day1.text.slice(0, 200) : '没有第 1 天卡片')

  if (day1) {
    // 逐项对后端探针验过的同样的数字 —— 前端画错了任何一个都会红
    check(day1.text.includes(OUT_STATION) && day1.text.includes(IN_STATION),
      `站名画对了（${OUT_STATION} → ${IN_STATION}）`)
    check(day1.text.includes(TRAIN_NO), `车次号画对了（${TRAIN_NO}）`)
    // ⚠️ 格式必须带"分钟"两个字 —— 光有数字 53 是看不出单位是分钟还是秒的
    check(day1.text.includes(DURATION_TEXT),
      `⭐ 耗时画成了「${DURATION_TEXT}」（带单位，不是光秃秃的 53）`,
      'formatDuration 把分钟数说成人话，这是这一行最该看的东西')
    check(day1.text.includes(PRICE_TEXT), `票价画对了（${PRICE_TEXT}）`)
    check(/0[89]:\d\d/.test(day1.text),
      '⭐ 画的是**时刻**（08:36 这种），不是 ISO 原文 —— 切片切错位会画出 "2026-" 之类',
      day1.text.match(/\d{2}:\d{2}/g)?.join(' ') ?? '一个 HH:MM 都没有')
    check(day1.text.includes('去飞猪看这趟车'), '有「去飞猪看这趟车」的原文链接')
    // 顺序：交通发生在"到"之前，所以必须排在住宿前面
    const iTransfer = day1.text.indexOf('🚄')
    const iStay = day1.text.indexOf('🏨') >= 0 ? day1.text.indexOf('🏨') : day1.text.indexOf('住宿')
    check(iTransfer >= 0 && iStay >= 0 && iTransfer < iStay,
      '⭐ 交通行排在住宿行**前面**（"怎么到的"发生在"今晚住哪"之前）',
      `🚄@${iTransfer} vs 住宿@${iStay}`)
  }

  /* ── 最后一天：回程 ─────────────────────────────────────────── */
  check(!!last?.text.includes('🚄'),
    '⭐ 最后一天也有 🚄（回程不是另一类东西，是同一张图上的同一条边）',
    last ? last.text.slice(0, 200) : '没有最后一天卡片')

  if (last) {
    // 回程方向是反的 —— 这条能验出"靠天数推断方向"那类错误
    const outIdx = last.text.indexOf(OUT_STATION)
    const inIdx = last.text.indexOf(IN_STATION)
    check(inIdx >= 0 && outIdx > inIdx,
      `⭐ 回程方向是反的（${IN_STATION} → ${OUT_STATION}），不是把去程又画了一遍`,
      `收到的顺序：${last.text.match(/..站|珠海|广州南/g)?.join(' · ')}`)
  }

  /* ── 溯源里能不能看见车次查询 ───────────────────────────────── */
  // ⚠️ 必须**先展开** <details>。
  //    收起的 details，内容根本不在 innerText 里 —— 第一版没展开就断言，
  //    于是这条检查永远是红的。那是我自己的断言 bug，不是产品没做这件事。
  //    值得记下来：一个因为"元素被折叠"而永远失败的检查，和一个永远通过的检查一样没用 ——
  //    它指向的不是产品状态。（这次是假红，上一次 verify-l1.mjs 的 `?? []` 是假绿，两个方向都踩过。）
  await page.$$eval('details', (els) => els.forEach((e) => { e.open = true }))
  const body = await page.innerText('body')

  check(body.includes('search-train'),
    '⭐ 溯源里能看到真实的 search-train 命令（成本是可见的）',
    '这条把"界面上的数字"和"真的发出去的那条命令"连起来了')
  // 两个方向都要在 —— 只出现一条，说明回程那一查根本没发出去
  check(body.includes(`--origin ${ORIGIN}`) && body.includes(`--origin ${CITY}`),
    `⭐ 去程和回程两条车次命令都能看见（--origin ${ORIGIN} 与 --origin ${CITY} 都在）`,
    body.match(/search-train[^\n]{0,80}/g)?.join('\n      ') ?? '一条 search-train 都没渲染出来')
  check(body.includes('--total-duration-hour 3'),
    '⭐ 命令里带着 3 小时上限 —— 界面上能看见"这条约束真的被拿去查了"',
    '这是这份产品唯一的核心约束，它不该只活在配置文件和注释里')
  await page.screenshot({ path: `${SHOTS}l1-ui-3-provenance.png`, fullPage: true })
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
