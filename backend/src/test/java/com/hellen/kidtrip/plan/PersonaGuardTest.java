package com.hellen.kidtrip.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * persona 闸门。这是整件事里**唯一一道能自动挡住幻觉的关卡**，所以用例最多。
 *
 * 要钉住的三条（按重要性排）：
 *   ① 编造的 evidence 进不去  —— 包括"抄 assistant 自己说过的话"这种自我确认；
 *   ② 自造的 key 进不去      —— 推论只能发生在我们想清楚的 5 个维度上；
 *   ③ 真话必须进得去         —— 闸门太严等于这个功能不存在，而且失败是静默的。
 *
 * ⚠️ 这些断言都**验证过它能红**（见每条的注释）：把 saidByUser 改成恒真、
 *    把 key 白名单去掉、把 EVIDENCE_MIN 调成 0 —— 对应的用例立刻失败。
 *    没验证过能红的断言，只是"看起来在验证"，和没有断言一样。
 */
@DisplayName("persona 闸门：拿不出原话的推论一条都进不来")
class PersonaGuardTest {

    private static Persona.Inference i(String key, String value, String evidence) {
        return new Persona.Inference(key, value, evidence);
    }

    /** 一段真实对话。第 1 句就是用户最典型的"放松"表述 */
    private static final String USER_RELAX = "我想有一个放松的旅行，不想每天赶路";
    private static final String USER_DINOSAUR = "他最近特别迷恐龙";
    private static final String ASSISTANT_GUESS = "看来您比较在意预算";

    private static List<Message> history() {
        return List.of(
                new Message(Message.USER, "我们想 11 月带孩子出去玩几天"),
                new Message(Message.ASSISTANT, "好的，请问想去哪个城市？"),
                new Message(Message.USER, USER_RELAX),
                new Message(Message.ASSISTANT, "明白，那我尽量把行程排松一点。" + ASSISTANT_GUESS),
                new Message(Message.USER, USER_DINOSAUR)
        );
    }

    @Test
    @DisplayName("⭐ 真话进得去 —— 闸门的第一职责是别把对的挡在外面")
    void realEvidenceGetsThrough() {
        // 闸门太严的失败方式和幻觉一样静默：用户以为它记住了，其实一条都没留下。
        // 所以"能进"这件事必须和"进不去"一样被钉住。
        PersonaGuard.Screening s = PersonaGuard.screen(
                List.of(i("walking", "少走路", "不想每天赶路"),
                        i("interest", "恐龙", USER_DINOSAUR)),
                history());

        assertTrue(s.isClean(), "两条都该过：" + s.rejected());
        assertEquals(2, s.accepted().size());
        assertEquals("少走路", s.accepted().get(0).value());
        assertEquals("恐龙", s.accepted().get(1).value());
    }

    @Test
    @DisplayName("⭐ 编造的 evidence 进不去 —— 用户从没说过这句话")
    void fabricatedEvidenceIsRejected() {
        // 反证：把 saidByUser 改成恒真 → 这条立刻红（accepted 变成 1 条，isClean 变 true）。
        PersonaGuard.Screening s = PersonaGuard.screen(
                List.of(i("walking", "少走路", "我腿脚不太好走不了远路")),
                history());

        assertTrue(s.accepted().isEmpty(), "这句话历史里没有，整条必须丢");
        assertEquals(PersonaGuard.REASON_EVIDENCE_NOT_FOUND, s.rejected().get(0).reason());
    }

    @Test
    @DisplayName("⭐ assistant 自己说过的话不算依据 —— 否则幻觉会完成一次自我确认")
    void assistantWordsAreNotEvidence() {
        // 这是最阴的一条：模型上一轮随口猜"您比较在意预算"，
        // 这一轮把这句话抄成 evidence —— 拿模型自己的输出当自己的证据。
        // 于是幻觉不仅活下来了，还带上了"依据"，从此再也查不出来。
        PersonaGuard.Screening s = PersonaGuard.screen(
                List.of(i("budget", "想省着花", ASSISTANT_GUESS)),
                history());

        assertTrue(s.accepted().isEmpty(), "这句话在历史里确实存在，但它是 assistant 说的");
        assertEquals(PersonaGuard.REASON_EVIDENCE_NOT_FOUND, s.rejected().get(0).reason());
    }

    @Test
    @DisplayName("自造的 key 进不去 —— 推论只能发生在我们想清楚的维度上")
    void unknownKeysAreRejected() {
        // 反证：把 PersonaTrait.ofKey 的白名单判断去掉 → 这条立刻红。
        PersonaGuard.Screening s = PersonaGuard.screen(
                List.of(i("不要早起", "晚点出门", "我不想早起"),
                        i("prefers_walking", "少走路", "不想每天赶路"),
                        i("weather", "怕晒", "不想每天赶路")),
                history());

        assertTrue(s.accepted().isEmpty(), "三个 key 闭集里都没有");
        assertEquals(3, s.rejected().size());
        for (PersonaGuard.Rejection r : s.rejected()) {
            assertEquals(PersonaGuard.REASON_UNKNOWN_KEY, r.reason());
        }
    }

