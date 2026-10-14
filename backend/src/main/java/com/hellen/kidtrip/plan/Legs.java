package com.hellen.kidtrip.plan;

import com.hellen.kidtrip.retrieval.Transfer;

import java.util.List;

/**
 * 这趟行程的**两段真实城际交通**：家 → 目的地（去程）、目的地 → 家（回程）。
 *
 * ── 它为什么必须是一个独立的类型，而不能等到挂到某一天上 ─────────────
 *   因为查它的时刻**比"每一天排什么"更早**。这两段的日期只由画像决定：
 *     去程日 = startDate            回程日 = startDate + 天数 - 1
 *   —— 一个不需要知道"排了哪些景点""几天能排满"就能算出来的东西。
 *
 *   而在拿到它们之前，排程那一步是**瞎的**：实测撞到过一份行程，里面第 5 天下午 15:00
 *   排着珠江夜游，而它挂的那趟返程车是当天 06:18 发的 —— 人早在江边活动开始前就上车了。
 *   两个字段单看都"有道理"，拼起来是个不可能成立的行程。
 *   这类矛盾不是某一处算错了，而是**两处各自都算对了、却互相不知道对方**。
 *
 *   所以顺序改成：先查这两段事实 → 让排程看见它们 → 再挂到第一天和最后一天上。
 *   ⚠️ 这**没有多花任何一次上游调用**（原来也是查这两段），只是把已有结果往前用了一步。
 *
 * ── 一段为什么是一组走法，而不是一个 ────────────────────────────────
 *   2026-10-14 加机票之后，同一段路可能**同时**有火车和飞机两个答案。
 *   于是每一段是一个 {@link Options}：`primary` 是"按它安排当天时间"的那一个，
 *   `alternatives` 是"同一段路还有别的走法"。分成两个字段而不是一个 List，
 *   是因为这两者的**用途完全不同**：
 *     primary      → 进 prompt（模型要按它的发车/到达时刻安排第 1 天和最后一天）
 *     alternatives → 只给用户看（不进 prompt，见 {@link Options} 的注释）
 *
 * ── 为什么允许两段都是 null ────────────────────────────────────
 *   "查不到"和"没问出发地"都不是错误（见 PlanService.canQueryLegs）。它是一份
 *   **尽力而为的情报**，不是前提条件：没有它，行程照样排得出，只是那两天的安排
 *   少一个真实约束、行程里少一行交通。
 */
public record Legs(Options outbound, Options inbound) {

    /**
     * 同一段路的全部走法。
     *
     * ── 为什么 alternatives **不进 prompt** ──────────────────────────
     *   模型要做的事只有一件：按 primary 的时刻决定"第 1 天第一个点最早几点"和
     *   "最后一天最后一个点最晚几点"。把"另外还可以飞"也喂给它，它大概率会在 tips 里
     *   写一句"也可以考虑飞机"—— 一句**我们无法核对**的话（价格、时刻都可能被它改写）。
     *   而它一个字都不会让排程更准：当天的时间安排只有一个答案。
     *   所以：**能算准的东西留在代码里，只有需要它做选择的东西才进 prompt。**
     *
     * @param primary      这一段的主选走法。null = 这一段什么走法都没查到
     * @param alternatives 同一段路的其它走法（目前只可能是飞机：火车那条腿带娃偏累时才查）。
     *                     **永远非 null**（没有就是空 list），调用方不必判空
     */
    public record Options(Transfer primary, List<Transfer> alternatives) {

        public Options {
            alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
        }

        /** 什么都没查到 */
        public static Options empty() {
            return new Options(null, List.of());
        }

        public boolean isEmpty() {
            return primary == null && alternatives.isEmpty();
        }
    }

    /** 一段都没有 —— 缺出发地 / 同城 / 查询失败，都用这一个值表达，调用方不必分支 */
    public static Legs empty() {
        return new Legs(Options.empty(), Options.empty());
    }

    public boolean hasAny() {
        return outbound.primary() != null || inbound.primary() != null;
    }

    /** 去程的主选走法 —— prompt 和首日冲突检查都只认它 */
    public Transfer outboundTransfer() {
        return outbound.primary();
    }

    /** 回程的主选走法 */
    public Transfer inboundTransfer() {
        return inbound.primary();
    }
}
