import { FLYAI_CLI } from './_paths.mjs'
/**
 * 酒店「档次」实测 —— 一次性探针，回答一个问题：
 *
 *   现在的默认是 `--hotel-stars 4,5`，用户看到的是皇冠假日 / JW万豪 / 索菲特，¥478~¥1277。
 *   这个筛选当初是为了**挡下限**（深圳宽查回来 10 家全是青旅/日租公寓），
 *   但它把**上限**也一起锁死了 —— 一档人只能看见豪华档。
 *
 * 所以这里要跑的是：把星级筛拿掉之后，上游到底还回不回"不适合带娃"的东西？
 *   ① 完全不带筛选（基准线）—— 青旅/公寓会不会卷土重来？
 *   ② `--hotel-stars 3,4,5`  —— 放宽下限，档位跨度能不能拉开？
 *   ③ `--sort price_asc`     —— 不走星级，改用排序，能不能拿到便宜的？
 *   ④ `--max-price N`        —— 直接卡预算，这是不是比星级更贴近用户真正在意的？
 *
 * 判据不是条数，是三件事：
 *   · 档次词分布（豪华型/高档型/四星级/舒适型/经济型）
 *   · 价格分布（min / 中位 / max）
 *   · 有没有"青旅/公寓/客栈/日租"这类不适合亲子过夜的混进来
 *
 * 用法：CITY=上海 node probe-hotel-tier.mjs
 *      CI=2026-11-17 CO=2026-11-26 可换日期（不带日期会拿不到 star 字段）
 */

import { execFile } from 'node:child_process'

const NODE = process.env.TEST_NODE || 'node'
const GAP = Number(process.env.GAP || 9) * 1000
const CITY = process.env.CITY || '上海'
const CI = process.env.CI || '2026-11-17'
const CO = process.env.CO || '2026-11-26'

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

function run(args) {
  return new Promise((resolve) => {
    execFile(NODE, [FLYAI_CLI, ...args], { encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 }, (err, stdout, stderr) => {
      let j = null
      try { j = JSON.parse(stdout) } catch { }
      const list = j?.data?.itemList ?? []
      const code = /HTTP (\d{3})/.exec(stderr || '')?.[1] ?? (err ? 'ERR' : '200')
      resolve({ code, list, err: (stderr || '').replace(/\s+/g, ' ').slice(0, 160), status: j?.status, msg: j?.message })
    })
  })
}

/** 名字里带这些词 → 不是带娃要住的那种 */
const BAD = ['公寓', '客栈', '青旅', '青年旅舍', '青年公寓', '日租', '短租', '民宿', '旅馆', '招待所', '公馆']
const isBad = (name) => BAD.some((w) => (name || '').includes(w))
const yuan = (s) => {
  const m = /(\d+(?:\.\d+)?)/.exec(s || '')
  return m ? Number(m[1]) : null
}

function report(title, r) {
  console.log('\n' + '─'.repeat(72))
  console.log(`【${title}】  status=${r.status} 条数=${r.list.length}  ${r.code !== '200' ? 'HTTP ' + r.code : ''}`)
  if (r.err) console.log('  stderr: ' + r.err)
  if (!r.list.length) { console.log('  (空)'); return }
  const prices = r.list.map((h) => yuan(h.price)).filter((v) => v != null)
  const stars = {}
  for (const h of r.list) {
    const s = (h.star || '').trim() || '(空)'
    stars[s] = (stars[s] || 0) + 1
  }
  const bad = r.list.filter((h) => isBad(h.name)).length
  const sorted = [...prices].sort((a, b) => a - b)
  const mid = sorted.length ? sorted[Math.floor(sorted.length / 2)] : null
  console.log(`  档次: ${Object.entries(stars).map(([k, v]) => `${k}×${v}`).join('  ')}`)
  console.log(`  价格: min=¥${sorted[0]}  中位=¥${mid}  max=¥${sorted[sorted.length - 1]}   (${prices.length}/${r.list.length} 有价)`)
  console.log(`  ⚠️ 疑似不适合带娃（青旅/公寓/客栈…）: ${bad} 条`)
  for (const h of r.list) {
    console.log(`     ${(h.star || '').trim().padEnd(4)} ¥${String(yuan(h.price) ?? '-').padStart(5)}  ${h.name}`)
  }
}

const base = ['search-hotel', '--dest-name', CITY, '--check-in-date', CI, '--check-out-date', CO]

const cases = [
  ['① 不带任何筛选（基准）', base],
  ['② --hotel-stars 3,4,5（放宽下限）', [...base, '--hotel-stars', '3,4,5']],
  ['③ --sort price_asc（不筛星级，价格升序）', [...base, '--sort', 'price_asc']],
  ['④ --max-price 500（卡预算）', [...base, '--max-price', '500']],
]

console.log(`城市=${CITY}  入离=${CI} → ${CO}  间隔=${GAP / 1000}s  共 ${cases.length} 次调用`)

for (let i = 0; i < cases.length; i++) {
  const [title, args] = cases[i]
  if (i > 0) await sleep(GAP)
  const r = await run(args)
  report(title, r)
  console.log(`  ↳ 实发参数: flyai ${args.join(' ')}`)
}

console.log('\n完成。')
