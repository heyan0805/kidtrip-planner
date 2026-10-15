package com.hellen.kidtrip.plan;

/**
 * `POST /api/plan/chat` 的 data 字段。逐字镜像前端 `types/plan.ts` 的 `PlanChatData`。
 *
 * 三个字段，但它们的分工必须分清 —— **多出来的那一个（persona）和前两个不是一类东西**：
 *   reply   —— 给**人**看的：助手这一轮要说的话
 *   params  —— 给**机器**用的：合并后的完整画像（前端拿它去更新状态、判断能否开始生成）
 *   persona —— 给**人**看的另一件事：**我们从这个用户身上看出了什么**，每条都带他的原话
 *
 * ── persona 为什么和 params 在同一次响应里，却必须是两个字段 ────────────
 *   它们来自同一次模型调用（所以放一起，理由见下），但**性质相反**：
 *     params 是用户**说过的**（事实，写错了拿原话就能核对）；
 *     persona 是我们**推出来的**（推论，只能拿原话当依据）。
 *   混成一个字段的后果很具体：`TravelParams.mergedWith` 的"没答的字段保留原值"
 *   会把推错的结论永久粘住，而且它会被下一轮的 prompt 当成**事实**渲染出去 ——
 *   于是"我们猜的"变成了"用户说的"。**详见 Persona 的类注释第 ①② 条。**
 *
 *   前端也照这个分工用：params 拿去更新画像卡和生成时机，persona 单独渲染成
 *   「我这样理解你」那张卡片，而且**把它原样带回来**（`/generate` 的请求体里）。
 *   不带回来的话，第二次以后的行程就看不见我们的判断了 —— 而它正是"懂你"这件事的全部。
 *
 * 为什么不分两个接口（一个出话术、一个出画像）：
 *   它们来自**同一次模型调用、同一段对话上下文**。
 *   拆成两次调用就要把同样的历史发两遍、算两遍 —— 花两份钱去得到一份结果，
 *   而且两次之间还可能不一致（第一份话说"你想玩几天"、第二份画像里天数已经有了）。
 *   **同一份上下文推出来的东西，就该在同一次调用里产出。**
 */
public record PlanChatData(
        String reply,
        TravelParams params,
        /**
         * 我们目前对这个用户的判断。**可能为空**（第 1 轮、或这轮一句偏好都没推出来）——
         * 那不是缺参数，是正常状态：空 persona 就相当于"还不了解他"。
         */
        Persona persona
) {

    public PlanChatData {
        params = params == null
                ? TravelParams.empty()
                : params;
        // 归一成 empty() 而不是留 null：前端就不用判两种"没有"。
        // 而且 empty() 是**不可变单例语义**的值对象，不是需要区分实例的东西。
        persona = persona == null ? Persona.empty() : persona;
    }
}
