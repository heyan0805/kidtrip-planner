import { PROBE_WORKSPACE } from './_paths.mjs'
/**
 * 降级路验收：**没有后端**的时候，界面还能不能跑、而且跑得像不像"降级"。
 *
 * ══ 为什么这条也要验 ══════════════════════════════════════════════
 * 现场演示最大的风险不是"功能做少了"，是**整个页面打不开**：
 * 会场网络不通、后端忘开、端口被占、上游挂了 —— 任意一条都会让 Demo 变成一片空白。
 *
 * 这条降级路（VITE_USE_MOCK）是那种"专门为出事那天准备的东西"，
 * 而它有它自己的失效方式：**它平时永远不会被走到**。
 * 代码腐化得最快的从来不是每天跑的那条路，是一年走一次的那条 ——
 * 等到真出事那天才发现它早就坏了，而那时候已经没有时间修了。
 *
 * 所以这里要验三件完全不同的事：
 *   ① 它在**没有任何后端**的情况下真的打得开、真的出得来一份完整行程；
 *   ② 交通那一行照样渲染（L1 的功能在降级路上不能丢）；
 *   ③ ⭐ **它看起来必须像降级** —— 界面上到处是"（假数据）"、warning 里写着、
 *      顶上还有提示条。一份看起来像真结果的假数据，比打不开更糟：
 *      你会拿它去讲，讲完才发现讲的是编的。
 *
 * 用法：
 *   VITE_USE_MOCK=true npx vite --host 127.0.0.1 --port 5175   # 先起 mock 模式的 dev server
 *   APP_URL=http://127.0.0.1:5175/ NODE_PATH=<workspace>/node_modules node verify-mock-ui.mjs
 *
 * ⚠️ 跑之前**把后端停掉**（`netstat -ano | grep 8003 | grep LISTENING` 取 PID → taskkill）。
 *    后端开着的时候跑，这个脚本还是会通过（Mock 模式压根不发请求），
 *    但"没有后端也能用"这句话就变成了**没验证过的说法** —— 而这个脚本存在的全部理由
 *    就是验证那句话。（第一次跑就是这么验的：停掉 8003，两个 200 变一个 000。）
 */

import { createRequire } from 'node:module'

const WORKSPACE = PROBE_WORKSPACE
  .replace(/\/+$/, '')
const require = createRequire(`${WORKSPACE}/noop.js`)
const { chromium } = require('playwright-core')

