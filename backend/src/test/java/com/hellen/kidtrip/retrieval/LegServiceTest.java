package com.hellen.kidtrip.retrieval;

import com.hellen.kidtrip.flyai.FlyaiProperties;
import com.hellen.kidtrip.flyai.raw.FlyaiTrain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
