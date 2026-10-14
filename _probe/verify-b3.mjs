/**
 * B3 验收脚本：打 /api/retrieval/pool，把"候选池 + 溯源"整个摊开看。
 *
 * 为什么用 Node 发请求而不是 curl：
 *   curl 在 Windows 终端里的中文/编码不确定性太多，而 node 原生 fetch 全程 UTF-8，
 *   参数和输出的编码都不经过 shell —— 这是"排障时要减少变量"的具体应用。
 *
 * 用 Node 跑：node verify-b3.mjs
 */

const BASE = 'http://127.0.0.1:8003/api'

async function post(path, body) {
  const res = await fetch(BASE + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  return res.json()
}

function yuan(v) {
  return v == null ? '—' : '¥' + v
}

function report(title, body, res) {
  console.log('\n' + '═'.repeat(70))
  console.log(title)
  console.log('  请求:', JSON.stringify(body))
  console.log('─'.repeat(70))
  if (res.code !== 0) {
    console.log('  ❌ code =', res.code, '|', res.message)
    return
  }
  const { candidates, provenance: p } = res.data

  // ① 形状检查 —— 这一步专门验"规范化到底做没做"
  const kinds = [...new Set(candidates.map(c => c.kind))]
  console.log('  候选', candidates.length, '条 | kind 取值:', JSON.stringify(kinds))
  const bad = candidates.filter(c => c.kind !== 'poi' && c.kind !== 'hotel')
  console.log('  「kind 字段有没有真的出现在 JSON 里」:', bad.length === 0 && kinds.length > 0 ? '✅ 有' : '❌ 缺失')

  // ② 类型检查 —— "字符串数字"有没有被规范掉
  const wrongType = candidates.filter(c => typeof c.priceYuan !== 'number' && c.priceYuan !== null)
  console.log('  priceYuan 是否已是数字或 null:', wrongType.length === 0 ? '✅' : '❌ ' + JSON.stringify(wrongType[0]))

  // ③ 内容
  const pois = candidates.filter(c => c.kind === 'poi')
  const hotels = candidates.filter(c => c.kind === 'hotel')
  console.log(`  ├─ 景点 ${pois.length} 条${pois.length ? '：' : ''}`)
  for (const p2 of pois.slice(0, 12)) {
    console.log(`  │    ${p2.name} | ${p2.category ?? '无类别'} | ${yuan(p2.priceYuan)} | 等级${p2.level ?? '?'} | ${p2.poiId}`)
  }
  if (pois.length > 12) console.log(`  │    …还有 ${pois.length - 12} 条`)
  console.log(`  └─ 酒店 ${hotels.length} 条${hotels.length ? '：' : ''}`)
  for (const h of hotels.slice(0, 5)) {
    console.log(`       ${h.name} | 星级${h.star ?? '未标注'} | ${yuan(h.priceYuan)} | 评分${h.rating ?? '?'}`)
  }
  if (hotels.length > 5) console.log(`       …还有 ${hotels.length - 5} 条`)

  // ④ 溯源
  console.log('  ── 溯源 ──')
  console.log('    实际发出的命令:')
  for (const q of p.queries) console.log('      ' + q)
  console.log(`    轮次 ${p.searchRounds} | 上游调用 ${p.upstreamCalls} 次 | 缓存命中 ${p.cacheHits} 次 | 耗时 ${p.elapsedMs}ms`)
  console.log(`    找到 ${p.candidatesFound} 条 → 留下 ${p.candidatesKept} 条`)
  console.log(`    生效规则: 价格上限=${p.rules.maxTicketPriceYuan ?? '不限'} 等级下限=${p.rules.minPoiLevel ?? '不限'} 每天最多${p.rules.maxSpotsPerDay}个`)
  console.log(`    放宽过: ${p.relaxed ? '是' : '否'} | 仍缺类别: ${p.missingCategories.length ? p.missingCategories.join('、') : '无'}`)
  if (p.rejected.length) {
    console.log(`    被筛掉 ${p.rejected.length} 条:`)
    for (const r of p.rejected) console.log(`      ✗ ${r.name} —— ${r.reason}`)
  }
  if (p.warnings.length) {
    for (const w of p.warnings) console.log(`      ⚠️ ${w}`)
  }
}

const t0 = Date.now()

// A. 基线：不带价格上限，不查酒店。期望：只发 1 次宽查，10 条上下，价格是数字。
await report('【A】基线 · 宽查一次到底能拿到什么',
  { city: '深圳', days: 3, wantedCategories: ['动物园', '海洋馆'], needHotel: false },
  await post('/retrieval/pool', { city: '深圳', days: 3, wantedCategories: ['动物园', '海洋馆'], needHotel: false }))

// B. 加价格上限、只要 1 天（所以"数量够"成立，不会触发放宽）。
//    期望：看到"被筛掉 + 原因"，而且上游调用为 0（宽查的参数没变，命中缓存）。
await report('【B】价格上限 ¥200 · 看"筛掉了谁、为什么"',
  { city: '深圳', days: 1, wantedCategories: ['动物园'], needHotel: false, maxTicketPriceYuan: 200 },
  await post('/retrieval/pool', { city: '深圳', days: 1, wantedCategories: ['动物园'], needHotel: false, maxTicketPriceYuan: 200 }))

// C. 同样的上限，但要 3 天 → 数量不够 → 触发**放宽**（免费，不重新查）。
//    期望：relaxed = true，被筛掉的又回来了，上游调用依然 0（门票部分全命中缓存），只有酒店是新调用。
await report('【C】价格上限 ¥200 + 3 天 · 看"允许放宽时会发生什么"',
  { city: '深圳', days: 3, wantedCategories: ['动物园'], needHotel: true, maxTicketPriceYuan: 200 },
  await post('/retrieval/pool', { city: '深圳', days: 3, wantedCategories: ['动物园'], needHotel: true, maxTicketPriceYuan: 200 }))

// D. 换一座城市，且期望一个宽查里大概率没有的类别 → 触发定向补查。
//    期望：searchRounds = 2，queries 里能看到两条命令，其中一条带 --category。
await report('【D】换城市 + 期望"动物园" · 看"补查是怎么被触发的"',
  { city: '珠海', days: 2, wantedCategories: ['动物园'], needHotel: false },
  await post('/retrieval/pool', { city: '珠海', days: 2, wantedCategories: ['动物园'], needHotel: false }))

// E. 把"需要多少个点"抬高到补查也补不满的程度 → 才会走到**放宽规则**那条路。
//    期望：relaxed = true；之前被价格筛掉的又回来了（所以 rejected 变空）；
//    而且**上游调用为 0** —— 放宽是纯本地重算，不重新查飞猪。
await report('【E】要求太高，补查也补不满 · 看"放宽规则"这条免费回退路径',
  { city: '深圳', days: 6, wantedCategories: ['动物园'], needHotel: false, maxTicketPriceYuan: 200 },
  await post('/retrieval/pool', { city: '深圳', days: 6, wantedCategories: ['动物园'], needHotel: false, maxTicketPriceYuan: 200 }))

console.log('\n' + '═'.repeat(70))
console.log(`全程耗时 ${((Date.now() - t0) / 1000).toFixed(1)} 秒（含后端内部的限流间隔）`)
