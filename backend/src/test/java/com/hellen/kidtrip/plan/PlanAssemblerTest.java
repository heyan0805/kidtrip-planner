package com.hellen.kidtrip.plan;

import com.hellen.kidtrip.candidate.HotelCandidate;
import com.hellen.kidtrip.candidate.PoiCandidate;
import com.hellen.kidtrip.llm.PlanDraft;
import com.hellen.kidtrip.rule.PlanRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PlanAssembler 的单元测试 —— **本工程第一个真正有意义的测试**。
 *
 * 为什么之前没有测试，现在有了：
 *   在此之前，我们所有的验证都得"真跑一次、用眼睛看"——因为每一步都依赖外部（飞猪、模型）。
 *   而 PlanAssembler 是纯函数：给同样的输入，永远同样的输出，不联网、不花钱、不看时钟。
 *   于是"模型不听话时会发生什么"这件事，可以在 5 毫秒内被反复验证，而不是每次烧 20 秒和一笔 token。
 *
 * 这就是"可测性是被设计出来的"：它不是靠补测试补出来的，
 * 是靠**把不可测的东西（模型的自由输出）挡在外面，只让可测的东西（表查找 + 计数）进来**。
 *
 * 注意这些用例全部在描述"模型可能怎么不听话"：
 *   编号写错、景点排重、数量超了、天数不对。
 * 每一条都对应一个真实会发生的情况，不是为了凑覆盖率写的。
 */
class PlanAssemblerTest {

    /* ============================================================
     * 构造测试数据的小工具
     * ============================================================ */

    private static PoiCandidate poi(String id, String name, Double price) {
        return new PoiCandidate(
                id, name, "广东省深圳市南山区某某路 1 号", "主题乐园",
                22.5, 113.9, price, 5, "本地人推荐榜第 1 名",
                "item-" + id, "全天票 成人票",
                "https://img.example/" + id + ".jpg",
                "https://book.example/" + id);
    }

    private static TravelParams params(String pace, int days) {
        return new TravelParams(null, "深圳", days, "2026-10-24", List.of("室内"), pace, 4, null);
    }

    private static PlanDraft draft(String summary, PlanDraft.Day... days) {
        return new PlanDraft(summary, List.of(days), List.of("记得提前预约"));
    }

    /** 不带住宿的一天（大多数用例关心的是景点那套逻辑） */
    private static PlanDraft.Day day(int no, String theme, PlanDraft.Item... items) {
        return new PlanDraft.Day(no, theme, null, null, List.of(items));
    }

    /** 带住宿的一天。hotelCode 可以直接传 "H1"，也可以传 "none" 表达"当天返程不住" */
    private static PlanDraft.Day dayAt(int no, String theme, String hotelCode, PlanDraft.Item... items) {
        return new PlanDraft.Day(no, theme, hotelCode, "住这儿离明天近", List.of(items));
    }

    private static HotelCandidate hotel(String id, String name, Double pricePerNight) {
        return new HotelCandidate(
                id, name, "广东省深圳市南山区某某路 2 号", 22.6, 113.95,
                pricePerNight, "经济型", 4.6,
                "https://img.example/" + id + ".jpg",
                "https://book.example/" + id);
    }

    private static PlanDraft.Item item(String code, String time) {
        return new PlanDraft.Item(code, time, "带上水和防晒");
    }

    /**
     * 简称：不带酒店的装配。
     * 存在的理由很实在 —— 14 个既有用例全都不关心住宿，让它们每个都多写一个 List.of()
     * 只会把"这条用例在测什么"淹没在噪音里。真正测住宿的用例显式传酒店列表。
     */
    private static PlanAssembler.Assembled assemble(PlanDraft draft, List<PoiCandidate> pois,
                                                   TravelParams params, PlanRules rules) {
        return PlanAssembler.assemble(draft, pois, List.of(), params, rules);
    }

    /* ============================================================
     * 用例
     * ============================================================ */

