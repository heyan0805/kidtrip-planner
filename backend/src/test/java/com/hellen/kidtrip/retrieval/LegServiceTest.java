package com.hellen.kidtrip.retrieval;

import com.hellen.kidtrip.flyai.FlyaiProperties;
import com.hellen.kidtrip.flyai.raw.FlyaiTrain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 车次那一层的**纯函数**部分：挑班次、翻译成契约、时间换算。
 *
 * 为什么这一层的测试比别处更要紧：
 *   它整条路径的失败方式都是**静默**的 —— 挑错一班车、把中转站当成目的地、
 *   把 150 分钟向上取整成 2 小时，全都不会抛异常、不会写日志、
 *   只会让用户看到一份"看起来正常但事实是错的"行程。
 *   而这一层又拿不到"用户觉得对不对"的反馈：他不会来告诉你"这班车我其实坐不了"。
 *
 * 所以这些用例钉的不是"功能有没有"，而是**每条规则的边界长什么样**。
 */
@DisplayName("车次：挑班次 / 翻译契约 / 时间换算")
class LegServiceTest {

    /* ============================================================
     * 造数据的小工具 —— 全部对着实测的真实形状来
     * ============================================================ */

    /** 一趟直达：单 segment，时间和站名都按实测的格式（空格分隔、带秒） */
    private static FlyaiTrain direct(String depStation, String depAt, String arrStation, String arrAt,
                                     String duration, String price) {
        return new FlyaiTrain(
                List.of(new FlyaiTrain.Journey("直达", List.of(
                        segment(depStation, depAt, arrStation, arrAt, duration)), duration, "")),
                price, duration, "https://router.feizhu.com/" + depStation);
    }

    /** 一趟中转：多个 segment，**第一段的到达站是中转站** */
    private static FlyaiTrain transfer(String depStation, String depAt, String transferStation,
                                       String arrivalStation, String arrAt, String duration) {
        FlyaiTrain.Segment first = segment(depStation, depAt, transferStation, "2026-11-17 10:30:00", "55");
        FlyaiTrain.Segment second = segment(transferStation, "2026-11-17 11:20:00", arrivalStation, arrAt, "120");
        return new FlyaiTrain(
                List.of(new FlyaiTrain.Journey("中转", List.of(first, second), duration, "25")),
                "239.00", duration, "https://router.feizhu.com/transfer");
    }

    private static FlyaiTrain.Segment segment(String depStation, String depAt, String arrStation, String arrAt,
                                              String duration) {
        return new FlyaiTrain.Segment(
                "广州", depStation + "站", depStation, depAt,
                "珠海", arrStation + "站", arrStation, arrAt,
                duration, "动车", "D947", "二等座", "火车");
    }

    /* ============================================================
     * 挑班次
     * ============================================================ */

    @Test
    @DisplayName("去程：跳过 06:30（太早）和 20:58（到了就是晚上），挑 08:36")
    void outboundSkipsTooEarlyAndTooLate() {
        // 这三条是 2026-10-13 实测广州→珠海真实存在的班次（时长都是 53~56 分，所以上游按
        // 时长排序后它们的相对顺序就是返回顺序）。
        List<FlyaiTrain> candidates = List.of(
                direct("广州南", "2026-11-17 06:30:00", "珠海", "2026-11-17 07:26:00", "56", "70.00"),
                direct("广州南", "2026-11-17 08:36:00", "珠海", "2026-11-17 09:29:00", "53", "70.00"),
                direct("广州南", "2026-11-17 20:58:00", "珠海", "2026-11-17 21:53:00", "55", "70.00"));

        FlyaiTrain chosen = LegService.pick(candidates, LegService.Direction.OUTBOUND);

        assertEquals("2026-11-17 08:36:00", chosen.journeys().get(0).segments().get(0).depDateTime(),
                "06:30 出门对孩子太狠；20:58 出发等于第一天整天废掉");
    }

