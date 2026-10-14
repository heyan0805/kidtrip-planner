import { useEffect, useRef, useState } from 'react'
import { REQUIRED_FIELDS, applySwaps, swapKey, usePlanChat } from '../../hooks/usePlanChat'
import type { SpotEdits } from '../../hooks/usePlanChat'
import type {
  PlanProgress,
  PlanProgressStep,
  SpotOption,
  Transfer,
  TravelDay,
  TravelParams,
  TravelPlan,
  TravelSpot,
} from '../../types/plan'

/**
 * 对话页 —— B5 的可见部分。
 *
 * 它刻意做得很朴素：这一轮要验证的是**上下文传递**（历史 + 画像能不能一轮轮攒起来），
 * 不是视觉。所以每个元素都对应一个真实的状态，没有装饰。
 *
 * 信息结构就是 usePlanChat 里那两份状态的投影：
 *   左边（中间那块）—— messages：正在发生什么
 *   右边（画像卡）  —— params：目前为止攒出了什么
 * 这两个东西并排显示，是为了让"上下文"这件事**看得见**：
 * 你能看到自己聊了 5 轮之后，画像里哪些格子被填上了、哪些还是"待确认"。
 */
export default function TravelPlanner() {
  const chat = usePlanChat()
  const [draft, setDraft] = useState('')
  const listRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  /** 上一帧的 busy —— 用来识别"忙 → 不忙"这个**边沿**，见下面那个 effect */
  const wasBusy = useRef(false)

  // 新消息进来时滚到底 —— 否则用户看不到自己刚说的话（消息在下面）
  useEffect(() => {
    const el = listRef.current
    if (el) el.scrollTop = el.scrollHeight
  }, [chat.messages.length, chat.sending])

  const busy = chat.sending || chat.generating

  /**
   * 一轮结束后，把光标送回输入框。
   *
   * 为什么值得写：这一轮结束之后，用户接下来唯一想做的事就是**接着打字**
   * （继续回答问题，或者对刚生成的行程提要求）。光标不在那儿，他每轮都要先点一下，
   * 而这一下没有任何信息量。
   *
   * ⚠️ 判据是"从忙到不忙"这个边沿，不是"不忙就聚焦"。
   *    写成后者的话，每次渲染都会抢一次焦点 —— 用户想在页面上选一段文字复制都会被弹回来，
   *    那比原来的问题更烦人。
   *
   * ⚠️ `preventScroll`：默认的 focus() 会把视口滚到输入框。而"生成完成"那一刻用户
   *    很可能正在往下看刚出来的行程，一滚就把视线拉走了。这里只要光标，不要滚动。
   *
   * ⚠️ 刻意**不做** autoFocus（首屏自动聚焦）：手机上一进页面就弹键盘，而这台设备
   *    未必是用户第一眼想打字的地方。用户的原话也是"AI 回答完后"。
   */
  useEffect(() => {
    if (wasBusy.current && !busy) inputRef.current?.focus({ preventScroll: true })
    wasBusy.current = busy
  }, [busy])

  async function submit() {
    const text = draft
    setDraft('')
    await chat.sendRound(text)
  }

  return (
    <div className="min-h-screen bg-gray-50">
      <div className="mx-auto max-w-5xl px-4 py-8">
        <header className="flex items-baseline justify-between">
          <h1 className="text-xl font-semibold text-gray-900">童行 · 带娃旅游规划器</h1>
          <button
            type="button"
            onClick={chat.reset}
            disabled={busy || (chat.messages.length === 0 && !chat.plan)}
            className="text-sm text-gray-500 hover:text-gray-800 disabled:opacity-40"
          >
            重新开始
          </button>
        </header>

        {/* Mock 提示。必须显眼 —— 不提示等于把假数据当成真结果给用户 */}
        {chat.mockNotice && (
          <div className="mt-4 rounded-lg border border-amber-300 bg-amber-50 px-4 py-2 text-sm text-amber-900">
            ⚠️ {chat.mockNotice}
          </div>
        )}

        <div className="mt-6 grid gap-6 md:grid-cols-[1fr_320px]">
          {/* ── 左：对话区 ────────────────────────────────── */}
          <section className="flex min-h-[520px] flex-col rounded-xl border border-gray-200 bg-white">
            <div ref={listRef} className="flex-1 space-y-3 overflow-y-auto p-4">
              {chat.messages.length === 0 && (
                <p className="text-sm text-gray-400">
                  比如说：「想带孩子去珠海玩 3 天，孩子 6 岁」—— 我会一轮轮把信息问清楚。
                </p>
              )}
              {chat.messages.map((m) => (
                // key 用消息自己的 id 而不是数组下标：列表是追加式的，
                // 但下标 key 在"前面插入/删除"时会错位到别的消息上（React 会复用错的 DOM）
                <div key={m.id} className={m.role === 'user' ? 'text-right' : 'text-left'}>
                  <span
                    className={
                      'inline-block max-w-[85%] whitespace-pre-wrap rounded-2xl px-3 py-2 text-sm ' +
                      (m.role === 'user'
                        ? 'bg-blue-600 text-white'
                        : 'bg-gray-100 text-gray-800')
                    }
                  >
                    {m.content}
                  </span>
                </div>
              ))}
              {chat.sending && <p className="text-xs text-gray-400">正在想…</p>}
            </div>

            {chat.error && (
              <div className="mx-4 mb-2 rounded-lg border border-red-300 bg-red-50 px-3 py-2 text-sm text-red-800">
                {chat.error}
              </div>
            )}

            <div className="flex gap-2 border-t border-gray-200 p-3">
              <input
                ref={inputRef}
                value={draft}
                onChange={(e) => setDraft(e.target.value)}
                onKeyDown={(e) => {
                  // 中文输入法在组词过程中也会触发 Enter（keyCode 229），
                  // 不挡的话会出现"字还没选完就发出去了"。isComposing 就是给这个用的。
                  if (e.key === 'Enter' && !e.nativeEvent.isComposing) {
                    e.preventDefault()
                    void submit()
                  }
                }}
                placeholder={busy ? '请稍等这一轮结束…' : '说说你的计划'}
                disabled={busy}
                className="flex-1 rounded-lg border border-gray-300 px-3 py-2 text-sm outline-none focus:border-blue-500 disabled:bg-gray-50"
              />
              <button
                type="button"
                onClick={() => void submit()}
                // 忙碌时禁用，而不是"点了没反应" —— 闸门在 hook 里，这里要让它可见
                disabled={busy || !draft.trim()}
                className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:bg-gray-300"
              >
                发送
              </button>
            </div>
          </section>

          {/* ── 右：画像卡 ────────────────────────────────── */}
          <aside className="space-y-4">
            <ProfileCard params={chat.params} missing={chat.missingFields} />

            <button
              type="button"
              onClick={() => void chat.generate()}
              disabled={!chat.canGenerate || busy}
              className="w-full rounded-lg bg-gray-900 px-4 py-2.5 text-sm font-medium text-white hover:bg-gray-800 disabled:bg-gray-300"
            >
              {chat.generating ? '正在生成…' : '生成行程'}
            </button>
            {!chat.canGenerate && (
              // 点不了的时候必须说清**为什么**。一个灰着的按钮、不写原因，
              // 用户只会以为界面坏了，而不是回去把信息补上 ——
              // 而这恰好是他唯一能做的动作。
              <p className="text-xs text-amber-700">
                还差{missingLabels(chat.missingFields)} —— 这几项各自直接决定查什么、怎么排。
              </p>
            )}
            {/* 原来这里是一句"这一步要真的去查飞猪、再让模型排期，25~60 秒是正常的"。
                它被这张面板取代了 —— 那句话在解释"为什么这么久"，
                而面板直接把正在走的每一步摊开。**解释不如让人看见。**

                放在按钮正下方，是为了不用滚动就能看到它：
                用户的眼睛刚点完这个按钮，进度就该出现在他正在看的地方。 */}
            {chat.generating && <GeneratingPanel progress={chat.progress} />}
          </aside>
        </div>

        {chat.plan && (
          <PlanView
            plan={chat.plan}
            swaps={chat.swaps}
            onSwap={chat.swapSpot}
            onRestore={chat.restoreSpot}
          />
        )}
      </div>
    </div>
  )
}

