import { PROBE_WORKSPACE } from './_paths.mjs'
/**
 * 完整跑一遍给人看 —— 不是验收，是"打开看看它长什么样"。
 *
 * 和 verify-b5-ui.mjs 的区别：那个是断言脚本（跑完就断言，不点生成）；
 * 这个一路走到底：三轮对话 → 点生成 → 等 25~60 秒 → 截完整行程。
 *
 * 用本机 Edge（channel:'msedge'），免掉几百 MB 的 Chromium 下载。
 *
 * 用法：node demo-run.mjs
 *   ROUNDS='["第一句","第二句","第三句"]'  换成自己想跑的对话（默认是珠海 3 天那组）
 *   TAG=b6                                 截图前缀（不传就是 demo）
 */

import { createRequire } from 'node:module'

const WORKSPACE = PROBE_WORKSPACE
  .replace(/\/+$/, '')
const require = createRequire(`${WORKSPACE}/noop.js`)
const { chromium } = require('playwright-core')

const APP_URL = process.env.APP_URL ?? 'http://127.0.0.1:5173/'
/** 截图前缀，方便同一个目录里放多组演示 */
const TAG = process.env.TAG ?? 'demo'
// ⚠️ 常量不要叫 URL —— 会遮蔽全局 URL 构造函数
const SHOTS = new URL('./shots/', import.meta.url).pathname.replace(/^\//, '')

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage({ viewport: { width: 1280, height: 900 } })
page.on('pageerror', (e) => console.log('   [页面错误]', e.message))
page.on('console', (m) => {
  if (m.type() === 'error') console.log('   [console.error]', m.text())
})

/** 读右侧画像卡 */
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

/** 发一轮，等它结束 */
async function send(text) {
  await page.fill('input', text)
  await page.click('button:has-text("发送")')
  await page.waitForSelector('text=正在想', { timeout: 8_000 }).catch(() => {})
  await page.waitForFunction(
    () => !document.body.innerText.includes('正在想'),
    null,
    { timeout: 120_000 },
  )
}

try {
  await page.goto(APP_URL, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('h1', { timeout: 15_000 })
  console.log('✅ 页面已打开')
  await page.screenshot({ path: `${SHOTS}${TAG}-0-start.png` })

  const ROUNDS = process.env.ROUNDS
    ? JSON.parse(process.env.ROUNDS)
    : [
        '想带孩子去珠海玩 3 天，孩子 6 岁',
        '需要带推车，节奏慢一点',
        '想看看海洋馆和动物',
      ]
  for (let i = 0; i < ROUNDS.length; i++) {
    const t0 = Date.now()
    await send(ROUNDS[i])
    console.log(`✅ 第 ${i + 1} 轮结束（${((Date.now() - t0) / 1000).toFixed(1)}s）`)
  }

  console.log('   画像：', JSON.stringify(await readProfile()))
  await page.screenshot({ path: `${SHOTS}${TAG}-1-chat.png`, fullPage: true })

  console.log('⏳ 点「生成行程」，要 25~60 秒…')
  const t0 = Date.now()
  await page.click('button:has-text("生成行程")')
  // 行程展示区的 details summary 出现 = 真的拿到结果了
  await page.waitForSelector('text=这份行程是怎么来的', { timeout: 200_000 })
  console.log(`✅ 行程生成完成，耗时 ${((Date.now() - t0) / 1000).toFixed(1)}s`)

  await page.screenshot({ path: `${SHOTS}${TAG}-2-plan.png`, fullPage: true })

  /* ── 把结果打印成摘要，方便不看图也知道发生了什么 ────────── */
  const summary = await page
    .$eval('section h2', (e) => e.textContent?.trim())
    .catch(() => '(没读到)')
  console.log('\n摘要：', summary)

  const days = await page.$$eval('article h3', (els) => els.map((e) => e.textContent?.trim()))
  console.log(`行程天数：${days.length}`)
  days.forEach((d) => console.log('   ', d))

  const hotels = await page.$$eval('article p.text-gray-700', (els) =>
    els.map((e) => e.textContent?.replace(/\s+/g, ' ').trim()),
  )
  console.log('住宿：')
  hotels.forEach((h, i) => console.log(`   第 ${i + 1} 天`, h))

  const meta = await page
    .$eval('section p.text-xs', (e) => e.textContent?.replace(/\s+/g, ' ').trim())
    .catch(() => '')
  console.log('账本：', meta)
} catch (e) {
  console.log('❌ 异常：', e.message)
  await page.screenshot({ path: `${SHOTS}${TAG}-error.png`, fullPage: true }).catch(() => {})
} finally {
  await browser.close()
}