    @Test
    @DisplayName("⭐ 一条都不满足时段 → 退回第一条，**绝不能返回 null**")
    void fallsBackInsteadOfReturningNull() {
        // 这是整层里最要紧的一条：返回 null 会被上层读成"这天没有交通"，
        // 而真相是"有车，只是时刻不理想"。用户看到的是"没有车次"——一句**假话**，
        // 而且他会因此放弃这趟行程。给一份真实但不理想的结果，永远比给一句干脆的错话好。
        List<FlyaiTrain> overnight = List.of(
                direct("广州南", "2026-11-17 02:00:00", "珠海", "2026-11-17 02:55:00", "55", "70.00"),
                direct("广州南", "2026-11-17 23:30:00", "珠海", "2026-11-18 00:25:00", "55", "70.00"));

        FlyaiTrain chosen = LegService.pick(overnight, LegService.Direction.OUTBOUND);

        assertEquals(overnight.get(0), chosen, "宁可给凌晨 2 点这班（真实的），也不能说'没有车'");
    }

    @Test
    @DisplayName("回程：怕的是太晚**到家**，所以挑 19:03 到的那班，而不是 23:40 到的")
    void inboundPrefersArrivingBeforeEvening() {
        // 回程两头都判：发车不能太早（最后一天还要玩），到达不能太晚（别把疲劳带到第二天）。
        // 这一组候选检的是**后一头** —— 18:13 那班在窗口内，23:40 那班超了上界。
        List<FlyaiTrain> candidates = List.of(
                direct("珠海", "2026-11-22 18:13:00", "广州南", "2026-11-22 19:03:00", "50", "70.00"),
                direct("珠海", "2026-11-22 22:45:00", "广州南", "2026-11-22 23:40:00", "55", "70.00"));

        FlyaiTrain chosen = LegService.pick(candidates, LegService.Direction.RETURN);

        assertEquals("2026-11-22 19:03:00", chosen.journeys().get(0).segments().get(0).arrDateTime());
    }

    @Test
    @DisplayName("⭐ 回程：最快的首班车（06:18）绝不能被选中 —— 它'到达 ≤ 20:00'照样成立")
    void inboundRejectsFastestButTooEarlyTrain() {
        // 这三条是实测的广州→深圳（2026-11-21）候选，形状正是"**最快的那班恰好是最早的那班**"。
        // 老规则只判"到达 ≤ 20:00"，第一条 06:47 到达就满足了 —— 于是稳定返回一趟凌晨六点多的车，
        // 而那天下午用户还带着孩子排着珠江夜游。**判据缺一条下界，就会一路倒向最早那班。**
        //
        // 这正是"上界单独成立时必然失灵"的那个情形：列表按时长升序，"第一条满足的"由耗时决定，
        // 而短途线路上耗时最短的常常就是首班车 —— 结果规则表面在说"别太晚到家"，
        // 实际执行出来是"越早越好"。
        List<FlyaiTrain> candidates = List.of(
                direct("广州南", "2026-11-21 06:18:00", "深圳北", "2026-11-21 06:47:00", "29", "74.50"),
                direct("广州南", "2026-11-21 14:16:00", "深圳北", "2026-11-21 14:45:00", "29", "74.50"),
                direct("广州南", "2026-11-21 21:07:00", "深圳北", "2026-11-21 22:27:00", "80", "86.00"));

        FlyaiTrain chosen = LegService.pick(candidates, LegService.Direction.RETURN);

        assertEquals("2026-11-21 14:16:00", chosen.journeys().get(0).segments().get(0).depDateTime(),
                "13:00 前发车等于最后一天上午全在赶路；22:27 才到家是把疲劳带去第二天");
    }

    @Test
    @DisplayName("回程：19:10 发车虽然够晚，但 20:05 才到 —— 上界照样要挡")
    void inboundRejectsArrivingAfterEvening() {
        // 这两条也是实测的珠海→广州（2026-11-21）。补下界的时候很容易顺手把上界弱化掉，
        // 而"别太晚到家"是回程最初的那条理由，不能被新加的下界挤走。
        List<FlyaiTrain> candidates = List.of(
                direct("珠海", "2026-11-21 19:10:00", "广州南", "2026-11-21 20:05:00", "55", "70.00"),
                direct("珠海", "2026-11-21 17:55:00", "广州南", "2026-11-21 18:50:00", "55", "70.00"));

        FlyaiTrain chosen = LegService.pick(candidates, LegService.Direction.RETURN);

        assertEquals("2026-11-21 17:55:00", chosen.journeys().get(0).segments().get(0).depDateTime(),
                "20:05 进门，洗漱完就 21 点多了");
    }

