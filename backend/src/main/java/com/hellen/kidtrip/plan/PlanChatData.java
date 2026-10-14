package com.hellen.kidtrip.plan;

/**
 * `POST /api/plan/chat` 的 data 字段。逐字镜像前端 `types/plan.ts` 的 `PlanChatData`。
 *
 * 只有两个字段，但它们的分工必须分清：
 *   reply  —— 给**人**看的：助手这一轮要说的话
 *   params —— 给**机器**用的：合并后的完整画像（前端拿它去更新状态、判断能否开始生成）
 *
 * 为什么不分两个接口（一个出话术、一个出画像）：
 *   它们来自**同一次模型调用、同一段对话上下文**。
 *   拆成两次调用就要把同样的历史发两遍、算两遍 —— 花两份钱去得到一份结果，
 *   而且两次之间还可能不一致（第一份话说"你想玩几天"、第二份画像里天数已经有了）。
 *   **同一份上下文推出来的东西，就该在同一次调用里产出。**
 */
public record PlanChatData(
        String reply,
        TravelParams params
) {

    public PlanChatData {
        params = params == null
                ? TravelParams.empty()
                : params;
    }
}
