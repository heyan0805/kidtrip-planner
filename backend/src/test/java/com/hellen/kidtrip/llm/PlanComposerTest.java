package com.hellen.kidtrip.llm;

import com.hellen.kidtrip.candidate.PoiCandidate;
import com.hellen.kidtrip.plan.Legs;
import com.hellen.kidtrip.plan.TravelParams;
import com.hellen.kidtrip.retrieval.Transfer;
import com.hellen.kidtrip.retrieval.TransportMode;
import com.hellen.kidtrip.rule.PlanRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 排程那一步：**真实往返车次有没有进到模型眼前**。
 *
 * 这个测试盯的是一类很特别的 bug，值得先说清楚它特别在哪：
 *
 *   **返回值上完全看不出来。** 车次查出来了、也挂到响应上了，看起来一切正常 ——
 *   但排程时模型压根不知道那两个时刻，于是它排出"第 1 天 10:00 逛动物园"（车 10:39 才到）
 *   和"最后一天 15:00 珠江夜游"（车 14:16 就走）。用户看到的是一份自相矛盾的行程，
 *   而**每一个字段单独看都是对的**。
 *
 *   这类错只能从"喂进去的那段文本"上验 —— 所以要断言的是**输入**，不是输出。
 *   这也是整个工程那条分工的又一次应用：事实必须来自上游，并且必须**一路被送到**
 *   做决定的那一层；中间任何一处"我查到了但我没说"，都会变成一份看起来没问题的错行程。
 */
