import { useEffect, useRef, useState } from 'react'
import { REQUIRED_FIELDS, applySwaps, swapKey, usePlanChat } from '../../hooks/usePlanChat'
import type { SpotEdits } from '../../hooks/usePlanChat'
import { PERSONA_LABELS } from '../../types/plan'
import type {
  HotelStay,
  Persona,
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
 * 信息结构就是 usePlanChat 里状态的投影：
 *   左边（中间那块）—— messages：正在发生什么
 *   右边（画像卡）  —— params：他**说过的**事实
 *   右边（理解卡）  —— persona：我们**推**的偏好（每条挂他的原话）
 * 这几样并排显示，是为了让"上下文"这件事**看得见**：
 * 你能看到自己聊了 5 轮之后，画像里哪些格子被填上了、哪些还是"待确认"，
 * 也能看到系统从你的话里**推**出了什么 —— 推错了当场就能用话纠正。
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
    <div className="min-h-screen bg-gh-canvas">
      <div className="mx-auto max-w-5xl px-4 py-8">
        <header>
          <div className="flex items-center justify-between">
            <div className="flex min-w-0 items-center gap-2.5">
              {/* logo：大人+小孩两个剪影。品牌感是"被记住"的第一步，
                  但刻意不画复杂插画 —— 一块绿色圆角底 + 两个人形，缩到 16px 依然认得出 */}
              <span
                aria-hidden
                className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-gh-green text-white"
              >
                <svg viewBox="0 0 24 24" fill="currentColor" className="h-5 w-5">
                  <circle cx="9" cy="7" r="3.2" />
                  <path d="M3 19.5c0-3.3 2.7-5.5 6-5.5s6 2.2 6 5.5v.5H3z" />
                  <circle cx="17.5" cy="11" r="2.3" opacity=".7" />
                  <path d="M13.8 20c.1-2.5 2-4.3 4.4-4.3.9 0 1.7.2 2.4.7-1 2.1-3 3.6-5.4 3.6z" opacity=".7" />
                </svg>
              </span>
              <h1 className="truncate text-xl font-semibold text-gh-fg">童行 · 带娃旅游规划器</h1>
              {/* 徽标只声明身份（AI + 亲子），不声明能力等级 —— "专家""官方"这类词背不起 */}
              <span className="shrink-0 rounded-full bg-gh-accent-soft px-2 py-0.5 text-xs font-medium text-gh-accent">
                AI 亲子版
              </span>
            </div>
            <button
              type="button"
              onClick={chat.reset}
              disabled={busy || (chat.messages.length === 0 && !chat.plan)}
              className="shrink-0 pl-3 text-sm text-gh-fg-muted hover:text-gh-fg disabled:opacity-40"
            >
              重新开始
            </button>
          </div>
          {/* 副标题只说"我是干什么的"，不讲架构。
              原来首屏只有标题 + 一个空聊天框 + 右边一张"待确认 ×8"的清单，
              第一眼看起来像一张要填的表，而不是一个能替他干活的东西 ——
              评委不会先读 README 再决定要不要用。 */}
          <p className="mt-1.5 text-sm text-gh-fg-muted">
            说一句想怎么玩，我去查真实的景点、酒店和车次，排出一份每个数字都能点开溯源的行程。
          </p>
        </header>

        {/* Mock 提示。必须显眼 —— 不提示等于把假数据当成真结果给用户 */}
        {chat.mockNotice && (
          <div className="mt-4 rounded-lg border border-[#d4a72c66] bg-[#fff8c5] px-4 py-2 text-sm text-gh-fg">
            ⚠️ {chat.mockNotice}
          </div>
        )}

        <div className="mt-6 grid gap-6 md:grid-cols-[1fr_320px]">
          {/* ── 左：对话区 ────────────────────────────────── */}
          <section className="ui-card flex min-h-[520px] flex-col">
            {/* 就绪条：一句"系统活着"的状态。右边的词只写**做得到的**：
                数据是真的（飞猪查的）、行程可溯源（provenance 每条都能点）——
                效果图上的"官方真实数据比对"不抄，我们不是官方，这种话评委一追问就露怯 */}
            <div className="flex items-center justify-between border-b border-gh-border-muted px-4 py-2 text-xs">
              <span className="flex items-center gap-1.5 text-gh-fg-muted">
                <span aria-hidden className="h-2 w-2 rounded-full bg-gh-green" />
                带娃旅行助理已就绪
              </span>
              <span className="text-gh-fg-faint">真实数据 · 行程可溯源</span>
            </div>
            <div ref={listRef} className="flex-1 space-y-3 overflow-y-auto p-4">
              {chat.messages.length === 0 && (
                // 居中的 hero 空态：这半屏是首屏主角，一行小灰字撑不起"欢迎来用"的姿态。
                // 效果图顶部那枚小徽章不抄 —— 头部已有 logo + AI 亲子版徽标，再摆一个是重复。
                <div className="flex h-full flex-col items-center justify-center gap-5 text-center">
                  <div>
                    <p className="text-2xl font-semibold tracking-tight text-gh-fg">说说你的计划 ——</p>
                    <p className="mx-auto mt-2 max-w-md text-sm text-gh-fg-muted">
                      我会一轮轮把信息问清楚，再真的去查。
                      怕走路、要午睡、想避开人多这些细节都能直接说。
                    </p>
                  </div>
                  {/* 一键示例：评委的真实处境是"不知道能问什么、也懒得想"。
                      三个都自带出发地，点下去到看见行程少一轮追问 —— 十几秒换来的是
                      他愿不愿意继续看下去。
                      ⚠️ 只留这一组入口：另一个方案在中间还摆三张"建议问题"卡，
                      和这两个 chips 功能重复，同屏两组"你可以这样问"只会让人犹豫点哪个。 */}
                  <div>
                    <p className="text-xs font-medium text-gh-fg-muted">没想好？点一个，直接发出去：</p>
                    <div data-probe="examples" className="mt-2 flex flex-wrap justify-center gap-2">
                      {EXAMPLES.map((ex) => (
                        <button
                          key={ex.label}
                          type="button"
                          // 直接发、不走输入框：多一步"填进去"就多一次放弃的机会。
                          disabled={busy}
                          onClick={() => void chat.sendRound(ex.text)}
                          className="rounded-full border border-gh-border bg-white px-3.5 py-2 text-[13px] text-gh-fg hover:border-gh-accent hover:bg-gh-accent-soft disabled:opacity-50"
                        >
                          {/* icon 单独 span：探针按 [data-label] 抠文字，emoji 不掺进断言 */}
                          <span aria-hidden className="mr-1">{ex.icon}</span>
                          <span data-label>{ex.label}</span>
                        </button>
                      ))}
                    </div>
                  </div>
                </div>
              )}
              {chat.messages.map((m) => (
                // key 用消息自己的 id 而不是数组下标：列表是追加式的，
                // 但下标 key 在"前面插入/删除"时会错位到别的消息上（React 会复用错的 DOM）
                <div key={m.id} className={m.role === 'user' ? 'text-right' : 'text-left'}>
                  <span
                    className={
                      'inline-block max-w-[85%] whitespace-pre-wrap rounded-2xl border px-3 py-2 text-sm ' +
                      (m.role === 'user'
                        ? 'border-[#b1d3f0] bg-gh-accent-soft text-gh-fg'
                        : 'border-gh-border-muted bg-gh-canvas text-gh-fg')
                    }
                  >
                    {m.content}
                  </span>
                </div>
              ))}
              {chat.sending && <p className="text-xs text-gh-fg-faint">正在想…</p>}
            </div>

            {chat.error && (
              <div className="mx-4 mb-2 rounded-lg border border-[#ff818266] bg-[#ffebe9] px-3 py-2 text-sm text-gh-red">
                {chat.error}
              </div>
            )}

            <div className="border-t border-gh-border-muted p-3">
              <div className="flex gap-2">
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
                  placeholder={
                    busy
                      ? '请稍等这一轮结束…'
                      // 占位符顺便当"怎么问"的示范：给结构（谁+去哪+几天），不给具体答案
                      : '说说你的计划，例如：想带俩娃去海边玩 3 天，不想太赶'
                  }
                  disabled={busy}
                  className="flex-1 rounded-md border border-gh-border bg-white px-3 py-1.5 text-sm text-gh-fg outline-none focus:border-gh-accent focus:ring-2 focus:ring-[#0969da33] disabled:bg-gh-canvas"
                />
                <button
                  type="button"
                  onClick={() => void submit()}
                  // 忙碌时禁用，而不是"点了没反应" —— 闸门在 hook 里，这里要让它可见
                  disabled={busy || !draft.trim()}
                  className="rounded-md bg-gh-green px-4 py-1.5 text-sm font-medium text-white hover:bg-gh-green-hover disabled:opacity-50"
                >
                  发送
                </button>
              </div>
              {/* 一行小字换来少一次"打了字没反应"的困惑。放右下角，不跟占位符抢地方 */}
              <p className="mt-1.5 text-right text-xs text-gh-fg-faint">按 Enter 发送</p>
            </div>
          </section>

          {/* ── 右：画像卡 + 理解卡 ──────────────────────── */}
          <aside className="space-y-4">
            <ProfileCard params={chat.params} missing={chat.missingFields} />

            {/* persona 卡放在画像卡正下面：两张卡并排回答同一个问题的两半 ——
                "他说过的"（事实）和"我们推的"（偏好）。位置挨着，用户才看得出
                哪些格子是他说的、哪些是我们添的 —— 这个边界必须看得见。 */}
            <PersonaCard persona={chat.persona} />

            {/* CTA 常驻可视区：右栏比一屏高时，按钮会钉在视口底部跟着你走 ——
                "信息齐了，就差你点这一下" 的动作不能藏在滚动条底下。
                sticky 只在"自然位置在屏外"时生效，滚过右栏底（去看行程）就正常随页走。

                ⚠️ **只在 md 以上钉住**。手机上是单列布局，aside 就紧贴着对话卡，
                    sticky bottom-4 会把一个全宽按钮连同下面那条黄色提示一起按在视口底部，
                    正好压住"待确认"清单的最后几行 —— 那几行恰恰是"还差什么"的答案，
                    用户要看的就是它，却被自己的按钮盖住（实测截图确认过）。
                    手机上让它随页滚：滚到它的时候，上面的清单也已经看完了。 */}
            <div data-probe="cta" className="z-10 md:sticky md:bottom-4">
              <button
                type="button"
                onClick={() => void chat.generate()}
                disabled={!chat.canGenerate || busy}
                className="w-full rounded-md bg-gh-green px-4 py-2 text-sm font-medium text-white shadow-md hover:bg-gh-green-hover disabled:opacity-50"
              >
                {chat.generating ? '正在生成…' : '生成行程'}
              </button>
              {!chat.canGenerate && (
                // 点不了的时候必须说清**为什么**。一个灰着的按钮、不写原因，
                // 用户只会以为界面坏了，而不是回去把信息补上 ——
                // 而这恰好是他唯一能做的动作。
                <p className="mt-2 rounded-lg border border-[#d4a72c66] bg-[#fff8c5] px-3 py-1.5 text-xs text-gh-fg">
                  还差{missingLabels(chat.missingFields)} —— 这几项各自直接决定查什么、怎么排。
                </p>
              )}
            </div>
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
 * 首屏的三个示例
 * ============================================================ */

/**
 * ⚠️ `label` 和 `text` 是两份文案，不是写重了：
 *    label 要短到一行挤得下（评委扫一眼就知道有没有自己那个场景）；
 *    text 会**当成用户消息原样发给模型**，所以必须是一句完整的人话 ——
 *    写成「珠海3天6岁」这种关键词串，模型反而要多问两轮才敢填格子。
 *
 * ⚠️ 三个都自带出发地，且城市远/中/近各一个：
 *    近的（深圳→珠海）基本不查大交通，远的（北京→杭州）会真去查机票和车次 ——
 *    点哪一个都不撞同一条链路，顺手把两种演示都覆盖了。
 *
 * icon 是个 emoji 而不是图标库：一个就有"这卡是活的"的表情，
 * 也不引入整套依赖。选跟场景强相关的（轮船/奶瓶/树叶），不要装饰性的。
 */
const EXAMPLES: Array<{ icon: string; label: string; text: string }> = [
  { icon: '🎡', label: '珠海 3 天 · 孩子 6 岁', text: '从深圳出发，想带孩子去珠海玩 3 天，孩子 6 岁' },
  { icon: '🍼', label: '上海 2 天 · 1 岁宝宝', text: '从广州出发，想带孩子去上海玩 2 天，孩子 1 岁，节奏慢一点' },
  { icon: '🌿', label: '杭州 5 天 · 俩娃 3 岁 + 8 岁', text: '从北京出发，想带两个孩子去杭州玩 5 天，一个 3 岁一个 8 岁' },
]

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
  { key: 'childAges', label: '孩子年龄' },
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
    <div className="ui-card p-4">
      <h2 className="text-sm font-semibold text-gh-fg">已收集的信息</h2>
      <p className="mt-0.5 text-xs text-gh-fg-faint">这就是"上下文"里被提炼出来的那部分</p>
      {/* 时间轴：竖线画在 dl 上（before），每个字段一颗点 ——
          填了的实心、还空着的空心描金，一眼看出"聊到哪了" */}
      <dl className="relative mt-3 space-y-2.5 before:absolute before:bottom-2 before:left-[3.5px] before:top-2 before:w-px before:bg-gh-border">
        {FIELDS.map((f) => {
          // ⭐ 必填项还空着时，在**这个格子本身**上说清楚 ——
          //    它是"生成按钮为什么点不了"在页面上的答案，
          //    而答案必须落在用户正在看的那个位置，不能只在按钮下面写一行小字。
          const required = REQUIRED_FIELDS.includes(f.key)
          const blank = missing.includes(f.key)
          return (
            <div key={f.key} className="flex items-start gap-2.5 text-sm">
              <span
                className={
                  'z-10 mt-1.5 h-2 w-2 shrink-0 rounded-full ring-4 ring-white ' +
                  (blank ? 'border-[1.5px] border-[#d4a72c] bg-white' : 'bg-gh-fg-faint')
                }
              />
              <dt className="flex-1 text-gh-fg-muted">
                {f.label}
                {required && (
                  <span className="ml-0.5 text-amber-600" title="必填">
                    *
                  </span>
                )}
              </dt>
              <dd className={'text-right font-medium ' + (blank ? 'text-[#9a6700]' : 'text-gh-fg')}>
                {renderField(f.key, params)}
              </dd>
            </div>
          )
        })}
      </dl>
      {/* 这句解释是必要的：不然"为什么有的格子带星、有的不带"就成了一个要猜的事。
          同时它把"不填也能继续"明说出来 —— 隐含的可选项等于没有可选项。 */}
      <p className="mt-3 border-t border-gh-border-muted pt-2.5 text-[11px] leading-snug text-gh-fg-faint">
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
  // childAges 是数组，但它的每一项都要带单位（"5 岁、8 岁"而不是"5、8"）——
  // 所以必须在通用数组分支**之前**拦下它。
  if (key === 'childAges' && Array.isArray(v)) {
    return v.length > 0 ? v.map((a) => `${a} 岁`).join('、') : '待确认'
  }
  if (Array.isArray(v)) return v.length > 0 ? v.join('、') : '待确认'
  if (key === 'days') return `${v} 天`
  return String(v)
}

