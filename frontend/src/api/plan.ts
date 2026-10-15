import { request } from './http'
import type {
  HotelStay,
  Persona,
  PersonaInference,
  PlanChatData,
  PlanChatRequest,
  PlanGenerateRequest,
  PlanProgress,
  SpotOption,
  Transfer,
  TravelParams,
  TravelPlan,
} from '../types/plan'

/**
 * 规划相关的两个接口 —— 一个文件一层，因为它们变化的原因相同（都是"我们的契约改了"）。
 *
 * 这一层薄得几乎不像一层，但它买到三件事：
 *   ① 路径和超时**只有一个地方写**（后端改路径时不会漏掉某处）
 *   ② 组件里读到的是 `chatPlan(...)` 而不是 `fetch('/api/plan/chat')` ——
 *      别人搜"谁在用这个接口"时能搜到调用点，而不是一堆字符串
 *   ③ 每个接口的假数据就放在它旁边，写 Mock 时不用翻到另一个文件
 *
 * ⚠️ 路径只写相对路径（`/plan/chat`）。前缀 `/api` 由 VITE_API_BASE 提供。
 *    写成 `/api/plan/chat` 也能跑，但那样"改前缀"就要改这里，而前缀本来是环境的事。
 */

/** 对话一轮。第一个参数是**完整上下文**（历史 + 当前画像），不是这一轮的增量 */
export function chatPlan(req: PlanChatRequest, signal?: AbortSignal): Promise<PlanChatData> {
  return request<PlanChatData>('/plan/chat', {
    body: req,
    signal,
    // 聊天只调一次模型，比 generate 快得多；30 秒还没回来说明真出问题了
    timeoutMs: 30_000,
    mock: () => mockChat(req),
  })
}

/**
 * 生成行程。第一个参数一般只填 params —— retrieval 交给后端去规划。
 *
 * `runId` 排在**第三位**，而且可选。它只为进度服务：带上它，后端就会用这个 id
 * 把这一趟的五步记下来，前端就能一边等一边看。
 * 不放在 req 里，是因为它压根不是这次生成的输入 ——
 * 它是顺带告诉后端一声"这一趟记个 id，我待会儿要看"。
 */
export function generatePlan(
  req: PlanGenerateRequest,
  signal?: AbortSignal,
  runId?: string,
): Promise<TravelPlan> {
  return request<TravelPlan>('/plan/generate', {
    body: req,
    signal,
    headers: runId ? { 'X-Run-Id': runId } : undefined,
    // 不传超时，用 http.ts 的默认值（180 秒）—— 这个接口的正常耗时就是 25~90 秒。
    // 这里**刻意不写**一个"看起来更合理"的数字：写 60 就会复现那个"超时被当成连不上"的 bug。
    // （同一条不要在这里重复声明的地方还有一处：180 那个数字是跟着后端预算算出来的，
    //   理由写在 http.ts 的 DEFAULT_TIMEOUT_MS 上，改的时候只改那一处。）
    mock: () => mockPlan(req),
  })
}

/**
 * 问一次"这趟生成走到哪了"。前端在 /generate 飞着的时候每秒问一次。
 *
 * 三个刻意的选择：
 *
 * ① **不传 mock。** request() 只在"network 失败 **且** 备了 mock"时才降级，
 *    所以不传就是不参与降级 —— 进度是纯观测，问不到就安静算了。
 *    绝不该因为它失败，而在界面顶上挂出一条"正在使用假数据"的横幅：
 *    **一个观测动作不该改变用户对数据源的整体判断。**
 *
 * ② **超时 5 秒**，比默认那 180 秒短得多。180 是给 /generate 那种重活留的；
 *    进度是个几十字节的查询，5 秒不回来说明这一次不值得等 —— 反正下一拍 1 秒后就到。
 *
 * ③ **它可能返回 null**（后端查不到这个 runId，或那条记录已过期）。那不是错误：
 *    轮询的最后一拍总要在生成结束之后落地，null 就是"这趟已经不在场上了"。
 */
export function fetchPlanProgress(runId: string, signal?: AbortSignal): Promise<PlanProgress | null> {
  return request<PlanProgress | null>(`/plan/progress/${encodeURIComponent(runId)}`, {
    method: 'GET',
    signal,
    timeoutMs: 5_000,
  })
}