    @Test
    @DisplayName("正常路径：事实全部回填自候选，只有 time/tips 来自模型")
    void hydratesFactsFromCandidates() {
        List<PoiCandidate> pois = List.of(poi("P1", "深圳欢乐谷", 227.0));
        PlanDraft d = draft("安排轻松", day(1, "欢乐谷一日", item("C1", "09:30")));

        PlanAssembler.Assembled a = assemble(d, pois, params("慢", 1), PlanRules.DEFAULT);

        assertEquals(1, a.days().size());
        TravelSpot spot = a.days().get(0).spots().get(0);
        assertEquals("深圳欢乐谷", spot.name(), "名字必须来自候选，不是模型写的");
        assertEquals(Double.valueOf(227.0), spot.priceYuan(), "价格必须来自候选");
        assertEquals("P1", spot.poiId(), "poiId 必须来自候选，否则追溯不了");
        assertEquals("https://book.example/P1", spot.bookUrl());
        assertEquals(Double.valueOf(22.5), spot.lat());
        assertEquals("09:30", spot.time(), "时间来自模型");
        assertEquals("带上水和防晒", spot.tips(), "提示来自模型");
    }

    @Test
    @DisplayName("模型编造了清单外的编号 → 丢弃并记账（这就是白名单机制）")
    void dropsHallucinatedCandidateId() {
        List<PoiCandidate> pois = List.of(poi("P1", "深圳欢乐谷", 227.0));
        PlanDraft d = draft("安排", day(1, "Day1", item("C1", "09:30"), item("C99", "14:00")));

        PlanAssembler.Assembled a = assemble(d, pois, params("慢", 1), PlanRules.DEFAULT);

        assertEquals(1, a.days().get(0).spots().size(), "C99 不在清单里，必须被丢掉");
        assertTrue(a.warnings().stream().anyMatch(w -> w.contains("不存在的编号")),
                "丢掉了就一定要说，不能让用户拿到一份少了点的行程还不知道为什么");
    }

    @Test
    @DisplayName("同一个景点被排了两天 → 第二次丢弃")
    void dropsDuplicates() {
        List<PoiCandidate> pois = List.of(poi("P1", "深圳欢乐谷", 227.0), poi("P2", "世界之窗", 219.0));
        PlanDraft d = draft("安排",
                day(1, "Day1", item("C1", "09:30")),
                day(2, "Day2", item("C1", "09:30"), item("C2", "13:30")));

        PlanAssembler.Assembled a = assemble(d, pois, params("慢", 2), PlanRules.DEFAULT);

        assertEquals(1, a.days().get(0).spots().size());
        assertEquals(1, a.days().get(1).spots().size(), "C1 重复了，第 2 天只该剩 C2");
        assertEquals("世界之窗", a.days().get(1).spots().get(0).name());
        assertTrue(a.warnings().stream().anyMatch(w -> w.contains("重复安排")));
    }

    @Test
    @DisplayName("一天排超了上限 → 截断（数量这种算得准的事，不信模型）")
    void truncatesOverflow() {
        List<PoiCandidate> pois = List.of(
                poi("P1", "A", 100.0), poi("P2", "B", 100.0),
                poi("P3", "C", 100.0), poi("P4", "D", 100.0));
        PlanDraft d = draft("安排", day(1, "Day1",
                item("C1", "09:30"), item("C2", "11:00"), item("C3", "14:00"), item("C4", "16:00")));

        // 节奏=慢 → 每天 2 个
        PlanAssembler.Assembled a = assemble(d, pois, params("慢", 1), PlanRules.DEFAULT);

        assertEquals(2, a.days().get(0).spots().size(), "慢节奏每天只能 2 个");
        assertTrue(a.warnings().stream().anyMatch(w -> w.contains("超过每天 2 个的上限")));
    }

    @Test
    @DisplayName("模型少排了天数 → 按画像补齐（宁可给个空天，也不要少一天）")
    void padsMissingDays() {
        List<PoiCandidate> pois = List.of(poi("P1", "深圳欢乐谷", 227.0));
        PlanDraft d = draft("安排", day(1, "只有一天", item("C1", "09:30")));

        PlanAssembler.Assembled a = assemble(d, pois, params("中", 3), PlanRules.DEFAULT);

        assertEquals(3, a.days().size(), "画像说 3 天，就必须有 3 天");
        assertEquals(2, a.days().get(1).day());
        assertEquals("第 2 天", a.days().get(1).theme(), "模型没给主题时要有兜底文案，不能是 null");
        assertEquals(0, a.days().get(2).spots().size());
    }

