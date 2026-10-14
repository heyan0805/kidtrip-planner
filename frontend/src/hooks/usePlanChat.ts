import { useCallback, useEffect, useRef, useState } from 'react'
import { chatPlan, fetchPlanProgress, generatePlan } from '../api/plan'
import { ApiError, subscribeMockNotice } from '../api/http'
import type {
  Message,
  PlanProgress,
  SpotOption,
  TravelDay,
  TravelPlan,
  TravelParams,
  TravelSpot,
} from '../types/plan'

/**
 * 对话的状态机 —— "上下文传递"的前端一侧全部在这里。
 *
 * ── 它要维护两份状态，而且必须是两份 ─────────────────────────────
 *   messages —— 对话历史（给人看的，越攒越长）
 *   params   —— 已提取的画像（给机器用的，只有 7 个字段）
 * 为什么不合成一份：历史的长度不应该影响已提取的事实。用户聊了 20 轮之后，
 * "想去珠海"那句话可能已经被裁掉了，但 `params.city` 依然是"珠海"。
 * **把事实从历史里提炼出来单独存，是让长对话不退化的关键一步。**
 *
 * ── 这个 hook 里有三个坑，每一个都写在代码旁边了 ─────────────────
 *   ① 过期闭包 —— 异步流程里用 state 会读到旧值
 *   ② 并发闸门 —— 一次只允许一个请求在飞（就是最开始那个"消息被吞掉"的 bug）
 *   ③ 卸载后 setState —— 不加处理会有警告和残留状态
 */

/** 界面用的消息：比契约多一个 id，只是为了 React 的列表 key */
export interface ChatBubble extends Message {
  id: string
}

export interface UsePlanChat {
  messages: ChatBubble[]
  params: TravelParams
  plan: TravelPlan | null
  sending: boolean
  generating: boolean
  error: string | null
  /** 正在用本地假数据时的提示（可能为 null） */
  mockNotice: string | null
  /**
   * 必填项齐了没有。
   *
   * ⚠️ 判据是 {@link REQUIRED_FIELDS} 那五项，**不是**"知道城市就行"（见那段说明）。
   */
  canGenerate: boolean
  /**
   * 还差哪几项必填。
   *
   * 它存在的唯一理由是让界面**说清为什么点不了** —— 一个灰着的按钮、不写原因，
   * 用户只会以为界面坏了，而不是回去把信息补上。
   */
  missingFields: Array<keyof TravelParams>
  /** 用户手动换过的点（稀疏覆盖，不是整份行程）。见下面 swaps 的说明 */
  swaps: SpotEdits
  /**
   * 生成期间的进度 —— 后端每走完一步就把它报出来。
   *
   * 三种"没有进度"要分清楚，不过界面对它们的处理是一样的（**都不假装有进度**）：
   *   null       —— 还没开始，或者已经收场
   *   五步全待办 —— 后端刚收到请求，还没跑到第一步（一两毫秒的窗口，肉眼见不到）
   *   一直拿不到 —— 轮询失败（后端在忙、网络抖）。**这不是错误**，安静退化成普通等待就行
   */
  progress: PlanProgress | null
  sendRound: (text: string) => Promise<void>
  generate: () => Promise<void>
  swapSpot: (day: number, index: number, option: SpotOption) => void
  restoreSpot: (day: number, index: number) => void
  reset: () => void
}

let bubbleSeq = 0
function bubble(role: Message['role'], content: string): ChatBubble {
  bubbleSeq += 1
  return { id: `m${bubbleSeq}`, role, content }
}