/* ============================================================
 * 假数据 —— 只在 VITE_USE_MOCK 生效时才会被用到
 *
 * 两条自觉的原则：
 *   ① **假数据必须长得像假的**（名字里带"示例"）。它永远不会被误认为真结果，
 *      哪怕提示没显示出来。
 *   ② **Mock 不做超出能力的事**：它只做最粗暴的关键词抠取，
 *      不要去"模拟一个聪明的模型" —— 那会让你在开发时对真实行为产生错误预期。
 * ============================================================ */

const MOCK_FIELDS: Array<{ key: keyof TravelParams; ask: string }> = [
  { key: 'origin', ask: '从哪座城市出发？' },
  { key: 'city', ask: '想去哪座城市呀？' },
  { key: 'days', ask: '打算玩几天？' },
  { key: 'startDate', ask: '哪天出发？（比如 2026-10-24）' },
  { key: 'childAge', ask: '孩子多大了？' },
  { key: 'pace', ask: '节奏想怎么安排？（慢 / 中 / 快）' },
  { key: 'stroller', ask: '需要带推车吗？' },
]

/**
 * 假数据也推 persona —— 但只做**关键词 → 维度**这一件粗暴的事，
 * 不假装理解语义（和 mockChat 抠参数是同一条规矩：模拟聪明的模型
 * 会在开发时给出错误的预期）。
 *
 * evidence 直接用**用户那句原话**：它本来就是本轮真实发出的消息，逐字成立 ——
 * 这一点上假数据没有说谎，前端卡片把它当"用户原话"展示是安全的。
 */
const MOCK_PERSONA_RULES: Array<{ key: PersonaInference['key']; value: string; hit: RegExp }> = [
  { key: 'walking', value: '少走路', hit: /放松|轻松|不想折腾|怕累|少走/ },
  { key: 'rest', value: '要午睡', hit: /午睡|午觉/ },
  { key: 'crowd', value: '避开人多', hit: /人多|排队|人挤/ },
  { key: 'budget', value: '想省着花', hit: /省钱|预算|便宜|划算/ },
]

/**
 * 抠出来的词后面常跟着动词/助词/标点（"珠海玩"、"广州出发"、"珠海，"），统一削掉。
 *
 * ⚠️ 这**仍然是抠关键词，不是理解语义** —— 只是让抠出来的东西"看起来像个地名"。
 *    为什么值得多这几行：降级到假数据本来就是为了"看起来还能用"，
 *    而"我们广州"这种输出比空白更像故障 —— 它一眼就能看出是坏的，
 *    于是用户会认为"连假数据都做不好"，而不是"这是降级的假数据"。
 */
function trimNoise(raw: string): string {
  return raw.replace(/[玩逛游乐去到的了呀吧呢啊，,。.！!？?、\s]+$/u, '')
}

/**
 * "3" → "03"、"3"（数字）也 → "03"。日期要凑成 ISO 的定长形状（后端是按这个形状解析的）。
 *
 * ⚠️ 签名写成 `string | number` 而不是 `string`，是被一次真实故障逼出来的：
 *    下面那个"过去的日期就顺延明年"的比较要用**今天**的年月日，
 *    而 `now.getMonth() + 1` / `now.getDate()` 是**数字** —— 只收 string 的话
 *    会在运行时报 `raw.padStart is not a function`。
 *    这类错误还有个更坏的性质：它只在**"只说月日、且要跟今天比"**这条分支上炸，
 *    结果是"11 月 17 日出发"永远抠不出来、生成按钮永远是灰的 ——
 *    而假数据这条路本来就是为了出事那天能顶上去（见下面 mockChat 里的那段注释）。
 *    参数类型放宽到这里（而不是在调用处补 `.toString()`）：调用处有三处，
 *    补在调用处就还得记住"下次新增一处也要补"。
 */
function pad2(raw: string | number): string {
  return String(raw).padStart(2, '0')
}

