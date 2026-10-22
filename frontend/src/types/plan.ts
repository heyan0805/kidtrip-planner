/**
 * 前后端共享的领域类型（契约）。
 *
 * 规矩一：字段名必须与后端 JSON 逐字一致（camelCase）。
 * 规矩二：这个文件里只放"我们自己的形状"。飞猪的原始返回在 flyai.ts，
 *        规范化之后的候选池在这里 —— 因为候选池是我们的概念，不是飞猪的。
 */

import type { PoiCategory } from './flyai'

/* ============================================================
 * 一、对话与画像
 * （这一节第 4 步就定下了，本轮一个字都没改 —— 需求没变，契约就不该动）
 * ============================================================ */

export interface Message {
  role: 'user' | 'assistant'
  content: string
}

/** 旅行参数：所有字段都可缺省，由对话逐轮补齐 */
export interface TravelParams {
  /**
   * **出发地城市**（"广州"）。和 city 是一对，缺一个"每段不能太长"这条约束就落不了地。
   *
   * 它是 2026-10-13 加的，理由和别的字段都不一样：别的字段是"多收集一点信息"，
   * 而 origin 是**一段交通能不能算出来的前提** —— "一段"是「出发地→目的地」和
   * 「目的地→出发地」，**没有出发地，这两段根本不存在**。
   *
   * 缺省的含义是"还没问过"，此时后端**不会**查车次、也不会报错（缺信息 ≠ 出错），
   * 只是行程里没有交通那一行。后端不会给它编默认值 —— 没人能假设用户的家在哪。
   *
   * ⚠️ origin === city 是合法的（本地游，比如"就在珠海玩 6 天"），
   *    后端用 TravelParams.sameCity() 判它，这种情况也不会去查车次。
   */
  origin?: string
  /**
   * **目的地城市**（"珠海"）。
   *
   * ⚠️ 只接受**一个具体城市**。"广东周边""珠三角""深圳附近"这类区域说法后端会挡掉
   *    （CityNames 黑名单），并且把它当作"还没问过"—— 因为拿它去查飞猪会静默返回空，
   *    整份行程排不出来，而用户永远不知道原因是城市名写错了。
   *    区域说法要由对话承担：模型替你挑一个最合适的城市，并说明按哪儿安排。
   */
  city?: string
  days?: number
  /**
   * 出发日期，ISO 字符串（"2026-10-24"）。**不是时间戳**。
   * 为什么用字符串：用户说的是"国庆去"，不是某个秒数；日期的时区语义比时刻简单得多。
   * 它决定四件事：酒店的 --check-in-date / 每天的日期标题 / 城际车次查哪一天 / 以后的天气查询。
   */
  startDate?: string
  preferences?: string[]
  /** 慢 / 中 / 快 */
  pace?: string
  /** 孩子的年龄，一个孩子一项（[5] / [5, 8]）。缺失或空 = 还没问过 */
  childAges?: number[]
  stroller?: boolean
}

/** POST /api/plan/chat 的 data 字段 */
export interface PlanChatData {
  reply: string
  params: TravelParams
  /**
   * 我们从他的话里**推**出来的判断（2026-10-15 加）。和 params 的分界线：
   *   params  —— 他**说过**的事实（"9 天""想去珠海"），错了他会纠正；
   *   persona —— 我们**推**的偏好（"怕走路""要午睡"），错了他**以为你懂他，全程不报错**。
   * 所以 persona 的每条都带 evidence（他的原话，后端逐字验证过才放行），
   * 前端展示时把原话一起亮出来 —— 让"我怎么理解你"变成看得见、可纠正的东西。
   *
   * 永远非 null（没有判断就是空数组）。
   */
  persona: Persona
}

/**
 * 一条**过了闸门的**推论：value 是我们概括的，evidence 是他自己的原话。
 * evidence 能进 Persona，是因为后端在对话历史里逐字找到过它 ——
 * 所以卡片上可以放心地把它当"用户原话"展示，不需要前端再验证一遍。
 */