    @Test
    @DisplayName("key 的空白和大小写要归一，且存下来的是**规范写法**")
    void keysAreNormalised() {
        PersonaGuard.Screening s = PersonaGuard.screen(
                List.of(i("  WALKING  ", "少走路", "不想每天赶路")),
                history());

        assertEquals(1, s.accepted().size(), "空白 + 大小写不该把一个正确值判成非法");
        assertEquals("walking", s.accepted().get(0).key(),
                "存下来必须是规范 key —— 否则 ' Walking ' 和 'walking' 在 Persona 里成了两个维度，"
                        + "同 key 覆盖的合并规则当场失效");
    }

    @Test
    @DisplayName("evidence 太短进不去 —— 不然任何推论都能挂一句'嗯'过关")
    void tooShortEvidenceIsRejected() {
        // 反证：把 EVIDENCE_MIN 调成 0 → 这条红。
        // "好" 确实在用户的下一句话里（"好的，请问…"是 assistant 说的，
        // 但用户后面还会说话），总之"好"这种词在真实对话里满地都是。
        List<Message> withAkwn = List.of(
                new Message(Message.USER, "好"),
                new Message(Message.USER, "嗯嗯")
        );

        PersonaGuard.Screening s = PersonaGuard.screen(
                List.of(i("walking", "少走路", "好"),
                        i("crowd", "避开人多", "嗯嗯")),
                withAkwn);

        assertTrue(s.accepted().isEmpty(), "这两句确实在历史里，但拿它们当依据等于没有依据");
        assertEquals(PersonaGuard.REASON_SHORT_EVIDENCE, s.rejected().get(0).reason());
        assertEquals(PersonaGuard.REASON_SHORT_EVIDENCE, s.rejected().get(1).reason());
    }

    @Test
    @DisplayName("刚好够长的依据能过 —— 边界要钉住，不然会误杀真话")
    void exactlyMinimumEvidencePasses() {
        // "我怕累" 三个字，是真实的依据（哪怕它推得过头，挡过头推论不是这一步的职责）。
        PersonaGuard.Screening s = PersonaGuard.screen(
                List.of(i("walking", "少走路", "我怕累")),
                List.of(new Message(Message.USER, "我怕累")));

        assertEquals(1, s.accepted().size(), "3 个字正好在下限上，必须放行");
    }

    @Test
    @DisplayName("空 evidence / 空 value 各有各的原因，不能混成一条")
    void emptyFieldsHaveDistinctReasons() {
        PersonaGuard.Screening s = PersonaGuard.screen(
                List.of(i("walking", "少走路", ""),
                        i("crowd", "", "不想每天赶路")),
                history());

        assertTrue(s.accepted().isEmpty());
        assertEquals(PersonaGuard.REASON_NO_EVIDENCE, s.rejected().get(0).reason());
        assertEquals(PersonaGuard.REASON_BLANK_VALUE, s.rejected().get(1).reason(),
                "两种情况排障时要能分开 —— 一个是模型没找依据，一个是它没下判断");
    }

    @Test
    @DisplayName("value 太长进不去 —— 那不是标签，是一句话")
    void overlyLongValueIsRejected() {
        String tooLong = "少".repeat(PersonaGuard.VALUE_MAX + 1);

        PersonaGuard.Screening s = PersonaGuard.screen(
                List.of(i("walking", tooLong, "不想每天赶路")),
                history());

        assertTrue(s.accepted().isEmpty());
        assertEquals(PersonaGuard.REASON_LONG_VALUE, s.rejected().get(0).reason());
    }

    @Test
    @DisplayName("value 正好到上限能过")
    void valueAtLimitPasses() {
        String atLimit = "少".repeat(PersonaGuard.VALUE_MAX);

        PersonaGuard.Screening s = PersonaGuard.screen(
                List.of(i("walking", atLimit, "不想每天赶路")),
                history());

        assertEquals(1, s.accepted().size());
    }

    @Test
    @DisplayName("同一个维度说了两遍：留第一遍，第二遍被丢")
    void duplicateKeyKeepsTheFirst() {
        // ⚠️ 第二条的 evidence 也必须是**真话**，否则它会先被"编造"那道关拦下，
        //    报的是 EVIDENCE_NOT_FOUND —— 这条测试就永远测不到去重逻辑，
        //    变成一条"看起来过了、其实什么也没验证"的假绿。
        //    （第一版就是这么写的，是反证时（把 EVIDENCE_MIN 调成 0）才发现的。）
        PersonaGuard.Screening s = PersonaGuard.screen(
                List.of(i("walking", "少走路", "不想每天赶路"),
                        i("walking", "无所谓", USER_RELAX)),
                history());

        assertEquals(1, s.accepted().size());
        assertEquals("少走路", s.accepted().get(0).value(), "先写的那条通常是它更确信的");
        assertEquals(PersonaGuard.REASON_DUPLICATE_KEY, s.rejected().get(0).reason());
    }

