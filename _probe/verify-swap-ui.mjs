import { PROBE_WORKSPACE } from './_paths.mjs'
/**
 * 「换一个」的前端验收：深圳 → 广州 5 天。
 *
 * 它要复核的是这一轮改动的**全部意义** ——
 *   生成之后用户能不能改行程，以及改的时候有没有被喂一句编造的话。
 *
 * ── 为什么后端过了还要在浏览器里再看一遍 ────────────────────────
 *   后端证明"数据对了"，前端证明"人看得见、点得动"。中间隔着整整一层渲染，
 *   而那一层的失败方式全是静默的（字段抄错、按钮点了没反应、换完 tips 还留着）。
 *   尤其这一轮：**换完之后如果 tips 没被清掉，后端一个字节都没错，
 *   而用户会看到一句读起来完全正常、实际上完全编造的建议。**
 *   那种错只有在浏览器里才看得见。
 *
 * 用法：
 *   APP_URL=http://127.0.0.1:5174/ NODE_PATH=<workspace>/node_modules node verify-swap-ui.mjs
 */

import { createRequire } from 'node:module'
import { readFileSync } from 'node:fs'

const WORKSPACE = PROBE_WORKSPACE
  .replace(/\/+$/, '')
const require = createRequire(`${WORKSPACE}/noop.js`)
const { chromium } = require('playwright-core')