export interface PersonaInference {
  /** 维度 key（后端闭集，就这四个） */
  key: 'walking' | 'crowd' | 'rest' | 'budget'
  /** 我们推的判断，如「少走路」 */
  value: string
  /** 他的原话（截取的那一小段），如「我想有一个放松的旅行」 */
  evidence: string
}

/**
 * key 的中文名（卡片上用）。**必须和后端 PersonaTrait 的 label 一致** ——
 * 两边各写一份是有意的：这四个词是产品语言不是技术实现，改它属于产品决策，
 * 改的时候应该两边一起被人工过目，而不是一处改动静默生效。
 */
export const PERSONA_LABELS: Record<PersonaInference['key'], string> = {
  walking: '怕走路',
  crowd: '怕人多',
  rest: '要午睡',
  budget: '预算敏感',
}

/** 我们对这个用户的理解。空数组 = 还没推出来任何判断 */
export interface Persona {
  inferences: PersonaInference[]
}

/**
 * POST /api/plan/chat 的请求体 —— **上下文传递的前端一侧**。
 *
 * 两个字段，缺一个对话就进行不下去：
 *   messages      —— 到目前为止的全部对话（用户 + 助手），**最后一条必须是用户说的**
 *   currentParams —— 上一次响应里带回来的 params，**原样带回来**
 *
 * 为什么要"原样带回来"而不是前端自己攒：
 *   因为画像的合并是**后端做**的（模型只输出这一轮新听出来的字段，
 *   其余靠 TravelParams.mergedWith 保留）。前端要是自作聪明只传"本轮新增"，
 *   后端就会把它当成完整状态，把之前的城市、天数全丢掉 —— 而且没有任何报错，
 *   用户只会在点"生成行程"时发现"怎么又要重填一遍"。
 *   **谁做的合并，谁负责那份完整状态** —— 中间人不要替它做减法。
 *
 * ⚠️ currentParams 可以不给（第一次对话时），但**不要自己塞默认值**：
 *    比如 days 千万不要先填 3。后端拿到 days=3 会认为"天数已经问过了"，
 *    于是再也不问这件事，用户就得到一份自己没说天数、按 3 天排出来的行程。
 */
export interface PlanChatRequest {
  messages: Message[]
  currentParams?: TravelParams
  /**
   * 上一轮推出来的 persona，**原样带回来** —— 和 currentParams 是同一个道理：
   * 合并在后端做（同 key 覆盖、没提到的保留），前端不要替它做减法。
   * 第一次对话时不给。
   */
  currentPersona?: Persona
}

/**
 * 第二步的产物：把画像翻译成"**期望拿到什么**"。
 *
 * ⚠️ 它**不是前端传上来的**（B5 之后明确了这一点）。
 *    `POST /api/plan/chat` 的响应里只有 `{ reply, params }` —— 没有它。
 *    它的产出发生在 `/generate` 内部：请求里带上 retrieval 就用（脚本、排障走这条路），
 *    不带就由后端问一次模型，按画像规划出来。
 *
 *    为什么放在那一步而不是聊天里：因为**它决定我们要发几次上游请求**，
 *    而"有几次成本"这个信息，只有在准备去查的那一刻才完整。
 *    决定权留在知道成本的一侧 —— 这条和下面那句"描述的是期望，不是命令"是同一个原则。
 *
 * 它为什么是个"受控"的输出：wantedCategories 被限定为 PoiCategory（35 个中文值），
 * 所以模型**根本没有机会编造景点名**：它只能说"期望有动物园"，
 * 不能说"深圳阿凡达悬浮山乐园"。后端还会用同一份闭集**再过滤一遍** ——
 * 不是相信模型不会写错，而是让它写错了也没用。
 *
 * ⚠️ 2026-10-13 改了语义（实测上游限流后被迫）：原来叫 `categories`，意思是
 *    "拿这几个分类各查一次"。那个前提是**可以并发/多次查询** ——
 *    实测被推翻了：并发 6 个 → 3 个 429；连续串行 6 个 → 后 3 个 451 风控。
 *    所以现在是：后端先发**一次宽查**（不带 --category，实测一次覆盖 7 个分类），
 *    只有当 wantedCategories 里有"一个都没出现"的时候，才为它补发一次定向查询。
 *
 *    也就是说：**这个类型描述的是"期望"，不是"命令"。**
 *    模型说想要什么，后端决定怎么取、取几次 —— 决定权在有成本信息的那一侧。
 */