    @Test
    @DisplayName("回程：整天只有早班车的线路 → 退回第一条（真实但不理想），绝不能说'没有车'")
    void inboundFallsBackWhenAllTrainsAreMorning() {
        // 小城市之间的线路一天可能只有几班早车，这时"没有一班落在窗口里"是**结论**，
        // 但正确动作不是返回 null —— 上层会把它显示成"没有车次"，而那是一句假话，
        // 用户会因此放弃一趟其实能走的行程。
        List<FlyaiTrain> morningOnly = List.of(
                direct("桂林西", "2026-11-19 07:20:00", "广州南", "2026-11-19 09:24:00", "124", "223.00"),
                direct("桂林西", "2026-11-19 08:50:00", "广州南", "2026-11-19 10:54:00", "124", "223.00"));

        FlyaiTrain chosen = LegService.pick(morningOnly, LegService.Direction.RETURN);

        assertEquals(morningOnly.get(0), chosen, "真实但不理想，永远好过一句干脆的错话");
    }

    @Test
    @DisplayName("时刻读不出来的条目不参与时段判断，但不该因此把整批都丢掉")
    void unreadableTimeIsSkippedNotFatal() {
        List<FlyaiTrain> mixed = List.of(
                direct("广州南", null, "珠海", null, "53", "70.00"),
                direct("广州南", "2026-11-17 09:06:00", "珠海", "2026-11-17 10:00:00", "54", "70.00"));

        assertEquals("2026-11-17 09:06:00",
                LegService.pick(mixed, LegService.Direction.OUTBOUND)
                        .journeys().get(0).segments().get(0).depDateTime());
    }

    /* ============================================================
     * ⭐ 过夜车 —— 判跨午夜必须比**日期**，不能比时刻
     * ============================================================ */

    /**
     * 实测（2026-11-17 上海 → 深圳，12 小时窗口）：候选里那趟 **20:15 发、次日 07:03 到**的过夜车
     * 被判成了"最合适的回程"。
     *
     * 因为 `RETURN_ARRIVE_BY` 那条上界原来只比 `LocalTime`，而 `07:03 ≤ 20:00` 成立 ——
     * 次日早上和当天早上在 LocalTime 里**长得一模一样**。于是这趟 10 小时 48 分的夜车
     * 一路绿灯，而"带娃坐一整夜"这件事它一个字都不会提。
     *
     * 这类错的形状在这个工程里出现过很多次：**规则对，取值方式错。**
     * （`Transfer.arrDateTime` 早就带上了日期，正是为了这件事 —— 只是 pick 这条路没用它。）
     *
     * 注意这条是被前一个修复**暴露出来**的：在"超限车次不再被丢弃"之前，
     * 上海 → 深圳这段整段消失，我们连这趟夜车都看不到，也就不会挑错它。
     */
    @Test
    @DisplayName("⭐ 跨午夜的到达一律不合格（'次日 07:03 到' ≠ '当天 07:03 到'）")
    void overnightArrivalIsNeverAcceptable() {
        FlyaiTrain overnight = direct("上海虹桥", "2026-11-25 20:15:00",
                "深圳北", "2026-11-26 07:03:00", "648", "668.00");

        assertFalse(LegService.acceptable(overnight, LegService.Direction.RETURN),
                "只看时刻的话它'早上 7 点到家'，完美落在 20:00 上界之内 —— 这正是要挡的");
    }

    @Test
    @DisplayName("同日到达但超过 20:00 也不合格 —— 加日期判断不能把原来的上界挤掉")
    void sameDayButLateIsStillRejected() {
        // 补"跨午夜"这一条时最容易顺手做的事，是把 RETURN_ARRIVE_BY 弱化掉（"反正日期管着了"）。
        // 这两条判断管的是两件不同的事：日期管"是不是第二天"，上界管"当天是不是太晚"。
        FlyaiTrain late = direct("上海虹桥", "2026-11-25 15:10:00",
                "深圳北", "2026-11-25 21:40:00", "390", "668.00");

        assertFalse(LegService.acceptable(late, LegService.Direction.RETURN), "21:40 到家太晚了");
        assertTrue(LegService.acceptable(afternoonTrain("14:30:00", "19:50:00"),
                LegService.Direction.RETURN), "14:30 发、19:50 到才是那条窗口真正想要的车");
    }

