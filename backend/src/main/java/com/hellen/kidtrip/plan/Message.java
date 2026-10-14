package com.hellen.kidtrip.plan;

/**
 * 一条对话消息。逐字镜像前端 `types/plan.ts` 的 `Message`。
 *
 * 它为什么在 plan 包而不是 llm 包：
 *   因为它是**我们对外承诺的形状**（前端要按它拼请求体）。
 *   llm 包里的东西是我们"借来的工具"，plan 包里的东西是我们"答应别人的事"。
 *   一个类型该放哪儿，看它是被谁约束的。
 */
public record Message(String role, String content) {

    public static final String USER = "user";
    public static final String ASSISTANT = "assistant";

    public Message {
        content = content == null ? "" : content.strip();
    }

    /**
     * 把 role 归一成 user / assistant 两种。
     *
     * **非法值一律当 user**，这是个有偏向的兜底，理由是不对称的：
     *   当成 user（"用户说了这句话"）→ 模型最多多读一句用户的话，无害；
     *   当成 assistant（"我已经这么答过"）→ 模型会认为这句话是**自己说过的**，
     *   于是可能不再回答它，或者把里面的信息当成自己编的 —— 那是有害的方向。
     * 拿不准时，选代价小的那一边。
     */
    public String normalizedRole() {
        return ASSISTANT.equalsIgnoreCase(role == null ? null : role.strip()) ? ASSISTANT : USER;
    }

    public boolean isUser() {
        return USER.equals(normalizedRole());
    }
}