export interface RetrievalPlan {
  /** 画像期望覆盖的类别。后端用它判断"要不要补查"，不是用它逐条去查。 */
  wantedCategories: PoiCategory[]
  /**
   * 偏好词，比如"室内""雨天""亲子"。
   * ⚠️ 它**不会**变成 CLI 的 --keyword 参数 —— 实测那是窄筛：
   *    `--keyword 亲子` 只回 5 条（全是室内儿童乐园）且**票价 0 条**，
   *    一用就等于把宽查攒下来的池子毁掉。
   * 它的用途留在最后的组装阶段（排序偏好），现在只作为"意图记录"回显给用户。
   */
  keywords: string[]
  needHotel: boolean
}

/* ============================================================
 * 二、候选池 —— 规范化之后的形状，校验层的输入与输出
 *
 * 飞猪给的 FlyaiPoi/FlyaiHotel 是不能直接用的（"¥198" 比不了大小、
 * "22.596039" 算不了距离）。中间那层"规范化"负责把它们翻译成下面这些。
 * 翻译的好处：从这一节往下，全工程再也不会遇到字符串数字。
 * ============================================================ */

interface CandidateBase {
  name: string
  address: string
  /** 纬度。**缺坐标时是 null，不是 0** —— 0 是几内亚湾，会把距离算成几千公里 */
  lat: number | null
  /** 经度 */
  lng: number | null
  /**
   * 门票/房价（元）。null 的含义是"飞猪上没有可售的票/房商品"，
   * 可能是免费公园（真的不要钱），也可能是门票已含在母景区里 —— 这两种分不出来。
   * 所以规则里遇到 null 只能当"价格未知、不参与比价"，**绝不能当"不合格"筛掉**。
   */
  priceYuan: number | null
  imageUrl: string
  /** 飞猪跳转短链。⚠️ 带追踪码，每次请求都会重新生成 —— 只能当"点开看详情"用 */
  bookUrl: string
}

export interface PoiCandidate extends CandidateBase {
  kind: 'poi'
  poiId: string
  category: string
  /** 1~5，原始可能是 null */
  level: number | null
  rank: string | null
  /** 门票商品 id。null = 这条景点在飞猪上没有可订的票 */
  itemId: string | null
  ticketName: string | null
}

export interface HotelCandidate extends CandidateBase {
  kind: 'hotel'
  hotelId: string
  /** "经济型" / " " / null —— 已 trim，" " 会被规范成 null */
  star: string | null
  rating: number | null
}

/**
 * 判别联合（discriminated union）：一个数组里装两种形状，靠 kind 区分。
 * 好处：写 switch (c.kind) 时 TS 会自动收窄 —— 进 'poi' 分支就有 poiId，
 * 进 'hotel' 分支就有 hotelId，用错了当场报红。这就是 role: 'user' | 'assistant' 的同一招。
 */
export type Candidate = PoiCandidate | HotelCandidate

/* ============================================================
 * 三、校验规则 —— 这是"策略"，不是"数据"
 *
 * 为什么单独一张表，不塞进 TravelParams：
 *   TravelParams 是**对话里问出来的**（用户说的）；
 *   PlanRules 是**我们定的产品策略**（系统说的）。
 *   混在一起，以后想改"每天最多几个景点"就得重新跑一遍对话。分开，改这里就行。
 * ============================================================ */

export interface PlanRules {
  /** 单人票价上限（元）。null = 不限 */
  maxTicketPriceYuan: number | null
  /** 最低景点等级 1~5。null = 不限 */
  minPoiLevel: number | null
  /** 每天最多几个景点 */
  maxSpotsPerDay: number
  /** 相邻两个点最大距离（公里） */
  maxDistanceKm: number
  /** 校验不通过时最多重查几轮 */
  maxRounds: number
  /** 整个生成过程的总耗时上限（毫秒） */
  budgetMs: number
}