async function mockChat(req: PlanChatRequest): Promise<PlanChatData> {
  await delay(300)

  const last = [...req.messages].reverse().find((m) => m.role === 'user')
  const text = last?.content ?? ''
  const current = req.currentParams ?? {}

  // 只做"关键词抠取"这一件粗暴的事，不假装理解语义
  const patch: TravelParams = { ...current }
  const nums = text.match(/\d+/g)?.map(Number) ?? []
  if (!patch.city) {
    const m = text.match(/([\u4e00-\u9fa5]{2,4})(?:市|周边)/) ?? text.match(/[去到]([\u4e00-\u9fa5]{2,4})/)
    if (m) patch.city = trimNoise(m[1])
  }
  // ⚠️ origin（出发地）必须也能被抠出来。
  //    加它之前，假数据这条路有个自相矛盾：`mockPlan` 里交通行写死是"广州南→珠海"，
  //    而画像卡上没有 origin 这一栏、显示"待确认" —— 一份说"从广州出发"、
  //    另一份说"不知道从哪出发"。**假数据里最不该出现的是一眼看得出的不一致**，
  //    因为降级本来就是为了"看起来还能用"，不一致比空白更像故障。
  //    前面的 `(?:我们|我|从)?` 是必须的：没有它，"我们广州出发" 会抠出"我们广州"。
  if (!patch.origin) {
    const m = text.match(/(?:我们|我|从)?([\u4e00-\u9fa5]{2,3})出发/)
    if (m) patch.origin = trimNoise(m[1])
  }
  if (patch.days == null && /天/.test(text) && nums[0]) patch.days = nums[0]

  // ── 孩子年龄："6 岁" ────────────────────────────────────────────
  // ⚠️ 这两段（年龄、日期）是 2026-10-14 补的，原因很具体：
  //    "生成行程"按钮在**五项必填**（出发地/目的地/天数/出发日期/孩子年龄）齐了之后才可点，
  //    而这份假数据原来只抠得出城市和天数 —— 于是降级路上按钮**永远是灰的**：
  //    后端一挂，用户看到的是一个打不开功能的界面。而降级路存在的全部意义
  //    就是"出事那天还能演示"，一个点不动的按钮等于这条退路也没了。
  const age = /(\d+)\s*岁/.exec(text)
  if (patch.childAge == null && age) patch.childAge = Number(age[1])

  // ── 出发日期：ISO（2026-10-24）/ 中文（11月17日）/ 短横（11-17）──
  // 年份的规矩和真实后端的 TravelDates 一致：**只说月日就按今年，过去了就顺延到明年**。
  // 这不是抄来的细节 —— 它是"过去的日期在飞猪那边会被直接拒绝"那条实测的落点
  // （见 application 里那份画像审计），假数据不该给出一个真实链路会拒绝的值。
  if (!patch.startDate) {
    const iso = /(\d{4})-(\d{1,2})-(\d{1,2})/.exec(text)
    const cn = /(\d{1,2})\s*月\s*(\d{1,2})\s*[日号]/.exec(text)
    const dashed = /(?:^|[^\d])(\d{1,2})-(\d{1,2})(?:[^\d]|$)/.exec(text)
    const now = new Date()
    if (iso) {
      patch.startDate = `${iso[1]}-${pad2(iso[2])}-${pad2(iso[3])}`
    } else if (cn || dashed) {
      const [m, d] = cn ? [cn[1], cn[2]] : [dashed![1], dashed![2]]
      // 先按今年算；要是已经过去了，就顺延一年 —— 和 TravelDates.normalize 同一条规矩
      const thisYear = `${now.getFullYear()}-${pad2(m)}-${pad2(d)}`
      const passed = new Date(`${thisYear}T00:00:00`) < new Date(`${now.getFullYear()}-${pad2(now.getMonth() + 1)}-${pad2(now.getDate())}T00:00:00`)
      patch.startDate = passed ? `${now.getFullYear() + 1}-${pad2(m)}-${pad2(d)}` : thisYear
    }
  }

  const missing = MOCK_FIELDS.filter((f) => {
    const v = patch[f.key]
    return v === undefined || v === null || v === ''
  })

  const reply = missing.length > 0
    ? `（假数据）我记下了。${missing[0].ask}`
    : '（假数据）信息已经齐了，点「生成行程」就可以了。'

  // ── persona：关键词命中就推一条，合并规则照抄后端 ──────────────────
  // "同 key 覆盖（这轮命中了就换新值）、没提到的保留" —— 上一轮推过的、
  // 这轮没再说的维度照样留着。前端不替后端做减法。
  const hitRules = MOCK_PERSONA_RULES.filter((r) => r.hit.test(text))
  const kept = (req.currentPersona ?? { inferences: [] }).inferences
    .filter((it) => !hitRules.some((r) => r.key === it.key))
  const persona: Persona = {
    inferences: [
      ...kept,
      ...hitRules.map((r) => ({ key: r.key, value: r.value, evidence: text })),
    ],
  }

  return { reply, params: patch, persona }
}

