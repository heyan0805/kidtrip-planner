package com.hellen.kidtrip.plan;

import com.hellen.kidtrip.retrieval.Transfer;
import com.hellen.kidtrip.retrieval.TransportMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 首尾两天和真实车次的一致性检查。
 *
 * 这一层要防的错，形状和别处都不同：**每个字段单独看都是对的。**
 *   第 1 天 10:00 逛动物园 —— 合理；去程车 10:39 到 —— 也是真的。
 *   最后一天 15:00 珠江夜游 —— 合理；返程车 14:16 发车 —— 也是真的。
 * 拼起来却是两份**根本走不成**的行程，而且全程没有一个环节会报错。
 *
 * 这类错没法靠"某一步返回值的断言"抓住（响应里每个字段都对）。
 * 能抓住它的只有一件事：**把两个来源的事实放在一起比一遍**。
 *
 * ⚠️ 而且这一层是**兜底**，不是主路：主路是把时刻喂给模型（见 PlanComposer.renderLegs），
 *    让它一开始就排对。这里管的是"它没听话"的那部分 —— 不悄悄改行程，只把矛盾说出来。
 */
@DisplayName("首尾两天和车次的一致性：矛盾的行程要走得出来、还得说得出来")
class PlanServiceLegConflictTest {

    /* ============================================================
     * 造数据 —— 用的都是实测的真实班次
     * ============================================================ */

    private static TravelSpot spot(String time, String name) {
        return new TravelSpot(time, name, "一句提示", "poi-" + name,
                29.9, "https://book.example/x", "https://img.example/x.jpg", 23.1, 113.2);
    }

    private static TravelDay day(TravelSpot... spots) {
        return new TravelDay(1, "当天主题", List.of(spots), null, List.of(), null, List.of(), null);
    }

    /** 实测：深圳→广州 11-17，10:10 发、10:39 到广州南（29 分钟） */
    private static Transfer arrivingAt(String arr) {
        return new Transfer("深圳", "广州", TransportMode.TRAIN, "深圳北", "广州南",
                "2026-11-17T10:10", arr, 29, 74.5, "高铁G390", "二等座", true, null, null);
    }

    /** 实测：广州→深圳 11-21 有这一班（14:16 发、14:45 到） */
    private static Transfer departingAt(String dep) {
        return new Transfer("广州", "深圳", TransportMode.TRAIN, "广州南", "深圳北",
                dep, "2026-11-21T14:45", 29, 74.5, "高铁G1005", "二等座", true, null, null);
    }

    /* ============================================================
     * 第 1 天
     * ============================================================ */

    @Test
    @DisplayName("⭐ 第 1 天 10:00 的景点 + 10:39 到站 → 必须报出来（实测那份行程原样）")
    void firstDaySpotBeforeArrivalIsCaught() {
        String warning = PlanService.firstDayConflict(
                day(spot("10:00", "广州动物园")), arrivingAt("2026-11-17T10:39"));

        assertNotNull(warning, "人还没下高铁，这一个点就已经开始了 —— 这不可能成立");
        assertTrue(warning.contains("10:00") && warning.contains("广州动物园"),
                "要说清是**哪一个点**有问题：" + warning);
        assertTrue(warning.contains("10:39") && warning.contains("广州南"),
                "也要说清是**因为哪趟车**：" + warning);
    }

    @Test
    @DisplayName("到站后正好留够 1 小时 → 不报（边界是允许的开始，不是禁止的开始）")
    void firstDaySpotAfterBufferIsFine() {
        assertNull(PlanService.firstDayConflict(
                day(spot("11:39", "广州动物园")), arrivingAt("2026-11-17T10:39")),
                "10:39 到 + 1 小时 = 11:39，正好卡上就不该报 —— 报得太多等于没报");
    }

    @Test
    @DisplayName("⚠️ 点的顺序被模型打乱了也要抓到最早的那个 —— 取'最早的点'，不是'列表第一个'")
    void outOfOrderSpotsAreStillChecked() {
        // "列表第一个"是位置，"最早的点"是时间语义 —— 两者一不一致，取决于别人排没排序。
        // 而模型实测就是会把 14:00 写在 09:30 前面。依赖一个没被承诺过的前提，
        // 正是这个工程反复踩的那类坑：**规则对，取值方式错。**
        String warning = PlanService.firstDayConflict(
                day(spot("14:00", "越秀公园"), spot("09:30", "广州动物园")),
                arrivingAt("2026-11-17T10:39"));

        assertNotNull(warning, "真正赶不上的是 09:30 那个，不能因为它排在后面就漏掉");
        assertTrue(warning.contains("09:30"), warning);
        assertTrue(warning.contains("广州动物园"), warning);
    }