/**
 * 生成行程的**必填项**。
 *
 * ── 为什么不能只判 city ────────────────────────────────────────────
 *   `canGenerate` 原来写的是 `Boolean(params.city?.trim())` —— 只要知道"去哪"就能点。
 *   而同一张画像卡上分明还写着"天数 / 出发日期 / 孩子年龄"是**待确认**。
 *   后果不是报错，而是**静默地拿默认值把空缺补上**：后端 `TravelParams.orDefaults()`
 *   会把 days 补成 3，于是用户点下去、出来一份"玩 3 天"的行程 —— 而他刚才说的是 9 天。
 *   **默认值是对的兜底，但它绝不该被当成"用户已经说过了"。**
 *
 * ── 为什么是这五项 ────────────────────────────────────────────────
 *   每一项都直接决定"查什么、怎么排"，没有一项是我们有权替他定的：
 *     origin     从哪走   —— 没有它，"每段不能太长"这条约束根本落不了地
 *     city       去哪儿   —— 没有它，检索和排期都无从开始
 *     days       几天     —— 决定排几天、酒店要住几晚
 *     startDate  哪天走   —— 车次班次、酒店价格全跟着它
 *     childAge   孩子多大 —— 这个产品的身份约束，直接决定节奏和点位的现实性
 *
 * ── 哪些**刻意不在**必填里 ────────────────────────────────────────
 *   preferences / pace / stroller。它们是有默认值（而且有依据）的产品策略 ——
 *   用户不说，我们有权利替他定。把它们也设成必填，等于逼他先回答
 *   "要不要带推车"才能看到行程，那是把选择权收得太紧了。
 *   ⚠️ 代价说清楚：不常驻，不代表不用问。模型仍然会问，只是**没答也能继续**。
 *
 * ── 为什么导出它，而不是让界面自己再写一份 ──────────────────────────
 *   两个地方要用同一份清单：这里算 canGenerate，画像卡那边标"哪个格子必须填"。
 *   写两份一定会漂移，而漂移的症状是"按钮亮着、界面上却有个必填格子还空着" ——
 *   两个地方都不报错，只是互相矛盾，而且**没人会去查**。
 *   判据只能有一个来源，所以它对界面是公开的。
 */
export const REQUIRED_FIELDS: Array<keyof TravelParams> = [
  'origin',
  'city',
  'days',
  'startDate',
  'childAge',
]

/**
 * 这个格子算填上了吗。
 *
 * ⚠️ 五类"没填"都要挡住，和 `TravelParams.mergedWith` 那边是**同一套规矩**：
 *   undefined / null / 空串（模型被要求输出字符串时常用空串表示"没有"）/
 *   0 或负数 / 空数组。
 *
 * ⚠️ 特别是数字：**0 必须算没填**。参照工程的 prompt 教模型"用 0 表示未确定"，
 *    而 `days: 0` 会被 orDefaults() 补成 3 —— 一个"没答"就这样变成了"3 天"。
 *    只看 null 的话，这个错永远不会被发现。
 */
function isFilled(v: unknown): boolean {
  if (v === undefined || v === null) return false
  if (typeof v === 'string') return v.trim() !== ''
  if (typeof v === 'number') return v > 0
  if (Array.isArray(v)) return v.length > 0
  return true
}

/** 进度轮询间隔。1 秒够了：五步里最短的一步也有好几秒，而每问一次只有几十字节 */
const PROGRESS_POLL_MS = 1000

/**
 * 为这一趟生成造一个 id。
 *
 * `crypto.randomUUID` 只在**安全上下文**（https / localhost）里存在。线上是 https
 * （走穿透也是 https），但用 `http://192.168.x.x` 在局域网打开时它就没有 —— 那时降级成一个弱 id。
 * 敢降级是因为后端只拿它当 Map 的键，**不需要不可猜**：
 * 它不参与鉴权、不落库、几分钟后自动清掉；猜到它最多是看到别人一次生成的步骤名。
 */