    @Test
    @DisplayName("⭐ 第一条是编的、第二条是真的 → 真的那条必须活下来（不能连坐）")
    void rejectedFirstDoesNotKillTheSecond() {
        // 这条钉的是"去重排在所有检查之后"这个顺序。
        // 如果 seen.add 提到前面（先记后查），第二条会被当成"重复"丢掉 ——
        // 一条真推论被一条编造的推论**连坐**，而且理由显示为"说了两遍"，
        // 排障时你永远查不出真正发生了什么。
        PersonaGuard.Screening s = PersonaGuard.screen(
                List.of(i("walking", "少走路", "我腿脚不太好走不了远路"),
                        i("walking", "少走路", "不想每天赶路")),
                history());

        assertEquals(1, s.accepted().size(), "第二条是真的，必须留下");
        assertEquals("不想每天赶路", s.accepted().get(0).evidence());
        assertEquals(PersonaGuard.REASON_EVIDENCE_NOT_FOUND, s.rejected().get(0).reason(),
                "被丢的那条理由应该指向它真正的问题（编造），不是'重复'");
    }

    @Test
    @DisplayName("把两句话拼起来当依据 → 不算（已知代价，钉成预期行为）")
    void concatenatedEvidenceIsNotAccepted() {
        // 用户分两句说了"我想有一个放松的旅行"和"他最近特别迷恐龙"，
        // 模型拼成一句当依据。逐条查会把它挡下来。
        //
        // 这是**刻意的**：拼接同时会放行"用户说了 A、也说了 B、所以推出 C"这类假因果。
        // 严的方向选"宁缺勿滥"——丢一条本可以留的推论（少一条偏好），
        // 好过放过一条编造的依据（一条静默生效的错误偏好）。
        // ⚠️ 这是已知代价，不是 bug。模型该做的是挑其中最能支撑结论的那一句。
        PersonaGuard.Screening s = PersonaGuard.screen(
                List.of(i("interest", "恐龙", "我想有一个放松的旅行他最近特别迷恐龙")),
                history());

        assertTrue(s.accepted().isEmpty());
        assertEquals(PersonaGuard.REASON_EVIDENCE_NOT_FOUND, s.rejected().get(0).reason());
    }

    @Test
    @DisplayName("换行和空白的差异不该误杀 —— 用户在输入框里换行是随手的事")
    void whitespaceDifferencesStillMatch() {
        // Message 的构造器只 strip 首尾，"我想有个\n放松的旅行" 中间那个换行会留着。
        // 不归一的话这条会被判成"用户没说过"，而它明明原样躺在历史里 ——
        // 这个坑只有真的往 UI 里敲多行文字才会撞上，也就是，一定会撞上。
        List<Message> multi = List.of(new Message(Message.USER, "我想有个\n放松的旅行"));

        PersonaGuard.Screening s = PersonaGuard.screen(
                List.of(i("walking", "少走路", "我想有个 放松的旅行")),
                multi);

        assertEquals(1, s.accepted().size(), "只差一个换行 vs 空格，内容就是同一句");
    }

    @Test
    @DisplayName("标点差异不该误杀 —— 少抄一个句号的代价不对称")
    void punctuationDifferencesStillMatch() {
        // 用户说"我想放松。"，模型 evidence 写"我想放松"。
        // 判成编造的话，代价是一条**真推论被丢掉**，用户看不见，只会觉得"它怎么没记住"。
        // 反过来放松一档没有任何代价：去掉标点后仍然逐字相同，不可能是造假。
        List<Message> punctuated = List.of(new Message(Message.USER, "我想放松，真的。"));

        PersonaGuard.Screening s = PersonaGuard.screen(
                List.of(i("walking", "少走路", "我想放松，真的")),
                punctuated);

        assertEquals(1, s.accepted().size());
    }

    @Test
    @DisplayName("evidence 是用户某句话的一小段就算命中（不必整句相等）")
    void evidenceMayBeASubstring() {
        // 用户那句话很长，而 prompt 要求 evidence 抄**支撑结论的那一小段**。
        // 要求整句相等会把这条正确用法判成非法。
        PersonaGuard.Screening s = PersonaGuard.screen(
                List.of(i("walking", "少走路", "不想每天赶路")),
                history());

        assertEquals(1, s.accepted().size());
    }

