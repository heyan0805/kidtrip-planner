package com.hellen.kidtrip.plan;

/**
 * `POST /api/plan/generate` 的请求体。
 *
 * 四个字段，各自的来历要说清楚：
 *   params              —— 画像（对话那一步的产物，也是这次组装的唯一依据）
 *   persona             —— **我们对这个用户的判断**（也是对话那一步的产物，每条带他的原话）。
 *                          **可以不给**。不给的效果只是"这份行程没有在为你做取舍"，
 *                          行程本身照样排得出来 —— 它是**加分项，不是前提**。
 *   retrieval           —— 检索条件（期望查什么）。**可以不给**，
 *                          不给就由 `RetrievalPlanner` 问一次模型，让它按画像规划出来（B5）。
 *                          为什么允许不给：这样这个接口既能被前端用（前端只管把 chat 拿到的
 *                          params 递进来，不用操心检索条件），也能被脚本和排障直接用
 *                          （只给 city 就能跑，不用为了验证先编一份检索计划）。
 *   maxTicketPriceYuan  —— 规则覆盖。它不是画像字段（用户没说过"预算 200"），
 *                          是我们为了**让规则校验能被看见**而留的旋钮：
 *                          默认规则不限价，不给这个字段的话"校验层到底有没有在工作"根本看不出来。
 *
 * ── persona 为什么是**原样递过来**，而不是在这里重新推一次 ────────────
 *   因为它是在**对话历史**上推出来的，而这个接口根本没有历史。
 *   想在这里重推，就得把整段历史也发过来 —— 那等于把两个接口的职责又揉回一起，
 *   而且会推两次（两次结果可能不一样，用户中途改过主意时必然不一样）。
 *   **谁有那份上下文，谁负责产出；这里只负责把它带进去。**
 *
 * 全部字段可空 —— 这是"入口处补默认值"的写法，而不是让下游每个方法都判一次 null。
 */
public record PlanGenerateRequest(
        TravelParams params,
        Persona persona,
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

    /**
     * 我们对这个用户的判断；没给就是"还没有任何判断"。
     *
     * 归一成 empty() 而不是留 null —— 理由和 `paramsOrEmpty` 一样（少一个三态就少一类漏判），
     * 但这里还多一层：**"没有 persona"是完全正常的输入，不是缺参数**。
     * 调用方可能压根没做对话（脚本、排障、只给 city 就跑），那时行程照样排得出来，
     * 只是没法在取舍上体现"懂你"。见 PlanGenerateRequest 类注释里那句"加分项，不是前提"。
     *
     * ⚠️ 注意这里**没有** `personaOrDefault()` 那种"没给就现推一份"的方法 ——
     *    persona 只能从**对话历史**上推，这个接口没有历史。
     *    留一个看似方便的兜底，只会让人以为"不传也能有"，从而把整条链路悄悄降级成
     *    "永远是空 persona" —— 和 PlanChatRequest 里那个被删掉的 retrievalOrDefault 是同一类陷阱。
     */
    public Persona personaOrEmpty() {
        return persona == null ? Persona.empty() : persona;
    }
}
