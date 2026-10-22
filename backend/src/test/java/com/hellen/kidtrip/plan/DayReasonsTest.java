package com.hellen.kidtrip.plan;

import com.hellen.kidtrip.retrieval.Transfer;
import com.hellen.kidtrip.retrieval.TransportMode;
import com.hellen.kidtrip.rule.PlanRules;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 每天那句「为什么这么排」的测试。
 *
 * 测的不是"句子好不好听"，是**三道防线**（每一道都是写这个类的原因）：
 *   ① 缺坐标 → 整句不说（不是拿算得出的几段下结论 —— 那会给出一个偏小的数字，
 *      而偏小恰是最容易让人放心的方向）；
 *   ② persona 方向对不上 → 不引用原话（"无所谓"被当成"排得轻松"的理由，
 *      就是一句引用着真原话的假话，界面上完全看不出来）；
 *   ③ 时刻读不出 → 整句不说（宁可不解释，也不编一个时刻）。
 *
 * 断言全部对着**产出句里的数字**（1.1 公里 / 12:00 / 2 个点），不是对着
 * "句子里含有『公里』两个字" —— 那样换了格式化措辞测试照样绿，等于没测。
 *
 * ⚠️ 素材设计：每个用例**只让目标片段成立**（fewerClause 尤其爱插队 ——
 *    点数只要少于常规它就说话，而"1 个点"的用例到处都是）。所以：
 *    「安静的天」用 3 个远距、时刻密集的点（三个片段全不触发）；
 *    「公里句」用 2 个近点且间隔 40 分钟（空档句不触发）；
 *    「空档句」用 2 个远点（公里句不触发）。
 */
class DayReasonsTest {

    /* ── 测试素材 ─────────────────────────────────────────── */

    private static TravelSpot spot(String time, String poiId, double lat, double lng) {
        return new TravelSpot(time, "点-" + poiId, "一句提示", poiId,
                45.0, "https://book.example/" + poiId, "https://img.example/" + poiId + ".jpg",
                lat, lng);
    }

    /** 没有坐标的点（飞猪真实数据里 lat/lng 就可能缺 —— 这正是缺坐标防线要模拟的） */
    private static TravelSpot spotNoCoords(String time, String poiId) {
        return new TravelSpot(time, "点-" + poiId, "一句提示", poiId,
                45.0, "https://book.example/" + poiId, "https://img.example/" + poiId + ".jpg",
                null, null);
    }

    /** 两个相距约 1.1 公里、间隔 40 分钟的点（纬度差 0.01° ≈ 1.11km）—— 只触发公里句 */
    private static List<TravelSpot> nearPair() {
        return List.of(spot("09:00", "p-a", 22.540, 114.050),
                spot("09:40", "p-b", 22.530, 114.050));
    }

    /** 两个相距约 4.4 公里、间隔 150 分钟的点 —— 只触发空档句 */
    private static List<TravelSpot> farPairWideGap() {
        return List.of(spot("09:00", "p-a", 22.540, 114.050),
                spot("11:30", "p-b", 22.500, 114.050));
    }

    /** 3 个远距、时刻密集的点 —— 数量=常规、跨度超线、间隔短：**任何片段都不触发** */
    private static List<TravelSpot> quietSpots() {
        return List.of(spot("09:00", "p-a", 22.540, 114.050),
                spot("10:00", "p-b", 22.500, 114.050),
                spot("11:00", "p-c", 22.460, 114.050));
    }

    /** 深圳出发去珠海的高铁（实测 G270 一类：10:10 发、10:39 到） */
    private static Transfer outbound() {
        return new Transfer("深圳", "珠海", TransportMode.TRAIN, "深圳北", "珠海",
                "2026-11-17T10:10", "2026-11-17T10:39", 29, 70.0,
                "高铁G270", "二等座", true, null, "https://book.example/g270");
    }

    /** 珠海回深圳的高铁（14:16 发） */
    private static Transfer inbound() {
        return new Transfer("珠海", "深圳", TransportMode.TRAIN, "珠海", "深圳北",
                "2026-11-21T14:16", "2026-11-21T15:00", 44, 70.0,
                "高铁G1234", "二等座", true, null, "https://book.example/g1234");
    }

    private static TravelParams trip() {
        return new TravelParams("深圳", "珠海", 3, "2026-11-17",
                List.of("动物园"), "中", List.of(6), false);
    }

    private static TravelDay day(int n, List<TravelSpot> spots, Transfer t, HotelStay hotel) {
        return new TravelDay(n, "主题", spots, t, List.of(), hotel, List.of(), null);
    }

