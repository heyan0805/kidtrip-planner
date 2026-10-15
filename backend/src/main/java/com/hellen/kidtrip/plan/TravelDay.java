package com.hellen.kidtrip.plan;

import com.hellen.kidtrip.retrieval.Transfer;

import java.util.List;

/**
 * 一天的行程。逐字镜像前端 `types/plan.ts` 的 `TravelDay`。
 *
 * ── `transfer` 和 `hotel` 是一对，回答的是同一类问题 ──────────────
 *   transfer —— **今天怎么到的**（城际交通，真实车次）
 *   hotel    —— **今晚住哪**
 *
 * 两者都是"每一天都有落点"，而且都各有三种合法状态：
 *   transfer：① 有值（真实车次）；② null 且不是首/末日（理论上不会出现，单城市行程只有两段）；
 *             ③ null 且是首/末——**没查**或**同城**（出发地==目的地），见 warnings / 画像
 *   hotel   ：① 有值 ② null 且后面还有行程（没查到）③ null 且是最后一天（当天返程）
 *
 * ⚠️ 方向**不靠天数推断，而是写在 Transfer 自己的 from / to 里** ——
 *    因为"第 1 天就是去程、最后一天就是回程"这个规则只在单城市行程下成立。
 *    等以后真支持多城市串联，中间几天也会有 transfer，那时方向只能来自数据本身。
 *    现在就把方向放进数据结构，是为了让"以后加多城市"**不用改契约**。
 *
 * ── 为什么允许相邻天是同一家酒店（"连住"）──────────────────────
 *   这是带娃旅行最反直觉的一条 —— 出发前觉得"每天换个地方更有新鲜感"，
 *   实际换一次酒店等于废掉半天（收拾、退房、寄存、入住、孩子重新适应环境）。
 *   所以正确的形状是"每天有落点"而**不是**"每天换一家"。
 *   前端渲染时比较相邻两天的 hotelId，相同就合并显示成"连住"。
 *
 * ⚠️ 这里刻意**没有** `nights` / `sameAsPrevious` 这类冗余字段：
 *   它们和 hotelId 是同一个真相的两种说法，多一个就多一个能对不上的地方。
 *   前端一行比较就能算出来的东西，不要塞进契约。
 *   —— 但这条规矩的**边界**要看清楚：`Transfer.depDateTime` 里的日期就不是冗余
 *   （夜车跨午夜时，它是唯一能说清"到底哪天到"的东西）。**能算出来的不存，算不出来的必须存。**
 *
 * ── `alternatives`：那一段路**还有别的走法**（2026-10-14 加机票时加的）────
 *   它和 transfer 的关系是"主选 / 备选"，不是"两个独立的交通"：
 *   同一天同一段路，用户要么坐火车要么坐飞机，不会两个都坐。
 *   所以它**不参与排程**（模型看不到它，见 PlanComposer.renderLegs），
 *   只回答用户一句"其实也可以飞，价格和时间是这样"。
 *
 *   ⚠️ 但它**不是**"飞机的专用字段"：里面装的是 Transfer（带 mode），
 *      以后要放大巴/轮渡也是往这里放。字段名叫 alternatives 而不是 flight，
 *      是为了让加第三种载体时**不用改契约**。
 *
 * ── `hotelAlternatives`：今晚**还能住哪儿**（2026-10-14 加）────────────
 *   和上面的 alternatives 是同一个思路，只是换成了住宿：
 *   hotel 是"主选"（模型的判断，会进 prompt），hotelAlternatives 是**另外几家**，
 *   同样**不进 prompt**，纯粹给用户看。
 *
 *   为什么住宿也需要这一层：上游一次查回 10 家，模型只挑 1 家，剩下 9 家原本直接丢掉 ——
 *   而用户看到的是一张"就住这儿"的卡片，连"还有别的选择"都不知道。
 *   和机票的区别是它**不来自另一次查询**：这些酒店本来就在候选池里，
 *   我们只是没把它露出来。（"给模型看多少"和"给用户多少可选"是两件事 ——
 *   景点那边早就这么分了，见 PlanService 里备选池那段注释。）
 *
 *   ⚠️ 连住时同一个酒店覆盖多天，所以**相邻几天的备选列表会是同一份**。
 *      这是刻意不去的重：去掉就得在这里引入"哪几天是同一段连住"的知识，
 *      而那是前端 mergeStays 已经在算的事（它一行比较 hotelId 就有了）。
 *      契约层多一个"能算出来的"字段，就多一个能对不上的地方。
 *
 * ── `why`：这一天**为什么是这个形状**（2026-10-15 加）────────────────────
 *   一句话，**由后端用本地算得出的事实拼装**，不是模型写的。
 *   理由写在 {@link DayReasons} 的类注释里，这里只说清它在契约上的三件事：
 *
 *   ① **可以是 null**，含义是"这一天没有值得解释的形状"（比如只有一个点、没有车次、
 *      也没连住）。前端遇到 null 就**什么都不渲染** —— 不要替它编一句"这样安排更轻松"，
 *      那种句子对任何一天都成立，等于一句废话。
 *   ② 它和 `theme` 的分工：theme 是"今天玩什么"（模型写的，会进 prompt），
 *      why 是"为什么这么排"（我们算的，**不进 prompt**）—— 一进一出的方向正好相反，
 *      所以别把两者合并成一句。
 *   ③ 空白会被归一成 null（紧凑构造器里那一行）：理由是"**一个字段只有一种表示'没有'的方式**"。
 *      留着 `""` 的话，前端就得同时防 null 和空串，而漏掉后者的症状是**凭空多出一个空段落**
 *      （`{day.why && …}` 在 JS 里对 `""` 是假值，看着没事；但别处只要有人写成
 *      `day.why !== null && …`，那一行就会渲染成一个只有边框没有内容的空气泡）。
 */
public record TravelDay(int day, String theme, List<TravelSpot> spots, Transfer transfer,
                        List<Transfer> alternatives, HotelStay hotel,
                        List<HotelStay> hotelAlternatives, String why) {

    public TravelDay {
        spots = spots == null ? List.of() : List.copyOf(spots);
        alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
        hotelAlternatives = hotelAlternatives == null ? List.of() : List.copyOf(hotelAlternatives);
        why = why == null || why.isBlank() ? null : why.strip();
    }
}