/**
 * 假数据里的两段交通。
 *
 * ⚠️ 里面的时刻和时长是 2026-10-13 从飞猪**实测**到的真实值（广州↔珠海），
 *    不是编的 —— 编一个"2 小时"看着没区别，但会让"时长是不是合理"这件事
 *    在没起后端时失去校准。**假数据唯一该假的地方是"它来自哪里"，不是"它长什么样"。**
 *
 * ⚠️ 所以站名和时间**故意不跟着 params 变**：换一个出发地就编一套时刻，
 *    那正是上面那句话禁止的事（编出来的数字会让校准失效）。
 *    代价是：如果降级时用的不是 广州↔珠海，这一行会和画像卡上的出发地对不上 ——
 *    这比"编一套看着合理但完全虚构的时刻"要好接受，因为它是**可见的示例**，
 *    而虚构的时刻是**看不出来的错**。
 *    能跟着 params 变的只有 `from` / `to` 这两个纯标识字段（它们不出现在界面上），
 *    留着它们对不上就成了字段级自相矛盾。
 */
const MOCK_TRANSFER_FIELDS = {
  mode: 'TRAIN',
  depStation: '广州南',
  arrStation: '珠海',
  depDateTime: '2026-11-17T08:36',
  arrDateTime: '2026-11-17T09:29',
  durationMinutes: 53,
  priceYuan: 70,
  serviceNo: '动车D947',
  seatClass: '二等座',
  direct: true,
  transferCity: null,
  // ⚠️ 两条交通的 jumpUrl 都是 **`.invalid` 占位域名**，这不是偷懒。
  //    `jumpUrl` 是整份假数据里唯一"不能编得像真的"的字段：时刻/时长/票价编错了还能靠
  //    "这是假的"兜住，而一个看起来正常的飞猪链接点过去会落到**别的航班**上 ——
  //    用户以为自己在看这一班，其实是另一班，而且他不会察觉。
  //    （`.invalid` 是 RFC 2606 保留 TLD，永不解析，一眼看得出是占位。）
  //    为什么不是干脆留 null：留 null 的话 `TransferLine` 里那个"去飞猪看这趟车"的
  //    分支在**降级路上永远不会被渲染** —— 而降级路存在的全部理由就是"出事那天还能演示"，
  //    少演示一个分支等于那段代码在无后端时从未被验证过。这和酒店那条
  //    `bookUrl: 'https://example.invalid/mock'` 是同一条规矩。
  jumpUrl: 'https://example.invalid/mock-train',
} as const

function mockOutbound(origin: string, city: string): Transfer {
  return { from: origin, to: city, ...MOCK_TRANSFER_FIELDS }
}

/**
 * 备选走法（飞机）的假数据。
 *
 * ⚠️ 它和上面那套火车数字**不是同一条路的换算**，而是一班**真实存在过的航班**的形状：
 *    2026-12-10 深圳→上海 深航 ZH9501 08:20 宝安 → 10:25 虹桥，125 分钟，¥610（实测）。
 *    借它的形状是因为它同时体现了三件必须渲染出来的事：机场短名（宝安/虹桥）、
 *    纯飞行时长（125 分）、以及一个和火车完全不同量级的票价。
 *    城市名跟着 params 走 —— 和"站名时间故意不跟着 params 变"那条规矩一致：
 *    能变的只有纯标识字段，时刻不能编。
 */
function mockInboundFlight(origin: string, city: string): Transfer {
  return {
    from: city,
    to: origin,
    mode: 'FLIGHT',
    depStation: '宝安',
    arrStation: '虹桥',
    // 回程日刻意和 mockInbound 同一天（11-22），否则两行并排看会像两段不同的路
    depDateTime: '2026-11-22T08:20',
    arrDateTime: '2026-11-22T10:25',
    durationMinutes: 125,
    priceYuan: 610,
    serviceNo: '深航ZH9501',
    seatClass: '经济舱',
    direct: true,
    transferCity: null,
    // 同上：`.invalid` 占位，只为让「查这个航班」这个分支在降级路上真的渲染出来。
    jumpUrl: 'https://example.invalid/mock-flight',
  }
}