/* ============================================================
 * 画像卡 —— 八个格子的当前状态
 * ============================================================ */

/**
 * 一个字段的描述。order 决定显示顺序，**问东西的顺序也应该和它一致**。
 *
 * ⚠️ origin 排在 city 前面，不是排版偏好：先问"从哪儿走"再问"去哪儿玩"，
 *    是用户脑子里本来就有的顺序（家 → 目的地）。顺序反了，模型会先问目的地，
 *    用户答了一个城市，下一轮才被问出发地 —— 而他刚才那句话里其实已经带上了。
 *
 * ⚠️ 这里**不重复声明**哪些是必填 —— 那是 `REQUIRED_FIELDS` 的事（唯一的判据来源）。
 *    这份清单只负责"有哪些字段、怎么叫、按什么顺序显示"。
 */
const FIELDS: Array<{ key: keyof TravelParams; label: string }> = [
  { key: 'origin', label: '出发地' },
  { key: 'city', label: '目的地' },
  { key: 'days', label: '天数' },
  { key: 'startDate', label: '出发日期' },
  { key: 'childAge', label: '孩子年龄' },
  { key: 'preferences', label: '偏好' },
  { key: 'pace', label: '节奏' },
  { key: 'stroller', label: '推车' },
]

/**
 * 把缺的 key 说成人话："出发地、孩子年龄"。
 *
 * 顺序按 FIELDS 取（也就是提问顺序），不是按 missing 数组的顺序 ——
 * 这样读起来和用户刚才被问的顺序一致，而不是一个跳来跳去的序列。
 */
function missingLabels(missing: Array<keyof TravelParams>): string {
  if (missing.length === 0) return ''
  return FIELDS.filter((f) => missing.includes(f.key))
    .map((f) => f.label)
    .join('、')
}

function ProfileCard({
  params,
  missing,
}: {
  params: TravelParams
  /** 还空着的**必填**项。只有必填项会出现在这里，所以它同时承担"这格必须填"这个信息 */
  missing: Array<keyof TravelParams>
}) {
  return (
    <div className="rounded-xl border border-gray-200 bg-white p-4">
      <h2 className="text-sm font-medium text-gray-900">已收集的信息</h2>
      <p className="mt-1 text-xs text-gray-400">这就是"上下文"里被提炼出来的那部分</p>
      <dl className="mt-3 space-y-2">
        {FIELDS.map((f) => {
          // ⭐ 必填项还空着时，在**这个格子本身**上说清楚 ——
          //    它是"生成按钮为什么点不了"在页面上的答案，
          //    而答案必须落在用户正在看的那个位置，不能只在按钮下面写一行小字。
          const required = REQUIRED_FIELDS.includes(f.key)
          const blank = missing.includes(f.key)
          return (
            <div key={f.key} className="flex items-start justify-between gap-3 text-sm">
              <dt className="text-gray-500">
                {f.label}
                {required && (
                  <span className="ml-0.5 text-amber-600" title="必填">
                    *
                  </span>
                )}
              </dt>
              <dd className={'text-right font-medium ' + (blank ? 'text-amber-600' : 'text-gray-900')}>
                {renderField(f.key, params)}
              </dd>
            </div>
          )
        })}
      </dl>
      {/* 这句解释是必要的：不然"为什么有的格子带星、有的不带"就成了一个要猜的事。
          同时它把"不填也能继续"明说出来 —— 隐含的可选项等于没有可选项。 */}
      <p className="mt-3 text-[11px] leading-snug text-gray-400">
        带 * 的五项必须填 —— 它们各自直接决定查什么、怎么排。
        其余不想说就留空，系统按带娃出行的常规替你定。
      </p>
    </div>
  )
}