/* ============================================================
 * 我这样理解你 —— persona 卡（2026-10-15 加）
 * ============================================================ */

/**
 * 我们推出来的偏好，每条挂着他自己的原话。
 *
 * ── 它和上面那张画像卡的分界线（这条是这个设计的核心）──────
 *   画像卡 —— 他**说过的**事实（"9 天""想去珠海"）。错了他会纠正："我说的是 4 天"。
 *   这张卡 —— 我们**推**的偏好（"怕走路""要午睡"）。错了他**以为你懂他，全程不报错**。
 *   所以每条必须亮出依据（他的原话）：让"我怎么理解你"从后台搬到台前，
 *   看得见、可核对。原话是后端**逐字验证过**才放进来的（PersonaGuard），
 *   前端可以放心把它当"用户原话"引用，不需要再验一遍。
 *
 * ── 为什么没有"删除"按钮 ───────────────────────────────────
 *   推错了的纠正路径是**用话纠正**：他说"其实我们体力很好"，下一轮模型
 *   就会推成相反方向的判断、把旧的覆盖掉（探针 S5 实测过）。
 *   一个 ✗ 按钮看着更直接，但它改不了根：对话历史里那句原话还在，
 *   下一轮模型还会从它再推出同一条 —— 按钮删掉的状态活不过一轮。
 *   硬要做成"删了就不再出"得给"作废"一个显式的协议词，那不是这一轮的事。
 *
 * ── 空的时候不渲染 ─────────────────────────────────────────
 *   没推出来任何判断时渲染一张空卡，等于天天挂着"我还不懂你"——
 *   那是噪声不是提示。空 = 安静，等第一条推论出来它自然出现。
 */
