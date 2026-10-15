package com.hellen.kidtrip.plan;

import com.hellen.kidtrip.candidate.Candidate;
import com.hellen.kidtrip.candidate.HotelCandidate;
import com.hellen.kidtrip.candidate.PoiCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 住宿备选（"今晚还能住哪儿"）的三条规则。
 *
 * 为什么它值得单独一组测试 —— 这一层**不产生任何新数据**，只是把已经查回来的
 * 酒店重新排一下、露出来。所以它写错了完全没有声音：
 *   · 忘了排除主选 → 用户看到"也可以住 <他本来就住的那家>"，像是系统在复读；
 *   · 把价格 null 当成 0 → 一份"订不了但排在最便宜那格"的推荐；
 *   · 忘了截断 → 一屏十几家酒店，把"一个决定"变成"一份列表"。
 * 三条都是**不报错**的错，而且都只在"有真数据、真打开界面"时才看得见 ——
 * 所以只能钉在这里。
 *
 * 另一件事：`hotelAlternativesOf` / `hotelStaysOf` / `attachHotelAlternatives`
 * 都被抽成了 static 纯函数（或用纯函数拼出来的），**可测性是设计出来的**，
 * 和 PlanServiceFlightTest 是同一个理由。
 */
@DisplayName("住宿备选：排哪几家、怎么排、在哪天露出来")
class PlanServiceHotelAlternativeTest {

    /* ============================================================
     * 造数据
     * ============================================================ */

    private static HotelCandidate candidate(String id, String name, String star, Double price) {
        return new HotelCandidate(id, name, "地址", 22.5, 114.0, price, star, null, "", "https://x/" + id);
    }

    /** HotelStay 有 11 个字段，测试里只关心 id / 名字 / 价格 / 档位 —— 其余一律占位 */
    private static HotelStay stay(String id, Double price) {
        return stay(id, price, "舒适型");
    }

    private static HotelStay stay(String id, Double price, String star) {
        return new HotelStay("名字-" + id, id, price, star, null, "地址", 22.5, 114.0,
                "https://book/" + id, "", null);
    }

    private static TravelDay dayWithHotel(int day, String hotelId) {
        return new TravelDay(day, "主题", List.of(), null, List.of(),
                hotelId == null ? null : stay(hotelId, 388.0), List.of(), null);
    }

    /* ============================================================
     * hotelAlternativesOf —— 排哪几家
     * ============================================================ */

    @Test
    @DisplayName("排除主选：备选里绝不能出现用户已经住的那一家")
    void excludesTheChosenOne() {
        var pool = List.of(stay("A", 300.0), stay("B", 200.0), stay("C", 400.0));

        var alts = PlanService.hotelAlternativesOf(pool, "A");

        assertEquals(List.of("B", "C"), alts.stream().map(HotelStay::hotelId).toList());
    }

    @Test
    @DisplayName("按价格升序：最便宜的排最前（这一行最大的用处就是'原来还有更便宜的'）")
    void sortedByPriceAscending() {
        var pool = List.of(stay("A", 500.0), stay("B", 143.0), stay("C", 283.0), stay("D", 158.0));

        var alts = PlanService.hotelAlternativesOf(pool, null);

        // 143 / 158 / 283 —— 只取前 3，500 那家被截掉（它是池子里最贵的）
        assertEquals(List.of("B", "D", "C"), alts.stream().map(HotelStay::hotelId).toList());
    }

    @Test
    @DisplayName("价格未知（null）排**最后** —— 它不是 ¥0，是'飞猪上没有可订房型'")
    void nullPriceGoesLast() {
        // 这一条要是写错（nullsFirst 或把 null 当 0），用户看到的第一条备选就是
        // "房价未知" —— 点进去发现订不了。而"最便宜的那格"恰好是他最先看的地方。
        var pool = List.of(stay("贵", 500.0), stay("无价", null), stay("便宜", 100.0));

        var alts = PlanService.hotelAlternativesOf(pool, null);

        assertEquals(List.of("便宜", "贵", "无价"), alts.stream().map(HotelStay::hotelId).toList());
    }

