import { request } from './http'
import type {
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

  const missing = MOCK_FIELDS.filter((f) => {
    const v = patch[f.key]
    return v === undefined || v === null || v === ''
  })

  const reply = missing.length > 0
    ? `（假数据）我记下了。${missing[0].ask}`
    : '（假数据）信息已经齐了，点「生成行程」就可以了。'

  return { reply, params: patch }
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
  depStation: '广州南',
  arrStation: '珠海',
  depDateTime: '2026-11-17T08:36',
  arrDateTime: '2026-11-17T09:29',
  durationMinutes: 53,
  priceYuan: 70,
  trainNo: '动车D947',
  seatClass: '二等座',
  direct: true,
  transferCity: null,
  jumpUrl: null,
}

function mockOutbound(origin: string, city: string): Transfer {
  return { from: origin, to: city, ...MOCK_TRANSFER_FIELDS }
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
      hotel: {
        name: '（假数据）示例酒店',
        hotelId: 'mock-hotel-1',
        priceYuan: 388,
        star: '高档型',
        rating: null,
        address: '示例地址',
        lat: null,
        lng: null,
        bookUrl: 'https://example.invalid/mock',
        imageUrl: '',
        tips: '连住不用收拾行李',
      },
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
