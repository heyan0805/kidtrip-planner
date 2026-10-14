package com.hellen.kidtrip.plan;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * 出发日期的守门 —— 把"模型猜出来的年份"和"用户真正的意思"对齐。
 *
 * ── 它为什么必须存在（2026-10-13 用户在界面上撞出来的）────────────────
 *   用户说"我想 **11 月 17 日**带孩子…玩 6 天"，模型给出的画像是：
 *     startDate = **2025**-11-17
 *   而真实今天是 2026-10-13 —— 它填了一个**已经过去的日期**，差了整整一年。
 *
 *   根因不是模型算错，是**我们从来没告诉它今天几号**。
 *   ChatComposer 的 prompt 里没有当前日期，模型只能拿自己训练时的"当下"去推年份 ——
 *   这必然错，且错得毫无规律。这类"让模型去猜它不可能知道的事实"是 prompt 的经典坑：
 *   凡是模型算不出来的，就得给它们；不能给，就得自己兜。
 *
 * ── 兜的方式：过去的日期一律顺延到"最近的那一次" ──────────────────────
 *   用户说"11月17日"时，他指的是**接下来那个 11 月 17 日**，不可能是去年的。
 *   所以规则只有两条，都是可以机械执行的：
 *     ① 解析不出来（不是 ISO 日期）→ 当作没答，交给上游保留原值；
 *     ② 解析出来了但在今天之前 → 顺延到"最近的将来那一次"。
 *
 * ⚠️ 为什么用 withYear 而不是"逐年 +1"的循环：
 *   逐年加要设一个上限（加几年算够？），上限一旦比实际差距小，就会返回"修正失败"，
 *   而那个 2020 年的输入（差距 6 年）刚好会把它撞出来。
 *   withYear(今天的年份) 一步就落在 [今天, 今天+1年) 里，再补一次 +1 年就落到位 ——
 *   **没有上限，也就没有"上限设小了"这个失败模式。**
 *
 * ⚠️ 一个刻意不做的判断：**不设"太远了"的上限**。
 *   比如"2035 年"看起来很可疑，但它也可能真的是用户在规划很远的一次旅行。
 *   我们没有依据说它错，就不判它错 —— 猜错了要背锅，不猜就不背。
 */
public final class TravelDates {

    /**
     * 规范化结果。
     *
     * 带上 corrected 而不是只返回字符串，是因为**"我们改过用户的东西"这件事必须能被看见** ——
     * 调用方要据此决定要不要记一笔（见 PlanService 里那条提醒）。
     * 只返回一个字符串的话，这个信息在半路上就丢了，用户会觉得日期是"凭空变了"。
     */
    public record Normalized(String date, boolean corrected) {
    }

    private TravelDates() {
    }

    public static Normalized normalize(String raw) {
        return normalize(raw, LocalDate.now());
    }

    /**
     * @param today 显式传进来，而不是在里面直接取 LocalDate.now() ——
     *              这样它就是一个**纯函数**，能被单测钉死在"2026-10-13 那天会发生什么"。
     *              时间相关的方法如果自己取当前时间，测试就只能写成"相对现在"，
     *              那种测试过两年会自己变红。
     */
    public static Normalized normalize(String raw, LocalDate today) {
        if (raw == null || raw.isBlank()) {
            return new Normalized(null, false);
        }
        LocalDate date;
        try {
            date = LocalDate.parse(raw.strip());
        } catch (DateTimeParseException e) {
            // 不是合法 ISO 日期 → 丢掉，当作"这一轮没听到日期"。
            // 注意这里返回的也是 null，和"没答"是同一个结果：对下游来说，一个解析不出来的日期
            // 和一个没有日期是一样的（都不能用），而"能用的旧值"由 mergedWith 保留。
            return new Normalized(null, false);
        }

        if (!date.isBefore(today)) {
            return new Normalized(date.toString(), false);
        }

        // 过去的日期永远不是一个有效的出发日期 —— 顺延到最近的那一次。
        LocalDate rolled = date.withYear(today.getYear());
        if (rolled.isBefore(today)) {
            rolled = rolled.plusYears(1);
        }
        return new Normalized(rolled.toString(), true);
    }
}
