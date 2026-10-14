package com.hellen.kidtrip.plan;

/**
 * 被筛掉的一条 + 原因。逐字镜像前端 `RejectedCandidate`。
 *
 * 它和检索层的 `rule.Rejected` 内容一样、位置不同 —— 这是**故意**的：
 *   rule.Rejected 是**层内**的类型（检索层自己记账用），改了不影响接口；
 *   这个是我们对**前端承诺**的字段名，改它就要动契约。
 *   两者形状碰巧一样是巧合，不是可以合并的理由 —— 它们变化的原因不同。
 */
public record RejectedCandidate(String name, String reason) {
}