/** 默认规则。数值可以改，但要注意每个数字都是从真实数据里得出的，不是拍的 */
export const DEFAULT_PLAN_RULES: PlanRules = {
  // 实测（2026-10-13，逐条查证）：**主流售票景点都带票价** ——
  // 深圳欢乐谷 ¥227 / 世界之窗 ¥219 / 野生动物园 ¥198 / 珠海长隆海洋王国 ¥362.88。
  // 之前看到的"10 条只有 1 条有价"，是因为那一批里 9 条是免费公园或园区内的子景点
  // （鲸鲨馆、海洋天地、麦哲伦企鹅岛…）。**采样取错，结论就会错。**
  // 默认仍不限价，但理由变了：等画像里有了 budget 字段再启用，而不是"没数据"。
  maxTicketPriceYuan: null,
  minPoiLevel: null,
  maxSpotsPerDay: 3,
  maxDistanceKm: 15,
  maxRounds: 2,
  budgetMs: 30_000,
}

/* ============================================================
 * 四、行程与"可追溯信息"
 * ============================================================ */

/**
 * 行程里的一个点。
 * 关键差别：它必须能追溯到飞猪上的**真实商品**（poiId / bookUrl / 真实价格），
 * 不再是模型凭空编出来的字符串。
 */
export interface TravelSpot {
  time: string
  name: string
  tips?: string
  /** 稳定的景点标识。去重和追溯都靠它 —— 别用 bookUrl，那个每次请求都在变 */
  poiId: string
  priceYuan: number | null
  bookUrl: string
  imageUrl: string
  lat: number | null
  lng: number | null
}

/**
 * 今晚住哪。
 *
 * 三条规矩，都和景点**不一样**：
 *
 * ① `priceYuan` 是**每晚房价**，不是门票。
 *    两者量级接近（都是几百元），所以做费用汇总时写错变量名会得出一份
 *    "看起来合理"的错误预算 —— 静默错误。汇总时必须分开算：门票 × 人数，房价 × 晚数。
 *
 * ② **相邻天可以是同一家**（连住）。
 *    带娃旅行换一次酒店 = 废掉半天（收拾、退房、寄存、入住、孩子重新适应环境）。
 *    所以渲染时要把 hotelId 相同的连续几天合并成"连住"，而不是列三遍同样的卡片。
 *    这也是它和 spots 最大的区别：景点重复是 bug，酒店重复是常态。
 *
 * ③ `hotelTips` 是模型的表达（为什么住这儿），其余字段全部由后端从候选池回填。
 */
export interface HotelStay {
  name: string
  /** 稳定的酒店标识。连住判定和追溯靠它 —— 别用 bookUrl，那个每次请求都在变 */
  hotelId: string
  /** **每晚**房价（元）。null = 飞猪上没有可订的房型，不是"免费" */
  priceYuan: number | null
  /**
   * 已 trim。实测是**中文档次词**："豪华型" / "高档型" / "四星级" / "舒适型" / "经济型"。
   * 所以别去 parseInt 它 —— "高档型" parse 出来是 NaN。
   * 要做"是不是青旅"的判断，直接比字符串：`star === '经济型'`。
   * 为 null = 上游没回传（**不带入离日期查询时一律是 null**）。
   */
  star: string | null
  /** ⚠️ 后端明确标注：实测恒为 null，上游不下发评分。别拿它排序或筛选，那只会永远得到同一个顺序 */
  rating: number | null
  address: string
  lat: number | null
  lng: number | null
  /**
   * 飞猪上这家的详情页。**点开就是"你要去的那几天"的房态** ——
   * 上游给的是一条带入离日期的路由短链，实测 302 落到飞猪 H5 详情页：
   *   …/app/trip/rx-hotel-detail/pages/detail?shid=<这家酒店的 id>&checkIn=2026-11-17&checkOut=2026-11-26…
   * 所以它是**能直接下单的入口**，不是"介绍一下这家店"。
   *
   * ⚠️ 但它**每次请求都可能变**（同一家酒店在不同查询里拿到的短码不一样）——
   *    所以别拿它做标识或去重，稳定标识是上面的 hotelId。
   *    也**不要**把它当成"预订成功"的证据：我们做的是搜索，下单在飞猪那边。
   */
  bookUrl: string
  imageUrl: string
  /** 为什么今晚住这儿（离明天的点近 / 连住不用收拾行李）。可能为 null */
  tips: string | null
}