function renderField(key: keyof TravelParams, p: TravelParams): string {
  if (key === 'stroller') {
    // ⭐ 三态：**"还没问过"绝不能显示成"不需要"**。
    //    这一行是整个设计里最容易被写错的地方 ——
    //    写成 `p.stroller ? '需要' : '不需要'` 就会把"待确认"显示成"不需要"，
    //    用户以为系统已经知道了，其实从没问过。
    if (p.stroller === undefined || p.stroller === null) return '待确认'
    return p.stroller ? '需要' : '不需要'
  }
  const v = p[key]
  if (v === undefined || v === null || v === '') return '待确认'
  if (Array.isArray(v)) return v.length > 0 ? v.join('、') : '待确认'
  if (key === 'days') return `${v} 天`
  if (key === 'childAge') return `${v} 岁`
  return String(v)
}

/* ============================================================
 * 生成中 —— 让那几十秒看得见
 * ============================================================ */

/**
 * 生成行程时的面板。它替代的是原来那两句话。
 *
 * ── 原来那里是什么 ────────────────────────────────────────────
 *   一句「正在生成（要几十秒）…」加一句「这一步要真的去查飞猪、再让模型排期，25~60 秒是正常的」。
 *   那是全流程体验最差的一段：用户没有任何办法判断系统是在干活，还是已经卡死了。
 *   **解释"为什么慢"，永远比不上让人看见它在动。**
 *
 * ── 三个刻意的取舍 ────────────────────────────────────────────
 *
 * ① **没有百分比，也没有一条会走到头的进度条。**
 *    五步的耗时完全不可预测（模型那两段在 10~30 秒之间浮动），
 *    任何"已完成 68%"都只能靠编 —— 而这个产品最不能出的错就是"看起来很像真的、其实是编的"。
 *    所以这里只有一条**不确定态的扫光**，它的全部含义是"在动，但不知道还要多久"。
 *    而已经走完的步骤、以及它们真实花了多少，照实显示 —— 那一部分是真的。
 *
 * ② **计时在前端自己算，不读后端的 elapsedMs。**
 *    两者说的是同一件事，但前端每 0.5 秒自己跳一次、不用等轮询；
 *    而轮询只要抖一下，读后端的数字就会**卡住不动** —— 那恰好是最像死机的样子。
 *    从这里挂载那刻（≈ 点下按钮那刻）开始算，也才是用户真正感受到的等待时间。
 *
 * ③ **拿不到明细时也要有东西可看。** 轮询失败（后端忙、网络抖）不是错误，
 *    面板退化成"一条扫光 + 一个真的在走的秒表"。这比一片空白好得多。
 */
function GeneratingPanel({ progress }: { progress: PlanProgress | null }) {
  const [elapsedMs, setElapsedMs] = useState(0)

  useEffect(() => {
    const startedAt = Date.now()
    const t = setInterval(() => setElapsedMs(Date.now() - startedAt), 500)
    return () => clearInterval(t)
  }, [])

  const steps = progress?.steps ?? []

  return (
    <div className="rounded-xl border border-blue-200 bg-blue-50/60 p-3">
      <div className="flex items-baseline justify-between">
        <span className="text-xs font-medium text-gray-900">正在生成行程</span>
        <span className="text-xs tabular-nums text-gray-500">
          已用 {Math.floor(elapsedMs / 1000)} 秒
        </span>
      </div>

      {/* 不确定态扫光。它不知道终点在哪 —— 这正是我们要表达的意思，不是没做完 */}
      <div className="mt-2 h-1 overflow-hidden rounded-full bg-blue-100">
        <div className="h-full w-1/3 animate-sweep rounded-full bg-blue-500" />
      </div>

      {steps.length > 0 ? (
        <ol className="mt-3 space-y-1.5">
          {steps.map((s) => (
            <StepLine key={s.key} step={s} />
          ))}
        </ol>
      ) : (
        <p className="mt-3 text-xs text-gray-500">正在查飞猪，这一步要几十秒。</p>
      )}

      <p className="mt-2 text-[11px] leading-snug text-gray-400">
        每步要花多久事先不知道，所以这里只说走到哪一步，不报百分比。
      </p>
    </div>
  )
}

/**
 * 面板里的一步。
 *
 * 四种状态**各有各的形状**（打勾 / 乘号 / 实心呼吸点 / 空心点），不靠颜色单独区分 ——
 * 只靠颜色的话，色弱的人看到的就是"四个灰点里有一个稍微不同"。
 */
