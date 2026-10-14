/**
 * 唯一的 HTTP 出口。
 *
 * 规矩：**任何组件、任何 hook 里都不许出现裸 fetch。** 全部走这里的 request()。
 *
 * 为什么这条规矩值得写死：
 *   后端约定的"统一响应"是 `{ code, message, data }`，而且 **HTTP 状态码恒为 200**
 *   （业务失败也用 200 + code 非 0 表达）。于是"这次请求到底成没成"这件事，
 *   **不能靠 res.ok 判断** —— 每个调用点都自己写一遍这段判断，
 *   就等于把同一条协议抄 N 遍，抄错一处就是"失败了却当成成功"。
 *   集中在这里，协议只有一个实现，也只有一个地方会错。
 *
 * 这一层负责且只负责四件事：
 *   ① 拼前缀、序列化请求体、发出去
 *   ② 把 `{ code, message, data }` 翻译成"要么返回 data，要么抛 ApiError"
 *   ③ 超时
 *   ④ 在开发环境按需降级到本地假数据（Mock）
 * 它**不**负责业务判断（比如"哪些字段齐了才能生成"），那是调用方的事。
 */

/** 后端统一响应。code === 0 表示成功，非 0 时 message 直接给用户看 */
export interface ApiResult<T> {
  code: number
  message: string
  data: T
}

/**
 * 失败的**种类**。比"一个 message"重要得多 —— 它决定调用方能做什么。
 *
 *   business    —— 后端明确拒绝了（code 非 0）。message 是给人看的，重试没用；
 *   network     —— 根本没连上。**唯一一种"可以降级到 Mock"的失败**；
 *   timeout     —— 连上了，但等太久。⚠️ 和 network 是**两件事**，见下面那段；
 *   badResponse —— 连上了、也回了，但内容不对（HTTP 非 200 / 不是 JSON）。多半是代理配错了；
 *   aborted     —— 调用方自己取消的（组件卸载）。这不是错误，不该弹提示。
 */
export type ApiErrorKind = 'business' | 'network' | 'timeout' | 'badResponse' | 'aborted'

export class ApiError extends Error {
  readonly kind: ApiErrorKind
  /** 业务错误码（kind === 'business' 时才有意义），否则 -1 */
  readonly code: number

  constructor(kind: ApiErrorKind, message: string, code = -1) {
    super(message)
    this.name = 'ApiError'
    this.kind = kind
    this.code = code
  }
}

const API_BASE: string = import.meta.env.VITE_API_BASE ?? '/api'

/**
 * 默认超时 180 秒 —— **不是随手写的数字，是算出来的**。
 *
 * `/generate` 正常 25~60 秒（检索 2~10 秒 + 规划检索条件 10~20 秒 + 排行程 10~30 秒）。
 *
 * ⚠️ 2026-10-13 从 120 秒提到 180 秒，因为一次生成又多了**两段城际车次查询**。
 *    为什么加这两次查询会占到这么长：单次 CLI 实测 1.3~2.7 秒，但中间要留调用间隔
 *    （上游会限流），而且被限流时的退避是 15 秒 —— 所以最坏情况两段加起来能到 40 秒
 *    （见 LegService.LEG_BUDGET_MS 的算术）。这个数字是**跟着后端预算算出来的**，
 *    哪天后端那两处的预算改了，这里要跟着改。
 *
 * ⚠️ 这里修的是一个真实的坑：之前用 60 秒，而一次正常的生成**经常超过 60 秒**。
 *    于是每次超时都会被当成"后端连不上"，然后在 auto 模式下**静默降级到 Mock** ——
 *    用户等了 60 秒，拿到一份假的行程，还以为是真的。
 *    这个 bug 最恶劣的地方在于：它看起来完全正常，而且只在"后端慢"的时候出现。
 *
 * 修法有两层（缺一不可）：
 *   ① 把超时放到足够长（现在是 180 秒）；
 *   ② **超时不再等同于"连不上"** —— 见下面 request() 里对 mock 降级的判据。
 *      只做 ① 的话，只要后端再慢一点，同样的事还会发生。
 */
const DEFAULT_TIMEOUT_MS = 180_000

/* ============================================================
 * Mock：只用于本地开发，由 VITE_USE_MOCK 控制
 *   auto （默认）—— 后端**连不上**时才降级
 *   true         —— 强制假数据
 *   false        —— 只用真后端（生产必须）
 * ============================================================ */

type MockMode = 'auto' | 'true' | 'false'

function mockMode(): MockMode {
  const raw = String(import.meta.env.VITE_USE_MOCK ?? 'auto').toLowerCase()
  if (raw === 'true') return 'true'
  if (raw === 'false') return 'false'
  return 'auto'
}

/** 当前是不是在用假数据。界面要据此显示提示 —— 不提示等于骗用户 */
let mockNotice: string | null = null
const noticeListeners = new Set<(notice: string | null) => void>()

/**
 * 订阅"正在用 Mock"的提示。
 *
 * 为什么要有它：auto 模式下的降级是**静默发生**的。如果不告诉用户，
 * 他会把假数据当成真结果 —— 而这份假数据看起来和数据一样正常。
 * **任何"悄悄地换了数据源"的行为，都必须留下痕迹。**
 */
export function subscribeMockNotice(listener: (notice: string | null) => void): () => void {
  noticeListeners.add(listener)
  listener(mockNotice)
  return () => {
    noticeListeners.delete(listener)
  }
}

function setMockNotice(notice: string | null) {
  mockNotice = notice
  noticeListeners.forEach((l) => l(notice))
}

