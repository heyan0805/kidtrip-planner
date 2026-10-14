package com.hellen.kidtrip.plan;

import java.util.List;

/**
 * 第二步（画像 → 检索条件）的产物，由大模型**受控输出一次**。
 * 逐字镜像前端 `types/plan.ts` 的 `RetrievalPlan`。
 *
 * 注意这个类型在本次 B4 里的地位：它还**不是**模型产出的 ——
 * B4 只做第六段（组装攻略）。这一步目前由调用方（脚本 / 以后的前端）直接给进来，
 * 等 B5 把"画像 → 检索条件"那次受控调用做出来，它才由模型填。
 *
 * 为什么要现在就把它写进契约：因为 `PlanProvenance` 要回传它 ——
 * 用户有权看见"这次是按什么条件去查的"。先定形状，再补产出它的那一端，
 * 是"契约先行"的正常节奏，不是过度设计。
 */
public record RetrievalPlan(
        List<String> wantedCategories,
        List<String> keywords,
        Boolean needHotel
) {

    /** 兜底：没给检索计划时用它 —— 不限类别、要酒店。 */
    public static RetrievalPlan fallback() {
        return new RetrievalPlan(List.of(), List.of(), true);
    }

    public RetrievalPlan orDefaults() {
        return new RetrievalPlan(
                wantedCategories == null ? List.of() : wantedCategories,
                keywords == null ? List.of() : keywords,
                needHotel == null || needHotel
        );
    }
}