    @Test
    @DisplayName("⭐ 夜车排在前面也抢不走合格的白天车（它不该被自己的'时刻'骗到）")
    void overnightDoesNotWinOverAProperAfternoonTrain() {
        // 列表按时长升序，所以"第一条"未必是最短的那班。
        // 这里故意把夜车放第一个，模拟它排在候选最前面的情形。
        FlyaiTrain overnight = direct("上海虹桥", "2026-11-25 20:15:00",
                "深圳北", "2026-11-26 07:03:00", "648", "668.00");
        FlyaiTrain afternoon = afternoonTrain("14:30:00", "19:50:00");

        assertEquals(afternoon, LegService.pick(List.of(overnight, afternoon), LegService.Direction.RETURN),
                "白天有一班合格的，就不该给用户一趟夜车");
    }

    @Test
    @DisplayName("只剩夜车可选 → 兜底仍然把它给出去（真实好过一句错话），但它只是兜底")
    void overnightIsStillGivenWhenItIsTheOnlyOption() {
        FlyaiTrain overnight = direct("上海虹桥", "2026-11-25 20:15:00",
                "深圳北", "2026-11-26 07:03:00", "648", "668.00");

        assertEquals(overnight, LegService.pick(List.of(overnight), LegService.Direction.RETURN),
                "返回 null 会被上层读成'没有车次'—— 那是一句假话");
        assertFalse(LegService.acceptable(overnight, LegService.Direction.RETURN),
                "但它是**兜底**选中的，不是'合格' —— 两者的区别以后要用得上（比如单独告警）");
    }

    /** 一趟同日下午到的车：发车 ≥ 13:00、到达 ≤ 20:00，正是回程窗口想要的那种 */
    private static FlyaiTrain afternoonTrain(String depAt, String arrAt) {
        return direct("上海虹桥", "2026-11-25 " + depAt, "深圳北", "2026-11-25 " + arrAt, "320", "668.00");
    }

    /* ============================================================
     * 翻译成契约
     * ============================================================ */

    @Test
    @DisplayName("⭐ 中转方案必须用 first/last —— 把中转站当目的地是这份契约最危险的错法")
    void transferUsesLastSegmentAsDestination() {
        // 实测的中转形状：珠海 → 广州南（换乘）→ 桂林西。
        // 如果图省事写 segments.get(0)，用户会看到"珠海→广州南"——
        // 一份把他送到了中转站就结束的行程，而且**不报错**。
        FlyaiTrain train = transfer("珠海", "2026-11-17 17:55:00", "广州南",
                "桂林西", "2026-11-17 21:18:00", "203");

        Transfer t = LegService.toTransfer(train, "珠海", "桂林");

        assertEquals("珠海", t.depStation(), "上车站在第一段");
        assertEquals("桂林西", t.arrStation(), "⭐ 下车站在**最后一段**，不是第一段的到达站");
        assertEquals("2026-11-17T21:18", t.arrDateTime(), "到达时间同理取最后一段");
        assertFalse(t.direct());
        assertEquals("广州南", t.transferCity(), "换乘站单独留一个字段，前端才知道显示成'经广州南'");
        assertEquals(203, t.durationMinutes(), "整体耗时用 totalDuration（含候车），不是各段之和");
    }

    @Test
    @DisplayName("直达方案：direct=true 且 transferCity 为 null（不是空串）")
    void directHasNoTransferCity() {
        Transfer t = LegService.toTransfer(
                direct("广州南", "2026-11-17 08:36:00", "珠海", "2026-11-17 09:29:00", "53", "70.00"),
                "广州", "珠海");

        assertTrue(t.direct());
        assertNull(t.transferCity(), "直达是'没有中转站'，不是'中转站名字是空的' —— 前者只有一个表示法");
        assertEquals("广州南", t.depStation());
        assertEquals("珠海", t.arrStation());
        assertEquals(53, t.durationMinutes());
        assertEquals(70.0, t.priceYuan());
        assertEquals("动车D947", t.trainNo(), "车型 + 编号要拼好 —— 单看 D947 分不出高铁还是动车");
        assertEquals("二等座", t.seatClass());
        assertEquals("广州", t.from());
        assertEquals("珠海", t.to());
    }

    @Test
    @DisplayName("价格取不到时是 null，不是 0（'不知道'和'免费'不是一回事）")
    void missingPriceIsNull() {
        assertNull(LegService.toTransfer(
                direct("广州南", "2026-11-17 08:36:00", "珠海", "2026-11-17 09:29:00", "53", null),
                "广州", "珠海").priceYuan());
    }

