/**
 * 一次性探针：同一条线（深圳→上海）在**不同日期**上的机票 vs 高铁。
 *
 * 想回答的问题：11/17 是深圳秋假，机票贵；换个普通日子（12/10）会便宜多少？
 *
 * 上游限流是真的（B3 实测：连续 6 个不间隔，后 3 个 451），所以每次调用之间
 * 固定隔 9 秒，且一共只发 4 次。
 *
 * 用法：node probe-flight-date.mjs
 */
import { FLYAI_CLI } from './_paths.mjs'
import { execFile } from 'node:child_process'

const CLI = FLYAI_CLI
const run = (args) =>
  new Promise((res) => {
    execFile('node', [CLI, ...args], { maxBuffer: 1 << 26 }, (e, so, se) =>
      res({ out: so, err: se, code: e ? e.code : 0 }))
  })
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

/** 上游同一个航班号会出现两条不同价（实测过），去重时留便宜的，但把两个价都记下来 */
function dedupe(items) {
  const map = new Map()
  for (const it of items) {
    const seg = it.journeys?.[0]?.segments?.[0] ?? {}
    const key = `${seg.marketingTransportNo ?? '?'}|${seg.depDateTime ?? ''}`
    const price = it.ticketPrice ?? it.price
    const prev = map.get(key)
    if (!prev) map.set(key, { it, prices: [price] })
    else prev.prices.push(price)
  }
  return [...map.values()]
}

async function probe(label, sub, args) {
  const t0 = Date.now()
  const { out, err, code } = await run([sub, ...args])
  const ms = Date.now() - t0
  let j = null
  try { j = JSON.parse(out) } catch { }
  const list = j?.data?.itemList

  console.log(`\n──────── ${label}  [exit ${code}, ${ms}ms]`)
  console.log(`    ${sub} ${args.join(' ')}`)
  if (!list) {
    console.log(`    ❌ 无结果  status=${j?.status} message=${j?.message}`)
    console.log(`       stderr=${(err || '').trim().slice(0, 200)}`)
    return null
  }

  const rows = dedupe(list).map(({ it, prices }) => {
    const jr = it.journeys?.[0] ?? {}
    const segs = jr.segments ?? []
    const first = segs[0] ?? {}
    const last = segs.at(-1) ?? {}
    return {
      no: `${first.marketingTransportName ?? ''}${first.marketingTransportNo ?? ''}`,
      from: first.depStationShortName ?? first.depCityName ?? '?',
      to: last.arrStationShortName ?? last.arrCityName ?? '?',
      dep: (first.depDateTime ?? '').slice(5, 16).replace('T', ' '),
      arr: (last.arrDateTime ?? '').slice(5, 16).replace('T', ' '),
      dur: it.totalDuration,
      stops: (jr.journeyType ?? 0) - 1,
      price: Math.min(...prices),
      prices: [...new Set(prices)].sort((a, b) => a - b),
    }
  })

  const saysTrain = rows[0] && /[GDKCZT]\d+/.test(rows[0].no)
  console.log(`    ${saysTrain ? '车次' : '航班'}去重后 ${rows.length} 条（原始 ${list.length} 条）`)
  console.log(`    价格区间  ¥${Math.min(...rows.map((r) => r.price))} ~ ¥${Math.max(...rows.map((r) => r.price))}`)
  console.log(`    耗时最快  ${Math.min(...rows.map((r) => r.dur))} 分`)
  console.log(`    直达      ${rows.filter((r) => r.stops === 0).length} 条`)
  for (const r of rows.sort((a, b) => a.price - b.price).slice(0, 6)) {
    console.log(
      `    · ${r.no.padEnd(9)} ${r.from}→${r.to}  ${r.dep} → ${r.arr}  ` +
      `${String(r.dur).padStart(4)} 分  ${r.stops === 0 ? '直达' : `中转${r.stops}`}  ` +
      `¥${r.price}${r.prices.length > 1 ? `（同班次另有 ¥${r.prices.filter((p) => p !== r.price).join('/¥')}）` : ''}`,
    )
  }
  return rows
}

const O = process.env.ORIGIN ?? '深圳'
const D = process.env.DEST ?? '上海'
const D_HOLIDAY = process.env.DATE_A ?? '2026-11-17'
const D_NORMAL = process.env.DATE_B ?? '2026-12-10'
const base = ['--origin', O, '--destination', D, '--sort-type', '4']

const fA = await probe(`机票 ${O}→${D}  ${D_HOLIDAY}（秋假）`, 'search-flight',
  [...base, '--dep-date', D_HOLIDAY, '--journey-type', '1'])
await sleep(9000)

const fB = await probe(`机票 ${O}→${D}  ${D_NORMAL}（普通日子）`, 'search-flight',
  [...base, '--dep-date', D_NORMAL, '--journey-type', '1'])
await sleep(9000)

const tA = await probe(`高铁 ${O}→${D}  ${D_HOLIDAY}`, 'search-train', [...base, '--dep-date', D_HOLIDAY])
await sleep(9000)

const tB = await probe(`高铁 ${O}→${D}  ${D_NORMAL}`, 'search-train', [...base, '--dep-date', D_NORMAL])

const cheap = (rows) => (rows?.length ? Math.min(...rows.map((r) => r.price)) : null)
console.log('\n══════ 对账 ══════')
console.log(`              ${D_HOLIDAY}          ${D_NORMAL}`)
console.log(`机票最低价     ¥${cheap(fA) ?? '-'}                ¥${cheap(fB) ?? '-'}`)
console.log(`高铁最低价     ¥${cheap(tA) ?? '-'}                ¥${cheap(tB) ?? '-'}`)
for (const [tag, f, t] of [['秋假', fA, tA], ['普通', fB, tB]]) {
  if (!f?.length || !t?.length) continue
  const ratio = (cheap(f) / cheap(t)).toFixed(2)
  console.log(`${tag}：机票/高铁 = ${ratio} 倍`)
}
