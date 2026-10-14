package com.hellen.kidtrip.plan;

/**
 * `POST /api/plan/generate` 的请求体。
 *
 * 三个字段，各自的来历要说清楚：
 *   params              —— 画像（对话那一步的产物，也是这次组装的唯一依据）
 *   retrieval           —— 检索条件（期望查什么）。**可以不给**，
 *                          不给就由 `RetrievalPlanner` 问一次模型，让它按画像规划出来（B5）。
 *                          为什么允许不给：这样这个接口既能被前端用（前端只管把 chat 拿到的
 *                          params 递进来，不用操心检索条件），也能被脚本和排障直接用
 *                          （只给 city 就能跑，不用为了验证先编一份检索计划）。
 *   maxTicketPriceYuan  —— 规则覆盖。它不是画像字段（用户没说过"预算 200"），
 *                          是我们为了**让规则校验能被看见**而留的旋钮：
 *                          默认规则不限价，不给这个字段的话"校验层到底有没有在工作"根本看不出来。
 *
 * 全部字段可空 —— 这是"入口处补默认值"的写法，而不是让下游每个方法都判一次 null。
 */
public record PlanGenerateRequest(
        TravelParams params,
        RetrievalPlan retrieval,
        Integer maxTicketPriceYuan
) {

    /**
     * ⚠️ 注意这里**没有** `retrievalOrDefault()` 了（B5 之前有，返回 fallback()）。
     *
     * 删掉它的原因是个真实的坑：留着它，PlanService 里就很容易写成
     * `request.retrievalOrDefault()` —— 而那一行会让"模型规划检索条件"这一步
     * **静默地永远不执行**（因为永远拿得到一个非 null 的 fallback）。
     * 代码能编译、能跑、结果看着也正常，只是 B5 白做了。
     *
     * 现在 retrieval 为 null 有明确含义："请帮我规划一次"，
     * 而这层判断**只能由编排层做**（它才知道要不要花那次调用）——
     * 所以决定权不该被一个便利方法提前吃掉。
     * 一个"看起来方便"的默认值方法，如果会让某条路径永远走不到，那它就是个陷阱。
     */
    public TravelParams paramsOrEmpty() {
        return params == null ? TravelParams.empty() : params;
    }
}
