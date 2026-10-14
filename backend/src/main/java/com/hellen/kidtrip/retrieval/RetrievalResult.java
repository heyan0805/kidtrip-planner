package com.hellen.kidtrip.retrieval;

import com.hellen.kidtrip.candidate.Candidate;
import com.hellen.kidtrip.rule.PlanRules;
import com.hellen.kidtrip.rule.Rejected;

import java.util.List;

/**
 * 检索结果 = 候选池 + 溯源。
 *
 * 为什么溯源（Provenance）和候选池**平级**返回，而不是塞进日志：
 *   "这份结果是哪来的"是产品需求，不是排障需求。用户有权看见：
 *   查了几次上游、花了多久、筛掉了谁、为什么。尤其在我们这套架构里
 *   （大模型只做选择、事实全部来自飞猪），**可追溯性就是可信度本身**。
 */
public record RetrievalResult(
        /** 景点 + 酒店混在一个数组里，靠 kind 区分（对齐前端 `type Candidate = PoiCandidate | HotelCandidate`） */
        List<Candidate> candidates,
        Provenance provenance
) {

    public record Provenance(
            /** 实际按什么条件查的（已补齐默认值之后的版本） */
            RetrievalRequest request,
            /** 实际生效的规则（可能已经被放宽过） */
            PlanRules rules,
            /** 真实发出去的命令，逐条。排障时最常见的误判是"我以为我查的是 A" */
            List<String> queries,
            /** 1 = 只有宽查；2 = 还补查过 */
            int searchRounds,
            /** 飞猪一共回来了多少条（规范化之前） */
            int candidatesFound,
            /** 过完规则剩下多少条 */
            int candidatesKept,
            List<Rejected> rejected,
            /** 有没有放宽过规则（放宽**不花**上游调用） */
            boolean relaxed,
            /** 画像期望、但最终**仍然**一条都没有的类别（决定要不要补查的是补查**前**的那个列表，这里是补查后的结论） */
            List<String> missingCategories,
            List<String> warnings,
            /** 真正花掉的上游调用次数（缓存命中的不算）—— 这个数字直接对应限流额度 */
            int upstreamCalls,
            int cacheHits,
            long elapsedMs
    ) {
    }
}
