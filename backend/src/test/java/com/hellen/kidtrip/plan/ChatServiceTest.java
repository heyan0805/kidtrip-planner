package com.hellen.kidtrip.plan;

import com.hellen.kidtrip.llm.ChatComposer;
import com.hellen.kidtrip.llm.FakeChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * chat 的编排层 —— 它只做一件事：**把不合格的输入挡在模型之前**。
 *
 * 这些都是"不需要理解语义就能判断"的校验，所以归这里，也所以它们能被
 * 一个普通单测钉住（而不是非要起 MockMvc 才能覆盖）——
 * 参照工程的同一层把这两条校验写在 Controller 里，那两条分支至今没有被测到过。
 */
@DisplayName("chat 编排：输入守卫 + 历史裁剪")
class ChatServiceTest {

    /** 把"编排层交给了模型什么"记下来 —— 这是验证"上下文真的被传下去了"的唯一办法 */
    private static final class CapturingComposer extends ChatComposer {
        private List<Message> seenMessages;
        private TravelParams seenParams;
        private Persona seenPersona;
        private int calls = 0;

        CapturingComposer() {
            super(new FakeChatModel("{\"reply\":\"好\"}"), new ObjectMapper());
        }

        @Override
        public PlanChatData chat(List<Message> messages, TravelParams current, Persona currentPersona) {
            calls++;
            this.seenMessages = messages;
            this.seenParams = current;
            this.seenPersona = currentPersona;
            return new PlanChatData("好", current, currentPersona);
        }
    }

    private static Message user(String c) {
        return new Message("user", c);
    }

    private static Message assistant(String c) {
        return new Message("assistant", c);
    }

    @Test
    @DisplayName("messages 为空 → 抛 IllegalArgumentException（会被翻译成 4000，不是 500）")
    void rejectsEmptyMessages() {
        ChatService svc = new ChatService(new CapturingComposer());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> svc.chat(new PlanChatRequest(List.of(), null, null)));

        assertTrue(e.getMessage().contains("messages 不能为空"), e.getMessage());
    }

    @Test
    @DisplayName("messages 整个没传（null）→ 同样被挡住，不是 NPE")
    void rejectsNullMessages() {
        ChatService svc = new ChatService(new CapturingComposer());

        assertThrows(IllegalArgumentException.class,
                () -> svc.chat(new PlanChatRequest(null, null, null)));
    }

    @Test
    @DisplayName("⭐ 最后一条是助手说的 → 挡住。这种'礼貌的答非所问'比报错难查得多")
    void rejectsHistoryEndingWithAssistant() {
        ChatService svc = new ChatService(new CapturingComposer());
        List<Message> history = List.of(user("想去珠海"), assistant("玩几天？"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> svc.chat(new PlanChatRequest(history, null, null)));

        assertTrue(e.getMessage().contains("最后一条"), e.getMessage());
    }

    @Test
    @DisplayName("最后一条是空的 → 挡住（没听到用户在问什么）")
    void rejectsBlankLastMessage() {
        ChatService svc = new ChatService(new CapturingComposer());
        List<Message> history = List.of(user("想去珠海"), new Message("user", "   "));

        assertThrows(IllegalArgumentException.class,
                () -> svc.chat(new PlanChatRequest(history, null, null)));
    }

    @Test
    @DisplayName("正常一轮 → 把全部历史和当前画像交给模型")
    void passesContextDown() {
        CapturingComposer composer = new CapturingComposer();
        ChatService svc = new ChatService(composer);
        TravelParams current = new TravelParams(null, "珠海", null, null, null, null, null, null);
        List<Message> history = List.of(user("想去珠海"), assistant("玩几天？"), user("3 天"));

        svc.chat(new PlanChatRequest(history, current, Persona.empty()));

        assertEquals(1, composer.calls);
        assertEquals(3, composer.seenMessages.size(), "整段上下文都要传下去");
        assertEquals("3 天", composer.seenMessages.get(2).content());
        assertEquals("珠海", composer.seenParams.city());
    }

    @Test
    @DisplayName("⭐ currentParams 不给也不能被 orDefaults 补出'3 天' —— 那等于替用户答了题")
    void doesNotPrefillDays() {
        CapturingComposer composer = new CapturingComposer();
        ChatService svc = new ChatService(composer);

        svc.chat(new PlanChatRequest(List.of(user("想去珠海")), null, Persona.empty()));

        assertNull(composer.seenParams.days(),
                "天数必须是'还没问过'，不能变成 3 —— 否则模型以为天数已经确定，再也不问");
        assertNull(composer.seenParams.stroller());
    }

    @Test
    @DisplayName("历史超长 → 从最早的开始丢，保留最新的 24 条")
    void trimsOldestMessages() {
        CapturingComposer composer = new CapturingComposer();
        ChatService svc = new ChatService(composer);
        List<Message> history = new ArrayList<>();
        for (int i = 1; i <= 15; i++) {           // 15 轮 = 30 条
            history.add(user("用户第 " + i + " 句"));
            history.add(assistant("助手第 " + i + " 句"));
        }
        history.add(user("最后一句"));            // 本轮提问 —— 最后一条必须是用户说的

        svc.chat(new PlanChatRequest(history, null, null));

        List<Message> sent = composer.seenMessages;
        assertEquals(24, sent.size(), "31 条里保留最新 24 条");
        assertEquals("最后一句", sent.get(sent.size() - 1).content(),
                "最新的必须在 —— 丢的是最早的那些");
        assertTrue(sent.stream().noneMatch(m -> m.content().contains("用户第 1 句")),
                "最早的那几句应该被丢掉了");
    }

    @Test
    @DisplayName("⭐ 上一轮攒的判断也要原样交给模型 —— 不传下去的话卡片每轮都从零开始")
    void passesPersonaDown() {
        CapturingComposer composer = new CapturingComposer();
        ChatService svc = new ChatService(composer);
        Persona previous = Persona.of(List.of(
                new Persona.Inference("walking", "少走路", "我想安排得放松一点")));
        List<Message> history = List.of(user("想去珠海"), assistant("玩几天？"), user("3 天"));

        svc.chat(new PlanChatRequest(history, null, previous));

        assertEquals("少走路", composer.seenPersona.valueOf("walking"));
    }

    @Test
    @DisplayName("没给 currentPersona → 归一成空 persona，不是 null（下游只判空不判 null）")
    void missingPersonaBecomesEmpty() {
        CapturingComposer composer = new CapturingComposer();
        ChatService svc = new ChatService(composer);

        svc.chat(new PlanChatRequest(List.of(user("想去珠海")), null, null));

        assertTrue(composer.seenPersona.isEmpty());
    }

    @Test
    @DisplayName("单条消息超长 → 截断，不能让一个粘贴的长文把 prompt 撑爆")
    void truncatesHugeMessage() {
        CapturingComposer composer = new CapturingComposer();
        ChatService svc = new ChatService(composer);
        String huge = "水".repeat(5000);

        svc.chat(new PlanChatRequest(List.of(new Message("user", huge)), null, Persona.empty()));

        String sent = composer.seenMessages.get(0).content();
        assertTrue(sent.length() < 2100, "长度应该被压到 2000 出头，实际 " + sent.length());
        assertTrue(sent.endsWith("…（已截断）"), "截断要留痕，否则排障时会以为用户就发了这么点");
    }
}