    private static HotelStay hotel(String hotelId) {
        return new HotelStay("酒店" + hotelId, hotelId, 388.0, "舒适型",
                null, "某地址", 22.53, 114.05,
                "https://book.example/h", "https://img.example/h.jpg", "一句提示");
    }

    /** "你说过想放松"那个人 —— walking 有判断、有依据 */
    private static Persona relaxed() {
        return new Persona(List.of(new Persona.Inference("walking", "少走路",
                "我想有一个放松的旅行")));
    }

    /** "体力好"那个人 —— walking 也在、但方向相反 */
    private static Persona energetic() {
        return new Persona(List.of(new Persona.Inference("walking", "无所谓",
                "我们一家体力都挺好")));
    }

    private static final int PER_DAY_中 = 3; // PlanRules.DEFAULT.spotsPerDay("中")

    /** 慢节奏的常规量 —— 「2 个点」的天用这个基准，就不会误触「少于常规」句 */
    private static final int PER_DAY_慢 = 2;

    /* ── 片段：到站 / 收尾 ───────────────────────────────── */

    @Test
    @DisplayName("去程到站 → 第 1 天解释起步晚；数字来自真实车次")
    void arrivalExplainsTheFirstDay() {
        TravelDay d1 = day(1, List.of(spot("12:00", "p-1", 22.540, 114.050),
                spot("13:00", "p-2", 22.500, 114.050),
                spot("14:00", "p-3", 22.460, 114.050)), outbound(), null);
        List<TravelDay> out = DayReasons.attachWhy(List.of(d1), null, PlanRules.DEFAULT, trip());

        assertEquals("去程 10:39 到站，所以今天第一个点排在 12:00", out.get(0).why());
    }

    @Test
    @DisplayName("回程发车 → 最后一天解释收得早")
    void departureExplainsTheLastDay() {
        TravelDay last = day(3, quietSpots(), inbound(), null);
        List<TravelDay> out = DayReasons.attachWhy(List.of(last), null, PlanRules.DEFAULT, trip());

        assertEquals("回程 14:16 发车，今天排到 11:00 就收尾", out.get(0).why());
    }

    @Test
    @DisplayName("车次 from ≠ 画像 origin → 不当去程解释（判据是数据，不是第几天）")
    void transferFromAnotherCityIsNotOutbound() {
        // 车次从广州出发，画像说从深圳走 —— 即便它挂在第 1 天，也不是"从家出发"
        Transfer notOurs = new Transfer("广州", "珠海", TransportMode.TRAIN, "广州南", "珠海",
                "2026-11-17T09:00", "2026-11-17T09:40", 40, 60.0,
                "高铁G999", "二等座", true, null, null);
        TravelDay d1 = day(1, quietSpots(), notOurs, null);
        String why = DayReasons.whyOf(d1, null, Persona.empty(), PER_DAY_中, trip());

        assertNull(why, "不是从家出发的车次不该产生到站句：" + why);
    }

    /* ── 片段：公里 / 空档 / 余量 / 连住 ─────────────────── */

    @Test
    @DisplayName("两个点 1.1 公里内 → 说「走两步就到」，数字是真的")
    void walkableDaySaysSo() {
        String why = DayReasons.whyOf(day(2, nearPair(), null, null),
                null, Persona.empty(), PER_DAY_慢, trip());

        assertEquals("今天 2 个点都在 1.1 公里内，走两步就到", why);
    }

    @Test
    @DisplayName("persona 有 walking=少走路 → 引用他的原话当前缀")
    void citesTheOriginalWords() {
        String why = DayReasons.whyOf(day(2, nearPair(), null, null),
                null, relaxed(), PER_DAY_慢, trip());

        assertEquals("你说过「我想有一个放松的旅行」，所以今天 2 个点都在 1.1 公里内，走两步就到", why);
    }

    @Test
    @DisplayName("⚠️ 方向防线：walking=无所谓（体力好）→ 绝不引用原话")
    void oppositeDirectionNeverGetsCited() {
        // 这是本类最要命的一条：energetic 的原话是真的，但它是"不怕走路"的意思。
        // 拿它当"今天排得轻松"的理由 = 引用着真原话说假话，界面上完全看不出来。
        String why = DayReasons.whyOf(day(2, nearPair(), null, null),
                null, energetic(), PER_DAY_慢, trip());

        assertEquals("今天 2 个点都在 1.1 公里内，走两步就到", why,
                "不含「你说过」前缀才对 —— 方向反了的判断一个字都不能引用");
    }