    @Test
    @DisplayName("模型多排了天数 → 截断到画像的天数")
    void truncatesExtraDays() {
        List<PoiCandidate> pois = List.of(poi("P1", "A", 100.0), poi("P2", "B", 100.0));
        PlanDraft d = draft("安排",
                day(1, "Day1", item("C1", "09:30")),
                day(2, "Day2", item("C2", "09:30")),
                day(3, "Day3"));

        PlanAssembler.Assembled a = assemble(d, pois, params("中", 2), PlanRules.DEFAULT);

        assertEquals(2, a.days().size());
        assertTrue(a.warnings().stream().anyMatch(w -> w.contains("截断")));
    }

    @Test
    @DisplayName("价格缺失时是 null，不是 0 —— 免费公园和价格未知必须区分得开")
    void keepsNullPriceAsNull() {
        List<PoiCandidate> pois = List.of(poi("P1", "立新湖公园", null));
        PlanDraft d = draft("安排", day(1, "Day1", item("C1", "09:30")));

        PlanAssembler.Assembled a = assemble(d, pois, params("慢", 1), PlanRules.DEFAULT);

        assertNull(a.days().get(0).spots().get(0).priceYuan(),
                "写成 0 会让用户以为免费，而它其实只是'这张票飞猪上没有'");
    }

    @Test
    @DisplayName("时间格式不对 → 用默认时刻补上并记账")
    void patchesBadTime() {
        List<PoiCandidate> pois = List.of(poi("P1", "A", 100.0));
        PlanDraft d = draft("安排", day(1, "Day1", item("C1", "上午")));

        PlanAssembler.Assembled a = assemble(d, pois, params("慢", 1), PlanRules.DEFAULT);

        assertEquals("09:30", a.days().get(0).spots().get(0).time());
        assertTrue(a.warnings().stream().anyMatch(w -> w.contains("时间点")));
    }

    @Test
    @DisplayName("模型自己写的注意事项会跟我们的告警合成一个数组，且我们的在前")
    void mergesWarningsWithOursFirst() {
        List<PoiCandidate> pois = List.of(poi("P1", "A", 100.0));
        PlanDraft d = draft("安排", day(1, "Day1", item("C9", "09:30")));

        PlanAssembler.Assembled a = assemble(d, pois, params("慢", 1), PlanRules.DEFAULT);

        assertTrue(a.warnings().get(0).contains("不存在的编号"), "我们的告警要排在模型建议前面");
        assertTrue(a.warnings().contains("记得提前预约"), "模型写的注意事项也要保留");
    }

    @Test
    @DisplayName("去重后某天变空 → 从没用上的候选本地补排（这条是实测撞出来的）")
    void fillsDayEmptiedByDedupe() {
        List<PoiCandidate> pois = List.of(
                poi("P1", "世界之窗", 219.0), poi("P2", "欢乐谷", 227.0),
                poi("P3", "野生动物园", 198.0), poi("P4", "海洋世界", 223.0));
        // 2026-10-13 真跑时模型就是这么写的：第 2 天把第 1 天的 C3 又排了一遍。
        // 去重之后第 2 天变成空的 —— 用户看到的是一份"中间那天什么都没有"的行程。
        PlanDraft d = draft("安排",
                day(1, "海洋探秘日", item("C4", "09:30"), item("C3", "14:00")),
                day(2, "室内动物奇遇", item("C3", "09:30")),
                day(3, "城市漫步", item("C1", "09:30")));

        PlanAssembler.Assembled a = assemble(d, pois, params("慢", 3), PlanRules.DEFAULT);

        assertEquals(2, a.days().get(0).spots().size());
        assertEquals(1, a.days().get(2).spots().size());
        // 池子里只剩下 P2（欢乐谷）没被用过，就该补它
        assertEquals(1, a.days().get(1).spots().size(), "空天必须被补上");
        assertEquals("欢乐谷", a.days().get(1).spots().get(0).name());
        assertEquals("灵活安排", a.days().get(1).theme(), "补排那天的主题要换掉，否则主题和内容不符");
        assertNotNull(a.days().get(1).spots().get(0).poiId(), "补排的点同样必须可追溯");
        assertTrue(a.warnings().stream().anyMatch(w -> w.contains("补排")));
    }