/**
 * 一段**真实的**城际交通：坐哪一趟、几点发几点到、多久、多少钱。
 *
 * 它和 HotelStay 是同一类东西 —— 都是"从飞猪查回来的事实"，不是模型的表达。
 * 存在的理由只有一个：这个产品的核心是「带孩子，每一段移动都不能太长」，
 * 而一个约束如果屏幕上没有一行真实数字支撑，它就只是一句宣传语。
 *
 * ── 它同时是火车和飞机（`mode` 说的）────────────────────────────
 * 字段本身是**载体无关**的，因为两种载体要回答的问题一模一样：
 *   serviceNo —— "高铁G270" / "深航ZH9501"
 *   seatClass —— "二等座"   / "经济舱"
 *   depStation—— "深圳北"   / "宝安"（机场短名，后端给的就是上游的 stationShortName）
 * 所以没有第二个类型，界面按 `mode` 只换图标和几个词（"车站"↔"机场"、"车次"↔"航班"）。
 *
 * ── 三件渲染时必须知道的事 ──────────────────────────────────
 *
 * ① **方向写在 from / to 里，不要靠天数推断。**
 *    "第 1 天就是去程、最后一天就是回程"这个规则只在单城市行程下成立。
 *    照 from/to 渲染，以后加多城市串联时这个组件一个字都不用改。
 *
 * ② `depDateTime` / `arrDateTime` 是**带日期的** ISO（"2026-11-17T08:36"）。
 *    看着冗余（哪一天不是已经在 TravelDay.day 里了吗），但**夜车会跨午夜**：
 *    23:30 发、次日 00:25 到。只存时刻的话，"00:25" 会被显示成"比出发还早"。
 *    显示时取 T 后面那 5 个字符就行，但**别把它当成"当天的时间"**。
 *
 * ③ `direct === false` 时 `transferCity` 有值，要在界面上说清"经 XX 换乘"。
 *    把它藏起来的话，用户会以为这是一趟直达 —— 而带娃换乘和直达是两回事。
 *
 * ⚠️ 飞机的 `durationMinutes` 是**纯飞行时间**，不是门到门时间（机场还要提前到、
 *    落地还要进城）。所以界面上那个数字**不要**和火车的时间直接比大小 ——
 *    那是拿两种口径在对账。后端在 warnings 里已经说明这一点，界面不要另编一套说法。
 */
export interface Transfer {
  from: string
  to: string
  /**
   * 这一段坐什么走。**只影响图标和文案，不影响任何判断**。
   *
   * 它不由前端从"有没有 stationName"猜 —— 后端查的是 `search-train` 还是
   * `search-flight`，那件事在发请求时就定死了，比任何下游字段都可靠。
   */
  mode: 'TRAIN' | 'FLIGHT'
  /** 上车/登机地点的短名（"广州南" / "宝安"） */
  depStation: string
  /** 下车/落地地点的短名。**中转方案下这是最终到达地**，不是换乘站 */
  arrStation: string
  /** ISO 本地日期时间 "2026-11-17T08:36" —— 带日期，因为夜车会跨午夜 */
  depDateTime: string
  arrDateTime: string
  /**
   * 整段耗时（分钟）。中转时含候车，所以 ≥ 各段之和。
   * ⚠️ 飞机是**纯飞行时间**，见上面那段。
   */
  durationMinutes: number | null
  /** 这一段的票价（元）。null = 飞猪没给价，**不是免费** */
  priceYuan: number | null
  /** "动车D947" / "深航ZH9501" —— 载体名已拼好（单看编号分不出高铁还是动车、也看不出航司） */
  serviceNo: string | null
  /** "二等座" / "经济舱" */
  seatClass: string | null
  /** true = 一趟直达；false = 中途换乘 */
  direct: boolean
  /** 换乘站名，直达时为 null（火车才有这个概念） */
  transferCity: string | null
  /** 飞猪购票跳转链接 */
  jumpUrl: string | null
}