    @Test
    @DisplayName("journeys 为空的脏数据 → 返回 null，不抛异常（上游偶尔会回这种）")
    void emptyJourneysYieldsNullNotCrash() {
        FlyaiTrain broken = new FlyaiTrain(List.of(), "70.00", "53", null);

        assertNull(LegService.toTransfer(broken, "广州", "珠海"));
    }

    /* ============================================================
     * 时间与数字的换算
     * ============================================================ */

    @Test
    @DisplayName("上游用空格分隔日期和时间（不是 ISO 的 T），翻译成 ISO 到分钟")
    void isoMinutesNormalisesSeparatorAndPrecision() {
        assertEquals("2026-11-17T08:36", LegService.isoMinutes("2026-11-17 08:36:00"));
    }

    @Test
    @DisplayName("上游哪天去掉秒，也不能静默变成 null —— 有秒没秒都要吃得下")
    void isoMinutesToleratesMissingSeconds() {
        assertEquals("2026-11-17T08:36", LegService.isoMinutes("2026-11-17 08:36"));
    }

    @Test
    @DisplayName("垃圾时间 → null（而不是抛异常带走整批结果）")
    void garbageTimeYieldsNull() {
        assertNull(LegService.isoMinutes("明天早上"));
        assertNull(LegService.isoMinutes(null));
        assertNull(LegService.isoMinutes("   "));
    }

    @Test
    @DisplayName("⭐ 夜车跨午夜：到达日期必须跟着走到第二天，不能被压成'当天 00:40 到'")
    void crossMidnightKeepsTheRealDate() {
        // 23:30 发、次日 00:25 到。如果契约里只存时刻，"00:25" 会被前端画成
        // "凌晨就到了"——比出发还早。这就是 Transfer 里那个 depDateTime / arrDateTime
        // 为什么必须**带日期**（而 TravelDay 拒绝冗余字段的理由在这件事上不成立）。
        FlyaiTrain overnight = direct("广州南", "2026-11-17 23:30:00", "珠海", "2026-11-18 00:25:00",
                "55", "70.00");

        Transfer t = LegService.toTransfer(overnight, "广州", "珠海");

        assertEquals("2026-11-17T23:30", t.depDateTime());
        assertEquals("2026-11-18T00:25", t.arrDateTime(), "日期必须跟着跨过去");
    }

    @Test
    @DisplayName("时长字符串 → 分钟；'53分' 这种带单位的读不出来 → null（不硬凑一个值）")
    void minutesParsingIsStrictEnough() {
        assertEquals(53, LegService.minutesOf("53"));
        assertNull(LegService.minutesOf("53分"),
                "读不出来就只能当'不知道'—— 硬凑一个 53 会让它冒充成一个可校验的事实");
        assertNull(LegService.minutesOf(null));
        assertNull(LegService.minutesOf(""));
    }

    /* ============================================================
     * 分钟上限 → 上游的小时参数
     * ============================================================ */

    @Test
    @DisplayName("⭐ 向上取整：150 分钟 → 3 小时（180 分钟）。向下取整会把红线悄悄收紧到 120")
    void hoursCeilingRoundsUp() {
        assertEquals(3, LegService.hoursCeiling(150),
                "向下取整的话，用户配 150 却只放行 120 分钟内的车次 —— 不报错的谎");
        assertEquals(3, LegService.hoursCeiling(180), "正好 3 小时不能变成 4");
        assertEquals(1, LegService.hoursCeiling(60));
        assertEquals(2, LegService.hoursCeiling(61), "多一分钟也要进位，否则那 1 分钟就被悄悄砍掉了");
    }

    @Test
    @DisplayName("上限小于一小时也不会传出 0 小时（那会被上游当成'不限'或直接报错）")
    void hoursCeilingNeverZero() {
        assertEquals(1, LegService.hoursCeiling(45));
        assertEquals(1, LegService.hoursCeiling(1));
    }

    /* ============================================================
     * ⭐ 粗筛窗口 —— 这一节是 2026-11-17「深圳 → 上海没有车票信息」那个 bug 的修复点
     * ============================================================ */

