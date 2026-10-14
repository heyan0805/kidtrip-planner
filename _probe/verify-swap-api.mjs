/**
 * 「换一个」的备选池 —— 后端契约验收。
 *
 * 用法：
 *   1) 后端带缓存启动（见 prerun.mjs 输出的启动命令）
 *   2) node _probe/verify-swap-api.mjs
 *
 * ── 这个脚本最要紧的一条原则 ────────────────────────────────────
 *   **不许只看接口自己报的数字。**
 *   它自己读飞猪缓存原始数据、自己按 provenance.queries 里那几条真实命令
 *   把候选重新捞一遍，再和 spotPool 对账。
 *
 * ── 一条我想了很久该怎么验的性质（写在最前面，因为它最容易被做成假通过）──
 *   `spotPool` 必须来自**全部合格候选**，而不是"送进 prompt 的那一份"。
 *   后者的写法（顺手用被截断过的 pois）不会报错，只会让用户的选项少一截。
 *
 *   但这条性质**在当前数据下验不出来**：上游一次查询最多回 10 条，
 *   而检索只发 1 次宽查 + 0~2 次补查 —— 实测广州 30 条、桂林 21 条，
 *   都**没有碰到**那个 30 条上限。没碰到，两种写法就长得一模一样。
 *
 *   所以这里不写一条"永远通过"的断言充当验证，而是：
 *     ① 用独立复算把"池子 = 查询结果并集（减去被筛掉的）"钉死（这一条永远有效）
 *     ② 池子 > 30 时，明说"这条性质已被证明"
 *     ③ 等于 30 时，明说"本次没有区分力" —— 把不知道的事说成"通过"才是真的危险
 *   真正的反证做在上限那一侧：把 PlanService.MAX_CANDIDATES_IN_PROMPT 临时调成 10，
 *   重编译重启，池子**必须仍然是全部候选**（见本文件末尾「反证记录」）。
 */
import { readFileSync } from 'node:fs'

const API = 'http://127.0.0.1:8003/api'
const CACHE = new URL('./flyai-cache.json', import.meta.url).pathname.replace(/^\//, '')

const CITY = '广州'
const ORIGIN = '深圳'
const START = '2026-11-17'
const DAYS = 5

/** PlanService.MAX_CANDIDATES_IN_PROMPT —— 写在这里是为了把"池子 30 条"这件事说清 */
const PROMPT_CAP = 30

/* ── 独立复算的数据源：飞猪原始缓存 ────────────────────────────── */
const cache = JSON.parse(readFileSync(CACHE, 'utf8'))
const entries = cache.entries ?? cache

/**
 * 按**命令**去缓存里捞原始条目。
 * ⚠️ 用 `id` 而不是 `poiId`：飞猪 search-poi 的原始字段就叫 `id`，
 *    `poiId` 是**我们规范化之后**的名字。第一版探针就是在这里写错，
 *    结果把 41 条广州候选全判成"查不到" —— 一个错误的复算会冤枉正确的代码。
 */
function rawByCommand(argsJoined) {
  for (const v of Object.values(entries)) {
    if ((v.args ?? []).join(' ') === argsJoined) {
      const j = JSON.parse(v.raw)
      return (j.data && j.data.itemList) || []
    }
  }
  return null
}

/* ── 检查框架 ──────────────────────────────────────────────────── */
let pass = 0
let fail = 0
function check(ok, label, detail = '') {
  if (ok) {
    pass += 1
    console.log(`  ✅ ${label}`)
  } else {
    fail += 1
    console.log(`  ❌ ${label}`)
    if (detail) console.log(`       ${detail}`)
  }
}

async function post(path, body) {
  const res = await fetch(API + path, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
  })
  return res.json()
}

/* ── 0. 健康检查 ───────────────────────────────────────────────── */
console.log('【0】后端')
const health = await (await fetch(API + '/health')).json()
check(health?.code === 0, '后端健康', JSON.stringify(health).slice(0, 200))
console.log('')

/* ── 1. 生成一次 ───────────────────────────────────────────────── */
console.log(`【1】${ORIGIN} → ${CITY} ${DAYS} 天：备选池本身`)
const res = await post('/plan/generate', {
  params: { origin: ORIGIN, city: CITY, days: DAYS, startDate: START },
})
check(res?.code === 0, '生成成功（code === 0）', JSON.stringify(res).slice(0, 300))
if (res?.code !== 0) {
  console.log('\n生成失败，后面的检查没法做')
  process.exit(1)
}

const plan = res.data
const pool = plan.spotPool
check(Array.isArray(pool), 'spotPool 是个数组', typeof pool)
check(pool.length > 0, `spotPool 非空（${pool.length} 条）`, `实际 ${pool?.length}`)

const usedIds = new Set(plan.days.flatMap((d) => d.spots.map((s) => s.poiId)))
console.log(`     行程里用了 ${usedIds.size} 个点，池子里有 ${pool.length} 条，可换 ${pool.length - usedIds.size} 个`)

/* ── 2. ⭐ 独立复算：池子 = 这次发出的查询查到的全部合格景点 ─────── */
console.log('')
console.log('【2】⭐ 独立复算：按 provenance.queries 里那几条命令，自己去缓存重捞一遍')