    @Test
    @DisplayName("最多 3 家：再多就从'一个决定'变成'一份列表'")
    void capsAtThree() {
        var pool = List.of(
                stay("A", 100.0), stay("B", 200.0), stay("C", 300.0),
                stay("D", 400.0), stay("E", 500.0));

        assertEquals(3, PlanService.hotelAlternativesOf(pool, null).size());
    }

    /* ============================================================
     * 跨档 —— 这是"查两档"这次改动的**全部意义**所在
     * ============================================================ */

    @Test
    @DisplayName("跨档轮转：两个档位都要出现（否则第二档那次查询就是纯浪费）")
    void spreadsAcrossTiers() {
        // 这条是真跑一次才补上的（2026-10-14，广州→珠海）：
        // 当时备选是"按价格取前 3"，而珠海最便宜的几家恰好全在舒适档，
        // 于是四星档**一家都没露出来** —— 多花的那次上游调用、多占的那份预算，
        // 换来的东西用户永远看不到。这类浪费没有任何信号，只能靠这条断言拦。
        var pool = List.of(
                stay("舒1", 108.0, "舒适型"),
                stay("舒2", 145.0, "舒适型"),
                stay("高1", 260.0, "高档型"),
                stay("高2", 283.0, "高档型"));

        var alts = PlanService.hotelAlternativesOf(pool, null);

        assertEquals(3, alts.size());
        assertTrue(alts.stream().anyMatch(h -> "舒适型".equals(h.star())),
                "备选里一个舒适档都没有：" + ids(alts));
        assertTrue(alts.stream().anyMatch(h -> "高档型".equals(h.star())),
                "备选里一个高档都没有 —— 那第二档那次查询就白花了：" + ids(alts));
    }

    @Test
    @DisplayName("跨档之后**展示顺序仍然是价格升序**（选出谁和怎么摆是两件事）")
    void crossTierPickIsStillDisplayedByPrice() {
        var pool = List.of(
                stay("舒1", 108.0, "舒适型"),
                stay("舒2", 145.0, "舒适型"),
                stay("高1", 260.0, "高档型"));

        var alts = PlanService.hotelAlternativesOf(pool, null);

        assertEquals(List.of(108.0, 145.0, 260.0), alts.stream().map(HotelStay::priceYuan).toList(),
                "轮转取完之后必须重新按价格排 —— 否则用户看到的是 ¥108 / ¥260 / ¥145 这种乱序");
    }

    @Test
    @DisplayName("单档位时退化成'按价格取前 3'（配置只写一档的人不该看到另一套行为）")
    void singleTierDegradesToCheapest() {
        var pool = List.of(
                stay("A", 500.0, "舒适型"), stay("B", 143.0, "舒适型"),
                stay("C", 283.0, "舒适型"), stay("D", 158.0, "舒适型"));

        var alts = PlanService.hotelAlternativesOf(pool, null);

        assertEquals(List.of("B", "D", "C"), ids(alts));
    }

    @Test
    @DisplayName("档位少的一家不够轮转时，从多的那档接着取（不能因为轮空就少给）")
    void roundRobinFillsFromTheBiggerTier() {
        var pool = List.of(
                stay("舒1", 108.0, "舒适型"), stay("舒2", 145.0, "舒适型"),
                stay("舒3", 160.0, "舒适型"), stay("高1", 260.0, "高档型"));

        var alts = PlanService.hotelAlternativesOf(pool, null);

        // 一轮各拿一家 → 舒1 + 高1；第二轮只剩舒适档 → 再拿舒2。共 3 家。
        assertEquals(List.of("舒1", "舒2", "高1"), ids(alts));
    }

    private static List<String> ids(List<HotelStay> stays) {
        return stays.stream().map(HotelStay::hotelId).toList();
    }