export interface TravelDay {
  day: number
  theme: string
  spots: TravelSpot[]
  /**
   * **今天怎么到的**（城际交通）。和 hotel 是一对：
   *   transfer —— 今天怎么到的     hotel —— 今晚住哪
   *
   * null 有三种含义，渲染时要分清：
   *   · 这一天本来就没有城际移动（行程中间的日子，或本地游 origin === city）；
   *   · 画像里没有出发地 → 后端**不查也不报错**，见 warnings；
   *   · 查了但没查成 / 两地之间没有够快的直达 → 见 warnings（两种话术不一样，别混）。
   *
   * ⚠️ 和 hotel 一样，**方向不要靠"第几天"推断** —— 直接用 transfer.from / to。
   */
  transfer: Transfer | null
  /**
   * 这一段路**还有别的走法**，和 transfer 是"备选 / 主选"的关系（不是两段交通）。
   *
   * 现在只可能是飞机：后端在"火车这条腿带娃偏累"时才补查一次航班。
   * 但字段名**不叫 flight** —— 以后要放大巴/轮渡也往这里放，那样不用再改契约。
   *
   * 三件事渲染时不能忘：
   *   · 它**不参与排程**（模型看不到它），当天的时间安排只按 transfer 那一个走；
   *   · 飞机的价格是**查询当时**的参考价，浮动比火车大得多 —— 得说明，别当确定数字；
   *   · 永远非 null（没有就是空数组），所以不用判空，直接 .map 就行。
   */
  alternatives: Transfer[]
  /**
   * **当晚**住哪。null 有两种含义，渲染时要分清：
   *   · 后面几天还有行程 → 这次没查到酒店数据（见 warnings）；
   *   · 最后一天 → 当天返程，不需要住宿。
   * 好消息是前端不用自己判断是哪种 —— 看 `warnings` 里有没有"没有查到可用的酒店"就够了。
   */
  hotel: HotelStay | null
  /**
   * 今晚**还能住哪儿** —— 同城查到、但模型没选的那几家（2026-10-14 加）。
   *
   * 和上面 `alternatives` 是同一个思路，只是换成了住宿：hotel 是主选，
   * 这里是备选。区别在于它**不来自另一次查询** —— 这些酒店本来就在候选池里
   * （上游一次查回 10 家，模型只挑 1 家，剩下 9 家原本直接丢掉），
   * 所以露出它们不增加任何上游成本。选它的理由和选 `alternatives` 一样：
   * "给模型看多少"和"给用户多少可选"是两件事，后者不该被前者的成本限制住。
   *
   * 三件事渲染时不能忘：
   *   · 它**不进 prompt**（模型看不到它），所以当天安排只以 `hotel` 为准；
   *   · 它按**价格升序**（后端排好的），也就是"从便宜的往上看" —— 顺着这个顺序展示，
   *     用户一眼就能看到"原来还有更便宜的"；
   *   · 永远非 null（没有就是空数组），不用判空，直接 .map。
   *
   * ⚠️ **连住时相邻几天的这一项是同一份**（同一个酒店覆盖多天）。
   *    别在每天卡下面都渲染一遍 —— 跟着「连住」合并后的那张住宿卡渲染一次就够，
   *    那个合并本来就在做（见页面里的 mergeStays）。
   */
  hotelAlternatives: HotelStay[]
  /**
   * **今天为什么是这个形状**（2026-10-15 加）。后端从本地算得出的事实拼出来：
   * "去程 10:39 到站，所以今天第一个点排在 12:00""3 个点都在 1.2 公里内，走两步就到"。
   *
   * 注意两个设计：
   *   · 这句**不是模型写的** —— 理由里每个数字（公里数、时刻、点数）都是后端
   *     真算过的。模型写理由会写出"这里不需要走很多路"这种没法核实的话。
   *   · 引用的用户原话（"你说过「…」，所以…"）**方向对得上才出现** ——
   *     他说"体力好"的时候，绝不会拿这句当"今天排得轻松"的理由。
   *
   * null = 今天没有任何值得解释的形状，**不是错误**：
   * 后端宁可空着也不兜底一句"为了让你轻松"那种对任何一天都成立的废话。
   */
  why: string | null
}

