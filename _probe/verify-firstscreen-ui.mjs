import { PROBE_WORKSPACE } from './_paths.mjs'
/**
 * 首屏验收（A + B 两件事，都在真实浏览器里量）。
 *
 * A. 首屏讲不讲得清自己是谁、能不能一键开跑
 *    · 标题下面那句副标题在不在
 *    · 三个示例 chip 在不在、文案对不对
 *    · 点一下**发出去的是完整那句话**（不是 chip 上的短标签）
 *    · 点完之后 chip 消失、助手回话进来
 *
 * B. 手机上的浮层还挡不挡"待确认"清单
 *    · 390 宽下 CTA 的 computed position 必须是 static（钉住才挡）
 *    · 390 宽下把页面从头滚到尾，CTA 矩形与 aside 里任何一行文字**一次都不能相交**
 *    · 1280 宽下必须还是 sticky —— 别为了修手机把桌面的"常驻按钮"弄没了
 *
 * 为什么 B 要滚着量、而不是只查一条 CSS：
 *   sticky 的遮挡只在"滚到某个中间位置"时发生 —— 页面在顶部、或者滚到底时，
 *   按钮都在自己的自然位置上，看不出问题。只断言 CSS 会漏掉真实的那一帧。
 *
 * 用法（mock 模式的前端，不需要后端）：
 *   cd frontend && VITE_USE_MOCK=true npx vite --port 5176
 *   NODE_PATH=<node workspace>/node_modules node _probe/verify-firstscreen-ui.mjs
 */
import { createRequire } from 'node:module'

const WORKSPACE = PROBE_WORKSPACE.replace(/\/+$/, '')
const require = createRequire(`${WORKSPACE}/noop.js`)
const { chromium } = require('playwright-core')

