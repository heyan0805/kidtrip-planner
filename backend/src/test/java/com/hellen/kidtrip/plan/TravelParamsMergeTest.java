package com.hellen.kidtrip.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 画像合并 —— "上下文累积"这个机制的全部实现就在这里。
 *
 * 这个测试要钉住的只有一句话：**模型没答的字段，一个都不许被覆盖。**
 * 它值得有这么多用例，因为失败方式全都是**静默的**：
 * 用户的画像会被悄悄退回旧值，他看不见，只会在生成行程时觉得"怎么又要重填"。
 */
@DisplayName("画像合并：只接受模型真的答了的字段")
class TravelParamsMergeTest {

    /**
     * 只写"这个用例关心的字段"，origin 一律留空 —— origin 有自己的用例（见下面那一组）。
     *
     * 为什么值得专门包一层：`new TravelParams(...)` 是**八个位置参数**，
     * 每加一个字段（origin 就是刚加的那个），这个文件里十几处构造全要手改一次，
     * 而且把两个 null 插错顺序**编译器不会说话** —— 那会变成"某个字段悄悄串位"的假绿。
     * 包一层之后，加字段只需要改这一个方法。
     */
    private static TravelParams p(String city, Integer days, String startDate,
                                  List<String> preferences, String pace, List<Integer> childAges, Boolean stroller) {
        return new TravelParams(null, city, days, startDate, preferences, pace, childAges, stroller);
    }

    /** 一个"聊了几轮之后"的典型画像 */
    private static TravelParams current() {
        return p("珠海", 3, "2026-10-24", List.of("动物园"), "慢", List.of(7), true);
    }

    private static final TravelParams EMPTY = TravelParams.empty();

    @Test
    @DisplayName("模型这一轮什么都没听出来（全 null）→ 原值一个都不动")
    void allNullPatchKeepsEverything() {
        assertEquals(current(), current().mergedWith(EMPTY));
    }

    @Test
    @DisplayName("patch 整个是 null → 返回原值，不抛 NPE")
    void nullPatchIsSafe() {
        assertEquals(current(), current().mergedWith(null));
    }

    @Test
    @DisplayName("新听出来的字段会覆盖旧的（用户改主意：3 天改 4 天）")
    void answeredFieldOverrides() {
        TravelParams patch = p(null, 4, null, null, null, null, null);

        TravelParams merged = current().mergedWith(patch);

        assertEquals(4, merged.days());
        assertEquals("珠海", merged.city(), "其余字段不受影响");
    }

    @Test
    @DisplayName("days=0 不算回答 —— 它是模型的'未确定'习惯，不是'0 天'")
    void zeroDaysIsNotAnAnswer() {
        // 这一条防的是一个很阴的坑：0 会先覆盖掉 3，然后被 orDefaults() 又补回 3，
        // 于是错误被完美地掩盖了 —— 直到用户说"我明明说了 5 天"，你才发现 0 擦过它。
        TravelParams patch = p(null, 0, null, null, null, null, null);
        assertEquals(3, current().mergedWith(patch).days());

        TravelParams negative = p(null, -1, null, null, null, null, null);
        assertEquals(3, current().mergedWith(negative).days());
    }

    @Test
    @DisplayName("空串不算回答 —— 模型被要求输出字符串时经常用 \"\" 表示'没有'")
    void blankStringIsNotAnAnswer() {
        TravelParams patch = p("  ", null, "", null, "", null, null);

        TravelParams merged = current().mergedWith(patch);

        assertEquals("珠海", merged.city());
        assertEquals("2026-10-24", merged.startDate());
        assertEquals("慢", merged.pace());
    }

    @Test
    @DisplayName("空数组不算回答 —— preferences 的对应形态")
    void emptyListIsNotAnAnswer() {
        TravelParams patch = p(null, null, null, List.of(), null, null, null);
        assertEquals(List.of("动物园"), current().mergedWith(patch).preferences());
    }

    @Test
    @DisplayName("⭐ stroller 三态：null 是'还没问过'，false 是'明确不需要' —— 两者绝不能混")
    void strollerKeepsThreeStates() {
        // 原值是 true（用户说过需要）
        assertEquals(Boolean.TRUE, current().mergedWith(EMPTY).stroller(),
                "模型没提推车 → 保留用户之前说的'需要'");

        TravelParams saysNo = p(null, null, null, null, null, null, false);
        assertEquals(Boolean.FALSE, current().mergedWith(saysNo).stroller(),
                "模型明确答了 false → 必须覆盖，这是有效答案，不是'没答'");

        // 反过来：原值还没问过（null），模型也没答 → 仍然是 null，不能变成 false
        TravelParams unknown = p("珠海", null, null, null, null, null, null);
        assertNull(unknown.mergedWith(EMPTY).stroller(),
                "两边都不知道 → 必须还是'还没问过'，写成 false 会让系统再也不问推车的事");
    }