/** 被规则筛掉的候选。不是偷偷丢掉，而是记账 —— 用户有权知道筛掉了什么、为什么 */
export interface RejectedCandidate {
  name: string
  reason: string
}

/** 这份行程是怎么来的（用户能看见，我们排障也靠它） */
export interface PlanProvenance {
  /** 本次实际按什么条件去查的 —— 第二步的产物回传前端，让用户看见"我查了什么" */
  retrievalPlan: RetrievalPlan
  /**
   * **真实发出去的查询命令**，逐条列出来（如 `flyai search-poi --city-name 深圳`）。
   * 为什么连命令都要回传：排障时最常见的误判是"我以为我查的是 A"。
   * 而且这一层直接暴露了成本 —— 用户能看见"这份行程花了几次上游调用"。
   */
  queries: string[]
  /** 实际查了几轮飞猪（1 = 只有宽查；2 = 还补查过） */
  searchRounds: number
  candidatesFound: number
  candidatesKept: number
  rejected: RejectedCandidate[]
  /** 第二轮是否放宽过条件（**放宽不花上游调用** —— 它只是松规则，不重新查） */
  relaxed: boolean
  elapsedMs: number
}

/** POST /api/plan/generate 的 data 字段 */
export interface TravelPlan {
  summary: string
  days: TravelDay[]
  warnings: string[]
  /** 新增：没有它，用户看到的就是一份"从天而降"的行程 */
  provenance: PlanProvenance
  /**
   * 这次检索到的**全部合格景点** —— 界面上「换一个」的备选来源。
   *
   * ⚠️ 三条性质，每一条理解错了都会做出一个错的交互：
   *
   * ① **它是"合格"的，不是"全部"的。** 被规则筛掉的（太贵、等级不够）不在这里，
   *    它们在 `provenance.rejected` 里躺着、理由也写着。
   *    所以界面**不能**把两处拼起来给用户选 —— 那等于让用户把一个
   *    "因为太贵被筛掉"的点换回来，而他看不到自己正在跨过一条规则。
   *
   * ② **它包含已经排进日程的那些点。** 乍看冗余，但这是对的：
   *    用户把 A 换成 B 之后，A 就重新变成一个可选项了。
   *    如果后端发的是"减掉已用的"，A 就永远回不来了 —— 而"换回来"是用户最需要的动作。
   *    所以切片这一刀由前端来切（它才知道当前用了哪些），而且**每换一次都要重切**。
   *
   * ③ **它不会跟着替换结果更新。** 这是后端那份数据，一次生成就固定了。
   *    用户换了一轮之后，"还有哪些可选"是新算出来的，不是从这里读的。
   *    ⚠️ 所以**千万不要 `setState(plan.spotPool)` 之后在里面做删改** ——
   *    那会把"后端查到了什么"和"用户当前选了什么"两件事揉进一个数组，
   *    而这个文件顶上那条规矩（谁做的合并，谁负责那份完整状态）在这里就是：
   *    后端的事实归后端，用户的改动归用户，两者在渲染时才叠在一起。
   */
  spotPool: SpotOption[]
}

/* ============================================================
 * 生成进度 —— GET /api/plan/progress/{runId}
 *
 * 它回答的是"这几十秒里系统在干什么"，**不是"还要多久"**。
 *
 * ⚠️ 这里故意**没有** percentage / progress 之类的字段：五段的耗时完全不可预测
 *    （模型那两段随负载在 10~30 秒之间浮动），任何百分数都只能靠编 ——
 *    而这个产品最不能出的错就是"看起来很像真的、其实是编的"。
 *    已经走完的部分是真的，没走完的不猜。界面上因此没有进度条，只有一条流水线。
 * ============================================================ */