function mockInbound(origin: string, city: string): Transfer {
  // 回程刻意用**晚班的真实数字**（18:13 发、19:03 到）：它同时验证两件事 ——
  // 方向读的是 from/to 而不是"第几天"，以及耗时能正确显示成"50 分钟"。
  return {
    from: city,
    to: origin,
    ...MOCK_TRANSFER_FIELDS,
    depDateTime: '2026-11-22T18:13',
    arrDateTime: '2026-11-22T19:03',
    durationMinutes: 50,
  }
}

/**
 * 「今晚还能住哪儿」的假数据 —— 三家，**价格从低到高**（后端就是按这个顺序给的，见
 * PlanService.hotelAlternativesOf）。
 *
 * 为什么假数据也要造这一层：它是 2026-10-14 新加的渲染分支，而这条分支在真实路上
 * 只在"后端查回多个档位"时才出现。降级路存在的全部意义是"出事那天还能演示"，
 * 一条永远画不出来的分支等于没有 —— 和当初给交通行补 `.invalid` 链接是同一个理由。
 *
 * ── 三个数字都是实测的（上海，2026-11-17 入住，`--hotel-stars 3` / `4` 各查一次）─
 *   易佰酒店     ¥143  舒适型
 *   如家旗下     ¥158  舒适型
 *   和颐         ¥283  高档型
 *   不只是"看起来像"：它们**刻意分布在两个档位上**，因为"备选要跨档"正是这一层
 *   存在的理由（如果三家都是舒适型，用户会觉得"那何必给我选"）。
 *
 * ⚠️ bookUrl 用 `.invalid` 占位（RFC 2606 保留 TLD，永不解析）—— 和交通行、酒店主选
 *    是同一个规矩：这是整份假数据里**唯一不能编得像真的**字段。时刻编错了有"这是假的"兜着，
 *    而一个看起来正常的飞猪链接会把用户带到**别家酒店**上，而且他不会察觉。
 */
function mockHotelAlternatives(): HotelStay[] {
  const alt = (
    n: number,
    name: string,
    priceYuan: number,
    star: string,
  ): HotelStay => ({
    name: `（假数据）${name}`,
    hotelId: `mock-hotel-alt-${n}`,
    priceYuan,
    star,
    rating: null,
    address: '示例地址（假数据）',
    lat: null,
    lng: null,
    // `.invalid` 而不是 null：null 会让「去飞猪订」这个分支在降级路上从不渲染，
    // 而那正是最需要被看到一眼的地方（主选、备选两处用的是同一个字段）。
    bookUrl: `https://example.invalid/mock-hotel-${n}`,
    imageUrl: '',
    tips: null,
  })
  return [
    alt(1, '易佰酒店示例店', 143, '舒适型'),
    alt(2, '如家旗下示例店', 158, '舒适型'),
    alt(3, '和颐示例店', 283, '高档型'),
  ]
}