const APP_URL = process.env.APP_URL ?? 'http://127.0.0.1:5176/'
const SHOTS = new URL('./shots/', import.meta.url).pathname.replace(/^\//, '')

const SUBTITLE = '我去查真实的景点、酒店和车次'
const CHIP_LABELS = ['珠海 3 天 · 孩子 6 岁', '上海 2 天 · 1 岁宝宝', '杭州 5 天 · 俩娃 3 岁 + 8 岁']
/** 点第一个 chip 之后，聊天里应该出现的是**这句**，不是上面那个短标签 */
const EXPECTED_SENT = '从深圳出发，想带孩子去珠海玩 3 天，孩子 6 岁'
const results = []
function check(ok, label, detail) {
  results.push({ ok, label, detail })
  console.log(`${ok ? '✓' : '✗'} ${label}`)
  if (detail) console.log(`      ${detail}`)
}

const browser = await chromium.launch({ channel: 'msedge', headless: true })

/* ── A：首屏 ───────────────────────────────────────────── */
const mobile = await browser.newPage({ viewport: { width: 390, height: 844 } })
mobile.on('pageerror', (e) => console.log('   [页面错误]', e.message))
await mobile.goto(APP_URL, { waitUntil: 'networkidle' })

const bodyText = await mobile.evaluate(() => document.body.innerText)
check(bodyText.includes(SUBTITLE), 'A1 标题下有那句"我是干什么的"', SUBTITLE)
check(
  bodyText.includes('AI 亲子版') && bodyText.includes('带娃旅行助理已就绪'),
  'A2 品牌徽标与就绪条在位',
  'AI 亲子版 / 带娃旅行助理已就绪',
)
check(bodyText.includes('按 Enter 发送'), 'A3 输入框下有 Enter 提示', '按 Enter 发送')

const labels = await mobile.$$eval('[data-probe="examples"] button [data-label]', (els) =>
  els.map((e) => e.textContent.trim()),
)
check(
  labels.length === CHIP_LABELS.length && CHIP_LABELS.every((t, i) => labels[i] === t),
  'A4 三个示例 chip 都在，文案逐字对得上（emoji 不掺进断言）',
  JSON.stringify(labels),
)

await mobile.screenshot({ path: `${SHOTS}firstscreen-mobile.png` })

// 点第一个 chip —— 断言"发出去的是完整那句话"。这是 A 的核心：
// chip 上写"珠海 3 天"是为了挤得下，真发出去的必须是能听懂的人话。
const before = await mobile.$$eval('[data-probe="examples"] button', (e) => e.length)
await mobile.click('[data-probe="examples"] button')
await mobile.waitForFunction(
  () => {
    const t = document.body.innerText
    return t.includes('从深圳出发，想带孩子去珠海玩 3 天，孩子 6 岁')
  },
  { timeout: 5000 },
).catch(() => {})

const afterClick = await mobile.evaluate(() => document.body.innerText)
check(afterClick.includes(EXPECTED_SENT), 'A5 点 chip 发出去的是完整那句话', EXPECTED_SENT)

// 助手回话之后 chip 应该让位（空态只在没有消息时出现）
await mobile.waitForTimeout(1200)
const chipsGone = await mobile.$$eval('[data-probe="examples"]', (e) => e.length)
check(chipsGone === 0 && before === 3, 'A6 开聊之后示例 chip 让位（空态只属于首屏）', `点击前 ${before} 个，之后 ${chipsGone} 个`)

await mobile.screenshot({ path: `${SHOTS}firstscreen-mobile-talked.png` })

/* ── B：手机浮层不挡字 ─────────────────────────────────── */
const posMobile = await mobile.$eval('[data-probe="cta"]', (el) => getComputedStyle(el).position)
check(posMobile === 'static', 'B1 390 宽下 CTA 不钉住（position: static）', `实测 ${posMobile}`)

/**
 * 把页面从头滚到尾，每一帧都问一句：CTA 的矩形，压到 aside 里哪行字了吗？
 * 只算**叶子文本元素**，并排除 CTA 自己和它的子孙（黄条就在里面，它是同伙不算受害者）。
 */
const hits = []
const maxScroll = await mobile.evaluate(
  () => document.documentElement.scrollHeight - window.innerHeight,
)
for (let y = 0; y <= Math.max(maxScroll, 0); y += 30) {
  const covered = await mobile.evaluate((top) => {
    window.scrollTo(0, top)
    const cta = document.querySelector('[data-probe="cta"]').getBoundingClientRect()
    const leaves = [...document.querySelectorAll('aside *')].filter(
      (el) =>
        el.children.length === 0 &&
        el.textContent.trim() &&
        !el.closest('[data-probe="cta"]'),
    )
    return leaves
      .map((el) => ({ text: el.textContent.trim().slice(0, 20), r: el.getBoundingClientRect() }))
      .filter(({ r }) => r.width > 0 && r.height > 0)
      .filter(({ r }) => !(r.right <= cta.left || r.left >= cta.right || r.bottom <= cta.top || r.top >= cta.bottom))
      .map(({ text }) => text)
  }, y)
  if (covered.length) hits.push({ y, covered })
}
check(
  hits.length === 0,
  'B2 390 宽下从头滚到尾，CTA 一次都没压到 aside 里的字',
  hits.length ? `压到了 ${hits.length} 帧，例：y=${hits[0].y} “${hits[0].covered[0]}”` : `扫了 ${Math.ceil(maxScroll / 30) + 1} 帧，零相交`,
)

/* ── 桌面别退化 ────────────────────────────────────────── */
const desktop = await browser.newPage({ viewport: { width: 1280, height: 1000 } })
await desktop.goto(APP_URL, { waitUntil: 'networkidle' })
const posDesktop = await desktop.$eval('[data-probe="cta"]', (el) => getComputedStyle(el).position)
check(posDesktop === 'sticky', 'B3 1280 宽下 CTA 仍然常驻（position: sticky）', `实测 ${posDesktop}`)
await desktop.screenshot({ path: `${SHOTS}firstscreen-desktop.png` })

await browser.close()

const bad = results.filter((r) => !r.ok)
console.log(`\n${results.length - bad.length}/${results.length} 通过`)
if (bad.length) {
  console.log('未通过：' + bad.map((r) => r.label).join(' / '))
  process.exit(1)
}