function PersonaCard({ persona }: { persona: Persona }) {
  if (persona.inferences.length === 0) return null
  return (
    // 这张卡就是截图里那张 GitHub changelog 卡的同构：
    // 白卡 + 左侧竖线时间轴 + 「小灰字（维度）→ 深色标题（判断）→ 小灰字（你的原话）」
    //
    // 2026-10-21：它是全页**唯一**一处"暖"的地方。
    //   它讲的不是事实，是**推断** —— 而事实卡（行程、查询账本）一律冷白。
    //   让底色替我们说话：暖的那块 = 它猜的，白的那块 = 它查到的。
    // ⚠️ 暖色只许停在这张卡里。多铺一张就等于引入第二个主色，
    //   那时"哪块更暖"就从信号变成了噪音（全站没有主次了）。
    <div className="ui-card border-[#ecdfc6] bg-[#fffdf6] p-4">
      <h2 className="text-sm font-semibold text-gh-fg">我这样理解你</h2>
      <p className="mt-0.5 text-xs text-gh-fg-faint">从你的话里推出来的，每条都附你自己的原话</p>
      <ul className="relative mt-3 space-y-3 before:absolute before:bottom-2 before:left-[3.5px] before:top-2 before:w-px before:bg-[#e6d9bf]">
        {persona.inferences.map((it) => (
          <li key={it.key} className="relative pl-5">
            {/* ring 用同一块暖底，不能用白色 —— 否则每个圆点周围都挂一个白圈 */}
            <span className="absolute left-0 top-1 h-2 w-2 rounded-full bg-[#d4a72c] ring-4 ring-[#fffdf6]" />
            <p className="text-xs leading-4 text-gh-fg-muted">{PERSONA_LABELS[it.key]}</p>
            <p className="mt-0.5 text-sm font-medium leading-snug text-gh-fg">{it.value}</p>
            {it.evidence && (
              <p className="mt-0.5 text-xs text-gh-fg-faint">你说过：「{it.evidence}」</p>
            )}
          </li>
        ))}
      </ul>
      <p className="mt-3 border-t border-[#ecdfc6] pt-2.5 text-[11px] leading-snug text-gh-fg-faint">
        推错了就直接说（比如「其实我们体力很好」），下一轮会更新。
      </p>
    </div>
  )
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
    <div className="ui-card p-3">
      <div className="flex items-baseline justify-between">
        <span className="text-xs font-medium text-gh-fg">正在生成行程</span>
        <span className="text-xs tabular-nums text-gh-fg-muted">
          已用 {Math.floor(elapsedMs / 1000)} 秒
        </span>
      </div>

      {/* 不确定态扫光。它不知道终点在哪 —— 这正是我们要表达的意思，不是没做完 */}
      <div className="mt-2 h-1 overflow-hidden rounded-full bg-gh-accent-soft">
        <div className="h-full w-1/3 animate-sweep rounded-full bg-gh-accent" />
      </div>

      {steps.length > 0 ? (
        <ol className="relative mt-3 space-y-1.5 before:absolute before:bottom-2 before:left-[6.5px] before:top-2 before:w-px before:bg-gh-border-muted">
          {steps.map((s) => (
            <StepLine key={s.key} step={s} />
          ))}
        </ol>
      ) : (
        <p className="mt-3 text-xs text-gh-fg-muted">正在查飞猪，这一步要几十秒。</p>
      )}

      <p className="mt-2 text-[11px] leading-snug text-gh-fg-faint">
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
      <span className="z-10 flex h-3.5 w-3.5 shrink-0 items-center justify-center leading-none">
        {step.state === 'DONE' && <span className="text-gh-green">✓</span>}
        {step.state === 'FAILED' && <span className="text-gh-red">✕</span>}
        {step.state === 'RUNNING' && (
          <span className="h-2 w-2 animate-breathe rounded-full bg-gh-accent ring-4 ring-white" />
        )}
        {step.state === 'PENDING' && (
          <span className="h-2 w-2 rounded-full border border-gh-border bg-white ring-4 ring-white" />
        )}
      </span>

      <span
        className={
          step.state === 'RUNNING'
            ? 'truncate font-medium text-gh-fg'
            : step.state === 'DONE'
              ? 'truncate text-gh-fg-muted'
              : 'truncate text-gh-fg-faint'
        }
      >
        {step.label}
      </span>

      {/* tookMs 为 null == 还没走完。**不能写成 `!step.tookMs`** —— 那样"耗时 0.0 秒"也会被当成没走完 */}
      <span className="ml-auto shrink-0 tabular-nums text-gh-fg-faint">
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
      <div className="ui-card p-4">
        <h2 className="text-base font-semibold text-gh-fg">{plan.summary}</h2>
        <p className="mt-2 text-xs text-gh-fg-muted">
          查了 {plan.provenance.searchRounds} 轮飞猪 · 命中 {plan.provenance.candidatesFound} 条 ·
          可用 {plan.provenance.candidatesKept} 条 · 耗时 {(plan.provenance.elapsedMs / 1000).toFixed(1)} 秒
        </p>
        {swapCount > 0 && (
          <p className="mt-2 text-xs text-gh-accent">
            你手动换过 {swapCount} 个点 —— 每个换过的点旁边都有「恢复原样」
          </p>
        )}
      </div>

      {plan.warnings.length > 0 && (
        <div className="rounded-lg border border-[#d4a72c66] bg-[#fff8c5] p-4">
          <h3 className="text-sm font-semibold text-gh-fg">注意事项</h3>
          <ul className="mt-2 list-disc space-y-1 pl-5 text-sm text-gh-fg">
            {plan.warnings.map((w, i) => (
              <li key={i}>{w}</li>
            ))}
          </ul>
        </div>
      )}

      {/* 行程天用 GitHub changelog 式的时间轴串起来（截图那张卡的同构）：
          竖线 + 每天一颗节点，"第 1 天、第 2 天"读起来才像"一段一段走过的旅程"，
          而不是一张张孤立的卡片。 */}
      <div className="relative ml-2 space-y-4 pl-6 before:absolute before:bottom-6 before:left-[4.5px] before:top-6 before:w-px before:bg-gh-border">
        {days.map((d, i) => (
          <div key={d.day} className="relative">
            {/* 圆点对准日期头的中线：卡片 p-4(16px) + 36px 缩略图行 / 2 - 圆点半径(5px) = 29px。
                B 手加了缩略图之后日期头从 20px 变 36px，top-5 是对旧行高算的 —— 不改圆点就漂到标题上方。 */}
            <span className="absolute -left-6 top-[29px] z-10 h-2.5 w-2.5 rounded-full bg-gh-fg-faint ring-4 ring-gh-canvas" />
            <DayCard
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
          </div>
        ))}
      </div>

      {/* 账本：真实发出去的查询命令。用户有权看见"这份东西是怎么来的" */}
      <details className="ui-card p-4">
        <summary className="cursor-pointer text-sm font-semibold text-gh-fg">
          这份行程是怎么来的（{plan.provenance.queries.length} 次查询）
        </summary>
        <div className="mt-3 space-y-2 text-xs text-gh-fg-muted">
          <p>
            检索条件：类别{' '}
            {plan.provenance.retrievalPlan.wantedCategories.length > 0
              ? plan.provenance.retrievalPlan.wantedCategories.join('、')
              : '（不限，走宽查）'}
            ，要酒店 {plan.provenance.retrievalPlan.needHotel ? '是' : '否'}
          </p>
          <ul className="space-y-1 font-mono">
            {plan.provenance.queries.map((q, i) => (
              <li key={i} className="break-all rounded bg-gh-canvas px-2 py-1">{q}</li>
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
  /** 这一段住宿的标注（"连住 N 晚" + 是不是第一晚）。undefined = 这天不是住宿段头一晚 */
  stay?: StayMark
  isLast: boolean
  alternatives: Alternative[]
  swaps: SpotEdits
  onSwap: (day: number, index: number, option: SpotOption) => void
  onRestore: (day: number, index: number) => void
}) {
  // 日期头缩略图：用当天**第一个点**的真图。
  // 图源是飞猪 POI 自带的 imageUrl（后端一直在发，前端一直没用）——
  // 不接 Unsplash/Places 那类图库：会配错图（把别的景点安在这天头上，
  // 比没图更糟）、有限流、还让首屏多一个外网依赖。
  // ⚠️ filter 的条件是 truthy：后端没图时发的是**空串**，不是"一张坏图"。
  //
  // 存整条 spot 而不是只存 url：点开放大时要显示"这是哪个景点"，
  // 只留一个 url 的话，那行说明就只能写成"第 2 天的图"——等于没说。
  //
  // ⭐ 一整天的图都在这里（不是只有第一张）：一天常常排 2~3 个场景
  //    （上午动物园、下午科技馆），每张图讲的都是不同的事。
  //    日期头只挂第一张当封面，其余的在放大层里左右翻 —— 一次看一张。
  const coverSpots = day.spots.filter((s) => s.imageUrl)
  const coverSpot = coverSpots[0]
  // 36px 的缩略图看不清是什么地方（真实图多是景区门脸/远景），所以点开看大图。
  const [zoomed, setZoomed] = useState(false)
  // 图挂了就把这个入口关掉：留一个能点开、但点开是坏图的按钮，比没有更糟。
  const [coverBroken, setCoverBroken] = useState(false)

  return (
    <article className="ui-card p-4">
      {/* min-h-9：有的天没图（后端给空串），标题会缩回一行高 ——
          而左边时间轴的圆点是按"36px 的头"定位的，行高一抖圆点就歪。
          锁死最小高度，有没有图圆点都在同一个位置。 */}
      <h3 className="flex min-h-9 items-center gap-2.5 text-sm font-semibold text-gh-fg">
        {coverSpot && (
          /* 缩略图挂在外层按钮上而不是直接裸奔：
             图片 404 时把 img 藏掉（visibility，不是 display——
             外框尺寸不变，下面那个时间轴圆点是对着这个 36px 的头定位的，
             布局一抖圆点就歪）。挂了的样子 = 一个浅灰空块，不顶歪任何东西。

             用 button 而不是给 span 挂 onClick：键盘能 Tab 到、回车能打开，
             且读屏会念出 aria-label —— 这三件事挂 onClick 的 div 全都没有。 */
          <button
            type="button"
            onClick={() => setZoomed(true)}
            disabled={coverBroken}
            aria-label={
              coverSpots.length > 1
                ? `放大查看这天的 ${coverSpots.length} 张图（从「${coverSpot.name}」开始）`
                : `放大查看「${coverSpot.name}」的图片`
            }
            title={coverBroken ? '这张图没加载出来' : '点开看大图'}
            className="flex h-9 w-9 shrink-0 cursor-zoom-in items-center justify-center overflow-hidden rounded border border-gh-border bg-gh-canvas transition-opacity hover:opacity-80 disabled:cursor-default disabled:hover:opacity-100"
          >
            <img
              src={coverSpot.imageUrl}
              alt=""
              loading="lazy"
              referrerPolicy="no-referrer"
              className={coverBroken ? 'invisible h-full w-full' : 'h-full w-full object-cover'}
              onError={() => setCoverBroken(true)}
            />
          </button>
        )}
        <span>
          第 {day.day} 天 · {day.theme}
        </span>
      </h3>

      {/* 放大层。挂在卡片里而不是提到页面顶层：
          它是 fixed 定位，挂在哪都不影响位置；留在卡片里反而让它
          "服务于这一天的图"这层关系在代码里也是看得见的。 */}
      {zoomed && coverSpot && (
        <ImageZoom
          // 一天的图全给它，第一张就是缩略图那张
          items={coverSpots.map((s) => ({ src: s.imageUrl, name: s.name, time: s.time }))}
          dayLabel={`第 ${day.day} 天 · ${day.theme}`}
          onClose={() => setZoomed(false)}
        />
      )}

      {/* 为什么这么排（2026-10-15 加）。一句话模板拼的话：里面的数字（今天几个点
          相隔多远、中午留了多长、几点到站）全部来自后端本地算出的事实 ——
          不交给模型写，因为模型会写出"这里不用走很多路"这种**没法核对**的句子。
          后端"没有可说的"就给 null，这里就不渲染：宁可不解释，也不硬凑一句。 */}
      {day.why && <p className="mt-1 text-xs leading-relaxed text-gh-fg-muted">💡 {day.why}</p>}

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
        {day.spots.length === 0 && <li className="text-sm text-gh-fg-faint">这天还没排上点</li>}
      </ul>

      <div className="ui-divide mt-3 pt-3 text-sm">
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
          <p className="text-gh-fg">
            🏨 {day.hotel.name}
            <span className="ml-2 text-gh-fg-muted">
              {/* 酒店这里是**每晚房价**，和上面的门票不是一个量纲 */}
              {day.hotel.priceYuan === null ? '房价未知' : `¥${day.hotel.priceYuan}/晚`}
            </span>
            {day.hotel.star && <span className="ml-2 text-gh-fg-faint">{day.hotel.star}</span>}
            {stay?.label && <span className="ml-2 text-gh-fg-faint">{stay.label}</span>}
            {/* 订房入口。⚠️ 在 2026-10-14 之前这里**什么都没有** ——
                hotel.bookUrl 后端一直在发，前端一个字都没渲染，也就是说
                整份行程里唯一能直接把用户带到酒店的那条路，一直是断的。
                交通行一直有"去飞猪看这趟车"，住宿这一行却没有，纯粹是漏了。
                实测这个链接是带入离日期的飞猪详情页（不是首页、不是搜索页），
                点开就是"你要去的那几天"的房态 —— 所以它值得一个明确的动作词。 */}
            {day.hotel.bookUrl && (
              <a
                href={day.hotel.bookUrl}
                target="_blank"
                rel="noreferrer"
                className="ml-2 text-gh-accent hover:underline"
              >
                去飞猪订
              </a>
            )}
          </p>
        ) : (
          /* ⚠️ hotel === null 有两种完全不同的含义，必须分开显示：
           *    最后一天 → 当天返程（正常，不用多说）
           *    其它天   → 这次没查到酒店数据（异常，要说出来，否则用户以为系统忘了）
           *  写成一个统一的"无住宿"会让前者显得像故障、后者显得像安排。 */
          <p className="text-gh-fg-faint">
            {isLast ? '当天返程，不需要住宿' : '这天还没有住宿安排'}
          </p>
        )}
        {day.hotel?.tips && <p className="mt-1 text-xs text-gh-fg-muted">{day.hotel.tips}</p>}

        {/* ── 今晚还能住哪儿 ───────────────────────────────────────────────
            排在主选下面：它和主选回答的是同一个问题（今晚住哪），只是另一个答案。
            和机票那个备选行同级，但有一个关键差别 —— 它**不花额外的上游调用**：
            这些酒店本来就在候选池里（上游一次回 10 家，模型只挑 1 家，剩下的原本直接丢掉）。

            ⚠️ 只在住宿段的**第一晚**渲染（`stay?.first`）：连住时后端每天都会带同一份备选，
               天天画就是同一个清单在屏幕上出现三遍。段内的"哪几天算连住"是 mergeStays 算的。

            ⚠️ 和 alternatives 一样要 `?? []`：前端静态包与后端 jar 不同版本时，
               旧后端不发这个字段，`.map` 会抛错并让 React 把**整份行程**卸载掉（白屏）。
               一处 `??` 把最坏结果从"白屏"降成"少一行"，不是通用兼容层。 */}
        {stay?.first &&
          (day.hotelAlternatives ?? []).map((h) => (
            <HotelAlternativeLine key={h.hotelId} hotel={h} />
          ))}
      </div>
    </article>
  )
}