const APP_URL = process.env.APP_URL ?? 'http://127.0.0.1:5174/'
const SHOTS = new URL('./shots/', import.meta.url).pathname.replace(/^\//, '')
const CACHE = new URL('./flyai-cache.json', import.meta.url).pathname.replace(/^\//, '')

const ORIGIN = '深圳'
const CITY = '广州'

/**
 * 类别集：**从飞猪缓存里现取，不写死在脚本里**。
 *
 * 第一版这里写死了一个白名单，结果漏了「自然风光」——
 * 而漏掉的表现是"有 6 条没带类别"这条断言失败：看起来像产品 bug，
 * 其实是脚本自己过期了。**写死的清单总会过期**（上游随时会加一个新类别），
 * 而它的失败方式又是"冤枉好人"，所以改成从数据源现取。
 */
const CATEGORIES = new Set()
for (const v of Object.values(JSON.parse(readFileSync(CACHE, 'utf8')).entries ?? {})) {
  if (!(v.args ?? []).join(' ').includes(`search-poi --city-name ${CITY}`)) continue
  for (const it of JSON.parse(v.raw).data?.itemList ?? []) {
    if (it.category) CATEGORIES.add(it.category)
  }
}

const results = []
function check(ok, label, detail) {
  results.push({ ok, label, detail })
  console.log(`${ok ? '✓' : '✗'} ${label}`)
  if (detail) console.log(`      ${detail}`)
}

/** "约 3.2 公里" / "不到 1 公里" → 数字。没有距离文本的条目回 null（= 算不出来） */
function kmOf(text) {
  const m = text.match(/约 ([\d.]+) 公里/)
  if (m) return Number(m[1])
  if (text.includes('不到 1 公里')) return 0.5
  return null
}

/**
 * 从清单项的 innerText 里抠出**名字**。
 *
 * ⚠️ 这里不能要求"名字和价格之间有空格"：名字、价格、类别、距离是四个行内 span，
 *    `innerText` 拼出来是「广州长隆水上乐园票价未知水上乐园不到 1 公里」——**中间没有空格**。
 *    （第一版就是这么写的，结果抠出来一整串，"清单里没有已用的点"那条断言
 *     因为衬不上任何名字而**静默通过**。这是这个工程里第 N 次同一个形状的坑：
 *     验证脚本自己坏了，却表现为"验证通过"。）
 */
function itemName(itemText) {
  const m = itemText.match(/^(.+?)(?:票价未知|¥)/)
  return m ? m[1].trim() : itemText.trim()
}

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage({ viewport: { width: 1280, height: 1200 } })
page.on('pageerror', (e) => console.log('   [页面错误]', e.message))
page.on('console', (m) => {
  if (m.type() === 'error') console.log('   [console.error]', m.text())
})

console.log(`\n═══ 「换一个」：${ORIGIN} → ${CITY} ═══`)
console.log(`目标：${APP_URL}`)

/**
 * 把行程读成一个结构。
 * ⚠️ 选择器全是**结构**（article > ul > li），不是 class —— class 是样式，
 *    改样式就会让验收脚本静静地什么都读不到，然后"通过"。
 */
async function readPlan() {
  return page.$$eval('article', (arts) =>
    arts.map((a) => {
      const ul = a.querySelector(':scope > ul')
      const lis = [...(ul?.children ?? [])].filter((x) => x.tagName === 'LI')
      return {
        title: a.querySelector('h3')?.textContent?.trim() ?? '',
        text: a.innerText.replace(/\s+/g, ' ').trim(),
        spots: lis.map((li) => {
          // 第一行是「时刻 | 名字/价格/tips」，第二行是按钮，第三行（可选）是清单
          const row = li.querySelector(':scope > div:nth-child(1)')
          const time = row?.querySelector('span')?.textContent?.trim() ?? null
          const nameBlock = row?.querySelector('span:nth-child(2)')
          const name = nameBlock?.querySelector('span:nth-child(1)')?.textContent?.trim() ?? null
          // 姓名块里那个 `block` 的 span 才是游玩建议。
          // ⚠️ 不能用 "text-xs" 选它 —— "换上的备选"那个小标签也带 text-xs，
          //    而它就是**换完之后**才出现的东西，用它选会在最该检查的时候选错元素。
          const tips = nameBlock?.querySelector('span.block')?.textContent?.trim() ?? null
          const btns = [...li.querySelectorAll('button')]
          const swap = btns.find((b) => b.textContent.includes('换一个'))
          const picker = li.querySelector(':scope > div:last-child ul')
          const items = picker
            ? [...picker.children].map((x) => x.innerText.replace(/\s+/g, ' ').trim())
            : []
          return {
            time,
            name,
            tips,
            text: li.innerText.replace(/\s+/g, ' ').trim(),
            swapLabel: swap?.textContent?.trim() ?? null,
            swapDisabled: swap?.disabled ?? null,
            hasRestore: btns.some((b) => b.textContent.includes('恢复原样')),
            pickerOpen: !!picker,
            items,
          }
        }),
      }
    }),
  )
}

/** 把清单里每一项的**名字**抠出来（到第一个 ¥/票价未知 之前）—— 定义在上面，这里不再重复 */

try {
  await page.goto(APP_URL, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('h1', { timeout: 15_000 })

  async function send(text) {
    await page.fill('input', text)
    await page.click('button:has-text("发送")')
    await page.waitForSelector('text=正在想', { timeout: 8_000 }).catch(() => {})
    await page.waitForFunction(() => !document.body.innerText.includes('正在想'), null, {
      timeout: 120_000,
    })
  }

  /* ── 对话 + 生成（和用户那一单同一批参数）────────────────────── */
  console.log('\n【1】对话与生成')
  await send(`想带孩子去${CITY}玩5天`)
  await send(`${ORIGIN}，11月17日出发`)
  await send('7岁，想带他户外，看看动物，干点农活啥的')
  await send('好的，不要推车')
  await page.click('button:has-text("生成行程")')
  await page.waitForFunction(() => !document.body.innerText.includes('正在生成'), null, {
    timeout: 180_000,
  })
  check(true, '行程生成完成')

  // 溯源必须展开才读得到池子大小（它是折叠的 <details>）
  await page.$$eval('details', (els) => els.forEach((e) => { e.open = true }))
  const provText = await page.innerText('body')
  const poolMatch = provText.match(/这次查到\s*(\d+)\s*个可用景点/)
  const poolSize = poolMatch ? Number(poolMatch[1]) : null
  console.log(`      溯源里说这次查到 ${poolSize} 个可用景点`)
  check(poolSize !== null && poolSize > 0, '溯源里报出了备选池的大小（成本是可见的）',
    poolMatch ? '' : '没找到那句「这次查到 N 个可用景点」')

  let days = await readPlan()
  const allSpots = days.flatMap((d) => d.spots)
  const usedNames = new Set(allSpots.map((s) => s.name))
  console.log(`      ${days.length} 天，共 ${allSpots.length} 个点：${[...usedNames].join('、')}`)

  /* ── 2. 每个点都能换 ─────────────────────────────────────────── */
  console.log('\n【2】每个点下面都有「换一个」')
  check(allSpots.length > 0, '读到了行程里的点', `读到 ${allSpots.length} 个`)
  check(allSpots.every((s) => s.name), '每个点的名字都读得出来（读不出来说明选择器已经和界面脱节了）',
    JSON.stringify(allSpots.filter((s) => !s.name).map((s) => s.text.slice(0, 60))))
  const noBtn = allSpots.filter((s) => !s.swapLabel)
  check(noBtn.length === 0, `全部 ${allSpots.length} 个点都有「换一个」按钮`,
    `缺按钮的：${JSON.stringify(noBtn.map((s) => s.name))}`)
  const disabled = allSpots.filter((s) => s.swapDisabled)
  check(disabled.length === 0, '按钮都是可点的（不是灰的）',
    `灰的：${JSON.stringify(disabled.map((s) => s.name))}`)

  // 按钮上写的数字 = 池子 - 用掉的（这是"真的还有东西可换"最直接的一句话）
  const expectedAvail = poolSize === null ? null : poolSize - allSpots.length
  console.log(`      按钮上写的数字应该是 ${poolSize} - ${allSpots.length} = ${expectedAvail}`)
  const nums = allSpots.map((s) => Number((s.swapLabel ?? '').match(/(\d+)\s*个/)?.[1] ?? NaN))
  check(nums.every((n) => n === expectedAvail),
    `每个按钮都写着「还有 ${expectedAvail} 个」`,
    `实际：${JSON.stringify(nums)}`)

  /* ── 挑一个"有游玩建议"的点来换 ──────────────────────────────── */
  // ⭐ 必须挑一个有 tips 的点：整个第 7 项检查（"换上的点不能带着上一个点的建议"）
  //    只有在原点**本来就有建议**的时候才有意义。挑到没有 tips 的点，
  //    那条断言会永远通过 —— 又一个"看起来在验证，其实在自欺"。
  const flat = days.flatMap((d, di) => d.spots.map((s, si) => ({ ...s, di, si })))
  const target = flat.find((s) => s.tips) ?? flat[0]
  const baseline = { name: target.name, time: target.time, tips: target.tips, text: target.text }
  if (target.tips) {
    check(true, `挑了一个本来就有游玩建议的点来换：第 ${target.di + 1} 天 ${baseline.name}`,
      `它的建议：「${baseline.tips}」`)
  } else {
    console.log('  ⚠️ 这次行程里没有一个点带游玩建议 → 第 7 项（tips 不能跟着搬过来）本次验不出来')
  }
  const spotLocator = () => page.locator('article').nth(target.di).locator('> ul > li').nth(target.si)

  /* ── 3. 点开清单 ─────────────────────────────────────────────── */
  console.log('\n【3】点开它的清单')
  await spotLocator().locator('button:has-text("换一个")').click()
  await page.waitForTimeout(300)

  days = await readPlan()
  const s0 = days[target.di].spots[target.si]
  check(s0.pickerOpen, '清单展开了')
  check(s0.items.length === expectedAvail,
    `清单条数 == 按钮说的数字（${s0.items.length} == ${expectedAvail}）`,
    `按钮说「${s0.swapLabel}」，清单里 ${s0.items.length} 条`)
  await page.screenshot({ path: `${SHOTS}swap-ui-1-picker.png`, fullPage: true })

  check(s0.items.every((it) => /(票价未知|¥)/.test(it)),
    '每一项都带了价格（或明说票价未知）',
    JSON.stringify(s0.items.slice(0, 3)))
  const withCat = s0.items.filter((it) => [...CATEGORIES].some((c) => it.includes(c)))
  check(withCat.length === s0.items.length,
    `每一项都带了类别（用户换点常常是想换个类型）—— 对照缓存里的 ${CATEGORIES.size} 个类别`,
    `没带类别的：${JSON.stringify(s0.items.filter((it) => ![...CATEGORIES].some((c) => it.includes(c))))}`)

  /* ── 4. ⭐ 清单里不能有"当前行程里已经在用的点" ─────────────── */
  console.log('\n【4】⭐ 清单里没有当前行程已经在用的点（整份行程，不是当天）')
  const offered = s0.items.map(itemName)
  // ⚠️ 先自检一下抠名字这一步真的在工作：抠不出来（名字里含"票价未知"之类）的话，
  //    下面那条断言会因为"衬不上任何名字"而静默通过 —— 那就等于没有这条检查。
  const weird = offered.filter((n) => n.includes('票价') || n.includes('¥') || n.length > 30)
  check(weird.length === 0, `名字抠取自检：${offered.length} 条都抠出了干净的名字`,
    `可疑的：${JSON.stringify(weird.slice(0, 3))}`)
  const collide = offered.filter((n) => usedNames.has(n))
  check(collide.length === 0,
    `清单 ${offered.length} 条，没有一条和行程里的 ${usedNames.size} 个点重名`,
    `撞上的：${JSON.stringify(collide)}`)
  console.log(`      清单前 6 条：${offered.slice(0, 6).join(' | ')}`)

  /* ── 5. 按远近排 ─────────────────────────────────────────────── */
  console.log('\n【5】清单是按"离这一天其它点多近"排的')
  const kms = s0.items.map(kmOf)
  const known = kms.filter((k) => k !== null)
  if (known.length === 0) {
    console.log('  ⚠️ 这一天的点都没有坐标 → 清单里不显示距离，这一项本次验不出来')
    console.log('     （这不是失败：距离算不出来时按飞猪的相关性顺序排，是设计好的退路）')
  } else {
    const ascending = known.every((k, i) => i === 0 || k >= known[i - 1])
    check(ascending, `距离是升序的（最近的在最上面）：${known.slice(0, 5).join(' km → ')} km → …`,
      `实际：${JSON.stringify(kms)}`)
    const firstNull = kms.indexOf(null)
    check(firstNull === -1 || kms.slice(firstNull).every((k) => k === null),
      '算不出距离的排在最后（"不知道多远"不能被当成"很近"）',
      `实际：${JSON.stringify(kms)}`)
    check(s0.items[0].includes('公里'), '第一条就写着它有多远', `第一条：${s0.items[0]}`)
  }

  /* ── 6. ⭐ 换掉它 ───────────────────────────────────────────── */
  console.log('\n【6】⭐ 点清单里的第一条，把它换掉')
  const chosen = offered[0]
  await spotLocator().locator('ul > li button').nth(0).click()
  await page.waitForTimeout(300)

  days = await readPlan()
  const after = days[target.di].spots[target.si]
  check(after.name === chosen, `点上的名字换成了清单第一条（${baseline.name} → ${chosen}）`,
    `现在写着：${after.name}`)
  check(after.name !== baseline.name, `不再是最初那个点（${baseline.name}）`)
  check(after.text.includes('换上的备选'), '标出了「换上的备选」（用户得知道哪个是自己改的）',
    after.text.slice(0, 200))
  // 时间格保留：用户换的是"去哪儿"，不是"几点去"
  check(after.time === baseline.time, `时间格没被动过（还是 ${baseline.time}）`, `现在 ${after.time}`)
  check(!after.pickerOpen, '选完之后清单自己收起来了')

  /* ── 7. ⭐ tips 必须被清掉 ──────────────────────────────────── */
  console.log('\n【7】⭐ 换上的点不能带着上一个点的游玩建议')
  // 这是这一轮最要紧的一条：模型的建议是按"某个点在某个行程里的位置"写的，
  // 搬到另一个点上就是一句读起来完全正常、实际完全编造的话。
  const marker = '换上的备选没有游玩建议'
  check(after.tips?.includes(marker) === true,
    `界面明说"换上的备选没有游玩建议"`, `换完之后那个位置写着：${JSON.stringify(after.tips)}`)
  if (baseline.tips) {
    check(after.tips !== baseline.tips && !(after.tips ?? '').includes(baseline.tips),
      '⭐ 原来的那句建议一个字都没有留下来（留着就是编造）',
      `原建议：「${baseline.tips}」`)
  }
  await page.screenshot({ path: `${SHOTS}swap-ui-2-swapped.png`, fullPage: true })

  /* ── 8. ⭐ 换回来这条路是通的 ────────────────────────────────── */
  console.log('\n【8】⭐ 换掉的那个点重新变成可选项（不然"换回来"永远做不到）')
  const altDay = target.di === 1 ? 0 : 1
  await page.locator('article').nth(altDay).locator('> ul > li').nth(0)
    .locator('button:has-text("换一个")').click()
  await page.waitForTimeout(300)
  days = await readPlan()
  const altItems = days[altDay].spots[0].items.map(itemName)
  check(altItems.includes(baseline.name),
    `第 ${altDay + 1} 天的清单里出现了刚被换掉的「${baseline.name}」`,
    `前 6 条：${altItems.slice(0, 6).join(' | ')}`)
  check(!altItems.includes(chosen),
    `刚换上去的「${chosen}」不再出现在别处的清单里（一个景点只能去一次）`,
    `清单里有：${altItems.includes(chosen)}`)
  // 收起来，别影响后面的截图
  await page.locator('article').nth(altDay).locator('> ul > li').nth(0)
    .locator('button:has-text("换一个")').click()
  await page.waitForTimeout(200)

  /* ── 9. 顶部有"你改过什么"的账 ──────────────────────────────── */
  console.log('\n【9】改过之后，顶部要说一句')
  const bodyAfter = await page.innerText('body')
  check(bodyAfter.includes('你手动换过 1 个点'), '顶部写着"你手动换过 1 个点"',
    bodyAfter.match(/你手动换过[^\n]*/)?.[0] ?? '（没找到）')

  /* ── 10. ⭐ 恢复原样 ────────────────────────────────────────── */
  console.log('\n【10】⭐ 点「恢复原样」')
  await spotLocator().locator('button:has-text("恢复原样")').click()
  await page.waitForTimeout(300)
  days = await readPlan()
  const restored = days[target.di].spots[target.si]
  check(restored.name === baseline.name, `名字回到原来的「${baseline.name}」`, `现在写着：${restored.name}`)
  check(!restored.text.includes('换上的备选'), '「换上的备选」标记消失了')
  check(!restored.hasRestore, '「恢复原样」按钮自己消失了（没改过的点不该有它）')
  // ⭐ 精确比对：tips 必须一个字不差地回来。
  //    这一条同时证明了两件事：撤销是真的撤销了，
  //    而且**我们从来没有改过后端那份 plan**（改的是它上面的一层覆盖）。
  //    （第一版这里拿"清单展开状态下"的文本当基线，522 字里含 16 条清单项，
  //      比出来的数字毫无意义 —— 基线取错，检查就等于没做。）
  check(restored.tips === baseline.tips,
    '⭐ 游玩建议一字不差地回来了（说明后端那份数据从没被改过）',
    `恢复前：「${baseline.tips}」\n       恢复后：「${restored.tips}」`)
  check(restored.text === baseline.text,
    '整个点的那一行和改动之前**逐字一致**',
    `改动前 ${baseline.text.length} 字 / 恢复后 ${restored.text.length} 字`)
  const bodyRestored = await page.innerText('body')
  check(!bodyRestored.includes('你手动换过'), '顶部的"你手动换过"也消失了')
  await page.screenshot({ path: `${SHOTS}swap-ui-3-restored.png`, fullPage: true })
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