    /**
     * 要钉住的事实只有一句：**发到上游的窗口不能等于那条上限。**
     *
     * 这条区别看起来很小，代价却是一个用户直接看得见的故障：
     * 窗口原来 = hoursCeiling(180) = 3 小时，而深圳 → 上海最快也 6.5 小时 ——
     * 上游按时长筛完返回 `{"status":1,"message":"行程时长过滤结果为空"}`，
     * 我们把它读成"这段路没有走法"。于是那份 9 天行程里**一行交通都没有**，
     * 注意事项还写着"在 180 分钟内没有直达车次"。
     * 而高铁是存在的 —— **一句听起来很确定的假话，让用户放弃了一条真能走的路线。**
     */
    @Test
    @DisplayName("⭐ 粗筛窗口至少 12 小时 —— 等于上限的话，'最快的一班也超限'的路连候选都拿不到")
    void searchWindowIsWiderThanTheLimit() {
        assertEquals(12, LegService.searchWindowHours(180),
                "180 分钟上限对应 3 小时窗口的话，深圳→上海会被判成'没有走法'");
        assertEquals(12, LegService.searchWindowHours(45), "上限比窗口小的时候，窗口仍然是 12");
    }

    @Test
    @DisplayName("上限调到 12 小时以上时窗口跟着放宽 —— 不是被 12 这个常量卡死")
    void searchWindowGrowsWithTheLimit() {
        assertEquals(20, LegService.searchWindowHours(20 * 60));
        assertEquals(13, LegService.searchWindowHours(12 * 60 + 1), "多一分钟也要跟着进位");
    }

    @Test
    @DisplayName("⭐ 真正发出去的参数带的是窗口，不是那条上限")
    void argsCarryTheWindowNotTheLimit() {
        List<String> args = LegService.args("深圳", "上海", "2026-11-17", LegService.searchWindowHours(180));

        int i = args.indexOf("--total-duration-hour");
        assertTrue(i >= 0, "这个参数不能丢 —— 它是服务端粗筛，少了就白拿一堆超长车次");
        assertEquals("12", args.get(i + 1), "这里要是 3，深圳→上海这种路会直接被上游判成'结果为空'");
        assertEquals("4", args.get(args.indexOf("--sort-type") + 1),
                "时长升序不能丢 —— pick 靠'第一条'拿最快的那班，不排就全错");
        assertEquals("2026-11-17", args.get(args.indexOf("--dep-date") + 1));
    }

    /* ============================================================
     * ⭐ 有车、但最快的一班也超过上限
     * ============================================================ */

    @Test
    @DisplayName("⭐ 全超限 → FOUND + overLimit，并且把最快的那班给出去（绝不能是 NO_EDGE）")
    void overLimitStillYieldsTheFastestTrain() {
        // 实测形状：深圳 → 上海只找得到 6 小时以上的车，而上限是 180 分钟。
        // 老行为是返回 NO_EDGE —— 用户看到"没有直达车次"（假话），行程里也没有交通那一行。
        List<FlyaiTrain> allTooLong = List.of(
                direct("深圳北", "2026-11-17 08:00:00", "上海虹桥", "2026-11-17 14:40:00", "400", "680.00"),
                direct("深圳北", "2026-11-17 09:15:00", "上海虹桥", "2026-11-17 16:05:00", "410", "680.00"));

        LegService.Leg leg = LegService.overLimitOrNone("深圳", "上海", "flyai search-train",
                12, 180, allTooLong, LegService.Direction.OUTBOUND);

        assertEquals(LegService.Outcome.FOUND, leg.outcome(), "有车就是有车 —— 报成'没有走法'是假话");
        assertTrue(leg.overLimit(), "但必须标出来它超了，否则界面上看起来就像一条时长合适的车");
        assertNotNull(leg.transfer());
        assertEquals(400, leg.transfer().durationMinutes(), "给的是最快的（列表已按时长升序）");
        assertEquals("上海虹桥", leg.transfer().arrStation(), "中转/直达都要取最终到达站");
    }

    @Test
    @DisplayName("一条候选都没有 → NO_EDGE（这时候'没有走法'才是真的）")
    void noCandidatesIsNoEdge() {
        LegService.Leg leg = LegService.overLimitOrNone("深圳", "上海", "flyai search-train",
                12, 180, List.of(), LegService.Direction.OUTBOUND);

        assertEquals(LegService.Outcome.NO_EDGE, leg.outcome());
        assertNull(leg.transfer());
        assertFalse(leg.overLimit());
        assertTrue(leg.detail().contains("12 小时"),
                "要说清查过多大的窗口，否则用户会以为我们只查了 3 小时就下结论：" + leg.detail());
    }