async function mockPlan(req: PlanGenerateRequest): Promise<TravelPlan> {
  await delay(800)
  const p = req.params
  const days = p.days && p.days > 0 ? Math.min(p.days, 3) : 3
  const city = p.city ?? '示例城市'
  // 出发地缺省仍然是广州 —— 和上面那套实测数字（广州↔珠海）配套，不是随手挑的
  const origin = p.origin ?? '广州'

  // 日程里的点和备选池**共用同一个工厂** —— 不共用的话两边的名字/编号会悄悄对不上，
  // 而"换一个"这个功能恰好是靠 poiId 去两者之间对照的：
  // 池子里少了日程里那个 id，「换回来」这条路就是断的，而且不报错。
  const mockSpot = (n: number): SpotOption => ({
    poiId: `mock-poi-${n}`,
    name: `（假数据）示例景点 ${n}`,
    // 故意全是同一个类别：假数据不去假装自己有真实的分类结构，
    // 而且"按类别分组"的排序逻辑在有真实数据的路上才验得出来（见 _probe 里的 UI 验收）。
    category: '示例类别',
    priceYuan: 100,
    bookUrl: 'https://example.invalid/mock',
    imageUrl: '',
    lat: null,
    lng: null,
  })

  const plan: TravelPlan = {
    summary: `（假数据）${city} ${days} 天亲子行程。这是本地假数据，不是真实查询结果。`,
    days: Array.from({ length: days }, (_, i) => ({
      day: i + 1,
      theme: `第 ${i + 1} 天（假数据）`,
      spots: [
        {
          // ⚠️ 第 1 天的点不能也写 09:30：上面那趟去程 09:29 才到站，
          //    09:30 开始的景点是个**走不成**的安排。而这份假数据存在的全部意义，
          //    就是"后端连不上时它看起来还像是真的" —— 一条自己和自己矛盾的假行程，
          //    比一条空白更像故障。（这条约束和后端 prompt 第 13 条是同一条：
          //    到达之后至少留 1 小时出站进城。）
          //    最后一天不用动：回程 18:13 才发车，09:30 的点离它远得很。
          time: i === 0 ? '10:30' : '09:30',
          ...mockSpot(i + 1),
          tips: '这条是假数据，用来在没起后端时把界面跑起来',
        },
      ],
      // 交通那一行照**真实形状**造假：第 1 天去程、最后一天回程、中间的日子没有城际移动。
      // 为什么假数据也要还原这个形状：界面上"这天没有交通"和"交通信息缺失"是两个分支，
      // 形状不对的话，没起后端的时候永远看不到其中一个 —— 而那正是最容易写错的那个。
      transfer: i === 0 ? mockOutbound(origin, city) : i === days - 1 ? mockInbound(origin, city) : null,
      // 备选走法：**回程刻意给一条航班**，为的是"主选 + 备选"这条渲染路径
      // 在没起后端的时候也能被看到 —— 否则它只在真后端查出一条长腿时才出现，
      // 而那正是最不容易复现的场景（要真实上游查得到、还得恰好超限）。
      // 数字同样取实测：2026-12-10 深圳→上海 深航 ZH9501 08:20→10:25，¥610（深圳→上海是反面例子，
      // 这里只是借那班航班的**形状**，城市名跟着 params 走 —— 和上面那套广州↔珠海的规矩一致）。
      alternatives: i === days - 1 ? [mockInboundFlight(origin, city)] : [],
      hotel: {
        name: '（假数据）示例酒店',
        hotelId: 'mock-hotel-1',
        priceYuan: 388,
        star: '高档型',
        rating: null,
        address: '示例地址',
        lat: null,
        lng: null,
        // 同上：不是 null，否则「去飞猪订」这个分支在降级路上从不渲染
        bookUrl: 'https://example.invalid/mock-hotel',
        imageUrl: '',
        tips: '连住不用收拾行李',
      },
      // 备选酒店：**每一天都填同一份**，照后端的行为（PlanService.attachHotelAlternatives
      // 就是每天都挂，因为"哪几天算同一段连住"是前端 mergeStays 算的事）。
      // 假数据要是只填第一天，"前端有没有真的按连住去重"这件事就验不出来了 ——
      // 而那恰恰是这一层最容易写错的地方（漏掉 first 判断 → 同一份清单在屏幕上出现三遍）。
      hotelAlternatives: mockHotelAlternatives(),
      // 每天那句"为什么这么排"（假数据版）。**数字全部取自这套 mock 自己的事实**：
      // 去程 09:29 到站 → 第一个点 10:30 才开始；酒店 tips 写着"连住不用收拾行李"。
      // 一句和自己的行程对不上的理由，比没有理由更糟（理由是拿来核对信任的，不是装饰）。
      // 最后一天刻意是 null —— "没有可说的就不渲染"这条分支也得在降级路上被走到。
      why:
        i === 0
          ? '（假数据）示例理由：上午 09:29 才到站，第一个点从 10:30 开始，留了出站进城的缓冲'
          : i === days - 1
            ? null
            : '（假数据）示例理由：连住同一家酒店，不用收拾行李',
    })),
    warnings: ['这是本地假数据（VITE_USE_MOCK 生效），不是后端返回的真实行程'],
    // 备选池里前 days 条**就是日程里的那几条**（同 id）—— 这不是冗余，是契约规定的：
    // 用户把 A 换成 B 之后，A 就重新变成可选项，所以池子必须含已用的。
    // 多出来的 4 条才是真正"没被排上"的备选。
    // 假数据要是不照这个形状造，"换一个"在降级路上就是灰的，而验收脚本会以为这功能不存在。
    spotPool: Array.from({ length: days + 4 }, (_, i) => mockSpot(i + 1)),
    provenance: {
      retrievalPlan: { wantedCategories: [], keywords: [], needHotel: true },
      queries: ['（假数据）没有真的查飞猪'],
      searchRounds: 1,
      candidatesFound: 0,
      candidatesKept: 0,
      rejected: [],
      relaxed: false,
      elapsedMs: 800,
    },
  }
  return plan
}

function delay(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms))
}