    @Test
    @DisplayName("被排除的那家不占名额：池子里只有 4 家、排除 1 家后仍然给满 3 家")
    void excludedDoesNotConsumeTheQuota() {
        // 这条防的是"先取前 3 再排除"那种写法 —— 那种写法下池子是 [主选, 便宜, 中, 贵] 时
        // 只会给出 2 家备选，而且不报错。用户看到的是"备选怎么比别人少"。
        var pool = List.of(stay("主选", 10.0), stay("A", 100.0), stay("B", 200.0), stay("C", 300.0));

        var alts = PlanService.hotelAlternativesOf(pool, "主选");

        assertEquals(List.of("A", "B", "C"), alts.stream().map(HotelStay::hotelId).toList());
    }

    @Test
    @DisplayName("空池 → 空列表（不是崩，也不是 null）")
    void emptyPoolYieldsEmpty() {
        assertEquals(List.of(), PlanService.hotelAlternativesOf(List.of(), "A"));
        assertEquals(List.of(), PlanService.hotelAlternativesOf(null, "A"));
    }

    @Test
    @DisplayName("不变量：返回的列表**永远**不含主选，且永远 <= 3 家")
    void neverContainsTheChosenOneAndNeverExceedsCap() {
        // 上面每条测一个侧面，这条把两个不变量一起钉住 —— 因为它们是"备选"这个概念的定义，
        // 而不是某个分支的行为。以后加档位、改排序都不该动到这两条。
        var pool = List.of(
                stay("A", 100.0), stay("B", 200.0), stay("C", 300.0),
                stay("D", 400.0), stay("E", 500.0));

        for (String chosen : List.of("A", "B", "C", "D", "E")) {
            var alts = PlanService.hotelAlternativesOf(pool, chosen);
            assertTrue(alts.size() <= 3, "选了 " + chosen + " 时备选超过 3 家");
            assertFalse(alts.stream().anyMatch(h -> chosen.equals(h.hotelId())),
                    "选了 " + chosen + " 时它自己出现在备选里");
        }
    }

    /* ============================================================
     * hotelStaysOf —— 从混合池里只挑酒店
     * ============================================================ */

    @Test
    @DisplayName("只挑酒店：景点绝不能混进住宿备选")
    void onlyHotelsMakeItIn() {
        // 和 SpotPoolTest 里"酒店不能混进景点备选"是**成对**的一条。
        // 判别联合（Candidate）让两种都收下只需要改一个 instanceof，编译得过、跑得起来，
        // 只是界面上会多出一条「也可以住 → 广州塔 ¥150」。全程不报错。
        var poi = new PoiCandidate("p1", "广州塔", "地址", "地标", 23.1, 113.3, 150.0, 5,
                null, null, null, "", "https://x");
        List<Candidate> pool = List.of(
                candidate("h1", "某酒店", "舒适型", 200.0),
                poi,
                candidate("h2", "另一家", "四星级", 400.0));

        var stays = PlanService.hotelStaysOf(pool);

        assertEquals(List.of("h1", "h2"), stays.stream().map(HotelStay::hotelId).toList());
    }

    @Test
    @DisplayName("投影保留事实、但清掉 tips：备选没有经过模型判断，不该带'为什么住这儿'")
    void projectionKeepsFactsButDropsTips() {
        // tips 是模型的表达（"离第二天第一个点近"）。备选压根没送给模型看过，
        // 给它编一句 tips 就成了**我们替模型说的话** —— 而这个项目从头到尾在防这件事。
        var stay = PlanService.hotelStaysOf(List.<Candidate>of(candidate("h1", "某酒店", "舒适型", 200.0))).get(0);

        assertEquals("h1", stay.hotelId());
        assertEquals("某酒店", stay.name());
        assertEquals(200.0, stay.priceYuan());
        assertEquals("舒适型", stay.star());
        assertEquals("https://x/h1", stay.bookUrl());
        assertNull(stay.tips());
    }