const APP_URL = process.env.APP_URL ?? 'http://127.0.0.1:5175/'
const SHOTS = new URL('./shots/', import.meta.url).pathname.replace(/^\//, '')

const results = []
function check(ok, label, detail) {
  results.push({ ok, label, detail })
  console.log(`${ok ? '✓' : '✗'} ${label}`)
  if (detail) console.log(`      ${detail}`)
}

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage({ viewport: { width: 1280, height: 1000 } })
page.on('pageerror', (e) => console.log('   [页面错误]', e.message))

console.log('\n═══ 降级路验收：没有后端时界面还能用吗 ═══')
console.log(`目标：${APP_URL}`)

try {
  await page.goto(APP_URL, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('h1', { timeout: 15_000 })
  check(true, '页面在**没有任何后端**的情况下打得开（这条是这一整套验收的全部意义）')

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
    // 首轮是 hero 大输入卡（圆形箭头按钮，没有"发送"文字）→ 走 Enter；
    // 开聊后是普通输入行，仍点按钮（verify-l1 的焦点断言依赖这个）
    const sendBtn = page.locator('button:has-text("发送")')
    if ((await sendBtn.count()) > 0) await sendBtn.click()
    else await page.press('input', 'Enter')
    await page.waitForSelector('text=正在想', { timeout: 8_000 }).catch(() => {})
    await page.waitForFunction(() => !document.body.innerText.includes('正在想'), null, {
      timeout: 30_000,
    })
  }

  // 和后端验收脚本用**同一句原文**：这样两条路走的是同一个场景，能直接对照
  await send('我们广州出发，想带孩子去珠海玩')
  await send('大概 3 天吧，孩子 6 岁')
  // ⚠️ 第三轮是 2026-10-14 补的，而且它本身也是一条断言（见下面"五项必填"那几条）：
  //    "生成行程"按钮要**五项必填**齐了才可点，而假数据原来只抠得出城市和天数 ——
  //    少这一轮的话按钮永远是灰的，整套降级路验收会在点击那一步超时。
  //    场景本身没变（还是广州→珠海 3 天），只是把画像补完整。
  await send('11 月 17 日出发')

  const profile = await readProfile()
  console.log('      画像：', JSON.stringify(profile))
  check(profile['出发地'] === '广州',
    '降级路上「出发地」也被抠出来了（不是"待确认"）',
    `出发地=${profile['出发地']} —— 抠成"我们广州"就是抠错了，这里同时守着那个正则的前缀`)
  check(profile['目的地'] === '珠海',
    '目的地抠出来是"珠海"而不是"珠海玩"',
    `目的地=${profile['目的地']} —— 尾字没削干净的话这里会是"珠海玩"`)
  // ⭐ 「生成行程」按钮的可点条件（五项必填）在**降级路上也必须能被满足**。
  //    这一条是 2026-10-14 补的，因为当时发现：假数据只抠得出城市和天数，
  //    于是后端一挂、按钮永远是灰的 —— 降级路存在的意义就是"出事那天还能演示"，
  //    一个点不动的按钮等于这条退路也没了。所以这里验的不是"抠得准"，
  //    而是"**五项凑得齐**"（缺任何一项，下面那次 click 就会超时）。
  const filled = Object.entries(profile).filter(([, v]) => v && v !== '待确认')
  check(profile['孩子年龄'] === '6 岁' && profile['出发日期'] === '2026-11-17',
    '⭐ 假数据也能把「孩子年龄」和「出发日期」抠出来（否则按钮永远是灰的）',
    JSON.stringify(profile))
  check(filled.length >= 5,
    `⭐ 必填的五项都齐了（现在有 ${filled.length} 项有值）—— 缺一项就点不动生成`,
    JSON.stringify(profile))

  /* ── persona 卡（我这样理解你）─────────────────────────────────
   * 2026-10-15 加。推论必须看得见：错了他不会纠正（他以为你懂他），
   * 所以唯一的安全网就是把"我们推了什么"亮在屏幕上、每条挂他的原话。
   * 这里验的不只是"画出来了"，还有**空的时候要安静** ——
   * 没聊到偏好就挂一张空卡说"我还不懂你"，那是噪声不是提示。 */
  console.log('')
  console.log('【我这样理解你】persona 卡的渲染')
  const beforePersona = await page.innerText('body')
  check(!beforePersona.includes('我这样理解你'),
    '⭐ 还没聊到偏好时这张卡不存在（空 = 安静，不是挂着一张空卡说"我还不懂你"）')

  // 一句话带两个偏好信号（轻松 → 怕走路；排队 → 怕人多），一次验"多推论"和"引用原话"
  await send('这次想安排得轻松一点，别去人太多的网红点排队')
  const asideText = await page.innerText('aside')
  check(asideText.includes('我这样理解你'), '⭐ 说了一句带偏好的话之后卡片出现了')
  check(asideText.includes('怕走路') && asideText.includes('少走路'),
    '⭐ 推出来的判断画出来了（怕走路 · 少走路）',
    asideText.slice(asideText.indexOf('我这样理解你')))
  check(asideText.includes('怕人多') && asideText.includes('避开人多'),
    '⭐ 一句话推出来的两条都在（怕人多 · 避开人多）')
  check(asideText.includes('你说过') && asideText.includes('轻松一点'),
    '⭐ 每条都挂着他的原话（依据是后端逐字验证过才放行的，卡片上直接引用）')
  check(asideText.includes('推错了就直接说'),
    '⭐ 卡片上写明了纠正方式（用话纠正，不是让他找设置页）')

  await page.click('button:has-text("生成行程")')
  await page.waitForFunction(() => !document.body.innerText.includes('正在生成'), null, {
    timeout: 60_000,
  })
  await page.screenshot({ path: `${SHOTS}mock-ui-plan.png`, fullPage: true })

  const cards = await page.$$eval('article', (els) =>
    els.map((e) => ({ title: e.querySelector('h3')?.textContent?.trim() ?? '', text: e.innerText.replace(/\s+/g, ' ').trim() })),
  )
  check(cards.length > 0, '降级路上照样生成出了行程卡片', `实际 ${cards.length} 张`)

  const day1 = cards[0]?.text ?? ''
  check(day1.includes('🚄'),
    '⭐ L1 的交通行在降级路上照样渲染 —— 功能不因为换了数据源而消失',
    day1.slice(0, 160))
  check(day1.includes('动车D947') && day1.includes('53 分钟') && day1.includes('¥70'),
    '交通行的数字用的是实测真值（动车D947 / 53 分钟 / ¥70）',
    '换一个假数字，就会让"时长合不合理"这件事在没起后端时失去校准')
  check((cards.at(-1)?.text ?? '').includes('广州南'),
    '最后一天也有回程（方向是反的）')

  /* ── ②c ⭐ 备选走法（机票）在界面上是什么样 ─────────────────── */
  // 为什么这一条要放进来：机票那一行只在"火车这条腿偏累"时才出现，
  // 而那需要**真实上游恰好查得到一条长腿**——现场最不容易复现的场景，
  // 所以它恰恰是最该有一条不需要后端就能跑到的渲染验收。
  // 假数据里第 1 天没有备选、最后一天有：两个分支都能被看到。
  const lastDay = cards.at(-1)?.text ?? ''
  console.log('')
  console.log('【机票备选】备选走法那一行的渲染')
  check(!day1.includes('也可以飞'),
    '第 1 天没有备选行（假数据里只有回程挂了航班）—— 没有就别画，别让它变成每张卡片都有的装饰',
    day1.slice(0, 160))
  check(lastDay.includes('也可以飞'),
    '⭐ 最后一天出现了「也可以飞」这一行',
    lastDay.slice(0, 200))
  check(lastDay.includes('深航ZH9501') && lastDay.includes('宝安') && lastDay.includes('虹桥'),
    '航班号与机场短名都画出来了（不能只画城市名 —— 宝安/浦东是两回事）',
    '机场名丢了的话用户没法判断"这班是从哪儿飞的"')
  check(lastDay.includes('飞行 2 小时 5 分'),
    '⭐ 时长前面带「飞行」两个字（纯飞行时间 ≠ 门到门时间，不加这个前缀会被当成全程）',
    lastDay.slice(0, 240))
  check(lastDay.includes('¥610'),
    '票价画出来了（读数来自 ticketPrice 那一支）')
  check(lastDay.includes('票价是查询当时的'),
    '⭐ 明说了"票价是查询当时的，机票会浮动"（实测同一条线隔一个月差 2.5 倍）')
  check(lastDay.includes('查这个航班'),
    '有飞猪链接（备选也要能直接买，不能只摆着看）')
  // ⭐ 但那个链接**必须指向一个一眼看得出是占位的域名**。
  //    2026-10-14 补：原来假数据的 jumpUrl 是 null，于是这条分支在降级路上压根不渲染 ——
  //    改 `.invalid` 占位域名之后它才真的被画出来，那时候才有必要盯住"它指向哪里"。
  //    `jumpUrl` 是整份假数据里唯一不能编得像真的的字段：时刻编错了有"这是假的"兜着，
  //    而一个看起来正常的飞猪链接会把人带到**别的航班**上，且他不会察觉。
  const altLink = await page.$$eval('article', (els) => {
    const last = els[els.length - 1]
    const a = [...(last?.querySelectorAll('a') ?? [])].find((x) =>
      x.textContent.includes('查这个航班'),
    )
    return a ? { href: a.getAttribute('href') ?? '' } : null
  })
  check(altLink?.href.includes('example.invalid') === true,
    '⭐ 链接指向的是 `.invalid` 占位域名（假数据绝不编一个像真的飞猪链接）',
    `实际 href=${altLink?.href}`)
  // 顺序：主选 → 备选 → 住宿。备选讲的是"怎么到"，必须紧挨着主选，
  // 排到住宿后面就变成一条和"今晚住哪"混在一起的注释了。
  const iTrain = lastDay.indexOf('🚄')
  const iAlt = lastDay.indexOf('也可以飞')
  const iHotel = lastDay.indexOf('🏨')
  check(iTrain >= 0 && iAlt > iTrain && (iHotel < 0 || iAlt < iHotel),
    '⭐ 顺序是"主选 → 备选 → 住宿"（备选紧跟主选，不越过住宿那条线）',
    `🚄@${iTrain} 备选@${iAlt} 🏨@${iHotel}`)

  /* ── ②d ⭐ 今晚还能住哪儿（酒店备选）────────────────────────── */
  // 这一层是 2026-10-14 加的，在此之前它**完全不在屏幕上**：
  // 后端一次查回 10 家、模型只挑 1 家，剩下 9 家连"存在过"都不显示。
  // 机票那边有同样的毛病（所以有了 ②c），住宿这边更难察觉 ——
  // 用户不觉得"只给一家"是个问题，只会觉得"这家有点贵"。
  console.log('')
  console.log('【住宿备选】落选的几家有没有摆出来')

  check(day1.includes('也可以住'),
    '⭐ 第 1 天出现了「也可以住」这一行',
    day1.slice(0, 260))
  check(day1.includes('易佰酒店示例店') && day1.includes('如家旗下示例店') && day1.includes('和颐示例店'),
    '三家备选的名字都画出来了（不能只画一个"还有 3 家"）',
    day1.slice(day1.indexOf('也可以住'), day1.indexOf('也可以住') + 200))
  check(day1.includes('¥143') && day1.includes('¥158') && day1.includes('¥283'),
    '⭐ 价格画出来了（用户看这一行就是为了找"原来还有更便宜的"）',
    '假数据那三个价格取的是实测真值：易佰 ¥143 / 如家 ¥158 / 和颐 ¥283')
  check(day1.includes('舒适型') && day1.includes('高档型'),
    '⭐ 跨档：舒适档和高档都出现了（全挤在一个档的话，后端多的那次查询就白花了）',
    '备选全是一个档次 = 第二次查询纯浪费，而且没有任何信号')

  // ⭐ 连住只渲染一次。假数据里三天都是同一家酒店、**每天带同一份备选**
  //    （和后端行为一致，见 attachHotelAlternatives），"哪几天算连住"是前端 mergeStays 算的。
  //    这条断言盯的就是那个去重：少写一个 first 判断，同一个清单会在屏幕上出现三遍，
  //    而且不会有任何报错 —— 用户只会觉得"可选的真多"。
  const altRowCount = cards.reduce((n, c) => n + (c.text.match(/也可以住/g)?.length ?? 0), 0)
  check(altRowCount === 3,
    '⭐ 整个页面只画了 3 行备选（连住时后端三天带的是同一份，去重靠前端）',
    `全页出现 ${altRowCount} 次「也可以住」`)
  check(!lastDay.includes('也可以住'),
    '⭐ 连住的第 2、3 天不重复画（画三遍用户会以为有 9 家备选）',
    lastDay.slice(0, 200))

  // ── 订房链接 ────────────────────────────────────────────────────
  // 这一条同时钉住另一个洞：**主选酒店以前压根没有链接**。
  // 交通行一直有"去飞猪看这趟车"，住宿那一行却一个字都没有 ——
  // 也就是说整份行程里唯一能把用户直接带到酒店的那条路，一直是断的。
  const bookLinks = await page.$$eval('a', (as) =>
    as.filter((a) => a.textContent.includes('去飞猪订')).map((a) => a.getAttribute('href') ?? ''))
  check(bookLinks.length >= 4,
    '⭐ 主选 + 3 家备选都有「去飞猪订」（能直接下单的入口，不是装饰）',
    `实际 ${bookLinks.length} 条：${bookLinks.slice(0, 3).join(' | ')}`)
  check(bookLinks.length > 0 && bookLinks.every((h) => h.includes('example.invalid')),
    '⭐ 那些链接全部指向 `.invalid` 占位域名（假数据绝不编一个像真的飞猪链接）',
    bookLinks.join(' | '))

  const iHotelRow = day1.indexOf('🏨')
  const iAltRow = day1.indexOf('也可以住')
  check(iHotelRow >= 0 && iAltRow > iHotelRow,
    '⭐ 顺序是"主选酒店 → 备选"（备选紧跟它回答的那个问题，不飘到别处）',
    `🏨@${iHotelRow} 备选@${iAltRow}`)

  /* ── 每天那句「为什么这么排」──────────────────────────────────
   * 2026-10-15 加。理由是模板拼的、数字来自本地事实 —— 但对界面来说
   * 要验的是三件事：有理由的天画出来、没理由的天**不硬凑**、
   * 理由和这张卡自己的事实对得上（对不上的理由比没有更糟）。 */
  console.log('')
  console.log('【为什么这么排】每天的 why')
  const whyCount = cards.reduce((n, c) => n + (c.text.match(/💡/g)?.length ?? 0), 0)
  check(whyCount === 2,
    '⭐ 3 天只有 2 句理由（最后一天没有可说的就给 null，不硬凑一句）',
    `💡 在行程卡里出现 ${whyCount} 次`)
  check(day1.includes('💡') && day1.includes('出站进城的缓冲'),
    '⭐ 第 1 天的理由和它自己的事实对得上（09:29 到站 → 第一个点 10:30 才开始）',
    day1.slice(0, 180))
  check((cards[1]?.text ?? '').includes('💡') && (cards[1]?.text ?? '').includes('连住同一家酒店'),
    '⭐ 第 2 天说的是连住（和它住宿那行"连住不用收拾行李"是同一件事，不是装饰句）')
  check(!lastDay.includes('💡'),
    '⭐ 最后一天没有理由行（不是每天都欠一句解释）',
    lastDay.slice(0, 140))

  /* ── ②b ⭐ 「换一个」在降级路上也得能用 ─────────────────────── */
  // 理由和第 ② 条同源：这份假数据存在的意义是"出事那天顶上去"，
  // 那么出事那天顶上去的东西**必须包含最重要的功能**。
  // 一个在降级路上是灰色的「换一个」，等于现场少了一半演示。
  console.log('')
  console.log('【换一个】降级路上的可换性')

  const readSpots = () =>
    page.$$eval('article', (arts) =>
      arts.flatMap((a) => {
        const ul = a.querySelector(':scope > ul')
        return [...(ul?.children ?? [])]
          .filter((x) => x.tagName === 'LI')
          .map((li) => {
            const row = li.querySelector(':scope > div:nth-child(1)')
            const btns = [...li.querySelectorAll('button')]
            const swap = btns.find((b) => b.textContent.includes('换一个'))
            const picker = li.querySelector(':scope > div:last-child ul')
            // 杂志版首行是「时间 | 名字 | 票价」三个并列 span，名字是第 2 个；
            // 游玩建议（或换上备选的"没有建议"声明）是 li 的直接 <p>（原来是嵌套 span.block）
            const name = row?.children[1]?.textContent?.trim() ?? null
            const tips =
              [...li.querySelectorAll(':scope > p')].map((p) => p.textContent.trim()).join(' ') || null
            return {
              name,
              tips,
              swapLabel: swap?.textContent?.trim() ?? null,
              swapDisabled: swap?.disabled ?? null,
              hasRestore: btns.some((b) => b.textContent.includes('恢复原样')),
              items: picker
                ? [...picker.children].map((x) => x.innerText.replace(/\s+/g, ' ').trim())
                : [],
            }
          })
      }),
    )

  let spots = await readSpots()
  check(spots.length > 0 && spots.every((s) => s.swapLabel),
    `降级路上每个点都有「换一个」（${spots.length} 个点）`,
    JSON.stringify(spots.map((s) => s.swapLabel)))
  check(spots.every((s) => !s.swapDisabled), '按钮都是可点的（不是灰的）',
    JSON.stringify(spots.filter((s) => s.swapDisabled).map((s) => s.name)))
  // 假数据里 pool = days + 4，用掉 days 个 → 可换 4 个
  const mockAvail = Number((spots[0]?.swapLabel ?? '').match(/(\d+)\s*个/)?.[1] ?? NaN)
  check(mockAvail === 4,
    `按钮说还有 4 个（假数据的池子 = 天数 + 4，用掉 ${spots.length} 个）`,
    `实际说「${spots[0]?.swapLabel}」`)

  const mockBase = { name: spots[0].name, tips: spots[0].tips }
  await page.locator('article').nth(0).locator('> ul > li').nth(0)
    .locator('button:has-text("换一个")').click()
  await page.waitForTimeout(250)
  spots = await readSpots()
  check(spots[0].items.length === 4, `清单里 4 条`, `实际 ${spots[0].items.length} 条`)
  check(spots[0].items.every((it) => it.includes('假数据')),
    '清单里的每一条也带着"（假数据）"—— 换上来的东西同样不能被误当成真的',
    JSON.stringify(spots[0].items))

  await page.locator('article').nth(0).locator('> ul > li').nth(0)
    .locator('ul > li button').nth(0).click()
  await page.waitForTimeout(250)
  spots = await readSpots()
  check(spots[0].name !== mockBase.name, `换上了（${mockBase.name} → ${spots[0].name}）`)
  check(spots[0].tips?.includes('换上的备选没有游玩建议') === true,
    '⭐ 降级路上同样会清掉上一个点的建议（这条规矩不因为数据源而变）',
    JSON.stringify(spots[0].tips))
  check(spots[0].hasRestore, '出现了「恢复原样」')

  await page.locator('article').nth(0).locator('> ul > li').nth(0)
    .locator('button:has-text("恢复原样")').click()
  await page.waitForTimeout(250)
  spots = await readSpots()
  check(spots[0].name === mockBase.name && spots[0].tips === mockBase.tips,
    '⭐ 「恢复原样」把名字和建议都精确还回来了',
    `${mockBase.name} / ${mockBase.tips}`)

  /* ── ②b 点第二个点的大图 → 放大层从**那张**开始（杂志版的 startIndex 链路）──
     mock 里每个点都有图；第 1 天有 2 个点，点第 2 个的图应该看到 "2 / 2"。 */
  await page.locator('article').nth(0)
    .locator('li button[aria-label*="放大"]').nth(1).click()
  await page.waitForTimeout(300)
  const zoomText = await page.locator('[role="dialog"]').innerText()
  check(zoomText.includes('2 / 2') && zoomText.includes('示例景点 101'),
    '⭐ 点第 2 个点的图，放大层从那张开始（不是永远从第一张）',
    zoomText.replace(/\s+/g, ' ').slice(0, 80))
  await page.keyboard.press('Escape')
  await page.waitForTimeout(200)

  /* ── ③ 看起来必须像降级 ─────────────────────────────────────── */
  await page.screenshot({ path: `${SHOTS}mock-ui-swap.png`, fullPage: true })
  const body = await page.innerText('body')
  check(body.includes('假数据'),
    '⭐ 界面上到处写着"（假数据）"—— 它不可能被误当成真实结果',
    `"假数据" 出现 ${(body.match(/假数据/g) ?? []).length} 次`)
  check(/正在用本地假数据|VITE_USE_MOCK/.test(body),
    '⭐ 页面上有"正在用假数据"的提示条（降级必须留痕，否则等于骗用户）',
    (body.match(/[^\n]*假数据[^\n]*/g) ?? []).slice(0, 2).join(' | '))
  check(body.includes('不是后端返回的真实行程'),
    '⭐ warnings 里也明说了"不是后端返回的真实行程"')
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