    @Test
    @DisplayName("池子里也没有没用过的候选时，就不补 —— 不许编造")
    void doesNotInventWhenNothingLeft() {
        List<PoiCandidate> pois = List.of(poi("P1", "A", 100.0));
        PlanDraft d = draft("安排",
                day(1, "D1", item("C1", "09:30")),
                day(2, "D2", item("C1", "09:30")));

        PlanAssembler.Assembled a = assemble(d, pois, params("慢", 2), PlanRules.DEFAULT);

        assertEquals(0, a.days().get(1).spots().size(), "没有可补的就必须留空，而不是编一个出来");
    }

    @Test
    @DisplayName("节奏决定每天几个点，但突破不了硬上限 4")
    void spotsPerDayFollowsPace() {
        PlanRules rules = PlanRules.DEFAULT;
        assertEquals(2, rules.spotsPerDay("慢"));
        assertEquals(3, rules.spotsPerDay("中"));
        assertEquals(4, rules.spotsPerDay("快"));
        assertEquals(rules.maxSpotsPerDay(), rules.spotsPerDay(""));
        assertEquals(rules.maxSpotsPerDay(), rules.spotsPerDay(null));
        // 就算规则表被人改成 9，硬上限也把它压回 4 —— 产品底线不该是配置项
        PlanRules crazy = new PlanRules(null, null, 9, 15, 2, 30_000);
        assertEquals(PlanRules.HARD_MAX_SPOTS_PER_DAY, crazy.spotsPerDay("快"));
    }

    @Test
    @DisplayName("模型返回 null 字段（漏了整段）时不能抛异常")
    void toleratesNullsFromModel() {
        List<PoiCandidate> pois = List.of(poi("P1", "A", 100.0));
        PlanDraft broken = new PlanDraft(null, null, null);

        PlanAssembler.Assembled a = assemble(broken, pois, params("中", 2), PlanRules.DEFAULT);

        assertEquals(2, a.days().size());
        assertNotNull(a.summary(), "summary 不能是 null —— 前端渲染 undefined 比渲染兜底文案糟糕得多");
        assertNotNull(a.warnings());
    }

    @Test
    @DisplayName("候选池为空时不炸，给出可读的结果")
    void handlesEmptyPool() {
        PlanAssembler.Assembled a = PlanAssembler.assemble(
                new PlanDraft("x", List.of(), List.of()), new ArrayList<>(), List.of(),
                params("中", 1), PlanRules.DEFAULT);

        assertEquals(1, a.days().size());
        assertEquals(0, a.days().get(0).spots().size());
    }

    /* ============================================================
     * 住宿：每晚住哪
     *
     * 这一节和上面那一节有一条**反向**的规矩，所以单独一组：
     *   景点：同一个只能出现一次（靠 usedPoiIds 去重）
     *   酒店：同一家连着住好几晚是对的（绝不能去重）
     * 把这两套合成一套"通用去重"，是重构里最容易踩的坑 —— 这几条用例就是钉住它的。
     * ============================================================ */

    @Test
    @DisplayName("住宿：事实全部回填自候选酒店，只有 hotelTips 来自模型")
    void hydratesHotelFromCandidates() {
        List<PoiCandidate> pois = List.of(poi("P1", "深圳欢乐谷", 227.0));
        List<HotelCandidate> hotels = List.of(hotel("HA", "深圳湾某酒店", 388.0));
        PlanDraft d = draft("安排",
                dayAt(1, "欢乐谷一日", "H1", item("C1", "09:30")));

        PlanAssembler.Assembled a = PlanAssembler.assemble(d, pois, hotels, params("慢", 1), PlanRules.DEFAULT);

        HotelStay stay = a.days().get(0).hotel();
        assertNotNull(stay, "模型指定了 H1，就必须有住宿落点");
        assertEquals("深圳湾某酒店", stay.name(), "名字必须来自候选，不是模型写的");
        assertEquals("HA", stay.hotelId(), "hotelId 必须来自候选，否则追溯不了");
        assertEquals(Double.valueOf(388.0), stay.priceYuan(), "房价（每晚）必须来自候选");
        assertEquals("经济型", stay.star());
        assertEquals(Double.valueOf(4.6), stay.rating());
        assertEquals("https://book.example/HA", stay.bookUrl());
        assertEquals("住这儿离明天近", stay.tips(), "hotelTips 是模型写的表达");
    }