const poiQueries = plan.provenance.queries.filter((q) => q.includes('search-poi'))
console.log(`     本次发出 ${poiQueries.length} 条景点查询：`)
for (const q of poiQueries) console.log(`       ${q.replace('flyai ', '')}`)

// 后端认的 poiId 就是原始 JSON 里的 `id`（`poiId` 是我们规范化之后的名字）
const expectedIds = new Set()
const missingQueries = []
for (const q of poiQueries) {
  const items = rawByCommand(q.replace(/^flyai /, ''))
  if (items === null) {
    missingQueries.push(q)
    continue
  }
  for (const it of items) expectedIds.add(String(it.id))
}
// 被规则筛掉的那些不在池子里 —— 它们在 provenance.rejected 里躺着、理由也写着
for (const r of plan.provenance.rejected ?? []) {
  for (const it of [...expectedIds]) {
    if (it === String(r.name)) expectedIds.delete(it)
  }
}

check(missingQueries.length === 0,
  '这次发出的每条景点查询都能在缓存里找到（否则下面的复算没有依据）',
  JSON.stringify(missingQueries))

const poolIds = new Set(pool.map((o) => o.poiId))
const dropped = [...expectedIds].filter((id) => !poolIds.has(id))
const extra = [...poolIds].filter((id) => !expectedIds.has(id))

console.log(`     复算：这几条查询在缓存里一共 ${expectedIds.size} 个不重复景点`)
check(expectedIds.size > 0, '复算拿到了非空的期望集合', `期望 ${expectedIds.size} 个`)
check(dropped.length === 0,
  `查询查到的景点一个都没少（少了会出现在这里）`,
  `少掉 ${dropped.length} 个：${JSON.stringify(dropped.slice(0, 5))}`)
check(extra.length === 0,
  `池子里没有"这次根本没查到"的东西`,
  `多出 ${extra.length} 个：${JSON.stringify(extra.slice(0, 5))}`)
check(pool.length === expectedIds.size,
  `⭐ 池子大小 == 复算结果（${pool.length} == ${expectedIds.size}）`,
  '这一条把"池子是不是被某个上限悄悄截过"直接钉死')

/* ── 2b. 池子有没有被 prompt 上限截断 —— 说清"本次能不能验" ───── */
console.log('')
console.log('【2b】池子有没有被送进 prompt 的那份清单拖着走（上限 ' + PROMPT_CAP + ' 条）')
if (pool.length > PROMPT_CAP) {
  check(true, `⭐ 池子 ${pool.length} 条 > 上限 ${PROMPT_CAP} —— 这条性质**已被证明**`,
    '池子比送进模型的那份还大，说明它来自"全部合格候选"')
} else if (pool.length === PROMPT_CAP) {
  console.log(`  ⚠️ 池子 ${pool.length} 条，刚好等于上限 —— **本次没有区分力**：`)
  console.log('     上游这次只回了这么多，两种写法（用全部候选 / 用被截断的清单）结果一样。')
  console.log('     所以这一项**不算通过**，也不算失败 —— 它只是在这里验不出来。')
  console.log('     真正的反证见文件末尾「反证记录」。')
} else {
  check(true, `池子 ${pool.length} 条 < 上限 ${PROMPT_CAP} —— 本次上限不可能绑定，池子当然也没被截`,
    '（这只说明"没被截"，不说明"代码用的是全部候选" —— 两者这次长得一样）')
}

/* ── 3. 酒店一条都不能混进来 ───────────────────────────────────── */
console.log('')
console.log('【3】池子里不能有酒店')
// 酒店和景点在 RetrievalService 里是**混装**的（Candidate 是判别联合），
// 两者只隔一个 instanceof —— 顺手放进来编译得过、跑得起来、不报错。
const rawHotelQueries = plan.provenance.queries.filter((q) => q.includes('search-hotel'))
const rawHotelNames = new Set()
for (const q of rawHotelQueries) {
  for (const it of (rawByCommand(q.replace(/^flyai /, '')) ?? [])) rawHotelNames.add(it.name)
}
console.log(`     本次 ${rawHotelQueries.length} 条酒店查询，缓存里共 ${rawHotelNames.size} 家`)
const leaked = pool.filter((o) => rawHotelNames.has(o.name)).map((o) => o.name)
check(leaked.length === 0, '没有任何一条是飞猪 search-hotel 回来的酒店', JSON.stringify(leaked))

/* ── 4. 每一条都要能追回飞猪的真实景点 ─────────────────────────── */
console.log('')
console.log('【4】每一条都能追回飞猪的真实景点（去重和追溯都靠 poiId）')
const strangers = pool.filter((o) => !expectedIds.has(o.poiId))
check(strangers.length === 0,
  `全部 ${pool.length} 条的 poiId 都在本次查询结果里`,
  JSON.stringify(strangers.slice(0, 5).map((o) => o.poiId)))

const noName = pool.filter((o) => !o.name || !o.name.trim())
check(noName.length === 0, '没有名字为空的条目', JSON.stringify(noName))