    /* ============================================================
     * 最后一天
     * ============================================================ */

    @Test
    @DisplayName("⭐ 最后一天 15:00 的活动 + 14:16 发车 → 必须报出来（实测那份行程原样）")
    void lastDaySpotAfterDepartureIsCaught() {
        String warning = PlanService.lastDayConflict(
                day(spot("09:30", "广州塔"), spot("15:00", "珠江夜游")), departingAt("2026-11-21T14:16"));

        assertNotNull(warning, "车 14:16 就开走了，15:00 的珠江夜游只可能发生在另一个平行时空");
        assertTrue(warning.contains("15:00") && warning.contains("珠江夜游"), warning);
        assertTrue(warning.contains("14:16") && warning.contains("广州南"), warning);
    }

    @Test
    @DisplayName("收尾点正好卡在发车前 3 小时 → 不报")
    void lastDaySpotBeforeBufferIsFine() {
        assertNull(PlanService.lastDayConflict(
                day(spot("11:16", "陈家祠")), departingAt("2026-11-21T14:16")),
                "14:16 发车 − 3 小时 = 11:16，卡上就放过");
    }

    /* ============================================================
     * 不该说话的时候一句都别说
     * ============================================================ */

    @Test
    @DisplayName("没有车次 → 闭嘴（缺信息不是矛盾）")
    void silentWhenNoLeg() {
        TravelDay d = day(spot("06:00", "早茶"));

        assertNull(PlanService.firstDayConflict(d, null), "没查到车次时，'早'只是个口味问题，不是冲突");
        assertNull(PlanService.lastDayConflict(d, null));
    }

    @Test
    @DisplayName("这一天没有安排 → 闭嘴（空的一天没有任何东西和车次冲突）")
    void silentWhenNoSpots() {
        assertNull(PlanService.firstDayConflict(day(), arrivingAt("2026-11-17T10:39")));
        assertNull(PlanService.lastDayConflict(day(), departingAt("2026-11-21T14:16")));
    }

    @Test
    @DisplayName("⭐ 时刻读不出来 → **静默跳过**，绝不猜一个值出来报假警告")
    void unreadableTimeIsSkippedNotGuessed() {
        // 模型写"下午三点"这种，已经有人在管（PlanAssembler）。这里要是硬猜一个 15:00 出来，
        // 就会多报一条基于猜测的冲突 —— 而**一条假的警告会让真的那条一起被忽略**。
        assertNull(PlanService.firstDayConflict(day(spot("下午三点", "动物园")), arrivingAt("2026-11-17T10:39")));
        assertNull(PlanService.lastDayConflict(day(spot("晚上", "珠江夜游")), departingAt("2026-11-21T14:16")));
    }

    @Test
    @DisplayName("模型写 '9:30'（不带前导零）也要能读出来 —— 否则最早的那天就没人管了")
    void singleDigitHourIsStillParsed() {
        // 宽松解析是有意为之：解析失败在这里是**静默的**（我们只是不校验了），
        // 于是"最该被管的那一天"反而悄悄溜过去了。
        assertNotNull(PlanService.firstDayConflict(
                day(spot("9:30", "广州动物园")), arrivingAt("2026-11-17T10:39")),
                "9:30 比 11:39 早得多，必须被抓到");
    }

    /* ============================================================
     * 超限车次的文案 —— 警告里那句话的说服力，全在它有没有具体数字
     * ============================================================ */

    /**
     * 这一节测的是 `warnOverLimit` 那句警告**唯一**的外部依赖：把分钟说成人话。
     *
     * 单独钉它是因为这句话的两种写坏方式都很隐蔽：
     *   写成 "400 分钟" —— 数字没错，但带娃出行里没人这么感受时间；
     *   读不出来时填一个平均值 —— 那是在**警告里编数字**，而这条警告的全部价值
     *   恰恰在于"它给了一个具体、可信的数字"。
     */
    @Test
    @DisplayName("400 分钟 → '6 小时 40 分'（不是 '400 分钟'）")
    void durationIsSpokenInHumanUnits() {
        assertEquals("6 小时 40 分", PlanService.humanMinutes(400));
        assertEquals("3 小时", PlanService.humanMinutes(180), "整点不写成 '3 小时 0 分'");
        assertEquals("55 分钟", PlanService.humanMinutes(55), "不到一小时就不提小时");
    }

    @Test
    @DisplayName("⭐ 耗时读不出来 → 说什么都不编一个数字出来")
    void unreadableDurationIsNotFabricated() {
        assertEquals("一段不短的车程", PlanService.humanMinutes(null));
        assertEquals("一段不短的车程", PlanService.humanMinutes(0),
                "写成 '0 分钟' 会让这句话读起来像故障");
    }
}
