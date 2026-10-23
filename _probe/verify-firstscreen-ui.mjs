import { PROBE_WORKSPACE } from './_paths.mjs'
/**
 * 首屏验收（hero 化 + 移动端不遮挡，都在真实浏览器里量）。
 *
 * 布局是**两阶段**的：
 *   hero（没开聊）：小徽章 + 两行大字 + 大输入卡 + 示例 chips + "还差什么"一行
 *   工作台（开聊后）：聊天卡 + 右栏画像/理解/CTA —— 原来的布局
 * 所以探针也分两段：A1~A5 量 hero（新页面直接量），A6~A8 和 B 组量工作台
 * （点一个 chip 让它进入对话态之后量）。
 *
 * B 组为什么滚着量：sticky 的遮挡只在"滚到某个中间位置"时发生，
 * 只断言 CSS 会漏掉真实的那一帧。CTA 只在工作台存在，所以 B 组天然在对话态跑。
 *
 * 用法（mock 模式的前端，不需要后端）：
 *   cd frontend && VITE_USE_MOCK=true npx vite --port 5176
 *   NODE_PATH=<node workspace>/node_modules node _probe/verify-firstscreen-ui.mjs
 */
import { createRequire } from 'node:module'

const WORKSPACE = PROBE_WORKSPACE.replace(/\/+$/, '')
const require = createRequire(`${WORKSPACE}/noop.js`)
const { chromium } = require('playwright-core')

const SITE = process.env.APP_URL ?? 'http://127.0.0.1:5176/'
const SHOTS = new URL('./shots/', import.meta.url).pathname.replace(/^\//, '')

const HERO_MAIN = '说说你的计划'
const HERO_SUB = '我去查真实的景点、酒店和车次'
const CHIP_LABELS = ['珠海 3 天 · 孩子 6 岁', '上海 2 天 · 1 岁宝宝', '杭州 5 天 · 俩娃 3 岁 + 8 岁']
/** 点第一个 chip 之后，聊天里应该出现的是**这句**，不是 chip 上的短标签 */
const EXPECTED_SENT = '从深圳出发，想带孩子去珠海玩 3 天，孩子 6 岁'

const results = []
function check(ok, label, detail) {
  results.push({ ok, label, detail })
  console.log(`${ok ? '✓' : '✗'} ${label}`)
  if (detail) console.log(`      ${detail}`)
}

const browser = await chromium.launch({ channel: 'msedge', headless: true })

/* ── A：hero 首屏 ──────────────────────────────────────── */
const mobile = await browser.newPage({ viewport: { width: 390, height: 844 } })
mobile.on('pageerror', (e) => console.log('   [页面错误]', e.message))
await mobile.goto(SITE, { waitUntil: 'networkidle' })

const bodyText = await mobile.evaluate(() => document.body.innerText)
check(
  bodyText.includes(HERO_MAIN) && bodyText.includes(HERO_SUB),
  'A1 hero 两行大字在位（主句 + 灰色次句）',
  `${HERO_MAIN} / ${HERO_SUB}`,
)
check(bodyText.includes('AI 亲子版'), 'A2 品牌徽标在位（hero 阶段）', 'AI 亲子版')
check(
  bodyText.includes('按 Enter 发送') && bodyText.includes('AI 会接着问你还没说的信息'),
  'A3 大输入卡底部两行提示在位（能力话术只写做得到的）',
  '按 Enter 发送 / AI 会接着问你还没说的信息',
)
check(
  bodyText.includes('不瞎猜，缺什么就问什么'),
  'A4 hero 顶部的徽章文案在位（这句只属于 hero，工作台没有）',
  '不瞎猜，缺什么就问什么',
)

const labels = await mobile.$$eval('[data-probe="examples"] button [data-label]', (els) =>
  els.map((e) => e.textContent.trim()),
)
check(
  labels.length === CHIP_LABELS.length && CHIP_LABELS.every((t, i) => labels[i] === t),
  'A5 三个示例 chip 都在，文案逐字对得上（emoji 不掺进断言）',
  JSON.stringify(labels),
)

await mobile.screenshot({ path: `${SHOTS}firstscreen-mobile.png` })

// 点第一个 chip —— 断言"发出去的是完整那句话"。
await mobile.click('[data-probe="examples"] button')
await mobile.waitForFunction(
  () => document.body.innerText.includes('从深圳出发，想带孩子去珠海玩 3 天，孩子 6 岁'),
  { timeout: 5000 },
).catch(() => {})

const afterClick = await mobile.evaluate(() => document.body.innerText)
check(afterClick.includes(EXPECTED_SENT), 'A6 点 chip 发出去的是完整那句话', EXPECTED_SENT)

// 助手回话之后：hero 应该让位给工作台（就绪条出现 = 聊天卡挂载了）。
// ⚠️ 对照基准必须用**点击前**的快照（bodyText）：hero 在用户气泡落地的一瞬间就切走，
//    等助手回复完再截图，"hero 消失"早就发生了 —— 拿点击后的文本对照恒假。
const afterReply = await mobile.evaluate(() => document.body.innerText)
check(
  afterReply.includes('带娃旅行助理已就绪') && !bodyText.includes('带娃旅行助理已就绪'),
  'A7 开聊后 hero 让位给工作台（就绪条出现、hero 消失）',
  '就绪条只在对话态出现',
)
const chipsGone = await mobile.$$eval('[data-probe="examples"]', (e) => e.length)
check(chipsGone === 0, 'A8 开聊之后示例 chip 让位', `之后 ${chipsGone} 个`)

await mobile.screenshot({ path: `${SHOTS}firstscreen-mobile-talked.png` })

/* ── B：工作台的浮层不挡字（对话态）────────────────────── */
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
await desktop.goto(SITE, { waitUntil: 'networkidle' })
await desktop.screenshot({ path: `${SHOTS}firstscreen-desktop.png` })
// CTA 只在工作台存在：发一句话进入对话态再量
await desktop.click('[data-probe="examples"] button')
await desktop.waitForFunction(
  () => document.body.innerText.includes('带娃旅行助理已就绪'),
  { timeout: 8000 },
).catch(() => {})
const posDesktop = await desktop.$eval('[data-probe="cta"]', (el) => getComputedStyle(el).position)
check(posDesktop === 'sticky', 'B3 1280 宽下 CTA 仍然常驻（position: sticky）', `实测 ${posDesktop}`)
await desktop.screenshot({ path: `${SHOTS}firstscreen-desktop-talked.png` })

await browser.close()

const bad = results.filter((r) => !r.ok)
console.log(`\n${results.length - bad.length}/${results.length} 通过`)
if (bad.length) {
  console.log('未通过：' + bad.map((r) => r.label).join(' / '))
  process.exit(1)
}