    @Test
    @DisplayName("住宿：同一家连着住多晚是**正常**的，绝不能被去重（和景点正好相反）")
    void allowsStayingAtSameHotelOnConsecutiveNights() {
        List<PoiCandidate> pois = List.of(poi("P1", "A", 100.0), poi("P2", "B", 100.0), poi("P3", "C", 100.0));
        List<HotelCandidate> hotels = List.of(hotel("HA", "某酒店", 388.0));
        PlanDraft d = draft("安排",
                dayAt(1, "D1", "H1", item("C1", "09:30")),
                dayAt(2, "D2", "H1", item("C2", "09:30")),
                dayAt(3, "D3", "H1", item("C3", "09:30")));

        PlanAssembler.Assembled a = PlanAssembler.assemble(d, pois, hotels, params("慢", 3), PlanRules.DEFAULT);

        assertEquals("HA", a.days().get(0).hotel().hotelId());
        assertEquals("HA", a.days().get(1).hotel().hotelId(), "第 2 晚还是同一家 —— 这不是重复，是连住");
        assertEquals("HA", a.days().get(2).hotel().hotelId());
        assertTrue(a.warnings().stream().noneMatch(w -> w.contains("重复")),
                "连住报成'重复安排'会把正确的答案说成错误");
    }

    @Test
    @DisplayName("住宿：模型只写第 1 天、中间几天留空 → 沿用前一晚（连住），且不报警")
    void inheritsPreviousStayWhenModelOmits() {
        List<PoiCandidate> pois = List.of(poi("P1", "A", 100.0), poi("P2", "B", 100.0), poi("P3", "C", 100.0));
        List<HotelCandidate> hotels = List.of(hotel("HA", "某酒店", 388.0));
        // ⚠️ 必须用 3 天：2 天的话第 2 天就是"最后一天"，走的是返程分支，测不到沿用。
        //    （第一版就是这么写错的 —— 见 lastDayOmittedMeansGoingHome。）
        PlanDraft d = draft("安排",
                dayAt(1, "D1", "H1", item("C1", "09:30")),
                day(2, "D2", item("C2", "09:30")),
                day(3, "D3", item("C3", "09:30")));

        PlanAssembler.Assembled a = PlanAssembler.assemble(d, pois, hotels, params("慢", 3), PlanRules.DEFAULT);

        assertEquals("HA", a.days().get(1).hotel().hotelId(), "第 2 天没回答 → 沿用第 1 天那家");
        assertNull(a.days().get(2).hotel(), "第 3 天是最后一天 → 按返程处理，不再沿用了");
        assertTrue(a.warnings().stream().noneMatch(w -> w.contains("住处")),
                "'模型偷懒没写'和'顾客连住同一家'在结果上是一样的，不该为它报警");
    }

    @Test
    @DisplayName("住宿：写 none = 明确不住，**绝不兜底补一家**")
    void explicitNoneMeansNoBed() {
        List<PoiCandidate> pois = List.of(poi("P1", "A", 100.0), poi("P2", "B", 100.0));
        List<HotelCandidate> hotels = List.of(hotel("HA", "某酒店", 388.0));
        PlanDraft d = draft("安排",
                dayAt(1, "D1", "H1", item("C1", "09:30")),
                dayAt(2, "D2", "none", item("C2", "09:30")));

        PlanAssembler.Assembled a = PlanAssembler.assemble(d, pois, hotels, params("慢", 2), PlanRules.DEFAULT);

        assertNull(a.days().get(1).hotel(),
                "模型明确说了不住（当天返程/自己订好了），补一家就是凭空多出用户没要的一晚");
    }

    @Test
    @DisplayName("住宿：最后一天'没回答'按当天返程处理，不沿前一晚（偏向代价小的那一边）")
    void lastDayOmittedMeansGoingHome() {
        List<PoiCandidate> pois = List.of(poi("P1", "A", 100.0), poi("P2", "B", 100.0));
        List<HotelCandidate> hotels = List.of(hotel("HA", "某酒店", 388.0));
        // 模型常犯的错：以为"同一家不用重复写"，于是只写了第 1 天
        PlanDraft d = draft("安排",
                dayAt(1, "D1", "H1", item("C1", "09:30")),
                day(2, "D2", item("C2", "09:30")));

        PlanAssembler.Assembled a = PlanAssembler.assemble(d, pois, hotels, params("慢", 2), PlanRules.DEFAULT);

        assertNotNull(a.days().get(0).hotel(), "第 1 晚要住");
        assertNull(a.days().get(1).hotel(),
                "多给一晚会让用户照它去订房、多花一笔钱；少给一晚他自己补问一句就行");
    }