    /* ============================================================
     * attachHotelAlternatives —— 在哪天露出来
     * ============================================================ */

    @Test
    @DisplayName("每一天都挂上同一份备选（'哪几天算连住'是前端的事，不在这里算）")
    void everyDayGetsTheSameList() {
        var pool = List.of(stay("主选", 388.0), stay("A", 100.0), stay("B", 200.0));
        var days = List.of(dayWithHotel(1, "主选"), dayWithHotel(2, "主选"), dayWithHotel(3, "主选"));

        var out = PlanService.attachHotelAlternatives(days, pool);

        for (TravelDay d : out) {
            assertEquals(List.of("A", "B"), d.hotelAlternatives().stream().map(HotelStay::hotelId).toList(),
                    "第 " + d.day() + " 天没挂上备选");
        }
    }

    @Test
    @DisplayName("排除的是**当天**的主选：两天住不同酒店时，各自的备选不一样")
    void excludesEachDaysOwnChoice() {
        var pool = List.of(stay("X", 300.0), stay("Y", 200.0), stay("Z", 500.0));
        var days = List.of(dayWithHotel(1, "X"), dayWithHotel(2, "Y"));

        var out = PlanService.attachHotelAlternatives(days, pool);

        assertFalse(out.get(0).hotelAlternatives().stream().anyMatch(h -> "X".equals(h.hotelId())));
        assertFalse(out.get(1).hotelAlternatives().stream().anyMatch(h -> "Y".equals(h.hotelId())));
    }

    @Test
    @DisplayName("主选为 null 的那天也给备选 —— '今晚没安排'正是最需要给几个选择的时候")
    void dayWithoutHotelStillGetsAlternatives() {
        var pool = List.of(stay("A", 100.0), stay("B", 200.0));
        var days = List.of(dayWithHotel(1, null));

        var out = PlanService.attachHotelAlternatives(days, pool);

        assertEquals(2, out.get(0).hotelAlternatives().size());
    }

    @Test
    @DisplayName("池子空 → 原样返回同一个对象（不为了挂空列表做一次无谓的拷贝）")
    void emptyPoolReturnsTheSameList() {
        var days = List.of(dayWithHotel(1, "主选"));

        assertSame(days, PlanService.attachHotelAlternatives(days, List.of()));
        assertSame(days, PlanService.attachHotelAlternatives(days, null));
    }

    @Test
    @DisplayName("挂备选时**别的字段一个都不能丢**（交通、景点、主题、tips）")
    void keepsEveryOtherField() {
        // 这个方法是"重建 TravelDay"而不是"就地改"，所以漏一个字段的后果是
        // 那份数据**静默消失**。TravelDay 现在有 7 个字段，这里是唯一盯着这件事的地方。
        var transfer = new com.hellen.kidtrip.retrieval.Transfer("广州", "珠海",
                com.hellen.kidtrip.retrieval.TransportMode.TRAIN, "广州南", "珠海",
                "2026-11-17T08:00", "2026-11-17T08:53", 53, 70.0,
                "动车D947", "二等座", true, null, "https://x");
        var spot = new TravelSpot("09:30", "长隆", "提示", "p1", 300.0, "https://b", "", 22.0, 113.0);
        var original = new TravelDay(1, "抵达日", List.of(spot), transfer, List.of(),
                stay("主选", 388.0), List.of(), null);

        var out = PlanService.attachHotelAlternatives(List.of(original), List.of(stay("A", 100.0))).get(0);

        assertEquals(1, out.day());
        assertEquals("抵达日", out.theme());
        assertEquals(1, out.spots().size());
        assertEquals("长隆", out.spots().get(0).name());
        assertSame(transfer, out.transfer());
        assertEquals(388.0, out.hotel().priceYuan());
        assertEquals(1, out.hotelAlternatives().size());
    }
}