    @Test
    @DisplayName("候选是 journeys 为空的脏数据 → NO_EDGE，而不是给一个字段全空的 Transfer")
    void brokenCandidateFallsBackToNoEdge() {
        List<FlyaiTrain> broken = List.of(new FlyaiTrain(List.of(), "680.00", "400", null));

        LegService.Leg leg = LegService.overLimitOrNone("深圳", "上海", "flyai search-train",
                12, 180, broken, LegService.Direction.OUTBOUND);

        assertEquals(LegService.Outcome.NO_EDGE, leg.outcome(),
                "假的一行交通比'没查到'更糟 —— 它看起来完全正常，没人会去查它");
        assertNull(leg.transfer());
    }

    /* ============================================================
     * ⭐ 拒绝查询时抛的是**哪个**异常 —— 这一条是给调用方看的
     * ============================================================ */

    /**
     * 为什么要专门钉"异常类型"这件事，而不只是钉"抛了异常"：
     *
     *   调用方 `PlanService.searchLeg` 是把"我们拒绝了这次查询"翻译成一句 warning 的地方。
     *   它当时的写法是 `catch (FlyaiException e)` —— 而这个 catch **永远不会命中**，
     *   因为 `LegService.search` 内部已经把每一个 FlyaiException 都翻译成了 Outcome
     *   （见它自己的 catch 分支），FlyaiException 根本逃不出这个方法。
     *   真正会逃出来的是 requireCity 抛的 IllegalArgumentException。
     *
     *   于是画像里写了个「广东周边」当出发地时（对话层会 sanitize，但脚本直接调 /generate 不会），
     *   异常一路穿过 attachLegs 打到 controller —— **整个 /generate 报 500**。
     *   而正确行为只是"这次车次查不了，行程照排，少一行交通"。
     *
     *   这类 bug 的形状在这个工程里出现过很多次了：**错的不是判断，是抓的那一层。**
     *   所以这里不测"抛异常了没"，而是把这个类型本身写进测试名里 ——
     *   谁要是把 LegService 改成抛 FlyaiException，这个用例就会红，提醒他回去改调用方。
     *
     * 为什么能用 null 当依赖：requireCity 在碰到 caller / normalizer 之前就抛了。
     * 这本身也是被测事实的一部分 —— 守门必须在**发出请求之前**，而不是等上游回一个空的
     * （上游对"广东周边"和"这个地方真没数据"的响应是一模一样的，等它就来不及了）。
     */
    private static LegService guardOnly() {
        return new LegService(null, null,
                new FlyaiProperties(null, "flyai-bundle.cjs", 0, 0, 0, null, 180, null));
    }

    @Test
    @DisplayName("⭐ 出发地是区域说法（广东周边）→ IllegalArgumentException，**不是** FlyaiException")
    void regionNameRejectedAsIllegalArgument() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> guardOnly().search("广东周边", "珠海", "2026-11-17", LegService.Direction.OUTBOUND));

        assertTrue(e.getMessage().contains("出发地"), "得说清是哪一头不对：" + e.getMessage());
        assertTrue(e.getMessage().contains("广东周边"), "得把用户写的那个词原样带回来，他才知道你在说哪句");
        assertTrue(e.getMessage().contains("具体"), "要给出该怎么办，不能只说'不合法'：" + e.getMessage());
    }

    @Test
    @DisplayName("目的地是区域说法同样被挡（两头都要守，不能只守出发地）")
    void regionNameRejectedOnDestinationToo() {
        assertThrows(IllegalArgumentException.class,
                () -> guardOnly().search("广州", "广东周边", "2026-11-17", LegService.Direction.OUTBOUND));
    }

    @Test
    @DisplayName("同城 → IllegalArgumentException（同城不需要城际交通，调用方本该先判掉）")
    void sameCityRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> guardOnly().search("珠海", "珠海", "2026-11-17", LegService.Direction.OUTBOUND));

        assertTrue(e.getMessage().contains("同一座城市"), e.getMessage());
    }

    @Test
    @DisplayName("出发地为空 → IllegalArgumentException（'不知道从哪走'要在发请求之前拦住）")
    void blankOriginRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> guardOnly().search("  ", "珠海", "2026-11-17", LegService.Direction.OUTBOUND));
    }
}