    @Test
    @DisplayName("没有历史 → 一条都留不下（没有依据就是没有依据）")
    void noHistoryMeansNothingSurvives() {
        List<Persona.Inference> raw = List.of(i("walking", "少走路", "我想放松"));

        assertTrue(PersonaGuard.screen(raw, null).accepted().isEmpty());
        assertTrue(PersonaGuard.screen(raw, List.of()).accepted().isEmpty());
    }

    @Test
    @DisplayName("没有输入 → 空结果，不抛 NPE")
    void emptyInputIsSafe() {
        assertTrue(PersonaGuard.screen(null, history()).accepted().isEmpty());
        assertTrue(PersonaGuard.screen(List.of(), history()).accepted().isEmpty());
        assertTrue(PersonaGuard.screen(null, null).rejected().isEmpty());
    }

    @Test
    @DisplayName("raw 里夹着 null 元素 → 跳过，不炸（它是模型输出的解析中间态）")
    void nullElementsInRawAreSkipped() {
        List<Persona.Inference> raw = new ArrayList<>();
        raw.add(null);
        raw.add(i("walking", "少走路", "不想每天赶路"));
        raw.add(null);

        PersonaGuard.Screening s = PersonaGuard.screen(raw, history());

        assertEquals(1, s.accepted().size());
        assertTrue(s.rejected().isEmpty(), "null 不是'一条被拦下的推论'，它压根不是推论，不该记账");
    }

    @Test
    @DisplayName("保序：accepted 和 rejected 都按模型给的顺序")
    void keepsOrder() {
        PersonaGuard.Screening s = PersonaGuard.screen(
                List.of(i("interest", "恐龙", USER_DINOSAUR),
                        i("walking", "少走路", "没说过的话"),
                        i("walking", "少走路", "不想每天赶路")),
                history());

        assertEquals(List.of("interest", "walking"),
                s.accepted().stream().map(Persona.Inference::key).toList());
        assertEquals(1, s.rejected().size());
    }

    @Test
    @DisplayName("被拦下的条目要保留原文 —— 不然'模型爱在哪儿编'这个数字就永远测不出来")
    void rejectionsKeepTheOriginalText() {
        PersonaGuard.Screening s = PersonaGuard.screen(
                List.of(i("不要早起", "晚点出门", "我不想早起")),
                history());

        PersonaGuard.Rejection r = s.rejected().get(0);
        assertEquals("不要早起", r.key());
        assertEquals("晚点出门", r.value());
        assertEquals("我不想早起", r.evidence());
        assertFalse(r.reason().isBlank(), "每条都要说清为什么丢 —— 光丢不说等于没排障");
    }

    @Test
    @DisplayName("Screening.toPersona() 的输出能直接喂给 mergedWith")
    void screeningFeedsStraightIntoMerge() {
        // 这条钉的是"闸门的输出就是 Persona 的合法输入"这个契约 ——
        // 中间不该再有一次转换（多一次转换就多一处能漏掉归一的地方）。
        PersonaGuard.Screening s1 = PersonaGuard.screen(
                List.of(i("walking", "少走路", "不想每天赶路")), history());
        PersonaGuard.Screening s2 = PersonaGuard.screen(
                List.of(i("interest", "恐龙", USER_DINOSAUR)), history());

        Persona persona = Persona.empty().mergedWith(s1.toPersona()).mergedWith(s2.toPersona());

        assertEquals(2, persona.size());
        assertEquals("少走路", persona.valueOf("walking"));
        assertEquals("恐龙", persona.valueOf("interest"));
    }

    @Test
    @DisplayName("isClean：没有条被拦下才是干净的")
    void isCleanMeansNothingWasDropped() {
        assertTrue(PersonaGuard.screen(List.of(), history()).isClean());
        assertTrue(PersonaGuard.screen(
                List.of(i("walking", "少走路", "不想每天赶路")), history()).isClean());
        assertFalse(PersonaGuard.screen(
                List.of(i("walking", "少走路", "编的一句话")), history()).isClean());
    }

    @Test
    @DisplayName("squeeze：去空白去标点转小写，null 安全")
    void squeezeNormalises() {
        assertEquals("我想放松", PersonaGuard.squeeze(" 我想 放松。 "));
        assertEquals("我想放松", PersonaGuard.squeeze("我想\n放松"));
        assertEquals("ab123", PersonaGuard.squeeze(" A-B 123 "), "连字符和空格都当不存在");
        assertEquals("ab_c123", PersonaGuard.squeeze(" A-B_C 123 "),
                "下划线**保留** —— 它不在忽略表里。它看着像标点，其实是英文标识符的一部分，"
                        + "而用户的原话里本来就不该有它；真出现时保留比抹掉更保守");
        assertEquals("", PersonaGuard.squeeze(null));
        assertEquals("", PersonaGuard.squeeze(" ，。！ "), "纯标点压完就是空的");
    }
}