function StepLine({ step }: { step: PlanProgressStep }) {
  return (
    <li className="flex items-center gap-2 text-xs">
      <span className="flex h-3.5 w-3.5 shrink-0 items-center justify-center leading-none">
        {step.state === 'DONE' && <span className="text-green-600">✓</span>}
        {step.state === 'FAILED' && <span className="text-red-600">✕</span>}
        {step.state === 'RUNNING' && (
          <span className="h-2 w-2 animate-breathe rounded-full bg-blue-600" />
        )}
        {step.state === 'PENDING' && (
          <span className="h-2 w-2 rounded-full border border-gray-300" />
        )}
      </span>

      <span
        className={
          step.state === 'RUNNING'
            ? 'truncate font-medium text-gray-900'
            : step.state === 'DONE'
              ? 'truncate text-gray-500'
              : 'truncate text-gray-400'
        }
      >
        {step.label}
      </span>

      {/* tookMs 为 null == 还没走完。**不能写成 `!step.tookMs`** —— 那样"耗时 0.0 秒"也会被当成没走完 */}
      <span className="ml-auto shrink-0 tabular-nums text-gray-400">
        {step.tookMs !== null
          ? formatSeconds(step.tookMs)
          : step.state === 'RUNNING'
            ? '进行中'
            : ''}
      </span>
    </li>
  )
}

/**
 * 毫秒 → 人能读的时长。
 *
 * 为什么给"不到 0.1 秒"单独一条：有的步骤（校验行程）真的是毫秒级的，
 * 显示成 "0.0 秒" 会让人以为那一步压根没做 —— 而它做了。
 * **"很快"和"没做"是两件事**，尤其在这套行程要拿去订酒店的场景里。
 */
