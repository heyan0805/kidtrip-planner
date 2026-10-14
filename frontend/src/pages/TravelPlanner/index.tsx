import { useEffect, useRef, useState } from 'react'
import { applySwaps, swapKey, usePlanChat } from '../../hooks/usePlanChat'
import type { SpotEdits } from '../../hooks/usePlanChat'
import type { SpotOption, Transfer, TravelDay, TravelParams, TravelPlan, TravelSpot } from '../../types/plan'

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

  // 新消息进来时滚到底 —— 否则用户看不到自己刚说的话（消息在下面）
  useEffect(() => {
    const el = listRef.current
    if (el) el.scrollTop = el.scrollHeight
  }, [chat.messages.length, chat.sending])

  const busy = chat.sending || chat.generating

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
            <ProfileCard params={chat.params} />

            <button
              type="button"
              onClick={() => void chat.generate()}
              disabled={!chat.canGenerate || busy}
              className="w-full rounded-lg bg-gray-900 px-4 py-2.5 text-sm font-medium text-white hover:bg-gray-800 disabled:bg-gray-300"
            >
              {chat.generating ? '正在生成（要几十秒）…' : '生成行程'}
            </button>
            {!chat.canGenerate && (
              <p className="text-xs text-gray-400">至少要知道去哪座城市，才能开始查。</p>
            )}
            {chat.generating && (
              <p className="text-xs text-gray-400">
                这一步要真的去查飞猪、再让模型排期，25~60 秒是正常的。
              </p>
            )}
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

function ProfileCard({ params }: { params: TravelParams }) {
  return (
    <div className="rounded-xl border border-gray-200 bg-white p-4">
      <h2 className="text-sm font-medium text-gray-900">已收集的信息</h2>
      <p className="mt-1 text-xs text-gray-400">这就是"上下文"里被提炼出来的那部分</p>
      <dl className="mt-3 space-y-2">
        {FIELDS.map((f) => (
          <div key={f.key} className="flex items-start justify-between gap-3 text-sm">
            <dt className="text-gray-500">{f.label}</dt>
            <dd className="text-right font-medium text-gray-900">{renderField(f.key, params)}</dd>
          </div>
        ))}
      </dl>
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
  return (
    <div className="mb-2 text-gray-700">
      <p>
        <span className="mr-1">🚄</span>
        <span className="font-medium">
          {hhmm(t.depDateTime)} {t.depStation}
        </span>
        <span className="mx-1 text-gray-400">→</span>
        <span className="font-medium">
          {hhmm(t.arrDateTime)} {t.arrStation}
        </span>
        {t.trainNo && <span className="ml-2 text-gray-500">{t.trainNo}</span>}
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
      {(!t.direct || t.jumpUrl) && (
        <p className="mt-1 flex items-center gap-2 text-xs">
          {!t.direct && (
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
              去飞猪看这趟车
            </a>
          )}
        </p>
      )}
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
