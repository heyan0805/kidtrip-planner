package com.hellen.kidtrip.plan;

import java.util.List;

/**
 * `POST /api/plan/chat` 的请求体。**这就是"上下文传递"的后端一侧**。
 *
 * 三个字段，各自都是"这一轮要接着往下聊"所必需的：
 *   messages       —— 到目前为止的**全部对话**（用户说的 + 助手说的），不含本轮提问
 *   currentParams  —— 到目前为止**已经攒出来的画像**
 *   currentPersona —— 到目前为止**已经从他的话里推出来的判断**（2026-10-15 加）
 *
 * ── currentPersona 为什么也要带回来 ──────────────────────────────
 *   因为 persona 是**增量合并**的（见 Persona.mergedWith），而合并需要"截至目前为止"那一份。
 *   不带回来的话，每一轮都会从空 persona 开始 —— 上一轮推出的判断下一轮就没了，
 *   而模型看到的【已经推出来的判断】永远是空的，也就永远做不了"改主意"这件事。
 *
 *   ⚠️ 和 currentParams 是同一条规矩：**原样带回来**，不要自己加工。
 *      谁做的合并，谁负责那份完整状态。
 *
 *   它**可以不给**（第一轮对话，或者调用方只关心画像）—— 缺省时按"还没有任何判断"处理。
 *   但和 currentParams 一样，**不要自己塞一个默认值**。
 *
 * 为什么不带 sessionId / 不存服务端会话：
 *   因为"上下文"在这套设计里是**客户端持有的一份状态**，服务端每次是无状态地重算。
 *   这样做的代价是每轮都要把历史重发一遍（token 费），换来的好处是：
 *     ① 服务端不需要会话存储、不需要过期清理、不怕实例重启；
 *     ② 用户刷新页面/换设备，只要把这份状态带上就能接着聊；
 *     ③ 出问题时，"模型看到了什么"完全等于"请求体里有什么"，可复现、可粘贴、可存成测试用例。
 *   对这类"对话短（几轮到十几轮）、并发低"的场景，这个交换是划算的。
 *   真到了需要长会话的时候再加 sessionId —— 那时会有别的理由，而不是"一开始就这么设计"。
 *
 * 注意 currentParams 是「**累积值而不是本轮增量**」：前端每轮把上一次返回的 params
 * 原样带回来。这样服务端不用自己维护状态，也能做到"用户改了主意"时立刻跟上。
 */
public record PlanChatRequest(
        List<Message> messages,
        TravelParams currentParams,
        Persona currentPersona
) {

    public List<Message> messagesOrEmpty() {
        return messages == null ? List.of() : List.copyOf(messages);
    }

    /**
     * 上一轮攒下来的判断；没给就是"还没有"。
     *
     * 归一成 empty() 而不是留 null，是为了让调用方只判空、不判 null ——
     * 少一个三态就少一类漏判（这条和 Persona.Inference 把 null 归一成空串是同一个取舍）。
     */
    public Persona currentPersonaOrEmpty() {
        return currentPersona == null ? Persona.empty() : currentPersona;
    }

    /**
     * ⚠️ 这里**刻意没有** `currentParams.orDefaults()`。
     *
     * orDefaults() 会把 days=null 补成 3 —— 那在 /generate 里是对的（总要有个天数才能排），
     * 但在对话里是**错的**：它会把"用户还没说过几天"变成"画像里写着 3 天"，
     * 于是模型以为这个字段已经问清楚了，再也不问。
     * 用户就会拿到一份"自己没说天数、系统自作主张按 3 天排"的行程。
     *
     * 同一个方法，在两条路径上用不同的方式 —— 这正是"默认值该在什么时候补"这件事
     * 不能一刀切的原因：**补默认值的时机，取决于"这个默认值会不会被误当成用户的回答"。**
     */
    public TravelParams currentOrEmpty() {
        return currentParams == null
                ? TravelParams.empty()
                : currentParams;
    }
}