    @Test
    @DisplayName("多轮累积：每轮补一个字段，前面的都不丢")
    void accumulatesAcrossTurns() {
        TravelParams t = EMPTY;
        t = t.mergedWith(p("珠海", null, null, null, null, null, null));
        t = t.mergedWith(p(null, 3, null, null, null, null, null));
        t = t.mergedWith(p(null, null, null, List.of("海洋馆"), null, null, null));
        t = t.mergedWith(p(null, null, null, null, null, List.of(5), null));
        t = t.mergedWith(p(null, null, null, null, null, null, true));

        assertEquals("珠海", t.city());
        assertEquals(3, t.days());
        assertEquals(List.of("海洋馆"), t.preferences());
        assertEquals(List.of(5), t.childAges());
        assertTrue(t.stroller());
        assertFalse(t.city().isBlank());
    }

    /* ============================================================
     * childAges —— 2026-10-22 由单值 childAge 改成列表。
     * 它的合并规则**和 preferences 故意不同**：整体替换，不逐项合并。
     * 理由见 TravelParams 里那段注释（模型每轮输出全部孩子的年龄）。
     * ============================================================ */

    @Test
    @DisplayName("childAges 是整体替换：原来的 [5] + 这轮的 [5,8] → [5,8]，不是 [5,5,8]")
    void childAgesReplacesWholeList() {
        TravelParams t = p(null, null, null, null, null, List.of(5), null);

        TravelParams merged = t.mergedWith(p(null, null, null, null, null, List.of(5, 8), null));
        assertEquals(List.of(5, 8), merged.childAges(),
                "模型这一轮说的是'全部孩子的年龄'，不是'这轮新听到的那一个'");

        // 修正也算替换：用户说"记错了，老大 9 岁" → 不该变成 [5, 9]
        assertEquals(List.of(5, 9), t.mergedWith(p(null, null, null, null, null, List.of(5, 9), null)).childAges());
        // 单个也不例外：从两个改成一个（用户说"其实只有一个孩子"）是允许的
        assertEquals(List.of(6), p(null, null, null, null, null, List.of(5, 8), null)
                .mergedWith(p(null, null, null, null, null, List.of(6), null)).childAges());
    }

    @Test
    @DisplayName("childAges 空列表 / null = 这轮没提 → 保留原值，不能把已知年龄抹掉")
    void emptyChildAgesDoesNotWipeKnownAges() {
        TravelParams t = p(null, null, null, null, null, List.of(5, 8), null);

        assertEquals(List.of(5, 8), t.mergedWith(p(null, null, null, null, null, List.of(), null)).childAges());
        assertEquals(List.of(5, 8), t.mergedWith(p(null, null, null, null, null, null, null)).childAges());
        // 反方向：原来没有，这轮也没给 → 仍然是空，不能变成一个凭空的 [0]
        assertNull(EMPTY.mergedWith(EMPTY).childAges());
    }

    @Test
    @DisplayName("youngestChildAge 取最小的那个 —— 节奏由走得最慢的孩子决定")
    void youngestChildAgeTakesMinimum() {
        assertEquals(Integer.valueOf(3), p(null, null, null, null, null, List.of(3, 8), null).youngestChildAge());
        assertEquals(Integer.valueOf(5), p(null, null, null, null, null, List.of(10, 5, 7), null).youngestChildAge(),
                "不看顺序，只看谁最小");
        assertNull(p(null, null, null, null, null, List.of(), null).youngestChildAge());
        assertNull(p(null, null, null, null, null, null, null).youngestChildAge());

        // 模型偶尔给半截输出（[5, null] 或 0）：已知里取最小，别让一个坏值废掉整条信息。
        // 用 Arrays.asList 而不是 List.of —— List.of 不接受 null 元素，会当场 NPE。
        assertEquals(Integer.valueOf(5), p(null, null, null, null, null, Arrays.asList(5, null), null).youngestChildAge());
        assertEquals(Integer.valueOf(2), p(null, null, null, null, null, List.of(0, 2), null).youngestChildAge());
        assertNull(p(null, null, null, null, null, List.of(0, -1), null).youngestChildAge(),
                "全是无效值 → 等于没有信息，不能返回 0 让规则层以为孩子是 0 岁");
    }

    @Test
    @DisplayName("childAgesText 是三个 prompt 共用的那一句，格式只有这一种")
    void childAgesTextIsSharedFormat() {
        assertEquals("5 岁", p(null, null, null, null, null, List.of(5), null).childAgesText());
        assertEquals("5 岁、8 岁", p(null, null, null, null, null, List.of(5, 8), null).childAgesText());
        // 没有时返回 null，由各调用方决定说"还没问过"/"未确定"/"未提供"
        assertNull(p(null, null, null, null, null, List.of(), null).childAgesText());
        assertNull(p(null, null, null, null, null, null, null).childAgesText());
        assertNull(p(null, null, null, null, null, List.of(0), null).childAgesText(),
                "0 岁不是年龄，是模型填错了 —— 当作没提供，别对用户说'0 岁'");
    }

