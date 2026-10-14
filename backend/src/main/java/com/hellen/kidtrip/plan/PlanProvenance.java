package com.hellen.kidtrip.plan;

import java.util.List;

/**
 * 这份行程是怎么来的。
 *
 * 它和 `SearchProvenance`（检索层的那个）的关系是"投影"，不是复制：
 * 检索层的溯源有 13 个字段（含上游调用数、缓存命中数、真实规则对象…）——
 * 那些是**给我们排障用的**；这个只有 8 个字段，是**给用户看的**。
 * 两层各自保留自己需要的粒度，中间是一次显式映射（见 PlanService），
 * 而不是"把内部对象直接序列化出去"。
 *
 * 后者看着省事，实际是最容易把内部结构泄露成公开契约的做法 ——
 * 一旦前端用了 `upstreamCalls`，你就再也不能自由地重构检索层了。
 */
public record PlanProvenance(
        /** 本次实际按什么条件去查的 —— 让用户看见"我查了什么" */
        RetrievalPlan retrievalPlan,
        /** 真实发出去的查询命令，逐条。排障时最常见的误判是"我以为我查的是 A" */
        List<String> queries,
        /** 1 = 只有宽查；2 = 还补查过 */
        int searchRounds,
        int candidatesFound,
        /**
         * 可用候选数 —— **景点 + 酒店**。
         *
         * 这条注释 2026-10-13 改过一次：以前写的是"只数景点"，因为那时酒店不进行程，
         * 算进来是虚的。现在酒店真的落到每一天上了，再只数景点，用户会看到
         * "可用 10 条"而他自己数出来还多一家酒店 —— 数字对不上他的观察，比数字难看更糟。
         */
        int candidatesKept,
        List<RejectedCandidate> rejected,
        /** 有没有放宽过规则（放宽不花上游调用） */
        boolean relaxed,
        /** 整个 /generate 的耗时（毫秒），含检索 + 大模型 */
        long elapsedMs
) {
}