const noCategory = pool.filter((o) => typeof o.category !== 'string')
check(noCategory.length === 0, 'category 字段在（界面按它分组，空串可以，缺字段不行）',
  JSON.stringify(noCategory.slice(0, 3)))

/* ── 5. null 是 null，0 是 0 ──────────────────────────────────── */
console.log('')
console.log('【5】价格和坐标：没有就是 null，不能变成 0')
// "0 元门票"在界面上显示成 ¥0 —— 那是"免费"的意思，而实际含义是"飞猪上没有可订的票"。
// 两者分不出来，但显示出来是两句完全不同的话。
const zeroPrice = pool.filter((o) => o.priceYuan === 0)
check(zeroPrice.length === 0, '没有 priceYuan === 0 的条目（0 会显示成「¥0」= 免费）',
  JSON.stringify(zeroPrice.map((o) => o.name)))

const fakeOrigin = pool.filter((o) => o.lat === 0 || o.lng === 0)
check(fakeOrigin.length === 0, '没有经纬度为 0 的条目（0,0 是几内亚湾，会让"附近"算成几千公里）',
  JSON.stringify(fakeOrigin.map((o) => o.name)))

const withCoord = pool.filter((o) => o.lat !== null && o.lng !== null).length
console.log(`     有坐标的 ${withCoord}/${pool.length} 条（前端的"按远近排"靠它，没有的会排在最后）`)

/* ── 6. ⭐ 池子必须包含已经排进日程的点 ───────────────────────── */
console.log('')
console.log('【6】⭐ 池子含"已经用掉"的点 —— 否则「换回来」这条路是断的')
const missingUsed = [...usedIds].filter((id) => !poolIds.has(id))
check(missingUsed.length === 0,
  `行程里用掉的 ${usedIds.size} 个点全都在池子里`,
  `少掉的是 ${JSON.stringify(missingUsed)} —— 用户换掉它之后就再也换不回来了`)
check(pool.length > usedIds.size,
  `池子比"用掉的"大（${pool.length} > ${usedIds.size}）—— 也就是说真的有东西可以换`,
  '如果相等，「换一个」按钮一个可选项都没有')

/* ── 7. 溯源里的数字要对得上 ───────────────────────────────────── */
console.log('')
console.log('【7】和溯源的数字对账')
check(pool.length <= plan.provenance.candidatesKept,
  `池子不超过 candidatesKept（${pool.length} ≤ ${plan.provenance.candidatesKept}）`,
  `candidatesKept 数的是"合格候选"（景点+酒店共 ${plan.provenance.candidatesKept} 条），池子只能是它的子集`)

/* ── 8. 稳定：同样的输入第二次还是同样的池子 ──────────────────── */
console.log('')
console.log('【8】同样的输入再来一次，池子一样（缓存命中，不再花上游调用）')
const res2 = await post('/plan/generate', {
  params: { origin: ORIGIN, city: CITY, days: DAYS, startDate: START },
})
const pool2 = res2?.data?.spotPool ?? []
check(pool2.length === pool.length,
  `两次池子条数一致（${pool.length} vs ${pool2.length}）`,
  '不一致说明检索有随机性 —— 那界面上「换一个」的清单会一次一个样')
const sameIds = pool2.length === pool.length
  && [...new Set(pool2.map((o) => o.poiId))].sort().join(',') === [...poolIds].sort().join(',')
check(sameIds, '两次池子里的 id 集合也一样（不只是条数相同）', '只比条数会漏掉"换了几个、补了几个"')

/* ── 汇总 ──────────────────────────────────────────────────────── */
console.log('')
console.log(`══════ 结果：${pass}/${pass + fail} 通过 ══════`)
console.log('（【2b】按"验不出来就不算通过"处理，没有计入分母）')
if (fail > 0) {
  console.log(`⚠️ 有 ${fail} 项没过`)
  process.exit(1)
}

/* ============================================================
 * 反证记录（2026-10-13 实测，因为【2b】在正常数据下验不出来）
 *
 * 做法：把 PlanService.MAX_CANDIDATES_IN_PROMPT 从 30 临时改成 10，
 *      重编译、重启、跑同一单（深圳→广州 5 天），然后看两个数字：
 *
 *        后端日志「候选景点 30 条，超出 prompt 上限 10，按飞猪的相关性顺序取前 10 条」
 *        后端日志「备选池：30 条（其中 10 条送进了 prompt）」
 *
 *      也就是说：**上限这次真的绑定了**（模型只看到 10 条），
 *      而池子纹丝不动还是 30 条。确认之后把上限改回 30。
 *
 * 这一步的作用：它是唯一能让【2b】"有区分力"的办法 ——
 * 上限不绑定时，两种写法（用全部候选 / 用被截断的清单）结果完全一样，
 * 那时任何断言都是自欺。这个工程里"我的验证真的能红吗"已经被证明是真问题
 * （见 MEMORY.md 里那条验收纪律）。
 *
 * 留在这里而不是删掉，是因为下次有人调这个上限（比如为了省钱调到 10），
 * 这段记录能立刻告诉他：**改了它，用户能换的东西不该跟着变。**
 * ============================================================ */
