package com.hellen.kidtrip.retrieval;

/**
 * 一段城际交通**坐什么走**：火车还是飞机。
 *
 * ── 为什么要显式存这个字段，而不是让前端/后端各自去猜 ───────────────
 *   上游其实也给了载体（`segments[].transportType` 直接写着 "火车" / "飞机"），
 *   但我们**不用它**，理由有两条：
 *     ① 我们自己知道得更早、更可靠 —— 查的是 `search-train` 还是 `search-flight`，
 *        这件事在发请求的那一刻就是确定的，不依赖任何下游字段；
 *     ② 它是"这一段是什么"的属性，不是"某一条 segment 是什么"的属性。
 *        中转方案有 N 个 segment，让调用方去 first.transportType() 里读，
 *        就等于把"哪些字段能信"的知识又复制了一份 —— 和 direct / transferCity
 *        显式存下来是同一条理由（见 Transfer 的注释）。
 *
 * ⚠️ **它不参与任何判断。** 没有一处规则写成"如果是飞机就……"：
 *    飞机的时长上限、时段窗口和火车用的是同一套（见 LegService.acceptable）。
 *    这个枚举的作用只是**把已定的事实带下去**（给界面换图标、给文案换个说法）。
 *    哪天有人想在这里加一条"飞机不受 X 限制"，先想清楚那是不是在偷偷取消
 *    "每一段移动都不能太长"这个项目的唯一约束 —— 本项目的答案是：不是。
 */
public enum TransportMode {
    /** 火车（高铁 / 动车 / 城际）。字段名是 `search-train` 换来的 */
    TRAIN("火车"),
    /** 飞机。走 `search-flight` —— 它**没有**服务端时长筛选，红线只能在本地判 */
    FLIGHT("飞机");

    private final String label;

    TransportMode(String label) {
        this.label = label;
    }

    /** "火车" / "飞机" —— 给用户看的文案用（日志、warnings）。界面有自己的图标映射 */
    public String label() {
        return label;
    }
}