function newRunId(): string {
  const c = globalThis.crypto
  if (c && typeof c.randomUUID === 'function') return c.randomUUID()
  return `run-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

/**
 * 把界面消息还原成契约消息 —— **把 id 摘掉再发出去**。
 *
 * 为什么不直接发 ChatBubble：那等于把"我们界面怎么渲染列表"这件事
 * 泄进了接口协议里。今天多一个 id 无所谓（Jackson 会忽略未知字段），
 * 明天多一个 `collapsed: true`、`loading: false` 呢？
 * **协议里只放对方需要的东西**，界面自己的状态自己留着。
 */
function toWire(messages: ChatBubble[]): Message[] {
  return messages.map(({ role, content }) => ({ role, content }))
}

/* ============================================================
 * 用户对行程的手动改动 —— "换一个"
 *
 * ── 为什么**只存改动**，不存一份被改过的行程 ────────────────────
 * 直觉写法是拿到 plan 之后 `useState(plan.days)`，然后在里面就地替换。
 * 那个写法有一个不会报错、但会慢慢烂掉的问题：**"后端查到的事实"和"用户做过的决定"
 * 被揉进了同一个数组**。之后每一次"这份数据到底哪来的"都答不上来 ——
 * 想重新按画像生成？被改过的那些要清掉，但清哪些？
 * 想把改动撤销？原始值已经不在了（被覆盖掉了）。
 *
 * 现在的写法是稀疏覆盖：`plan.days`（后端的，只读）+ `swaps`（用户改的，很小）。
 *   · 渲染时两者叠加（applySwaps），所以后端那份数据一个字节都不用改
 *   · 撤销 = 删掉一个键，**原始值一直在 plan 里**，不需要另存快照
 *   · 重新生成 = 换一份 plan + 清空 swaps，两件事各自独立
 *
 * 这其实就是这个工程一直在用的那条：**谁产生的状态，谁负责它**。
 * 后端产 fact，用户产 edit，界面只负责叠加 —— 中间人不要替它们做加法或减法。
 * ============================================================ */

/**
 * 用户换过的点。键是 `${day}:${index}`，值是换上去的那个备选。
 *
 * ⚠️ 键里必须带 day 和 index **两个**：只带名字不行（同一个景点在换过之后可能出现在别处），
 *    只带 poiId 更不行（那正是被换掉的东西）。而且这个键的格式和 applySwaps 是**一对**，
 *    改一个必须改另一个 —— 所以它们写在同一个文件里，不分开。
 */
export type SpotEdits = Record<string, SpotOption>

export function swapKey(day: number, index: number): string {
  return `${day}:${index}`
}

/**
 * 把用户改过的点叠到后端给的行程上。
 *
 * ⚠️ 两个字段是**故意**没跟着换的，各有各的理由：
 *
 * ① `time` 保留原值。用户点「换一个」换的是"去哪儿"，不是"几点去"。
 *    把时间也洗掉，他会发现自己刚排好的节奏被顺手抹平了 —— 而他从没要求过这件事。
 *
 * ② `tips` 置空。模型的建议是**针对某个点在某个行程里的位置**写的：
 *    「长隆野生动物世界很大，建议先坐小火车再步行」这句话搬到"广州动物园"上，
 *    就是一句读起来完全正常、但纯属编造的建议 ——
 *    **这正是这个产品最该避免的错：不是看起来很假，而是看起来很真。**
 *    所以宁可空着（界面会说明"换上的备选没有游玩建议"），也不能带一条别人的建议。
 */
export function applySwaps(days: TravelDay[], swaps: SpotEdits): TravelDay[] {
  if (Object.keys(swaps).length === 0) {
    // 没改过就**原样返回同一个数组**，不做无谓的 map ——
    // 引用不变，下游的 useMemo / 依赖比较就不会白白重算。
    return days
  }
  return days.map((d) => ({
    ...d,
    spots: d.spots.map((s, i) => {
      const swap = swaps[swapKey(d.day, i)]
      return swap ? toSwappedSpot(s, swap) : s
    }),
  }))
}

function toSwappedSpot(base: TravelSpot, swap: SpotOption): TravelSpot {
  return {
    time: base.time,
    name: swap.name,
    tips: undefined,
    poiId: swap.poiId,
    priceYuan: swap.priceYuan,
    bookUrl: swap.bookUrl,
    imageUrl: swap.imageUrl,
    lat: swap.lat,
    lng: swap.lng,
  }
}

export function usePlanChat(): UsePlanChat {
  const [messages, setMessages] = useState<ChatBubble[]>([])
  const [params, setParams] = useState<TravelParams>({})
  const [plan, setPlan] = useState<TravelPlan | null>(null)
  const [sending, setSending] = useState(false)
  const [generating, setGenerating] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [mockNotice, setMockNotice] = useState<string | null>(null)
  /** 用户手动换过的点。它和后端那份 plan 是**两个独立的状态**，渲染时才叠加（见上面那段说明） */
  const [swaps, setSwaps] = useState<SpotEdits>({})
  /** 生成进度。三种取不到的情况见 UsePlanChat.progress 的说明 */
  const [progress, setProgress] = useState<PlanProgress | null>(null)

  /* ── 坑 ①：过期闭包 ────────────────────────────────────────────
   * 下面两个 ref 是 state 的"镜像"，专门给异步流程读。
   *
   * 为什么必须这么做：函数组件每次渲染都会新建一套闭包。
   * sendRound 在 `await` 之后拿到的 messages/params，是**它被创建那一刻**的那一份 ——
   * 用户在这几十秒里如果又发了一句话（甚至只是 React 因为别的原因重渲染了），
   * 闭包里的 messages 就已经过期了。用它去拼下一次请求，就会**把新消息覆盖掉**。
   *
   * ref 不受渲染影响：`.current` 永远指向最新值。
   * 规矩：**异步流程里读状态，一律走 ref；写状态，一律走 setState 的镜像写入**
   * （见下面的 commitXxx —— 它保证两者永远一起变）。
   */
  const messagesRef = useRef<ChatBubble[]>([])
  const paramsRef = useRef<TravelParams>({})

  /* ── 坑 ②：并发闸门 ────────────────────────────────────────────
   * 一个共享的"有没有请求在飞"标志。
   *
   * 这是最开始那个 bug 的正解。当时的现象是"前一个请求没回来就能再发一个，
   * 然后新消息被吞了" —— 根因不是少了个 abort，而是**两道门没配合**：
   * 界面允许连点，而请求结果回来时又用旧快照整体覆盖了消息列表。
   *
   * 这里用闸门（而不是队列）的理由：对话是有顺序的语义，
   * 把用户连着说的两句话拆成两个并发请求，模型会分别拿到不完整的历史，
   * 第二轮的回复可能与第一轮矛盾。**宁可他等一下，也不要给他一份自相矛盾的对话。**
   * 界面侧靠 disable 按钮让"等一下"这件事可见（而不是点了没反应）。
   */
  const inFlight = useRef(false)

  /** 卸载时取消掉正在飞的请求 */
  const abortRef = useRef<AbortController | null>(null)

  /** 进度轮询的定时器。生成一结束就必须停掉 —— 不然它会一直问，问到一个刷新页面为止 */
  const pollTimer = useRef<ReturnType<typeof setInterval> | null>(null)

  /**
   * 上一拍还没回来。
   *
   * 为什么需要这个闸门：**请求偶尔会比间隔还慢**。那一拍要是照样发出去，
   * 慢的时候就会同时堆着两三个请求，而且越慢越堆 —— 而它们问的是同一件事，纯属浪费。
   * 和 sendRound 那个 inFlight 闸门是同一条规矩：**宁可少问一次，不要堆。**
   */
  const pollBusy = useRef(false)

  /**
   * 停掉轮询。
   *
   * ⚠️ 它必须定义在**卸载的 useEffect 之前** —— 那个 effect 的依赖数组会引用它，
   *    而依赖数组是在渲染时求值的：定义在后面就会撞上 const 的"先用后定义"，直接报错。
   */
  const stopPolling = useCallback(() => {
    if (pollTimer.current !== null) {
      clearInterval(pollTimer.current)
      pollTimer.current = null
    }
    pollBusy.current = false
  }, [])

  useEffect(() => {
    const unsubscribe = subscribeMockNotice(setMockNotice)
    return unsubscribe
  }, [])

  useEffect(() => {
    // 卸载时 abort：不 abort 的话，请求回来后 setState 会打到一个已经卸载的组件上
    // （React 18 之后不会再警告了，但那份响应依然是没人要的 —— 该取消就取消）
    return () => {
      // ⚠️ abort 管不住定时器。它只让"正在飞的这一拍"作废，
      //    而 setInterval 还会一拍一拍继续发下去 —— 组件都没了，它还在问后端。
      //    两件事都要做，少一个就有东西活着。
      stopPolling()
      abortRef.current?.abort()
    }
  }, [stopPolling])

  /** messages 与它的镜像一起写 —— 少写一个就会出现"界面是新的、请求是旧的" */
  const commitMessages = useCallback((next: ChatBubble[]) => {
    messagesRef.current = next
    setMessages(next)
  }, [])

  const commitParams = useCallback((next: TravelParams) => {
    paramsRef.current = next
    setParams(next)
  }, [])

  /* ── 生成进度：一边飞 /generate，一边每秒问一句"到哪了" ─────────────
   * 为什么是轮询、而不是让 /generate 流式返回？理由在后端 PlanController 顶上写着，
   * 一句话：这条产品要跑在内网穿透上，而 Cloudflare 免费隧道不支持 SSE。
   */
  const askProgress = useCallback(async (runId: string, signal: AbortSignal) => {
    if (pollBusy.current) return // 上一拍还在路上，这一拍直接放弃（见 pollBusy 的说明）
    pollBusy.current = true
    try {
      const p = await fetchPlanProgress(runId, signal)
      // null 说明后端那条记录已经不在了（这趟收场了）。**刻意不清空已有的进度** ——
      // 清掉会让面板在最后一刻闪回"一步都没走"的样子，比留着更让人困惑。
      if (!signal.aborted && p) setProgress(p)
    } catch {
      // ⚠️ 进度是观测，不是业务。它失败**绝不能**影响这次生成，也绝不能弹错 ——
      //    用户该看到的是行程（或者一个真实的生成失败），而不是"进度查询失败了"。
      //    这个空 catch 是有意的：后端在忙、或者网络抖一下，下一拍照问就是了。
    } finally {
      pollBusy.current = false
    }
  }, [])

  const startPolling = useCallback(
    (runId: string, signal: AbortSignal) => {
      stopPolling()
      // 先立刻问一次，不等第一个间隔 —— 否则头一秒面板里什么都没有，
      // 而那一秒恰恰是最需要看见"它在动"的一秒。
      void askProgress(runId, signal)
      pollTimer.current = setInterval(() => void askProgress(runId, signal), PROGRESS_POLL_MS)
    },
    [askProgress, stopPolling],
  )

  const sendRound = useCallback(
    async (text: string) => {
      const content = text.trim()
      if (!content) return
      // 闸门：已经有请求在飞就忽略这次点击。
      // ⚠️ 注意这里是"忽略"而不是"排队"，理由见上面 inFlight 的注释。
      if (inFlight.current) return

      inFlight.current = true
      setError(null)
      setSending(true)

      // 先把用户这句话落到界面上 —— 让他立刻看到自己发出去的东西，
      // 而不是盯着一个空输入框等十几秒（这是我们唯一的"即时反馈"）。
      const userMsg = bubble('user', content)
      commitMessages([...messagesRef.current, userMsg])

      const controller = new AbortController()
      abortRef.current = controller

      try {
        // ⭐ 这里读的是 **ref**，不是 state —— 就是坑 ① 的解法
        const data = await chatPlan(
          { messages: toWire(messagesRef.current), currentParams: paramsRef.current },
          controller.signal,
        )
        // 读最新的 ref 再追加，而不是用上面的快照：
        // 万一将来闸门被放开、允许并发，这一行也能保证不覆盖别的消息。
        commitMessages([...messagesRef.current, bubble('assistant', data.reply)])
        commitParams(data.params)
      } catch (e) {
        if (e instanceof ApiError && e.kind === 'aborted') return
        setError(messageOf(e))
      } finally {
        inFlight.current = false
        abortRef.current = null
        setSending(false)
      }
    },
    [commitMessages, commitParams],
  )

  const generate = useCallback(async () => {
    if (inFlight.current) return
    inFlight.current = true
    setError(null)
    setGenerating(true)
    setPlan(null)
    setProgress(null)
    // 旧行程上的手动改动必须跟着旧行程一起消失。
    // 不清的话，新行程的第 1 天第 1 个点会被上一个城市的某个备选盖掉 ——
    // 而键是 `${day}:${index}`，它在新行程里**同样成立**，所以这个错不会报错、只会显示成
    // "系统自己给我换了个景点"。（改了契约、键不再是位置相关时，这条也要跟着改。）
    setSwaps({})

    const controller = new AbortController()
    abortRef.current = controller

    // runId 必须在 /generate **发出去之前**就定下来 ——
    // 晚一步，头一两秒的进度就记不进去了，而后端那两秒正好是"在查车次"。
    const runId = newRunId()
    startPolling(runId, controller.signal)

    try {
      // 只传 params：**不要自己拼 retrieval**。
      // 检索条件该由后端按画像规划（那是它的正常产出方式），
      // 前端这里传一份自己猜的，反而会把那次规划顶掉。
      const result = await generatePlan({ params: paramsRef.current }, controller.signal, runId)
      setPlan(result)
    } catch (e) {
      if (e instanceof ApiError && e.kind === 'aborted') return
      setError(messageOf(e))
    } finally {
      stopPolling()
      inFlight.current = false
      abortRef.current = null
      setGenerating(false)
    }
  }, [startPolling, stopPolling])

  /**
   * 把某一天的第 index 个点换成 option。
   *
   * ⚠️ 这里**不做任何校验** —— 这是刻意的取舍，不是漏了：
   *    讲究版应该在这一步重跑一次规则（新点离当天其它点会不会太远、当天是不是被塞满了）。
   *    但那要一个后端接口（前端拿不到规则表和候选池的全貌，硬算会算出第二套规则）。
   *    现在让界面承担这个说明责任：清单是按"离当天其它点多近"排的，并且明说换完不重新校验。
   *    **把做不到的事说出来，比假装做了要好** —— 这和车次查不到时说"没查成"是同一条。
   */
  const swapSpot = useCallback((day: number, index: number, option: SpotOption) => {
    setSwaps((prev) => ({ ...prev, [swapKey(day, index)]: option }))
  }, [])

  /**
   * 撤销某个点的替换。
   * 注意它是"删掉一个键"，不是"从快照恢复" —— 原始值一直在 plan.days 里，从没被动过。
   */
  const restoreSpot = useCallback((day: number, index: number) => {
    setSwaps((prev) => {
      const key = swapKey(day, index)
      if (!(key in prev)) return prev
      const next = { ...prev }
      delete next[key]
      return next
    })
  }, [])

  const reset = useCallback(() => {
    stopPolling()
    abortRef.current?.abort()
    abortRef.current = null
    inFlight.current = false
    commitMessages([])
    commitParams({})
    setPlan(null)
    setSwaps({})
    setProgress(null)
    setError(null)
    setSending(false)
    setGenerating(false)
  }, [commitMessages, commitParams, stopPolling])

  /**
   * 还差哪几项必填。
   *
   * 每次渲染现算，**不做 memo**：它只过滤五个字段，代价小到可以忽略；
   * 而缓存它就得跟着 params 的引用走，白白多一处"什么时候该重算"的地方 ——
   * 那种地方正是这类小计算最容易出错、又最难发现的原因（值不该旧的时候旧了）。
   *
   * ⚠️ 判据必须是 `missingRequired.length === 0`，而不是反过来写
   *    `canGenerate = REQUIRED_FIELDS.every(...)` 再单独算一遍 —— 那样两处判据
   *    就有机会分叉，而"按钮亮着但还差一项"这种不一致不会报错。
   */
  const missingRequired = REQUIRED_FIELDS.filter((k) => !isFilled(params[k]))

  return {
    messages,
    params,
    plan,
    sending,
    generating,
    error,
    mockNotice,
    canGenerate: missingRequired.length === 0,
    missingFields: missingRequired,
    swaps,
    progress,
    sendRound,
    generate,
    swapSpot,
    restoreSpot,
    reset,
  }
}

/**
 * 把各种失败翻译成一句能给用户看的话。
 *
 * 只有这一处做翻译 —— 组件里不许再出现 `e.message`。
 * 因为 ApiError 的 message 已经是我们写给人的（见 http.ts），
 * 而**其它任何异常**（TypeError、React 内部错误）的 message 都不是给人看的。
 */
function messageOf(e: unknown): string {
  if (e instanceof ApiError) return e.message
  if (e instanceof Error) return `出了点意外：${e.message}`
  return '出了点意外，请重试'
}