async function useMock<T>(path: string, mock: (() => T | Promise<T>) | undefined, why: string): Promise<T> {
  if (!mock) {
    throw new ApiError('network', `没有为 ${path} 准备假数据，且当前用不了真后端`)
  }
  setMockNotice(why)
  return (await mock()) as T
}

export interface RequestOptions<T> {
  method?: 'GET' | 'POST'
  body?: unknown
  /**
   * 额外请求头。
   *
   * 眼下只有一个用处：/generate 用它带 `X-Run-Id`，好让后端把这一趟的进度登记下来。
   * 它**刻意不进请求体** —— 进度是旁路信息（"我想看它怎么跑"），不是这次生成要用的输入。
   * 塞进 body 就等于往那份被后端契约测试和前端类型两头钉着的形状里凭空加一个字段，
   * 以后没人说得清"这个字段到底受哪份测试保护"。
   */
  headers?: Record<string, string>
  /** 覆盖默认超时。只有明确知道"这个接口更快/更慢"时才传 */
  timeoutMs?: number
  /** 调用方的取消信号（组件卸载时用） */
  signal?: AbortSignal
  /** 这个接口的假数据。VITE_USE_MOCK 生效时才会被用到 */
  mock?: () => T | Promise<T>
}

/**
 * 发一次请求：成功返回 `data`，失败抛 {@link ApiError}。
 *
 * @throws ApiError 五种失败之一
 */
export async function request<T>(path: string, options: RequestOptions<T> = {}): Promise<T> {
  const mode = mockMode()

  if (mode === 'true') {
    return useMock(path, options.mock, 'VITE_USE_MOCK=true，正在用本地假数据')
  }

  try {
    const data = await realRequest<T>(path, options)
    // 一旦真后端通了，就把"正在用假数据"的提示撤掉。
    // 忘了这一步的后果：用户后来接上了后端，界面上却一直挂着那句提示。
    if (mockNotice) setMockNotice(null)
    return data
  } catch (e) {
    // ⭐ 降级的判据：只认 `network`（压根没连上）。
    //   · timeout **不降级** —— 超时往往意味着后端正在干活，只是慢
    //     （/generate 本来就要 25~60 秒）。把它当成"连不上"就会给出假行程。
    //   · business / badResponse **不降级** —— 那是后端已经明确答复了，
    //     用假数据盖掉一个真实的错误，等于把问题藏起来。
    if (mode === 'auto' && e instanceof ApiError && e.kind === 'network' && options.mock) {
      return useMock(path, options.mock, '连不上后端，已临时改用本地假数据（结果不是真的）')
    }
    throw e
  }
}

async function realRequest<T>(path: string, options: RequestOptions<T>): Promise<T> {
  const timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS
  const url = `${API_BASE}${path}`

  // 超时用 AbortController 实现，而不是 Promise.race：
  // race 只是"我们不等了"，请求其实还在发、还占着连接；
  // abort 是**真的告诉浏览器取消掉**它。前端放弃等待却让请求继续跑，
  // 会在后端留下一个没人要的重活（我们那个接口一次要花几十秒 + 一笔 token 费）。
  const controller = new AbortController()
  let timedOut = false
  const timer = setTimeout(() => {
    timedOut = true
    controller.abort()
  }, timeoutMs)

  // 外部 signal（组件卸载）也要能取消。两个 signal 合成一个传给 fetch。
  const onExternalAbort = () => controller.abort()
  options.signal?.addEventListener('abort', onExternalAbort)

  let res: Response
  try {
    res = await fetch(url, {
      method: options.method ?? 'POST',
      headers: { 'Content-Type': 'application/json', ...options.headers },
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: controller.signal,
    })
  } catch {
    // fetch 抛出的东西**区分不了**"超时"和"连不上"（都是同一个 AbortError / TypeError），
    // 所以只能靠我们自己记的 timedOut 标志来分辨 —— 这个标志必须在这里用。
    if (timedOut) {
      throw new ApiError(
        'timeout',
        `等了 ${Math.round(timeoutMs / 1000)} 秒还没等到后端响应。` +
        '它可能还在忙（生成行程本来就要几十秒），也可能卡住了 —— 可以再试一次。',
      )
    }
    if (options.signal?.aborted) {
      throw new ApiError('aborted', '请求已取消')
    }
    throw new ApiError(
      'network',
      `连不上后端（${url}）。它启动了吗？地址对吗？（开发环境看 .env.development 里的 VITE_PROXY_TARGET）`,
    )
  } finally {
    clearTimeout(timer)
    options.signal?.removeEventListener('abort', onExternalAbort)
  }

  if (!res.ok) {
    // 后端约定 HTTP 恒为 200，所以走到这里基本只有一个原因：**代理没配对**。
    // （比如 vite 把 /api 转发到了一个不存在的端口，就会拿到 502。）
    // 所以这句话要直接点出"这多半不是后端的锅"。
    throw new ApiError(
      'badResponse',
      `后端返回了 HTTP ${res.status}。按约定它应该恒为 200 —— 检查代理是否配对了（VITE_PROXY_TARGET）。`,
    )
  }

  let payload: ApiResult<T>
  try {
    payload = (await res.json()) as ApiResult<T>
  } catch {
    throw new ApiError('badResponse', '后端的响应不是合法 JSON。检查一下是不是被别的服务接走了。')
  }

  if (payload.code !== 0) {
    // 业务失败：message 是后端写给人看的，原样抛出。
    // 不在这一层加"code 2001 时改说 XX"这类映射 —— 那会让后端的文案改动影响前端。
    throw new ApiError('business', payload.message || `请求失败（code=${payload.code}）`, payload.code)
  }

  return payload.data
}