    /* ============================================================
     * origin —— 2026-10-13 新增。它自己的一组用例，因为它和其他字段
     * 的**理由不一样**：别的字段是"多收集一点信息"，而 origin 是
     * "每段移动不能太长"这条约束能不能成立的前提（没有它就没有"一段"）。
     * ============================================================ */

    @Test
    @DisplayName("origin 走'空值即未答'：模型没提出发地 → 保留原值，不会被抹掉")
    void originFollowsSameAnswerRuleAsCity() {
        TravelParams withOrigin = new TravelParams("广州", "珠海", 3, "2026-10-24",
                List.of("动物园"), "慢", List.of(7), true);

        assertEquals("广州", withOrigin.mergedWith(EMPTY).origin(),
                "模型这一轮没提出发地 → 上一轮问到的'广州'必须留着");

        TravelParams saysShenzhen = new TravelParams("深圳", null, null, null, null, null, null, null);
        assertEquals("深圳", withOrigin.mergedWith(saysShenzhen).origin(),
                "模型听出了新出发地 → 覆盖");
    }

    @Test
    @DisplayName("origin 的空串也不算回答（和 city 用的是同一条规则）")
    void blankOriginIsNotAnAnswer() {
        TravelParams withOrigin = new TravelParams("广州", "珠海", 3, null, null, null, null, null);
        TravelParams blank = new TravelParams("   ", null, null, null, null, null, null, null);

        assertEquals("广州", withOrigin.mergedWith(blank).origin());
    }

    @Test
    @DisplayName("sameCity：出发地==目的地是合法的本地游，不能被当成'没填'")
    void sameCityRecognisesLocalTrip() {
        TravelParams local = new TravelParams("珠海", "珠海", 6, "2026-10-24", null, "慢", List.of(6), null);
        assertTrue(local.sameCity(),
                "本地游（就在珠海玩 6 天）是合法输入 —— 判错会导致给一次本地出行报'没查到车次'");

        TravelParams crossCity = new TravelParams("广州", "珠海", 3, null, null, null, null, null);
        assertFalse(crossCity.sameCity());

        assertFalse(TravelParams.empty().sameCity(), "两边都空 → 不是'同城'，而是'不知道'");
        assertFalse(new TravelParams("广州", null, 3, null, null, null, null, null).sameCity(),
                "只说了出发地 → 同样不是'同城'");
    }

    @Test
    @DisplayName("orDefaults 不会给 origin 编一个默认值 —— 我们无法假设用户的出发地")
    void orDefaultsLeavesOriginAlone() {
        assertNull(EMPTY.orDefaults().origin());

        TravelParams withOrigin = new TravelParams("广州", "珠海", 6, "2026-10-24", null, null, null, null);
        assertEquals("广州", withOrigin.orDefaults().origin(), "有值就原样带过去；天数被补齐不影响它");
        assertEquals(6, withOrigin.orDefaults().days());
    }

    @Test
    @DisplayName("orDefaults 会把天数补成 3 —— 所以它只能用在生成行程那一步")
    void orDefaultsFillsDays() {
        // 这条是**记录一个约束**，不是验证一个功能：
        // orDefaults() 用在对话里就会替用户答"玩几天"，所以 PlanChatRequest 刻意没调它。
        // 写在这里，是为了让以后想"顺手补个默认值"的人先看到这条测试。
        assertEquals(3, EMPTY.orDefaults().days());
        assertEquals(10, p(null, 99, null, null, null, null, null).orDefaults().days(),
                "还有上限：最多 10 天");
        assertNull(EMPTY.orDefaults().stroller(), "三态不会被 orDefaults 破坏");
    }

    @Test
    @DisplayName("empty() 就是全空 —— 它是所有降级分支的起点，必须真的是空的")
    void emptyIsReallyEmpty() {
        // 这一条防的是"以后给 empty() 顺手加个默认值"——那会让所有降级分支悄悄带上城市。
        // ⚠️ 断言要写**逐字段**，不能写 `assertEquals(EMPTY, EMPTY.orDefaults()...)`：
        //    orDefaults() 会按设计把 days 补成 3、preferences 补成空列表，两者本来就不该相等。
        //    （第一版就是这么写错的 —— 测试写错和代码写错一样，都是"看起来在验证，其实在自欺"。）
        assertNull(EMPTY.origin());
        assertNull(EMPTY.city());
        assertNull(EMPTY.days());
        assertNull(EMPTY.startDate());
        assertNull(EMPTY.preferences());
        assertNull(EMPTY.pace());
        assertNull(EMPTY.childAges());
        assertNull(EMPTY.stroller());

        // 顺带钉住另一件事：empty() 每次调用都该是**相等**的（它是值，不是单例状态）
        assertEquals(EMPTY, TravelParams.empty());
    }
}
