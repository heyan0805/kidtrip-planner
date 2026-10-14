package com.hellen.kidtrip.plan;

import java.util.List;

/**
 * 行程里的一个点。逐字镜像前端 `types/plan.ts` 的 `TravelSpot`。
 *
 * 和"模型写的草稿"（llm.PlanDraft.Item）的差别，就是这个类的全部意义：
 * 除了 time / tips，**其余字段全部由后端从候选池里回填**。
 * 行程里每一个点都能顺着 poiId 追回飞猪上的那条真实商品。
 *
 * tips 和 theme 是模型的活 —— 那是"表达"，不是"事实"。
 * 大模型在这套架构里只做两类事：**从给定选项里选择**、**把选择表达成人话**。
 */
public record TravelSpot(
        String time,
        String name,
        String tips,
        /** 稳定景点标识。去重与追溯靠它 —— 别用 bookUrl，那个每次请求都在变 */
        String poiId,
        Double priceYuan,
        String bookUrl,
        String imageUrl,
        Double lat,
        Double lng
) {
}