/**
 * 点开看大图 —— 一天有几张图，就能翻几张。
 *
 * ── 为什么值得一个遮罩层 ─────────────────────────────────────────
 *   36px 的缩略图能回答"这天有图"，回答不了"这是哪儿、值不值得去" ——
 *   而家长扫一份行程时，下一个动作往往恰好就是这个。
 *
 * ── 四个刻意的取舍 ────────────────────────────────────────────────
 * ① **切换只在「这一天之内」，不跨天。**
 *    一天 3 个场景是"同一件事的三个侧面"，翻着看是自然的；
 *    跨天问的是另一个问题（"第 2 天和第三天哪边更好玩"），那属于重排布局，
 *    不属于这一层。所以计数写的是 "2 / 3" —— **分母是这一天**，不是整趟行程。
 * ② **只有一张时不给箭头、不给计数。** 摆着两个点不动的箭头，
 *    正是那种"看起来能用其实是坏的"控件 —— 比没有更糟。
 * ③ **关闭路径给三条**：点空白、点右上角 ×、按 Esc。
 *    遮罩层最恼人的失效就是"点哪儿都不关"，三条里缺一条都够用户骂一次。
 * ④ **打开时锁住页面滚动**，解锁还原原来的值（不是一律写回 ''）。
 *
 * ⚠️ 图片本身被点时不关（stopPropagation）：很多人会先点一下图，想"再放大点"，
 *    那一下把层关掉，会让人觉得点错了。箭头和说明文字同理。
 *
 * ⚠️ **循环翻**（到头接回第一张）：比"到边界按钮变灰"少一次"为什么点不动"，
 *    而且这里没有"顺序"的语义要守 —— 一天的三张图不分首尾。
 */
