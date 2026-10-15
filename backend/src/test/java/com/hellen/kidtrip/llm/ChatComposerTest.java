package com.hellen.kidtrip.llm;

import com.hellen.kidtrip.plan.Message;
import com.hellen.kidtrip.plan.Persona;
import com.hellen.kidtrip.plan.PlanChatData;
import com.hellen.kidtrip.plan.TravelParams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对话 → 画像。
 *
 * 覆盖三件事：**解析**（模型输出各种鬼东西会发生什么）、**重试**、**降级**。
 * 全部用 FakeChatModel 跑，一发网络请求都不发。
 */
@DisplayName("对话 → 画像：解析 / 重试 / 降级")
class ChatComposerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final TravelParams CURRENT =
            new TravelParams(null, "珠海", null, null, null, null, null, null);

    private static final List<Message> TURN = List.of(
            new Message("user", "想带孩子去珠海"),
            new Message("assistant", "珠海不错！打算玩几天？"),
            new Message("user", "大概 3 天吧，孩子 6 岁"));

    private static ChatComposer composer(FakeChatModel model) {
        return new ChatComposer(model, MAPPER);
    }

    /* ── 解析 ────────────────────────────────────────────────── */

    @Test
    @DisplayName("正常输出 → 话术 + 画像补丁")
    void parsesReplyAndPatch() {
        FakeChatModel model = new FakeChatModel("""
                {"reply":"3 天正好！需要带推车吗？",
                 "params":{"days":3,"childAge":6}}
                """);

        PlanChatData data = composer(model).chat(TURN, CURRENT, Persona.empty());

        assertEquals("3 天正好！需要带推车吗？", data.reply());
        assertEquals(3, data.params().days());
        assertEquals(6, data.params().childAge());
        assertEquals("珠海", data.params().city(), "上一轮的城市必须保留");
        assertEquals(1, model.calls());
    }

    @Test
    @DisplayName("模型加了 ```json 围栏和前后废话 → 照样能解析")
    void stripsFencesAndChatter() {
        FakeChatModel model = new FakeChatModel("""
                好的，这是结果：
                ```json
                {"reply":"记下了","params":{"days":4}}
                ```
                还需要我补充什么吗？
                """);

        PlanChatData data = composer(model).chat(TURN, CURRENT, Persona.empty());

        assertEquals("记下了", data.reply());
        assertEquals(4, data.params().days());
    }

    @Test
    @DisplayName("params 整个缺失 → 当成'这轮没有新信息'，画像原样不动")
    void missingParamsMeansNoNewInfo() {
        FakeChatModel model = new FakeChatModel("{\"reply\":\"信息已经齐了\"}");

        PlanChatData data = composer(model).chat(TURN, CURRENT, Persona.empty());

        assertEquals("信息已经齐了", data.reply());
        assertEquals(CURRENT, data.params());
    }

    @Test
    @DisplayName("reply 是空的 → 不能把空字符串甩给用户看")
    void blankReplyGetsFallback() {
        FakeChatModel model = new FakeChatModel("{\"reply\":\"   \",\"params\":{\"days\":3}}");

        PlanChatData data = composer(model).chat(TURN, CURRENT, Persona.empty());

        assertTrue(data.reply().length() > 0);
        assertEquals(3, data.params().days(), "话术兜底了，但画像该拿到的还是要拿到");
    }

    /* ── 重试与降级 ──────────────────────────────────────────── */

    @Test
    @DisplayName("第一次不是合法 JSON → 重试一次，用第二次的结果")
    void retriesOnceThenSucceeds() {
        FakeChatModel model = new FakeChatModel(
                "呃，我想想……（这里模型开始说人话，不带 JSON）",
                "{\"reply\":\"好的\",\"params\":{\"days\":3}}");

        PlanChatData data = composer(model).chat(TURN, CURRENT, Persona.empty());

        assertEquals(2, model.calls(), "必须真的重试了一次");
        assertEquals("好的", data.reply());
        assertEquals(3, data.params().days());
    }

    @Test
    @DisplayName("⭐ 两次都不是合法 JSON → **降级成一句人话，不抛错**，画像原样保留")
    void degradesAfterTwoFailures() {
        FakeChatModel model = new FakeChatModel("不是 JSON", "还不是 JSON");

        PlanChatData data = composer(model).chat(TURN, CURRENT, Persona.empty());

        assertEquals(2, model.calls(), "只试两次就放弃，不能无限重试");
        assertNotNull(data.reply());
        assertTrue(data.reply().contains("再说一遍"), "降级话术必须是能继续对话的，不是死胡同：" + data.reply());
        assertEquals(CURRENT, data.params(), "解析失败不能把画像弄丢");
    }

    @Test
    @DisplayName("⭐ 调用大模型本身失败 → 抛 LlmException（这个不降级，因为用户该知道'没连上'）")
    void callFailureThrows() {
        ChatComposer composer = composer(FakeChatModel.failing(new RuntimeException("connection reset")));

        LlmException e = assertThrows(LlmException.class,
                () -> composer.chat(TURN, CURRENT, Persona.empty()));

        assertTrue(e.getMessage().contains("connection reset"), e.getMessage());
    }

    @Test
    @DisplayName("调用失败不重试 —— 重试大概率还是失败，而用户已经在等了")
    void callFailureIsNotRetried() {
        FakeChatModel model = FakeChatModel.failing(new RuntimeException("timeout"));

        assertThrows(LlmException.class, () -> composer(model).chat(TURN, CURRENT, Persona.empty()));

        assertEquals(1, model.calls());
    }

    /* ── 渲染 ────────────────────────────────────────────────── */

    @Test
    @DisplayName("画像里的'还没问过'必须渲染成人话，不能是 null / 空")
    void rendersUnknownAsHumanWords() {
        // 通过"提示词里到底写了什么"来验证 —— 这是唯一能测 prompt 的办法：
        // 把假模型收到的 prompt 存下来看。
        CapturingModel model = new CapturingModel();
        composer(model).chat(TURN, new TravelParams(null, "珠海", null, null, null, null, null, null),
                Persona.empty());

        String prompt = model.lastPrompt;
        assertTrue(prompt.contains("目的地：珠海"), prompt);
        assertTrue(prompt.contains("出发地：还没问过"),
                "出发地没问到时必须渲染成'还没问过'——它的缺失会让'每段不能太长'这条约束整个失效：" + prompt);
        assertTrue(prompt.contains("天数：还没问过"), "null 不能被渲染成空或 0：" + prompt);
        assertTrue(prompt.contains("**还没问过**"), "stroller 的三态必须在 prompt 里说清楚：" + prompt);
        assertTrue(prompt.contains("用户：想带孩子去珠海"), "对话历史要带上：" + prompt);
    }

    @Test
    @DisplayName("⭐ prompt 里必须有「今天」——模型填错年份就是因为它不知道今天几号")
    void promptTellsTheModelToday() {
        CapturingModel model = new CapturingModel();
        composer(model).chat(TURN, CURRENT, Persona.empty());

        String prompt = model.lastPrompt;
        assertTrue(prompt.contains(LocalDate.now().toString()),
                "不给今天，模型只能拿训练时的'当下'去猜年份，必然错：" + prompt);
        assertTrue(prompt.contains("出发日期不可能在过去"), prompt);
    }

    @Test
    @DisplayName("⭐ prompt 必须点明「区域说法不能当城市」——并且示例里不能再出现「XX周边」")
    void promptForbidsRegionWordsAndTheExampleIsFixed() {
        CapturingModel model = new CapturingModel();
        composer(model).chat(TURN, CURRENT, Persona.empty());

        String prompt = model.lastPrompt;
        assertTrue(prompt.contains("广东周边"), "要拿真实的错误说法当反例讲清楚：" + prompt);
        assertTrue(prompt.contains("一个具体城市"), prompt);
        assertTrue(prompt.contains("只能有一个城市"), "多城市写法也要堵掉：" + prompt);
        // 这条最要紧：原来的示例写的是 `"珠海"、"深圳周边"` —— 我们自己把模型带偏的
        assertTrue(!prompt.contains("深圳周边"),
                "示例里出现「深圳周边」等于亲手教模型写错：" + prompt);
    }

    /* ── 洗净：模型照抄用户的话时，代码要挡（prompt 是优化，这才是保证）────────── */

    /** 固定一个"今天"，让这些用例讲的是"那天会发生什么"，而不是"相对现在" */
    private static final LocalDate SANITIZE_DAY = LocalDate.of(2026, 10, 13);

    @Test
    @DisplayName("⭐ 实测：模型把用户的「广东周边」当城市填 → 按「还没答」处理，下一轮会重新问")
    void regionWordIsNotAcceptedAsCity() {
        TravelParams patch = new TravelParams(null, "广东周边", 6, null, null, null, null, null);

        TravelParams cleaned = ChatComposer.sanitize(patch, SANITIZE_DAY);

        assertNull(cleaned.city(), "「广东周边」不是一个城市，不能当成已经拿到城市了");
        assertEquals(6, cleaned.days(), "其它字段不许被误伤");
    }

    @Test
    @DisplayName("真正的城市名不许被误伤 —— 而且没问题时应原样返回（连对象都不重建）")
    void realCitySurvivesSanitize() {
        TravelParams patch = new TravelParams(null, "珠海", 3, null, null, null, null, null);

        assertSame(patch, ChatComposer.sanitize(patch, SANITIZE_DAY),
                "用引用相等来钉住'这一轮什么都没改'这件事");
    }

    /* ============================================================
     * origin 的守门 —— city 有的防线它必须一模一样地有
     * ============================================================ */

    @Test
    @DisplayName("⭐ origin 和 city 是同一类错，必须用同一条判据挡住（加字段时最容易漏的就是这道防线）")
    void regionWordIsNotAcceptedAsOriginEither() {
        // 场景：用户说"从广东周边出发去珠海"。模型照抄成 origin="广东周边"。
        TravelParams patch = new TravelParams("广东周边", "珠海", 6, null, null, null, null, null);

        TravelParams cleaned = ChatComposer.sanitize(patch, SANITIZE_DAY);

        assertNull(cleaned.origin(),
                "区域说法不能当出发地 —— 下游拿它去查车次会得到'没有车次'这个**假结论**");
        assertEquals("珠海", cleaned.city(), "目的地是好的，不许被误伤");
        assertEquals(6, cleaned.days());
    }

    @Test
    @DisplayName("⭐ origin 和 city 同时是区域说法 → 两个一起清掉（用户那句'广东周边'会被复制到两个字段）")
    void bothRegionWordsAreClearedTogether() {
        // 这不是假想：prompt 里同时要填 origin 和 city，而用户只说了"广东周边"，
        // 模型很可能把它同时写进两个字段 —— 一侧挡住、另一侧漏过，症状就是
        // "同一个 bug 换了个字段又回来了"，而且只在有 origin 之后才出现。
        TravelParams patch = new TravelParams("广东周边", "广东周边", 6, null, null, null, null, null);

        TravelParams cleaned = ChatComposer.sanitize(patch, SANITIZE_DAY);

        assertNull(cleaned.origin());
        assertNull(cleaned.city());
    }

    @Test
    @DisplayName("origin == city 是合法的（本地游）—— 洗净层不能把它当成'填错了'")
    void sameOriginAndCityIsLegal() {
        TravelParams patch = new TravelParams("珠海", "珠海", 6, null, null, null, null, null);

        assertSame(patch, ChatComposer.sanitize(patch, SANITIZE_DAY),
                "本地游是合法输入，洗净层一个字都不该动它");
    }

    @Test
    @DisplayName("⭐ 实测：模型把「11月17日」填成去年的 2025-11-17 → 顺延到 2026-11-17")
    void pastDateIsRolledForward() {
        TravelParams patch = new TravelParams(null, null, null, "2025-11-17", null, null, null, null);

        assertEquals("2026-11-17", ChatComposer.sanitize(patch, SANITIZE_DAY).startDate());
    }

    @Test
    @DisplayName("⭐ 用户那一轮的真实输出（区域说法 + 去年日期同时出现）→ 两个一起修掉")
    void fixesBothRealWorldProblemsAtOnce() {
        TravelParams patch = new TravelParams(null, "广东周边", 6, "2025-11-17", null, null, null, null);

        TravelParams cleaned = ChatComposer.sanitize(patch, SANITIZE_DAY);

        assertNull(cleaned.city());
        assertEquals("2026-11-17", cleaned.startDate());
        assertEquals(6, cleaned.days(), "用户真正说清楚了的字段一个都不能丢");
    }

    @Test
    @DisplayName("洗净之后要能真的合并进画像 —— 顺延过的日期不能在半路上变成 null")
    void rolledDateActuallyLandsInProfile() {
        FakeChatModel model = new FakeChatModel("""
                {"reply":"11月17日好！孩子多大了？",
                 "params":{"city":"珠海","days":6,"startDate":"2020-11-17"}}
                """);

        PlanChatData data = composer(model).chat(TURN, null, Persona.empty());

        assertEquals("珠海", data.params().city());
        assertNotNull(data.params().startDate());
        assertTrue(LocalDate.parse(data.params().startDate()).isAfter(LocalDate.now()),
                "顺延之后必须是将来的一天，实际拿到：" + data.params().startDate());
    }

    @Test
    @DisplayName("区域说法被洗净之后，下一轮它还在 prompt 里以「还没问过」出现（否则模型永远不再问）")
    void regionWordBecomesUnansweredNotACity() {
        TravelParams afterRegion = ChatComposer.sanitize(
                new TravelParams(null, "广东周边", null, null, null, null, null, null), SANITIZE_DAY);

        CapturingModel model = new CapturingModel();
        composer(model).chat(TURN, afterRegion, Persona.empty());

        assertTrue(model.lastPrompt.contains("目的地：还没问过"),
                "目的地必须回退成「还没问过」，模型才会再问一次：" + model.lastPrompt);
    }

    /* ============================================================
     * persona —— 同一轮里顺带产出"我们从他话里看出来的"
     * ============================================================ */

    /** 一份"用户在话里露了偏好"的历史。**每条依据都必须能在这里面逐字找到** */
    private static final List<Message> PERSONA_TURN = List.of(
            new Message("user", "想带孩子去珠海玩 3 天，这次我想安排得放松一点"),
            new Message("assistant", "好的，那我按轻松一点的节奏来排。孩子多大了？"),
            new Message("user", "6 岁，他中午还要睡一会儿"));

    @Test
    @DisplayName("模型给出的判断 + 依据是用户原话 → 并进 persona，而且不碰 params")
    void personaLandsAlongsideParams() {
        FakeChatModel model = new FakeChatModel("""
                {"reply":"好，轻松一点",
                 "params":{"days":3},
                 "persona":[{"key":"walking","value":"少走路","evidence":"我想安排得放松一点"},
                            {"key":"rest","value":"要午睡","evidence":"他中午还要睡一会儿"}]}
                """);

        PlanChatData data = composer(model).chat(PERSONA_TURN, CURRENT, Persona.empty());

        assertEquals(2, data.persona().size());
        assertEquals("少走路", data.persona().valueOf("walking"));
        assertEquals("要午睡", data.persona().valueOf("rest"));
        assertEquals(3, data.params().days(), "persona 和 params 是两条路，谁都不许踩到对方");
    }

    @Test
    @DisplayName("⭐ 依据在历史里找不到 → 整条丢掉（这是 persona 唯一那道防幻觉的关卡）")
    void fabricatedEvidenceIsDropped() {
        // 这句话听起来完全合理，但用户没说过 —— 模型为了让判断"有凭据"编了一句。
        FakeChatModel model = new FakeChatModel("""
                {"reply":"好",
                 "persona":[{"key":"walking","value":"少走路","evidence":"我不爱走路，最好少走点"}]}
                """);

        PlanChatData data = composer(model).chat(PERSONA_TURN, CURRENT, Persona.empty());

        assertTrue(data.persona().isEmpty(),
                "编造的依据必须被拦下，实际拿到：" + data.persona().inferences());
        assertEquals("好", data.reply(), "拦一条判断不该影响这一轮的话术");
    }

    @Test
    @DisplayName("⭐ 拿助手自己说过的话当依据 → 也必须拦（自证：它的输出不能同时是它的证据）")
    void assistantWordsCannotBeEvidence() {
        FakeChatModel model = new FakeChatModel("""
                {"reply":"好",
                 "persona":[{"key":"walking","value":"少走路","evidence":"按轻松一点的节奏来排"}]}
                """);

        PlanChatData data = composer(model).chat(PERSONA_TURN, CURRENT, Persona.empty());

        assertTrue(data.persona().isEmpty(),
                "这句话是助手说的、不是用户说的，拿它当依据等于自证：" + data.persona().inferences());
    }

    @Test
    @DisplayName("增量合并：这一轮没说到的维度要保留（'没输出' ≠ '用户撤回了'）")
    void previousInferencesSurviveWhenNotMentioned() {
        Persona previous = Persona.of(List.of(
                new Persona.Inference("walking", "少走路", "我想安排得放松一点")));
        FakeChatModel model = new FakeChatModel("""
                {"reply":"好",
                 "persona":[{"key":"rest","value":"要午睡","evidence":"他中午还要睡一会儿"}]}
                """);

        PlanChatData data = composer(model).chat(PERSONA_TURN, CURRENT, previous);

        assertEquals("少走路", data.persona().valueOf("walking"), "没提到的旧判断必须留着");
        assertEquals("要午睡", data.persona().valueOf("rest"));
    }

    @Test
    @DisplayName("改主意：同一个维度给新判断 → 覆盖旧的（靠 value 覆盖表达，不靠缺失）")
    void newValueOverwritesOldOne() {
        Persona previous = Persona.of(List.of(
                new Persona.Inference("walking", "少走路", "我想安排得放松一点")));
        FakeChatModel model = new FakeChatModel("""
                {"reply":"好",
                 "persona":[{"key":"walking","value":"无所谓","evidence":"我想安排得放松一点"}]}
                """);

        PlanChatData data = composer(model).chat(PERSONA_TURN, CURRENT, previous);

        assertEquals("无所谓", data.persona().valueOf("walking"));
        assertEquals(1, data.persona().size(), "覆盖不是追加");
    }

    @Test
    @DisplayName("解析彻底失败降级时，已有的 persona 原样保留（和画像一样不能丢）")
    void personaSurvivesDegrade() {
        Persona previous = Persona.of(List.of(
                new Persona.Inference("walking", "少走路", "我想安排得放松一点")));
        FakeChatModel model = new FakeChatModel("不是 JSON", "还不是 JSON");

        PlanChatData data = composer(model).chat(PERSONA_TURN, CURRENT, previous);

        assertEquals(previous, data.persona());
        assertEquals(CURRENT, data.params());
    }

    @Test
    @DisplayName("没给 persona 字段 → 不报错，等价于'这一轮没有新判断'")
    void missingPersonaIsFine() {
        FakeChatModel model = new FakeChatModel("{\"reply\":\"好\",\"params\":{\"days\":3}}");

        PlanChatData data = composer(model).chat(PERSONA_TURN, CURRENT, Persona.empty());

        assertTrue(data.persona().isEmpty());
        assertEquals(3, data.params().days());
    }

    @Test
    @DisplayName("⭐ prompt 里必须教清楚可记的维度、并带上已经推出来的判断")
    void promptCarriesPersonaSections() {
        Persona previous = Persona.of(List.of(
                new Persona.Inference("walking", "少走路", "我想安排得放松一点")));
        CapturingModel model = new CapturingModel();
        composer(model).chat(PERSONA_TURN, CURRENT, previous);

        String prompt = model.lastPrompt;
        assertTrue(prompt.contains("不要发明第"), "维度是闭集，必须在 prompt 里说死：" + prompt);
        assertTrue(prompt.contains("walking（怕走路）"), "维度和它的中文名都要给：" + prompt);
        assertTrue(prompt.contains("怕走路：少走路"), "上一轮的判断要带上，否则做不了'改主意'：" + prompt);
        assertTrue(prompt.contains("依据只能来自"), "自证那条规矩必须在：" + prompt);
        assertTrue(prompt.contains("明确说了相反方向"), "反向偏好那条规矩必须在（探针实测漏掉它会丢判断）：" + prompt);
    }

    /** 只为了把收到的 prompt 存下来看一眼 */
    private static final class CapturingModel extends FakeChatModel {
        private String lastPrompt;

        CapturingModel() {
            super("{\"reply\":\"好\"}");
        }

        @Override
        public String chat(String prompt) {
            this.lastPrompt = prompt;
            return super.chat(prompt);
        }
    }
}