    @Test
    @DisplayName("⚠️ 缺坐标防线：只要有一段算不出 → 公里句整句不说")
    void missingCoordsKillTheWholeWalkClause() {
        // 第三个点没有坐标。如果实现拿"算得出的两段"下结论，会说"都在 1.1 公里内"
        // —— 而第三段可能远得多。这句宁可整个不说。
        // （第三个点 15:00 会让空档句成立 —— 无妨，这条断言只盯公里句有没有闭嘴。）
        TravelDay d = day(2, List.of(nearPair().get(0), nearPair().get(1),
                spotNoCoords("15:00", "p-c")), null, null);
        String why = DayReasons.whyOf(d, null, relaxed(), PER_DAY_中, trip());

        assertFalse(why != null && why.contains("公里"),
                "缺坐标的当天不该出现「都在 X 公里内」：" + why);
    }

    @Test
    @DisplayName("间隔 2 小时 30 分 → 说留了空档（最长的间隔，不是第一段）")
    void middayGapIsReported() {
        String why = DayReasons.whyOf(day(2, farPairWideGap(), null, null),
                null, Persona.empty(), PER_DAY_慢, trip());

        assertEquals("中午留了 2 小时 30 分的空档", why);
    }

    @Test
    @DisplayName("空档 + rest=要午睡 → 空档句带上原话前缀")
    void middayCitesRestEvidence() {
        Persona napper = new Persona(List.of(
                new Persona.Inference("rest", "要午睡", "孩子中午必须睡午觉")));
        String why = DayReasons.whyOf(day(2, farPairWideGap(), null, null),
                null, napper, PER_DAY_慢, trip());

        assertEquals("你说过「孩子中午必须睡午觉」，所以中午留了 2 小时 30 分的空档", why);
    }

    @Test
    @DisplayName("少于常规 → 说留了余量；基准来自规则表（3 个）")
    void fewerSpotsSaysSo() {
        // 2 个远点（公里句不触发）、间隔 40 分钟（空档句不触发）→ 只剩余量句
        String why = DayReasons.whyOf(day(2, List.of(spot("09:00", "p-a", 22.540, 114.050),
                spot("09:40", "p-b", 22.500, 114.050)), null, null),
                null, Persona.empty(), PER_DAY_中, trip());

        assertEquals("今天只排了 2 个点（常规 3 个），留了余量", why);
    }

    @Test
    @DisplayName("余量句不引用 persona —— 我们分不清少排是因为怕累还是没得排")
    void fewerClauseClaimsNoCredit() {
        // relaxed 的 walking 有判断有原话，但余量句**故意**不引用它
        String why = DayReasons.whyOf(day(2, List.of(spot("09:00", "p-a", 22.540, 114.050),
                spot("09:40", "p-b", 22.500, 114.050)), null, null),
                null, relaxed(), PER_DAY_中, trip());

        assertFalse(why.contains("你说过"), "余量句不该替 persona 认领功劳：" + why);
    }

    @Test
    @DisplayName("连住 → 第二天说「不用收拾行李」")
    void sameHotelSaysSo() {
        TravelDay d1 = day(1, quietSpots(), null, hotel("h-1"));
        TravelDay d2 = day(2, quietSpots(), null, hotel("h-1"));
        List<TravelDay> out = DayReasons.attachWhy(List.of(d1, d2), null,
                PlanRules.DEFAULT, trip());

        assertEquals("今晚还住同一家，不用收拾行李", out.get(1).why());
        assertNull(out.get(0).why(), "第 1 天没有昨天，不该有连住句");
    }

    @Test
    @DisplayName("换酒店 → 不说连住句")
    void differentHotelSaysNothing() {
        TravelDay d1 = day(1, quietSpots(), null, hotel("h-1"));
        TravelDay d2 = day(2, quietSpots(), null, hotel("h-2"));
        String why = DayReasons.whyOf(d2, d1, Persona.empty(), PER_DAY_中, trip());

        assertNull(why);
    }

    /* ── 三道防线的第三条：宁可不解释 ─────────────────────── */

    @Test
    @DisplayName("没有任何可说的事实 → null，不兜底一句万能废话")
    void quietDayStaysNull() {
        assertNull(DayReasons.whyOf(day(2, quietSpots(), null, null),
                null, Persona.empty(), PER_DAY_中, trip()));
    }