function ImageZoom({
  items,
  dayLabel,
  onClose,
}: {
  /** 这一天的图，按当天行程顺序。第一张就是日期头缩略图那张 */
  items: Array<{ src: string; name: string; time: string }>
  dayLabel: string
  onClose: () => void
}) {
  const [i, setI] = useState(0)
  // 挂了的是**哪几张**（记下标），不是"当前这张是不是坏的"。
  // 记下标的好处：翻回上一张时不用重置状态，也就不会闪一下空白。
  const [broken, setBroken] = useState<number[]>([])
  const count = items.length
  const step = (d: number) => setI((v) => (v + d + count) % count)

  // 依赖里带 onClose：调用方每次渲染都会给一个新函数（内联箭头），
  // 于是这张遮罩打开期间每次父组件重渲染都会重挂一次监听。代价可以忽略
  // （一次 remove + add），换来的是永远不会因为闭包拿到过期的 onClose。
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
      else if (e.key === 'ArrowRight') setI((v) => (v + 1) % count)
      else if (e.key === 'ArrowLeft') setI((v) => (v - 1 + count) % count)
    }
    window.addEventListener('keydown', onKey)
    const prevOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      window.removeEventListener('keydown', onKey)
      document.body.style.overflow = prevOverflow
    }
  }, [onClose, count])

  // 一张图都没有的东西不该存在（调用方只在有图时才渲染这层）。
  // 放在所有 hook 之后：条件 return 写在 hook 之前会让 hook 数量随渲染变化。
  if (count === 0) return null
  const cur = items[i]
  const curBroken = broken.includes(i)
  const arrowClass =
    'absolute top-1/2 z-10 flex h-11 w-11 -translate-y-1/2 items-center justify-center rounded-full bg-[rgba(31,35,40,0.5)] text-2xl leading-none text-white hover:bg-[rgba(31,35,40,0.85)]'

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label={`${dayLabel} 的图片`}
      onClick={onClose}
      className="fixed inset-0 z-50 flex flex-col items-center justify-center gap-3 bg-[rgba(31,35,40,0.72)] p-4 sm:p-8"
    >
      <button
        type="button"
        onClick={onClose}
        aria-label="关闭"
        autoFocus
        className="absolute right-4 top-4 z-10 flex h-9 w-9 items-center justify-center rounded-full bg-[rgba(31,35,40,0.55)] text-xl leading-none text-white hover:bg-[rgba(31,35,40,0.85)]"
      >
        ×
      </button>

      {count > 1 && (
        <>
          <button
            type="button"
            aria-label="上一张"
            onClick={(e) => {
              e.stopPropagation()
              step(-1)
            }}
            className={`${arrowClass} left-2 sm:left-6`}
          >
            ‹
          </button>
          <button
            type="button"
            aria-label="下一张"
            onClick={(e) => {
              e.stopPropagation()
              step(1)
            }}
            className={`${arrowClass} right-2 sm:right-6`}
          >
            ›
          </button>
        </>
      )}

      {curBroken ? (
        /* 一张坏图不该给一个空白洞：说清它没加载出来，并提示可以翻下一张。
           （这里的"坏"只影响这一张，不影响整层的其它图。） */
        <div className="flex h-[40vh] w-[70vw] max-w-lg flex-col items-center justify-center gap-1 rounded-lg border border-white/20 text-sm text-white/70">
          <span>这张图没加载出来</span>
          {count > 1 && <span className="text-xs text-white/45">可以翻到下一张看</span>}
        </div>
      ) : (
        <img
          // key 用 src：换图时让 <img> 重新挂载。否则上一张的缩放/加载状态
          // 会被复用，切过去一瞬间显示的还是旧图（同一毫秒内看不出来，但会闪）。
          key={cur.src}
          src={cur.src}
          alt={cur.name}
          onClick={(e) => e.stopPropagation()}
          onError={() => setBroken((v) => (v.includes(i) ? v : [...v, i]))}
          className="max-h-[78vh] max-w-[90vw] rounded-lg object-contain"
        />
      )}

      <p
        onClick={(e) => e.stopPropagation()}
        className="max-w-[90vw] text-center text-xs leading-relaxed text-white/85"
      >
        <span className="font-medium text-white">{cur.name}</span>
        <span className="ui-num ml-2 text-white/55">{cur.time}</span>
        <span className="ml-2 text-white/45">{dayLabel}</span>
        {count > 1 && (
          <span className="ui-num ml-2 rounded bg-white/15 px-1.5 py-0.5 text-white/85">
            {i + 1} / {count}
          </span>
        )}
        {/* 图是飞猪的商品图，原图可能就只有一两百像素 —— 与其让用户怀疑
            "是不是你们把图压坏了"，不如直说它本来就不大。 */}
        <span className="mt-1 block text-white/45">
          {count > 1 ? '点空白处或按 Esc 关闭 · ← → 看同一天的另一张' : '点空白处或按 Esc 关闭'}
          {' · 图是飞猪的商品图，原图可能不清晰'}
        </span>
      </p>
    </div>
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
        {/* 时间列：等宽 + 表格数字。比例字体里 "09:30" 比 "14:16" 窄一点，
            一列时间扫下去就是波浪；tabular-nums 让每个数字占同一宽度，列才对得齐。
            行高锁 leading-5（=右边点名的 text-sm 行高），否则时间会比名字高半行。 */}
        <span className="ui-num w-12 shrink-0 text-[13px] leading-5 text-gh-fg-muted">{spot.time}</span>
        <span className="flex-1">
          <span className="text-gh-fg">{spot.name}</span>
          {/* ⚠️ priceYuan 为 null 是"没有可售的票"，不是"免费" —— 所以写"票价未知" */}
          <span className="ml-2 text-gh-fg-muted">
            {spot.priceYuan === null ? '票价未知' : `¥${spot.priceYuan}`}
          </span>
          {swapped && (
            <span className="ml-2 rounded-full bg-gh-accent-soft px-2 py-0.5 text-xs text-gh-accent">
              换上的备选
            </span>
          )}
          {spot.tips ? (
            <span className="mt-1 block text-xs text-gh-fg-muted">{spot.tips}</span>
          ) : swapped ? (
            <span className="mt-1 block text-xs text-gh-fg-faint">
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
          className="rounded-md border border-gh-border bg-gh-canvas px-2 py-0.5 text-gh-fg hover:bg-[#eff2f5] disabled:opacity-50"
        >
          {alternatives.length === 0
            ? '没有别的可选了'
            : `换一个（这次查到的还有 ${alternatives.length} 个）`}
        </button>
        {swapped && (
          <button
            type="button"
            onClick={onRestore}
            className="text-gh-fg-faint underline-offset-2 hover:text-gh-fg hover:underline"
          >
            恢复原样
          </button>
        )}
      </div>

      {open && alternatives.length > 0 && (
        <div className="mt-2 pl-[60px]">
          <p className="mb-1 text-xs text-gh-fg-faint">
            这次查到、还没用上的景点，按离这一天其它点的远近排的 ——
            <span className="font-medium">换完不会重新校验行程</span>，所以别选太远的
          </p>
          <ul className="max-h-60 space-y-0.5 overflow-y-auto rounded-lg border border-gh-border bg-gh-canvas p-1.5">
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
                  <span className="text-gh-fg">{a.option.name}</span>
                  <span className="ml-2 text-gh-fg-muted">
                    {a.option.priceYuan === null ? '票价未知' : `¥${a.option.priceYuan}`}
                  </span>
                  {a.option.category && <span className="ml-2 text-gh-fg-faint">{a.option.category}</span>}
                  {a.km !== null && <span className="ml-2 text-gh-fg-faint">{formatKm(a.km)}</span>}
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
    <div className="mb-2 text-gh-fg">
      <p>
        <span className="mr-1">{flight ? '✈️' : '🚄'}</span>
        <span className="font-medium">
          <span className="ui-num">{hhmm(t.depDateTime)}</span> {t.depStation}
        </span>
        <span className="mx-1 text-gh-fg-faint">→</span>
        <span className="font-medium">
          <span className="ui-num">{hhmm(t.arrDateTime)}</span> {t.arrStation}
        </span>
        {t.serviceNo && <span className="ui-num ml-2 text-gh-fg-muted">{t.serviceNo}</span>}
        {/* 耗时是这一行的重点，加粗一档，因为"几个小时"本身就是产品约束 */}
        {t.durationMinutes !== null && (
          <span className="ml-2 font-medium text-gh-fg">{formatDuration(t.durationMinutes)}</span>
        )}
        {/* ⚠️ null 是"飞猪没给价"，不是免费 —— 和门票、房价用的是同一条文案规矩 */}
        <span className="ml-2 text-gh-fg-muted">
          {t.priceYuan === null ? '票价未知' : `¥${t.priceYuan}`}
        </span>
        {t.seatClass && <span className="ml-1 text-gh-fg-faint">{t.seatClass}</span>}
      </p>
      {(!t.direct || t.transferCity || t.jumpUrl) && (
        <p className="mt-1 flex items-center gap-2 text-xs">
          {(!t.direct || t.transferCity) && (
            <span className="rounded-full bg-[#fff8c5] px-2 py-0.5 text-[#9a6700]">
              需在{t.transferCity ?? '中途'}换乘
            </span>
          )}
          {t.jumpUrl && (
            <a
              href={t.jumpUrl}
              target="_blank"
              rel="noreferrer"
              className="text-gh-accent hover:underline"
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
    <div className="mb-2 text-gh-fg-muted">
      <p className="flex flex-wrap items-baseline gap-x-2">
        <span className="rounded-full bg-gh-accent-soft px-2 py-0.5 text-xs text-gh-accent">
          {flight ? '也可以飞' : '也可以坐'}
        </span>
        <span className="mr-0.5">{flight ? '✈️' : '🚄'}</span>
        <span>
          <span className="ui-num">{hhmm(t.depDateTime)}</span> {t.depStation}
        </span>
        <span className="text-gh-fg-faint">→</span>
        <span>
          <span className="ui-num">{hhmm(t.arrDateTime)}</span> {t.arrStation}
        </span>
        {t.serviceNo && <span className="ui-num">{t.serviceNo}</span>}
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
            className="text-gh-fg-faint hover:text-gh-accent"
          >
            {flight ? '查这个航班' : '看这趟车'}
          </a>
        )}
      </p>
      <p className="mt-0.5 text-xs text-gh-fg-faint">
        这是另一个走法，要不要选由你定；票价是查询当时的，机票会浮动。当天的时间安排按上面那一趟排的。
      </p>
    </div>
  )
}

/**
 * 今晚**还能住哪儿** —— 同城查到、但模型没选的那几家。
 *
 * ── 为什么它必须存在（2026-10-14）────────────────────────────────
 *   在这之前，用户的屏幕上每天只有**一家**酒店，而且他不知道那是从 10 家里挑的。
 *   后端一次查回 10 家，模型只挑 1 家，剩下 9 家连"存在过"都不显示 ——
 *   这跟机票那边把alternatives藏起来是同一个毛病，只是住宿更不容易被察觉：
 *   用户不觉得"只给一家"有什么问题，只会觉得"这家有点贵"。
 *   而这个项目的立场一直是：**给模型看多少、给用户多少可选，是两件事。**
 *
 * ── 三个刻意的取舍 ──────────────────────────────────────────────
 * ① **配色弱一档（灰调 + 琥珀标签），不抢主选。**
 *    今晚睡哪儿只有一个答案，主选是模型替这份行程做过的判断（离第二天第一个点近之类）。
 *    备选是"你也可以选它"，不是"系统也推荐它"。
 * ② **顺序是价格升序（后端排好的），顺着展示就行。**
 *    用户的原话是"档次似乎有点高" —— 那让他一眼看到更便宜的，就是这一行最大的用处。
 *    别在这里重新按距离或别的什么排：那会把"最便宜的"埋到中间去。
 * ③ **每一条都带订房链接。** 备选如果只能看不能订，那它就只是个装饰。
 *    它和主选用的是同一个 bookUrl 字段（带入离日期的飞猪详情页），所以是能直接下单的。
 */
function HotelAlternativeLine({ hotel: h }: { hotel: HotelStay }) {
  return (
    <div className="mb-2 text-gh-fg-muted">
      <p className="flex flex-wrap items-baseline gap-x-2">
        <span className="rounded-full bg-gh-accent-soft px-2 py-0.5 text-xs text-gh-accent">
          也可以住
        </span>
        <span className="mr-0.5">🏨</span>
        <span>{h.name}</span>
        <span className="text-gh-fg-faint">
          {/* 每晚房价 —— 和主选同一个量纲，也和上面的门票不是一个量纲 */}
          {h.priceYuan === null ? '房价未知' : `¥${h.priceYuan}/晚`}
        </span>
        {h.star && <span className="text-gh-fg-faint">{h.star}</span>}
        {h.bookUrl && (
          <a
            href={h.bookUrl}
            target="_blank"
            rel="noreferrer"
            className="text-gh-fg-faint hover:text-gh-accent"
          >
            去飞猪订
          </a>
        )}
      </p>
      {h.address && <p className="mt-0.5 text-xs text-gh-fg-faint">{h.address}</p>}
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
 * 一段住宿在界面上的标注。
 *
 * ⚠️ 为什么不是直接一个字符串（"连住 3 晚"）—— 那是 2026-10-14 为了**备选酒店**改的：
 *   备选（hotelAlternatives）要挨着住宿卡片显示，而连住时后端每天都会带上同一份备选，
 *   天天渲染就重复了。所以需要知道"哪一晚是这一段的第一晚"。
 *   `first` 就是这个信息；`label` 仍然是原来那个"连住 N 晚"（只有 1 晚时是空串）。
 *
 * 为什么不另起一个 Map 装 first：两个 Map 的 key 集合必须永远一致，
 * 而它们是在同一个 while 循环里生成的 —— 拆开就多了一个"哪天对不上"的机会。
 */
interface StayMark {
  label: string
  first: boolean
}

/**
 * 把"第 N 天住哪"变成"这家酒店覆盖了哪几天"。
 *
 * 只给**连续段的第一天**放一个 entry（label + first），段内其余天不放 ——
 * 读的时候 `stays.get(day)` 是 undefined，正好就是"这不是第一晚"。
 * 纯函数，和 PlanAssembler 里"酒店允许重复"那条规矩是配套的：
 * 后端保留重复（因为那是对的），前端负责把它渲染成一个决定。
 */
function mergeStays(days: TravelDay[]): Map<number, StayMark> {
  const out = new Map<number, StayMark>()
  let i = 0
  while (i < days.length) {
    const h = days[i].hotel
    if (!h) {
      i++
      continue
    }
    let n = 1
    while (i + n < days.length && days[i + n].hotel?.hotelId === h.hotelId) n++
    out.set(days[i].day, { label: n > 1 ? `连住 ${n} 晚` : '', first: true })
    i += n
  }
  return out
}