    @Test
    @DisplayName("住宿：第一晚就没回答 → 从候选里补第一家，并记账")
    void fillsFirstHotelWhenDayOneHasNoAnswer() {
        List<PoiCandidate> pois = List.of(poi("P1", "A", 100.0), poi("P2", "B", 100.0));
        List<HotelCandidate> hotels = List.of(hotel("HA", "第一家", 300.0), hotel("HB", "第二家", 400.0));
        // 同样要 ≥2 天：1 天的话第 1 天就是最后一天，走返程分支而不是补一家
        PlanDraft d = draft("安排", day(1, "D1", item("C1", "09:30")), day(2, "D2", item("C2", "09:30")));

        PlanAssembler.Assembled a = PlanAssembler.assemble(d, pois, hotels, params("慢", 2), PlanRules.DEFAULT);

        assertEquals("HA", a.days().get(0).hotel().hotelId(),
                "补的是池子第一家 —— 池子顺序就是飞猪的相关性顺序，和挑景点用同一条规则");
        assertTrue(a.warnings().stream().anyMatch(w -> w.contains("补上一家")),
                "替模型补了就要说，不能让用户以为这是模型挑的");
    }

    @Test
    @DisplayName("住宿：模型编了清单外的 H 编号 → 当'没回答'处理并记账")
    void unknownHotelCodeFallsBackAndWarns() {
        List<PoiCandidate> pois = List.of(poi("P1", "A", 100.0), poi("P2", "B", 100.0), poi("P3", "C", 100.0));
        List<HotelCandidate> hotels = List.of(hotel("HA", "某酒店", 388.0));
        PlanDraft d = draft("安排",
                dayAt(1, "D1", "H1", item("C1", "09:30")),
                dayAt(2, "D2", "H99", item("C2", "09:30")),
                day(3, "D3", item("C3", "09:30")));

        PlanAssembler.Assembled a = PlanAssembler.assemble(d, pois, hotels, params("慢", 3), PlanRules.DEFAULT);

        assertEquals("HA", a.days().get(1).hotel().hotelId(), "查不到就当没回答，走沿用");
        assertTrue(a.warnings().stream().anyMatch(w -> w.contains("住宿编号不在候选清单里")));
    }

    @Test
    @DisplayName("住宿：模型写小写 h1 也要认（大小写不归一 = 凭空多一条假告警）")
    void normalizesHotelCodeCase() {
        List<PoiCandidate> pois = List.of(poi("P1", "A", 100.0));
        List<HotelCandidate> hotels = List.of(hotel("HA", "某酒店", 388.0));
        PlanDraft d = draft("安排", dayAt(1, "D1", "h1", item("C1", "09:30")));

        PlanAssembler.Assembled a = PlanAssembler.assemble(d, pois, hotels, params("慢", 1), PlanRules.DEFAULT);

        assertEquals("HA", a.days().get(0).hotel().hotelId());
        assertTrue(a.warnings().stream().noneMatch(w -> w.contains("住宿编号")),
                "'h1 查不到'是假的 —— 那只是大小写，不该报给用户");
    }

    @Test
    @DisplayName("住宿：本次没有酒店数据 → 全部 null，且**不**凭空报一条'模型编了编号'")
    void noHotelDataMeansNullWithoutFalseAlarm() {
        List<PoiCandidate> pois = List.of(poi("P1", "A", 100.0), poi("P2", "B", 100.0));
        // needHotel=false 时上游不会返回酒店。此时模型无论写什么 H 编号都查不到 ——
        // 那不是它的错（它甚至没拿到清单），所以一条告警都不该有。
        PlanDraft d = draft("安排",
                day(1, "D1", item("C1", "09:30")),
                day(2, "D2", item("C2", "09:30")));

        PlanAssembler.Assembled a = PlanAssembler.assemble(d, pois, List.of(), params("慢", 2), PlanRules.DEFAULT);

        assertNull(a.days().get(0).hotel());
        assertNull(a.days().get(1).hotel());
        assertTrue(a.warnings().stream().noneMatch(w -> w.contains("住宿编号") || w.contains("住处")),
                "没有酒店数据时，检索层已经报过一次了，这里再报就是重复且误导");
    }
}