    @Test
    @DisplayName("⚠️ 时刻读不出 → 到站句整句不说（宁可不解释，不编时刻）")
    void unreadableClockKillsTheClause() {
        Transfer broken = new Transfer("深圳", "珠海", TransportMode.TRAIN, "深圳北", "珠海",
                "not-a-time", "also-not-a-time", 29, 70.0,
                "高铁G270", "二等座", true, null, null);
        String why = DayReasons.whyOf(day(1, quietSpots(), broken, null),
                null, Persona.empty(), PER_DAY_中, trip());

        assertNull(why, "时刻解析失败时到站句不该出现：" + why);
    }

    @Test
    @DisplayName("为什么 attachWhy 要在最后跑：车次还没挂上时到站句直接消失")
    void whyNeedsLegsAttachedFirst() {
        // 同一天，先不带车次（模拟 attachLegs 之前的状态）
        String why = DayReasons.whyOf(day(1, quietSpots(), null, null),
                null, Persona.empty(), PER_DAY_中, trip());

        assertNull(why, "车次没挂 → 到站句不存在 —— 一句不报只是少，这就是顺序敏感性的形状");
    }

    /* ── 纯函数 ───────────────────────────────────────────── */

    @Test
    @DisplayName("leansRelaxed：排除法 —— 只有明确说反方向的才不算")
    void leansRelaxedTable() {
        assertTrue(DayReasons.leansRelaxed("少走路"));
        assertTrue(DayReasons.leansRelaxed("想轻松点"));
        assertTrue(DayReasons.leansRelaxed(" 不想走太多路 "));
        assertFalse(DayReasons.leansRelaxed("无所谓"));
        assertFalse(DayReasons.leansRelaxed("不介意"));
        assertFalse(DayReasons.leansRelaxed("不用考虑"));
        assertFalse(DayReasons.leansRelaxed("都行"));
        assertFalse(DayReasons.leansRelaxed(null));
        assertFalse(DayReasons.leansRelaxed("  "));
    }

    @Test
    @DisplayName("kmInside：很小的距离保底 0.1（不说 0 公里）")
    void kmInsideFloor() {
        assertEquals("0.1 公里", DayReasons.kmInside(0.03));
        assertEquals("1.1 公里", DayReasons.kmInside(1.11));
    }

    @Test
    @DisplayName("超长的原话截到 24 字加省略号 —— 截的仍是原话，没改写")
    void quoteIsShortenedNotRewritten() {
        Persona p = new Persona(List.of(new Persona.Inference("rest", "要午睡",
                "孩子中午必须睡午觉所以我们不想安排太赶另外尽量避开人多的网红点")));
        String cited = DayReasons.cite(p, PersonaTrait.REST);

        assertTrue(cited.startsWith("你说过「孩子中午必须睡午觉所以我们不想安排太"));
        assertTrue(cited.endsWith("…」，所以"));
        assertEquals("你说过「".length() + DayReasons.QUOTE_MAX + "…」，所以".length(),
                cited.length());
    }

    /* ── 边界 ───────────────────────────────────────────── */

    @Test
    @DisplayName("attachWhy：空列表 / null persona / null rules 都不炸")
    void nullTolerances() {
        assertEquals(List.of(), DayReasons.attachWhy(List.of(), null, PlanRules.DEFAULT, null));
        // rules=null → DEFAULT；params=null 时 isOutbound 读不到 origin → 到站句不出现
        //（这正是"判据是数据"的形状：不知道从哪出发，就不解释"到站之后才开始"）。
        // 公里句/空档句不依赖画像，照样成立。
        String why = DayReasons.whyOf(day(2, nearPair(), null, null), null,
                Persona.empty(), PER_DAY_慢, null);
        assertEquals("今天 2 个点都在 1.1 公里内，走两步就到", why);
    }

    @Test
    @DisplayName("两句话上限：三个片段同时成立也只说前两条")
    void capsAtTwoClauses() {
        // 3 个近点：09:00 → 11:30 → 14:00。公里句（前缀）+ 空档句（150 分钟）成立，
        // 数量=常规不触发余量，但昨天同酒店 → 连住句是第三条，被上限挤掉。
        TravelDay d = day(2, List.of(
                spot("09:00", "p-a", 22.540, 114.050),
                spot("11:30", "p-b", 22.530, 114.050),
                spot("14:00", "p-c", 22.525, 114.045)), null, hotel("h-1"));
        String why = DayReasons.whyOf(d, day(1, quietSpots(), null, hotel("h-1")),
                relaxed(), PER_DAY_中, trip());

        assertEquals("你说过「我想有一个放松的旅行」，所以今天 3 个点都在 1.1 公里内，"
                + "走两步就到；中午留了 2 小时 30 分的空档", why);
    }
}