@DisplayName("排程 prompt：真实往返车次必须进到模型那一步")
class PlanComposerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 一份最小的合法输出。这些用例关心的不是模型回了什么，而是我们喂进去了什么 */
    private static final String OK_JSON = """
            {"summary":"稳一点",
             "days":[{"day":1,"theme":"抵达","hotelCandidateId":"none","hotelTips":"",
                      "items":[{"candidateId":"C1","time":"14:00","tips":"慢慢来"}]}],
             "warnings":["注意防晒"]}""";

    private static PoiCandidate poi(String id) {
        return new PoiCandidate(id, "广州动物园", "广州市越秀区先烈中路", "动物园",
                23.1, 113.2, 29.9, 5, "必玩榜第 1 名", "item-" + id, "全天票",
                "https://img.example/" + id + ".jpg", "https://book.example/" + id);
    }

    private static Transfer leg(String from, String to, String depStation, String arrStation,
                                String dep, String arr, Integer minutes, String serviceNo) {
        return new Transfer(from, to, TransportMode.TRAIN, depStation, arrStation, dep, arr,
                minutes, 74.5, serviceNo, "二等座", true, null, "https://router.feizhu.com/x");
    }

    /** 深圳 → 广州，5 天，和实测那份出问题的画像一样 */
    private static TravelParams params() {
        return new TravelParams("深圳", "广州", 5, "2026-11-17", List.of("户外", "动物园"), "慢", 7, false);
    }

    private static Legs realLegs() {
        return new Legs(
                new Legs.Options(leg("深圳", "广州", "深圳北", "广州南", "2026-11-17T10:10", "2026-11-17T10:39", 29, "高铁G390"), List.of()),
                new Legs.Options(leg("广州", "深圳", "广州南", "深圳北", "2026-11-21T14:16", "2026-11-21T14:45", 29, "高铁G1005"), List.of()));
    }

    /* ============================================================
     * 那一段文本本身
     * ============================================================ */

    @Test
    @DisplayName("⭐ 去程的**到达**时刻和回程的**发车**时刻都要写清楚 —— 它们是第 1 天和最后一天的边界")
    void renderLegsCarriesBothCriticalMoments() {
        String text = PlanComposer.renderLegs(realLegs());

        assertTrue(text.contains("11-17 10:10") && text.contains("11-17 10:39"),
                "第 1 天最早能几点开始，完全由**到达**时刻决定：" + text);
        assertTrue(text.contains("11-21 14:16"), "最后一天最晚能玩到几点，由**发车**时刻决定：" + text);
        assertTrue(text.contains("深圳北") && text.contains("广州南"),
                "站名要带上 —— 模型写 hotelTips 时才知道'离车站多远'是不是个理由：" + text);
        assertTrue(text.contains("高铁G390") && text.contains("高铁G1005"),
                "车次带上，出现矛盾时用户和我们才对得上是哪一趟：" + text);
        assertTrue(text.contains("29 分钟"), "耗时是'这一段累不累'的唯一依据：" + text);
    }

    @Test
    @DisplayName("⚠️ 没查到车次时要**明说没有**，而不是留白 —— 留白等于让模型自己编一个时刻")
    void renderLegsSaysSoWhenThereIsNothing() {
        String none = PlanComposer.renderLegs(Legs.empty());

        assertTrue(none.contains("没有查到"), none);
        assertFalse(none.contains("去程："), "没有事实的时候，不能摆出事实的架子：" + none);
        assertTrue(PlanComposer.renderLegs(null).contains("没有查到"),
                "null 和 empty 必须走出同一句话，不能一个正常一个 NPE");
    }

    @Test
    @DisplayName("只查到一段时就只写一段（不替另一段编时刻）")
    void renderLegsRendersOnlyWhatItHas() {
        String text = PlanComposer.renderLegs(new Legs(
                new Legs.Options(leg("深圳", "广州", "深圳北", "广州南", "2026-11-17T10:10", "2026-11-17T10:39", 29, "高铁G390"), List.of()),
                Legs.Options.empty()));

        assertTrue(text.contains("去程："), text);
        assertFalse(text.contains("回程："), "另一段没查到就该没有 —— 补一个假的比空着坏得多：" + text);
    }

    @Test
    @DisplayName("时刻形状不对/缺失 → 原样带出或明说未知，绝不能让这条约束静默消失")
    void renderMomentDegradesVisibly() {
        assertEquals("11-17 10:10", PlanComposer.renderMoment("2026-11-17T10:10"));
        assertEquals("时刻未知", PlanComposer.renderMoment(null));
        assertEquals("乱七八糟", PlanComposer.renderMoment("乱七八糟"),
                "形状不对就原样带出去 —— 解析失败时返回空串，等于这条约束凭空不见了");
    }

    /* ============================================================
     * ⭐ 整条链路上：它真的被送到了
     * ============================================================ */

    /**
     * 为什么"renderLegs 写对了"还不够，非要再测一遍整条路：
     *   因为这一步的失败方式恰恰是"**函数是对的，但它没被调用**"——
     *   我第一版就把 renderLegs 写好了，却忘了在模板里加那个 %s 占位符，
     *   于是它永远没被执行，而所有单元测试照样全绿。
     *   （这类"接错线"的错在这个工程里出现过好几次了，形状完全一样：
     *   判据对、数据对，只是那一句话没接上。）
     */
    @Test
    @DisplayName("⭐ 车次真的进了模型看到的 prompt（写对了 ≠ 被调用了）")
    void legsActuallyReachTheModelPrompt() {
        FakeChatModel model = new FakeChatModel(OK_JSON);
        new PlanComposer(model, MAPPER).draft(params(), List.of(poi("C1")), List.of(),
                PlanRules.DEFAULT, realLegs());

        String prompt = model.firstPrompt();
        assertNotNull(prompt);
        assertTrue(prompt.contains("10:39"),
                "去程到达时刻没进 prompt —— 模型又会排出'10:00 逛动物园'");
        assertTrue(prompt.contains("14:16"),
                "回程发车时刻没进 prompt —— 模型又会排出'15:00 珠江夜游'");
        assertTrue(prompt.contains("【已查到的往返交通】"),
                "那一段必须真的被插进模板里（曾经漏过那个 %s 占位符）");
    }

    @Test
    @DisplayName("没有车次也要能正常出草稿（车次是加分项，不是前提）")
    void draftWorksWithoutAnyLegs() {
        FakeChatModel model = new FakeChatModel(OK_JSON);

        PlanDraft draft = new PlanComposer(model, MAPPER).draft(params(), List.of(poi("C1")),
                List.of(), PlanRules.DEFAULT, Legs.empty());

        assertEquals(1, draft.days().size(), "没车次照样要排得出行程");
        assertTrue(model.firstPrompt().contains("没有查到往返车次"),
                "但必须让模型知道'这次没有车次'，否则它会在 tips 里编一句时刻");
    }
}
