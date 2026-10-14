import { useCallback, useEffect, useRef, useState } from 'react'
import { chatPlan, generatePlan } from '../api/plan'
import { ApiError, subscribeMockNotice } from '../api/http'
import type { Message, SpotOption, TravelDay, TravelPlan, TravelParams, TravelSpot } from '../types/plan'

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
  /** 必填项齐了没有 —— 只认 city：后端没有城市就什么都做不了 */
  canGenerate: boolean
  /** 用户手动换过的点（稀疏覆盖，不是整份行程）。见下面 swaps 的说明 */
  swaps: SpotEdits
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

  useEffect(() => {
    const unsubscribe = subscribeMockNotice(setMockNotice)
    return unsubscribe
  }, [])

  useEffect(() => {
    // 卸载时 abort：不 abort 的话，请求回来后 setState 会打到一个已经卸载的组件上
    // （React 18 之后不会再警告了，但那份响应依然是没人要的 —— 该取消就取消）
    return () => {
      abortRef.current?.abort()
    }
  }, [])

  /** messages 与它的镜像一起写 —— 少写一个就会出现"界面是新的、请求是旧的" */
  const commitMessages = useCallback((next: ChatBubble[]) => {
    messagesRef.current = next
    setMessages(next)
  }, [])

  const commitParams = useCallback((next: TravelParams) => {
    paramsRef.current = next
    setParams(next)
  }, [])

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
    // 旧行程上的手动改动必须跟着旧行程一起消失。
    // 不清的话，新行程的第 1 天第 1 个点会被上一个城市的某个备选盖掉 ——
    // 而键是 `${day}:${index}`，它在新行程里**同样成立**，所以这个错不会报错、只会显示成
    // "系统自己给我换了个景点"。（改了契约、键不再是位置相关时，这条也要跟着改。）
    setSwaps({})

    const controller = new AbortController()
    abortRef.current = controller

    try {
      // 只传 params：**不要自己拼 retrieval**。
      // 检索条件该由后端按画像规划（那是它的正常产出方式），
      // 前端这里传一份自己猜的，反而会把那次规划顶掉。
      const result = await generatePlan({ params: paramsRef.current }, controller.signal)
      setPlan(result)
    } catch (e) {
      if (e instanceof ApiError && e.kind === 'aborted') return
      setError(messageOf(e))
    } finally {
      inFlight.current = false
      abortRef.current = null
      setGenerating(false)
    }
  }, [])

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
    abortRef.current?.abort()
    abortRef.current = null
    inFlight.current = false
    commitMessages([])
    commitParams({})
    setPlan(null)
    setSwaps({})
    setError(null)
    setSending(false)
    setGenerating(false)
  }, [commitMessages, commitParams])

  return {
    messages,
    params,
    plan,
    sending,
    generating,
    error,
    mockNotice,
    canGenerate: Boolean(params.city?.trim()),
    swaps,
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