/** 某一步此刻的状态。后端报什么就是什么，前端不做任何推断 */
export type ProgressStepState = 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED'

export interface PlanProgressStep {
  /** 后端的 Stage 名。**只用来做 React 的 key**，不用来查文案（文案见下） */
  key: string
  /**
   * 这一步叫什么 —— **由后端给**。
   * 前端要是自己维护一份 key→文案 的表，后端改了阶段顺序而前端忘了跟，
   * 界面就会按错的顺序打勾，而这件事**不报错**，只是长期地错。
   */
  label: string
  state: ProgressStepState
  /**
   * 这一步真实花了多少毫秒。
   * ⚠️ null 的含义是**还没走完**，不是"花了 0"—— 所以判空不能写成 `!s.tookMs`。
   */
  tookMs: number | null
}

export interface PlanProgress {
  /** 全部步骤，顺序即执行顺序（后端给的，前端不许自己重排） */
  steps: PlanProgressStep[]
  /** 从后端开始干活到此刻。用它显示"已用 N 秒" */
  elapsedMs: number
  /** 这趟是否已经收场（成功或失败都算） */
  finished: boolean
  /** 只在 finished 为 true 时有意义：false 表示这趟没排出来 */
  ok: boolean
}

/**
 * 一个**还没被排进日程的合格景点**。逐字镜像后端 `plan/SpotOption.java`。
 *
 * 它和 `TravelSpot` 的区别不是"少几个字段"，而是**两类东西**：
 *   TravelSpot 是已经排进日程的一个点 —— 有时间、有模型写的 tips；
 *   SpotOption 是还没决定要不要去的一个候选 —— 没有时间（几点去是排期的事），
 *   也没有 tips（那是模型针对"某个点在某个行程里的位置"写的）。
 *
 * ⚠️ 这个区别挡住一个具体的假话：替换之后如果沿用原来那条 tips，用户会看到
 *    「广州动物园 ¥29.9 —— 长隆野生动物世界很大，建议先坐小火车再步行」。
 *    两句各自都真实，拼在一起是**看不见的错**。所以换上去的点宁可没有建议。
 */
export interface SpotOption {
  /** 稳定景点标识。去重和追溯都靠它 —— 别用 bookUrl，那个每次请求都在变 */
  poiId: string
  name: string
  /** 飞猪的商品标签（"动物园""博物馆""户外活动"）。**可能是空串**，原始数据里就有 */
  category: string
  /** 门票（元）。null = 飞猪上没有可订的票，**不是免费**（和 TravelSpot 用的是同一条规矩） */
  priceYuan: number | null
  bookUrl: string
  imageUrl: string
  /** 纬度。**缺坐标时是 null 不是 0** —— 0 是几内亚湾，会把"附近"算成几千公里 */
  lat: number | null
  lng: number | null
}

/**
 * POST /api/plan/generate 的请求体。
 *
 * 三个字段里只有 params 是必给：
 *   params             —— 来自 chat 的产物（**原样递过来**，不要自己加工）
 *   retrieval          —— 一般**不要传**。它是"检索条件"，不传时后端会问一次模型，
 *                         按画像自己规划 —— 这本来就是它的正常产出方式。
 *                         只有做排障、复现某个特定检索结果时才需要手动指定。
 *   maxTicketPriceYuan —— 规则覆盖（过滤掉贵过这个价的景点）。是留给"让规则校验能被看见"的旋钮。
 */
export interface PlanGenerateRequest {
  params: TravelParams
  retrieval?: RetrievalPlan
  maxTicketPriceYuan?: number
  /**
   * chat 攒出来的 persona（**原样递过来**）。编排层把它带进 prompt 让模型参考，
   * 但它**只影响取舍，不改变事实** —— 180 分钟上限、距离红线这些硬规则 persona 动不了。
   * 没聊出任何判断就不传，后端当空处理。
   */
  persona?: Persona
}
