package com.hellen.kidtrip.llm;

import dev.langchain4j.model.chat.ChatModel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 测试用的假模型：按顺序吐出预先准备好的字符串，不发任何网络请求。
 *
 * 为什么值得单独写一个类，而不是每个测试里内联一个匿名类：
 *   因为"模型会返回什么鬼东西"是这份工程里**最需要反复验证**的一类输入
 *   （带围栏的 JSON、缺字段的 JSON、纯废话、超长输出……）。
 *   有了它，试一次"模型不听话"只要 5 毫秒，而不是 20 秒加一笔 token。
 *   这和 PlanAssembler 能被单测是同一件事：
 *   **把不确定性挡在纯函数之外，再用假输入去撞它。**
 *
 * 实现上有个便宜可占：ChatModel 的 13 个方法**全都是 default**，
 * 所以只覆盖 `chat(String)` 就够了 —— 不需要去构造 ChatRequest / ChatResponse 那一串。
 * （哪天它把某个方法改成抽象方法，这里会立刻编译失败，而不是悄悄失效。）
 */
public class FakeChatModel implements ChatModel {

    private final Deque<String> replies = new ArrayDeque<>();
    /** 收到过的 prompt。假模型只"吐"是不够的 —— 有几件事的关键在于**我们喂进去了什么** */
    private final List<String> prompts = new ArrayList<>();
    private RuntimeException failWith;
    private int calls = 0;

    public FakeChatModel(String... replies) {
        this.replies.addAll(List.of(replies));
    }

    /** 造一个"一调用就炸"的模型，用来测调用失败（网络/超时/401）那条路径 */
    public static FakeChatModel failing(RuntimeException e) {
        FakeChatModel m = new FakeChatModel();
        m.failWith = e;
        return m;
    }

    /** 被调了几次 —— 用来钉住"重试一次"和"缓存命中就不该再调"这两件事 */
    public int calls() {
        return calls;
    }

    /**
     * 收到过的第一条 prompt。
     *
     * 为什么需要它：有一类 bug 的形状是"**代码里的数据是对的，但它没被送进模型那一步**"——
     * 比如车次查出来了、也挂到响应上了，可排程时模型压根不知道（于是排出一份走不成的行程）。
     * 这种错在返回值上**看不出来**（响应里车次明明是对的），只能从"喂进去的那段文本"上验。
     * 所以测试有时要断言的不是输出，而是输入。
     */
    public String firstPrompt() {
        return prompts.isEmpty() ? null : prompts.get(0);
    }

    @Override
    public String chat(String prompt) {
        calls++;
        prompts.add(prompt);
        if (failWith != null) {
            throw failWith;
        }
        if (replies.isEmpty()) {
            return "{}";
        }
        // 只剩最后一条时**反复给同一条**，而不是取完就没了。
        // 因为"第三次调用"通常不是测试的重点，但如果它意外发生了，
        // 重复最后一次比抛异常更容易暴露问题（异常会被降级逻辑吃掉，你就看不到它发生了）。
        return replies.size() == 1 ? replies.peek() : replies.poll();
    }
}
