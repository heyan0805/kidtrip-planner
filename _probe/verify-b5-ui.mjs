import { PROBE_WORKSPACE } from './_paths.mjs'
/**
 * B5 前端验收：用真实浏览器把"上下文传递"跑一遍。
 *
 * 为什么非要用浏览器：前端最有价值的那条逻辑是**跨轮次的状态累积** ——
 * 第 2 轮请求里到底带没带第 1 轮问出来的城市、响应回来之后画像卡有没有更新。
 * 这些东西用 curl 验不了（curl 只看得到单次请求），只能真的点一遍。
 *
 * 用本机 Edge 而不是下载 Chromium：只需要 playwright-core + channel:'msedge'，
 * 省掉几百 MB 的下载。代价是依赖本机装了 Edge（这份工程所在的机器有）。
 *
 * 用法：
 *   NODE_PATH=<node workspace>/node_modules node verify-b5-ui.mjs
 */

import { createRequire } from 'node:module'

/**
 * ESM 的 `import` **不认 NODE_PATH**（那是 CJS 时代的机制），
 * 所以不能靠环境变量把 playwright-core 指给 import 用。
 * 用 createRequire 从一个指定路径去解析，是 ESM 里引用"隔离目录下的包"的正规做法。
 */
const WORKSPACE = PROBE_WORKSPACE
  .replace(/\/+$/, '')
const require = createRequire(`${WORKSPACE}/noop.js`)
const { chromium } = require('playwright-core')

const APP_URL = process.env.APP_URL ?? 'http://127.0.0.1:5173/'
// ⚠️ 别把这个常量叫 URL —— 那会遮蔽全局的 URL 构造函数，
//    然后下面 `new URL(...)` 会报一句莫名其妙的 "URL is not a constructor"。
const SHOTS = new URL('./shots/', import.meta.url).pathname.replace(/^\//, '')

const results = []
function check(ok, label, detail) {
  results.push({ ok, label, detail })
  console.log(`${ok ? '✅' : '❌'} ${label}`)
  if (detail) console.log(`   ${detail}`)
}

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage({ viewport: { width: 1280, height: 900 } })

page.on('pageerror', (e) => console.log('   [页面错误]', e.message))
page.on('console', (m) => {
  if (m.type() === 'error') console.log('   [console.error]', m.text())
})

try {
  await page.goto(APP_URL, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('h1', { timeout: 15_000 })

  /** 读右侧画像卡：返回 { 城市: '珠海', 天数: '待确认', ... } */
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

  /** 发一轮并等它结束 */
  async function send(text) {
    await page.fill('input', text)
    await page.click('button:has-text("发送")')
    // 等"正在想…"出现（确认请求确实发出去了），再等它消失
    await page.waitForSelector('text=正在想', { timeout: 8_000 }).catch(() => {})
    await page.waitForFunction(
      () => !document.body.innerText.includes('正在想'),
      null,
      { timeout: 120_000 },
    )
  }

  /* ── 第 1 轮 ────────────────────────────────────────────── */
  console.log('══ 第 1 轮：说城市 ══')
  await send('想带孩子去珠海玩')
  let profile = await readProfile()
  console.log('   画像：', JSON.stringify(profile, null, 0))
  await page.screenshot({ path: `${SHOTS}b5-ui-1.png`, fullPage: true })

  check(profile['城市'] === '珠海', '第 1 轮：城市被提取并显示在画像卡上', `城市=${profile['城市']}`)
  check(profile['天数'] === '待确认', '没问到的字段显示「待确认」（而不是空的或 0）', `天数=${profile['天数']}`)
  check(profile['推车'] === '待确认', '⭐ 推车显示「待确认」而不是「不需要」——三态在界面上没被压扁',
    `推车=${profile['推车']}`)

  const bubbles1 = await page.$$eval('span.rounded-2xl', (e) => e.map((x) => x.textContent?.trim()))
  check(bubbles1.length >= 2, '界面上出现了用户消息 + 助手回复',
    `气泡数=${bubbles1.length}：${JSON.stringify(bubbles1.slice(0, 2))}`)

  /* ── 第 2 轮：这一轮的关键是"城市还在不在" ─────────────── */
  console.log('\n══ 第 2 轮：补天数和年龄（验上下文累积）══')
  await send('大概 3 天吧，孩子 6 岁')
  profile = await readProfile()
  console.log('   画像：', JSON.stringify(profile, null, 0))
  await page.screenshot({ path: `${SHOTS}b5-ui-2.png`, fullPage: true })

  check(profile['城市'] === '珠海',
    '⭐ 第 2 轮之后城市**还在** —— 说明上一轮的画像被带回去、又原样合并回来了',
    `城市=${profile['城市']}`)
  check(profile['天数'] === '3 天', '第 2 轮补上了天数', `天数=${profile['天数']}`)
  check(String(profile['孩子年龄']).includes('6'), '第 2 轮补上了孩子年龄', `孩子年龄=${profile['孩子年龄']}`)

  const bubbles2 = await page.$$eval('span.rounded-2xl', (e) => e.map((x) => x.textContent?.trim()))
  check(bubbles2.length >= 4, '对话历史累积成 4 条以上', `气泡数=${bubbles2.length}`)

  /* ── 第 3 轮 ────────────────────────────────────────────── */
  console.log('\n══ 第 3 轮：补推车和节奏 ══')
  await send('需要带推车，节奏慢一点')
  profile = await readProfile()
  console.log('   画像：', JSON.stringify(profile, null, 0))
  await page.screenshot({ path: `${SHOTS}b5-ui-3.png`, fullPage: true })

  check(profile['城市'] === '珠海' && profile['天数'] === '3 天',
    '⭐ 第 3 轮后，第 1、2 轮的字段全都还在（三轮累积成功）', JSON.stringify(profile))
  check(profile['推车'] === '需要', '推车被识别为「需要」', `推车=${profile['推车']}`)
  check(profile['节奏'] === '慢', '节奏被识别为「慢」', `节奏=${profile['节奏']}`)

  /* ── 生成按钮的状态 ─────────────────────────────────────── */
  const genDisabled = await page.$eval('button:has-text("生成行程")', (b) => b.disabled)
  check(genDisabled === false, '有城市之后「生成行程」按钮可点')
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
  failed.forEach((f) => console.log(`  ❌ ${f.label}${f.detail ? ` —— ${f.detail}` : ''}`))
  process.exit(1)
}