function formatSeconds(ms: number): string {
  if (ms < 100) return '不到 0.1 秒'
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)} 秒`
  const m = Math.floor(ms / 60_000)
  return `${m} 分 ${Math.round((ms % 60_000) / 1000)} 秒`
}

/* ============================================================
 * 行程展示
 * ============================================================ */

function PlanView({
  plan,
  swaps,
  onSwap,
  onRestore,
}: {
  plan: TravelPlan
  swaps: SpotEdits
  onSwap: (day: number, index: number, option: SpotOption) => void
  onRestore: (day: number, index: number) => void
}) {
  // ⭐ 用户换过的点在这里叠上去。**plan 本身一个字节都不改** ——
  //   它是后端查回来的事实，"用户改了什么"是另一件事（见 usePlanChat 里那段说明）。
  const days = applySwaps(plan.days, swaps)

  // 把相邻的同一家酒店合并成"连住 N 晚"。
  // 为什么不直接列三遍：**重复是酒店的常态，不是异常** ——
  // 带娃旅行就该少换酒店，所以界面上要把它呈现成"一个决定"，而不是三张一样的卡片。
  const stays = mergeStays(days)
  const swapCount = Object.keys(swaps).length

  return (
    <section className="mt-6 space-y-4">
      <div className="rounded-xl border border-gray-200 bg-white p-4">
        <h2 className="text-base font-medium text-gray-900">{plan.summary}</h2>
        <p className="mt-2 text-xs text-gray-500">
          查了 {plan.provenance.searchRounds} 轮飞猪 · 命中 {plan.provenance.candidatesFound} 条 ·
          可用 {plan.provenance.candidatesKept} 条 · 耗时 {(plan.provenance.elapsedMs / 1000).toFixed(1)} 秒
        </p>
        {swapCount > 0 && (
          <p className="mt-2 text-xs text-blue-700">
            你手动换过 {swapCount} 个点 —— 每个换过的点旁边都有「恢复原样」
          </p>
        )}
      </div>

      {plan.warnings.length > 0 && (
        <div className="rounded-xl border border-amber-300 bg-amber-50 p-4">
          <h3 className="text-sm font-medium text-amber-900">注意事项</h3>
          <ul className="mt-2 list-disc space-y-1 pl-5 text-sm text-amber-900">
            {plan.warnings.map((w, i) => (
              <li key={i}>{w}</li>
            ))}
          </ul>
        </div>
      )}

      {days.map((d, i) => (
        <DayCard
          key={d.day}
          day={d}
          stay={stays.get(d.day)}
          isLast={i === days.length - 1}
          // ⭐ 备选清单**每天算一次**（不是每个点算一次）：
          //    它只依赖"这一天的中心"和"整份行程用了哪些点"，这两个和"你在换哪一个"无关。
          //    所以同一天里每个点看到的清单是一样的 —— 这是对的：
          //    问的是"这一天附近还能去哪儿"，不是"这个点的替代品是什么"。
          alternatives={alternativesFor(d, days, plan.spotPool)}
          swaps={swaps}
          onSwap={onSwap}
          onRestore={onRestore}
        />
      ))}

      {/* 账本：真实发出去的查询命令。用户有权看见"这份东西是怎么来的" */}
      <details className="rounded-xl border border-gray-200 bg-white p-4">
        <summary className="cursor-pointer text-sm font-medium text-gray-900">
          这份行程是怎么来的（{plan.provenance.queries.length} 次查询）
        </summary>
        <div className="mt-3 space-y-2 text-xs text-gray-600">
          <p>
            检索条件：类别{' '}
            {plan.provenance.retrievalPlan.wantedCategories.length > 0
              ? plan.provenance.retrievalPlan.wantedCategories.join('、')
              : '（不限，走宽查）'}
            ，要酒店 {plan.provenance.retrievalPlan.needHotel ? '是' : '否'}
          </p>
          <ul className="space-y-1 font-mono">
            {plan.provenance.queries.map((q, i) => (
              <li key={i} className="break-all rounded bg-gray-50 px-2 py-1">{q}</li>
            ))}
          </ul>
          {/* 备选池的大小也报出来 —— 它是"这次查询的产出真的被用起来了"最直接的证据：
              查回来 41 个，行程里只用掉 10 个，剩下 31 个现在用户可以自己拿去换。 */}
          <p>
            这次查到 <strong>{plan.spotPool.length}</strong> 个可用景点 ——
            排进日程的之外，剩下的都在每个点下面的「换一个」里
          </p>
          {plan.provenance.rejected.length > 0 && (
            <div>
              <p className="mt-2">被筛掉的（{plan.provenance.rejected.length} 条）：</p>
              <ul className="mt-1 space-y-1">
                {plan.provenance.rejected.map((r, i) => (
                  <li key={i}>
                    {r.name} —— {r.reason}
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      </details>
    </section>
  )
}

function DayCard({
  day,
  stay,
  isLast,
  alternatives,
  swaps,
  onSwap,
  onRestore,
}: {
  day: TravelDay
  stay?: string
  isLast: boolean
  alternatives: Alternative[]
  swaps: SpotEdits
  onSwap: (day: number, index: number, option: SpotOption) => void
  onRestore: (day: number, index: number) => void
}) {
  return (
    <article className="rounded-xl border border-gray-200 bg-white p-4">
      <h3 className="text-sm font-medium text-gray-900">
        第 {day.day} 天 · {day.theme}
      </h3>

      <ul className="mt-3 space-y-2">
        {day.spots.map((s, i) => (
          <SpotRow
            // key 用 poiId 而不是下标：换一个点之后它必须**重新挂载**，
            // 否则"换一个"展开的那个列表会跟着旧点留在屏幕上 —— 而内容已经变了。
            key={s.poiId}
            spot={s}
            swapped={swapKey(day.day, i) in swaps}
            alternatives={alternatives}
            onPick={(o) => onSwap(day.day, i, o)}
            onRestore={() => onRestore(day.day, i)}
          />
        ))}
        {day.spots.length === 0 && <li className="text-sm text-gray-400">这天还没排上点</li>}
      </ul>

      <div className="mt-3 border-t border-dashed border-gray-200 pt-3 text-sm">
        {/* 交通排在住宿前面：它发生在"到"之前，而且它是这份行程里**唯一**能直接回答
            "这段路孩子受不受得了"的一行。 */}
        {day.transfer && <TransferLine transfer={day.transfer} />}

        {/* 同一段路的**其它走法**（目前只可能是飞机）。排在主选下面、住宿上面 ——
            它讲的还是"怎么到"这件事，只是另一个答案。

            ⚠️ 契约上 `alternatives` 永远非 null（后端没有就给空数组），所以 `?? []` 看起来是多余的。
            它不是 —— 2026-10-14 实测撞到过：**前端静态包与后端 jar 不是同一版**时
            （这里就是"新前端 + 改前的 8003"），旧后端根本不发这个字段，
            于是 `day.alternatives.map` 抛 `Cannot read properties of undefined (reading 'map')`，
            React 把**整份行程**卸载掉 —— 不是少一行机票，是白屏。
            少一个新字段变成整页打不开，是这套东西最差的失效方式（现场演示时尤其如此）。
            ⚠️ 但这只是**一处**兜底，不是通用兼容层：比如 `mode` 缺失不会崩，
            它只会画成 🚄 —— 静默画错。真正的解法是前后端同版本部署，
            这个 `??` 只负责把"最坏结果"从白屏降成"少一行"。
            （这条和 `day.transfer &&` 是同一个位置上的同一条规矩。） */}
        {(day.alternatives ?? []).map((alt) => (
          <AlternativeLine
            // key 用班次号 + 时刻：同一条走法在两段路里出现时（理论上不会），
            // 只靠班次号会撞 key，而 React 的 key 重复是**静默**的错。
            key={`${alt.mode}-${alt.serviceNo ?? ''}-${alt.depDateTime}`}
            transfer={alt}
          />
        ))}

        {day.hotel ? (
          <p className="text-gray-700">
            🏨 {day.hotel.name}
            <span className="ml-2 text-gray-500">
              {/* 酒店这里是**每晚房价**，和上面的门票不是一个量纲 */}
              {day.hotel.priceYuan === null ? '房价未知' : `¥${day.hotel.priceYuan}/晚`}
            </span>
            {day.hotel.star && <span className="ml-2 text-gray-400">{day.hotel.star}</span>}
            {stay && <span className="ml-2 text-gray-400">{stay}</span>}
          </p>
        ) : (
          /* ⚠️ hotel === null 有两种完全不同的含义，必须分开显示：
           *    最后一天 → 当天返程（正常，不用多说）
           *    其它天   → 这次没查到酒店数据（异常，要说出来，否则用户以为系统忘了）
           *  写成一个统一的"无住宿"会让前者显得像故障、后者显得像安排。 */
          <p className="text-gray-400">
            {isLast ? '当天返程，不需要住宿' : '这天还没有住宿安排'}
          </p>
        )}
        {day.hotel?.tips && <p className="mt-1 text-xs text-gray-500">{day.hotel.tips}</p>}
      </div>
    </article>
  )
}

/**
 * 行程里的一个点 —— 加了「换一个」的那个版本。
 *
 * ── 这一小段代码是这个产品从"一张海报"变成"一个工具"的地方 ──────────
 *   在这之前，一份行程出来之后用户能做的只有"看"或者"从头再来"。
 *   而家长真实的动作是："这个点孩子不感兴趣换一个""三天都是长隆，换掉一天"。
 *   池子里本来就有几十个合格候选、实际只用掉十来个 ——
 *   那些剩下来的，以前**一次上游调用都不花**却没人用得上。
 *
 * ── 三个刻意的取舍 ──────────────────────────────────────────────
 *
 * ① **换上的备选没有 tips，而且界面要明说**（见下面那段灰字）。
 *    模型的建议是按"某个点在某个行程里的位置"写的，搬过来就是编造，
 *    而且它读起来完全正常 —— 那是这个产品最该怕的错。
 *
 * ② **换完不重新校验行程**，这句话必须写在界面上。
 *    讲究版应该重跑一次规则（新点离当天其它点会不会太远），但那要一个后端接口
 *    （前端硬算会算出第二套规则）。做不到就说出来 ——
 *    和车次查不到时说"没查成"、不能说成"没有车次"是同一条。
 *    给用户的补偿是排序：清单按"离这一天其它点多近"排，最近的在最上面。
 *
 * ③ **「恢复原样」只在换过的点上出现。** 它不需要任何快照 ——
 *    原始值一直在后端那份 plan 里（我们只存了稀疏覆盖），所以删掉一个键就回到原状。
 */
function SpotRow({
  spot,
  swapped,
  alternatives,
  onPick,
  onRestore,
}: {
  spot: TravelSpot
  /** 这个点是用户换过的（不是模型排的） */
  swapped: boolean
  alternatives: Alternative[]
  onPick: (o: SpotOption) => void
  onRestore: () => void
}) {
  const [open, setOpen] = useState(false)

  return (
    <li className="text-sm">
      <div className="flex gap-3">
        <span className="w-12 shrink-0 text-gray-400">{spot.time}</span>
        <span className="flex-1">
          <span className="text-gray-900">{spot.name}</span>
          {/* ⚠️ priceYuan 为 null 是"没有可售的票"，不是"免费" —— 所以写"票价未知" */}
          <span className="ml-2 text-gray-500">
            {spot.priceYuan === null ? '票价未知' : `¥${spot.priceYuan}`}
          </span>
          {swapped && (
            <span className="ml-2 rounded bg-blue-50 px-1.5 py-0.5 text-xs text-blue-700">
              换上的备选
            </span>
          )}
          {spot.tips ? (
            <span className="mt-1 block text-xs text-gray-500">{spot.tips}</span>
          ) : swapped ? (
            <span className="mt-1 block text-xs text-gray-400">
              换上的备选没有游玩建议 —— 原来那句是按上一个点写的，留着会误导
            </span>
          ) : null}
        </span>
      </div>

      <div className="mt-1 flex items-center gap-3 pl-[60px] text-xs">
        <button
          type="button"
          onClick={() => setOpen((v) => !v)}
          disabled={alternatives.length === 0}
          className="rounded border border-gray-300 px-2 py-0.5 text-gray-600 hover:border-gray-400 hover:text-gray-900 disabled:border-gray-200 disabled:text-gray-300"
        >
          {alternatives.length === 0
            ? '没有别的可选了'
            : `换一个（这次查到的还有 ${alternatives.length} 个）`}
        </button>
        {swapped && (
          <button
            type="button"
            onClick={onRestore}
            className="text-gray-400 underline-offset-2 hover:text-gray-700 hover:underline"
          >
            恢复原样
          </button>
        )}
      </div>

      {open && alternatives.length > 0 && (
        <div className="mt-2 pl-[60px]">
          <p className="mb-1 text-xs text-gray-400">
            这次查到、还没用上的景点，按离这一天其它点的远近排的 ——
            <span className="font-medium">换完不会重新校验行程</span>，所以别选太远的
          </p>
          <ul className="max-h-60 space-y-0.5 overflow-y-auto rounded-lg border border-gray-200 bg-gray-50 p-1.5">
            {alternatives.map((a) => (
              <li key={a.option.poiId}>
                <button
                  type="button"
                  onClick={() => {
                    onPick(a.option)
                    setOpen(false)
                  }}
                  className="w-full rounded px-2 py-1 text-left text-xs hover:bg-white"
                >
                  <span className="text-gray-900">{a.option.name}</span>
                  <span className="ml-2 text-gray-500">
                    {a.option.priceYuan === null ? '票价未知' : `¥${a.option.priceYuan}`}
                  </span>
                  {a.option.category && <span className="ml-2 text-gray-400">{a.option.category}</span>}
                  {a.km !== null && <span className="ml-2 text-gray-400">{formatKm(a.km)}</span>}
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}
    </li>
  )
}

/**
 * 一个可以换上的备选，外加"它离这一天的中心有多远"。
 *
 * km 为 null 的含义是**算不出来**（有一边没有坐标），不是"很近"也不是"很远" ——
 * 所以它排最后，而不是排最前。把"不知道"当成"很近"是这里最容易犯的错。
 */
interface Alternative {
  option: SpotOption
  km: number | null
}

/** 只要经纬度两个字段的东西都能算距离 —— TravelSpot / SpotOption / 中心点自己都用它 */
interface Coords {
  lat: number | null
  lng: number | null
}

const EARTH_RADIUS_KM = 6371

/**
 * 「换一个」的候选清单：当前**还没被用上**的合格景点，按离这一天近不近排。
 *
 * ⚠️ "还没被用上"算的是**整份行程**，不是这一天。因为一个景点在整份行程里
 *    只能出现一次（PlanAssembler 专门去重过，重复是 bug）——
 *    只按当天过滤的话，换出来的点会和后面某一天撞上，而那是行程里最明显的一种错。
 *
 * ⚠️ 锚点是**这一天已排点的中心**（不是"被换掉那个点的位置"）。
 *    两者的区别在一天有 3 个点时看得出来：用中心，问的是"这一天附近还有哪儿能去"；
 *    用被换的那个点，问的就变成了"这个点的替代品在哪"。
 *    前者更接近用户点下「换一个」时脑子里在想的事。
 *
 * ⚠️ 这是**展示顺序**，不是规则。真正硬性的"相邻两点不能超过 15 公里"在后端
 *    RuleChecker 里判 —— 前端不许有第二份规则，否则改一处就会不一致。
 */
function alternativesFor(day: TravelDay, days: TravelDay[], pool: SpotOption[]): Alternative[] {
  const used = new Set(days.flatMap((d) => d.spots.map((s) => s.poiId)))
  const anchor = centroid(day.spots.length > 0 ? day.spots : days.flatMap((d) => d.spots))

  return pool
    .filter((o) => !used.has(o.poiId))
    .map((o) => ({ option: o, km: anchor === null ? null : distanceKm(o, anchor) }))
    // 距离算不出来的排最后（null → Infinity），**不是排最前** —— 理由见 Alternative 那段。
    // sort 是稳定的（ES2019 起），所以距离相同时保持飞猪给的相关性顺序，不会乱跳。
    .sort((a, b) => (a.km ?? Infinity) - (b.km ?? Infinity))
}

/**
 * 两点之间的大致距离（公里）。**只用来排序和显示，不是规则。**
 *
 * 用等距圆柱近似而不是 haversine：城市内几十公里的量级下两者差不到千分之一，
 * 而这里根本没有需要精确的地方 —— 真正要判"这一天跨太远"的是后端 `plan/Geo`，
 * 它用的就是**这一套公式**（连 6371 都一样），所以清单的排序和后端的判断不会打架。
 * 前端再实现一遍"正经的"距离反而危险：同一件事有两份实现，改一处就会不一致。
 *
 * ⚠️ 缺坐标是 **null 不是 0**（契约里那条"0 是几内亚湾"）。
 *    所以必须显式判 null。写成 `a.lat && b.lat` 看起来一样能用，
 *    但纬度为 0 的地方（就在赤道上）会被当成缺值。
 */
function distanceKm(a: Coords, b: Coords): number | null {
  if (a.lat === null || a.lng === null || b.lat === null || b.lng === null) return null
  const rad = Math.PI / 180
  const dLat = (b.lat - a.lat) * rad
  // 经度方向要按纬度收缩：同样的经度差，在广州（23°N）对应的实际距离只有赤道的 92%
  const dLng = (b.lng - a.lng) * rad * Math.cos(((a.lat + b.lat) / 2) * rad)
  return EARTH_RADIUS_KM * Math.hypot(dLat, dLng)
}

/** 一组点的中心。一个点都没有（或全都没坐标）时回 null —— 不给一个"假的原点" */
function centroid(spots: Coords[]): Coords | null {
  let lat = 0
  let lng = 0
  let n = 0
  for (const s of spots) {
    if (s.lat === null || s.lng === null) continue
    lat += s.lat
    lng += s.lng
    n++
  }
  return n === 0 ? null : { lat: lat / n, lng: lng / n }
}

/** 不到 1 公里就直说 —— 写 "0.3 公里" 会让这个数字看起来比它实际有的更可信 */
function formatKm(km: number): string {
  return km < 1 ? '不到 1 公里' : `约 ${km.toFixed(1)} 公里`
}

/**
 * 城际交通那一行 —— **这个产品最重要的一行**。
 *
 * 别的行回答"去哪儿玩"，这一行回答的是"这段路孩子受不受得了"。
 * 数据全部来自飞猪 search-train 的真实车次，不是模型写的。
 *
 * 三个显示上的讲究，每个都对应一个后端字段的设计决定：
 *
 * ① **方向读 from / to，不读"第几天"。** 靠天数推断的话，
 *    以后加多城市串联时这段"去程/回程"的文案就全错了，而且不会报错。
 *
 * ② **时刻用 hhmm() 从 ISO 里切，但绝不假设"就是当天"。**
 *    夜车 23:30 发、次日 00:25 到 —— 这里显示 00:25 是对的，
 *    错的写法是把它当成"当天凌晨到"再去和出发时间比大小。
 *
 * ③ **换乘必须显式说出来。** 带娃换乘和一趟直达完全是两回事，
 *    藏起来用户会以为这是一趟车坐到底。用醒目的颜色标出来，别做成一等公民之外的注释。
 */
function TransferLine({ transfer: t }: { transfer: Transfer }) {
  const flight = t.mode === 'FLIGHT'
  return (
    <div className="mb-2 text-gray-700">
      <p>
        <span className="mr-1">{flight ? '✈️' : '🚄'}</span>
        <span className="font-medium">
          {hhmm(t.depDateTime)} {t.depStation}
        </span>
        <span className="mx-1 text-gray-400">→</span>
        <span className="font-medium">
          {hhmm(t.arrDateTime)} {t.arrStation}
        </span>
        {t.serviceNo && <span className="ml-2 text-gray-500">{t.serviceNo}</span>}
        {/* 耗时是这一行的重点，加粗一档，因为"几个小时"本身就是产品约束 */}
        {t.durationMinutes !== null && (
          <span className="ml-2 font-medium text-gray-900">{formatDuration(t.durationMinutes)}</span>
        )}
        {/* ⚠️ null 是"飞猪没给价"，不是免费 —— 和门票、房价用的是同一条文案规矩 */}
        <span className="ml-2 text-gray-500">
          {t.priceYuan === null ? '票价未知' : `¥${t.priceYuan}`}
        </span>
        {t.seatClass && <span className="ml-1 text-gray-400">{t.seatClass}</span>}
      </p>
      {(!t.direct || t.transferCity || t.jumpUrl) && (
        <p className="mt-1 flex items-center gap-2 text-xs">
          {(!t.direct || t.transferCity) && (
            <span className="rounded bg-amber-100 px-1.5 py-0.5 text-amber-900">
              需在{t.transferCity ?? '中途'}换乘
            </span>
          )}
          {t.jumpUrl && (
            <a
              href={t.jumpUrl}
              target="_blank"
              rel="noreferrer"
              className="text-gray-500 hover:text-gray-800"
            >
              {flight ? '去飞猪看这班航班' : '去飞猪看这趟车'}
            </a>
          )}
        </p>
      )}
    </div>
  )
}

/**
 * 同一段路的**另一种走法**（目前只有飞机）—— 比主选弱一档，但信息一个不少。
 *
 * ── 为什么它必须存在，而且必须挨着主选显示 ────────────────────────
 *   后端只在"火车这条腿带娃偏累"时才补查它（超了 180 分钟那条线，或压根没有火车）。
 *   那种情况下用户看到"高铁 6 小时 40 分"是对的，但**不完整**：实测同一条线上
 *   12-10 的机票 ¥610，比白天的高铁（¥848）还便宜，而且只要 2 小时。
 *   把这个选项藏起来，等于替用户把选择收走了 —— 而这个项目的立场一直是
 *   "说清事实，决定权留给家长"。
 *
 * ── 三个刻意的取舍 ──────────────────────────────────────────────
 * ① **配色弱一档（灰 + 琥珀标签），不抢主选的位置。**
 *    并排的两个选项不该看起来一样重：当天的时间安排是按主选排的，别让用户以为
 *    "两个都订了"或者"系统推荐飞机"。
 * ② **明说价格是查询当时的。** 机票浮动比火车大得多（实测同一条航线隔一个月差 2.5 倍），
 *    不写这句，用户会把 ¥610 当成一个能拿去比价的确定数字。
 * ③ **绝不写"更划算"。** 我们不做这个判断 —— 价格会飘、机场要多花两小时、
 *    行李和值机也不是没有代价。给出事实，判断留给家长。
 */
function AlternativeLine({ transfer: t }: { transfer: Transfer }) {
  const flight = t.mode === 'FLIGHT'
  return (
    <div className="mb-2 text-gray-500">
      <p className="flex flex-wrap items-baseline gap-x-2">
        <span className="rounded bg-amber-50 px-1.5 py-0.5 text-xs text-amber-800">
          {flight ? '也可以飞' : '也可以坐'}
        </span>
        <span className="mr-0.5">{flight ? '✈️' : '🚄'}</span>
        <span>
          {hhmm(t.depDateTime)} {t.depStation}
        </span>
        <span className="text-gray-400">→</span>
        <span>
          {hhmm(t.arrDateTime)} {t.arrStation}
        </span>
        {t.serviceNo && <span>{t.serviceNo}</span>}
        {t.durationMinutes !== null && (
          <span>
            {/* ⚠️ 飞机的这个是**纯飞行时间**，不含提前到机场和进城 —— 后端在 warnings 里
                说清了，这里不重复解释，但也不写成"全程"。 */}
            {flight ? '飞行 ' : ''}
            {formatDuration(t.durationMinutes)}
          </span>
        )}
        <span>{t.priceYuan === null ? '票价未知' : `¥${t.priceYuan}`}</span>
        {t.jumpUrl && (
          <a
            href={t.jumpUrl}
            target="_blank"
            rel="noreferrer"
            className="text-gray-400 underline hover:text-gray-700"
          >
            {flight ? '查这个航班' : '看这趟车'}
          </a>
        )}
      </p>
      <p className="mt-0.5 text-xs text-gray-400">
        这是另一个走法，要不要选由你定；票价是查询当时的，机票会浮动。当天的时间安排按上面那一趟排的。
      </p>
    </div>
  )
}

/**
 * "2026-11-17T08:36" → "08:36"。
 *
 * 只切时刻，但**不要理解成"当天的时间"** —— 日期本身在 t.arrDateTime 里，
 * 需要判断"是不是跨天了"的时候要读完整串。这里只负责显示。
 */
function hhmm(isoDateTime: string): string {
  return isoDateTime.length >= 16 ? isoDateTime.slice(11, 16) : isoDateTime
}

/** 203 → "3 小时 23 分"。带娃出行里"3 小时 23 分"和"203 分钟"不是一个感受 */
function formatDuration(minutes: number): string {
  if (minutes < 60) return `${minutes} 分钟`
  const hours = Math.floor(minutes / 60)
  const rest = minutes % 60
  return rest === 0 ? `${hours} 小时` : `${hours} 小时 ${rest} 分`
}

/**
 * 把"第 N 天住哪"变成"这家酒店覆盖了哪几天"。
 *
 * 返回 Map<起始天, "连住 N 晚">，只在连续的第一天带这个标注。
 * 纯函数，和 PlanAssembler 里"酒店允许重复"那条规矩是配套的：
 * 后端保留重复（因为那是对的），前端负责把它渲染成一个决定。
 */
function mergeStays(days: TravelDay[]): Map<number, string> {
  const out = new Map<number, string>()
  let i = 0
  while (i < days.length) {
    const h = days[i].hotel
    if (!h) {
      i++
      continue
    }
    let n = 1
    while (i + n < days.length && days[i + n].hotel?.hotelId === h.hotelId) n++
    out.set(days[i].day, n > 1 ? `连住 ${n} 晚` : '')
    i += n
  }
  return out
}
