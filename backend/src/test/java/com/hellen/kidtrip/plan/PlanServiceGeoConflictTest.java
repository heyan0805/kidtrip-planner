package com.hellen.kidtrip.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 地理跨度的兜底检查。
 *
 * 和首尾两天那份测试（PlanServiceLegConflictTest）是同一个形状：
 * 要防的错**每一天单独看都成立，矛盾只存在于两天之间**。
 * 那边的两个事实是"点的时刻"和"车的时刻"，这边的是"住处的位置"和"点的位置"。
 *
 * 下面用的坐标**全部是从飞猪缓存里抄下来的真值** —— 包括那个 42.4 公里。
 * 手编一组"明显很远"的坐标只能证明减法没写反，证明不了它在我们真实拿到的数据上能抓到东西。
 */
@DisplayName("地理跨度：每天都没毛病，两天之间是一道沟")
class PlanServiceGeoConflictTest {

    /** 默认规则里的值（PlanRules.DEFAULT.maxDistanceKm）。测试里写死，是为了让它和规则解耦 */
    private static final int MAX_KM = 15;

    /* ============================================================
     * 造数据 —— 坐标都是实测真值
     * ============================================================ */

    private static TravelSpot spot(String time, String name, double lat, double lng) {
        return new TravelSpot(time, name, "一句提示", "poi-" + name,
                29.9, "https://book.example/x", "https://img.example/x.jpg", lat, lng);
    }

    /** 一个没有坐标的点 —— 用来验证"算不出来"时是闭嘴，不是报一条假的 */
    private static TravelSpot spotWithoutCoords(String time, String name) {
        return new TravelSpot(time, name, "一句提示", "poi-" + name,
                29.9, "https://book.example/x", "https://img.example/x.jpg", null, null);
    }

    private static HotelStay hotel(String name, double lat, double lng) {
        return new HotelStay(name, "hotel-" + name, 400.0, "高档型", null, "某地址",
                lat, lng, "https://book.example/h", "https://img.example/h.jpg", "一句住宿提示");
    }

    private static HotelStay hotelWithoutCoords(String name) {
        return new HotelStay(name, "hotel-" + name, 400.0, "高档型", null, "某地址",
                null, null, "https://book.example/h", "https://img.example/h.jpg", "一句住宿提示");
    }

    private static TravelDay day(int n, List<TravelSpot> spots, HotelStay hotel) {
        return new TravelDay(n, "当天主题", spots, null, hotel);
    }

    /* 桂林那次的真实坐标（飞猪缓存） */
    private static final double[] HOTEL_CITY = {25.267589, 110.289587};   // 地球村雅致漓江酒店（象山店）
    private static final double[] XIANG_BI_SHAN = {25.267579, 110.296573}; // 象鼻山景区
    private static final double[] RONG_HU = {25.275941, 110.29039};        // 榕湖公园
    private static final double[] DU_XIU_FENG = {25.281648, 110.299118};   // 独秀峰王城景区
    private static final double[] MA_SHAN = {24.932489, 110.490624};       // 漓江竹筏漂流(马山码头)

    /* ============================================================
     * ⭐ 整件事的起因
     * ============================================================ */

    @Test
    @DisplayName("⭐ 第 1 天住桂林市区，第 2 天一早去阳朔 → 必须报出来（实测那份行程原样）")
    void theMorningCommuteThatStartedAllThis() {
        // 模型自己写的 hotelTips 是"住阳朔遇龙河附近…减少往返桂林市区的时间" ——
        // 它知道该往那边挪，但它没意识到第二天早上得先从市区出发。42.4 公里照跑。
        TravelDay day1 = day(1, List.of(
                spot("09:30", "象鼻山景区", XIANG_BI_SHAN[0], XIANG_BI_SHAN[1]),
                spot("11:30", "榕湖公园", RONG_HU[0], RONG_HU[1]),
                spot("14:30", "桂林独秀峰王城景区", DU_XIU_FENG[0], DU_XIU_FENG[1])),
                hotel("地球村雅致漓江酒店（桂林两江四湖象鼻山店）", HOTEL_CITY[0], HOTEL_CITY[1]));
        TravelDay day2 = day(2, List.of(
                spot("09:30", "漓江竹筏漂流(马山码头)", MA_SHAN[0], MA_SHAN[1])),
                null);

        String warning = PlanService.morningCommuteConflict(day2, day1, MAX_KM);

        assertNotNull(warning, "第 1 天晚上住市区、第 2 天早上去 42 公里外 —— 这两行看着独立，现实里是同一次出门");
        assertTrue(warning.contains("地球村雅致漓江酒店"), "要说清是**哪家酒店**：" + warning);
        assertTrue(warning.contains("漓江竹筏漂流"), "也要说清是**哪个点**：" + warning);
        assertTrue(warning.contains("42.4"), "这个数字是实测的，报出来才有说服力：" + warning);
    }

    @Test
    @DisplayName("⭐ 同一次行程的第 1 天**不该**被误报 —— 那天三个点都在市区（最远 1.8 公里）")
    void thePackedDayInTheCityCentreIsClean() {
        // 这一条是在守"别乱报"：报得太多等于没报，用户会连真的那条一起忽略。
        // 第 1 天：象鼻山 → 榕湖 → 独秀峰，实测两两之间最大 1.6 公里左右。
        TravelDay day1 = day(1, List.of(
                spot("09:30", "象鼻山景区", XIANG_BI_SHAN[0], XIANG_BI_SHAN[1]),
                spot("11:30", "榕湖公园", RONG_HU[0], RONG_HU[1]),
                spot("14:30", "桂林独秀峰王城景区", DU_XIU_FENG[0], DU_XIU_FENG[1])),
                hotel("地球村雅致漓江酒店（桂林两江四湖象鼻山店）", HOTEL_CITY[0], HOTEL_CITY[1]));

        assertNull(PlanService.spotSpreadConflict(day1, MAX_KM),
                "市区这三个点挨得很近，这一天的排法是对的，不能报");
    }

    /* ============================================================
     * 当天相邻两点
     * ============================================================ */

    @Test
    @DisplayName("同一天里跨 42 公里 → 报")
    void sameDaySpreadIsCaught() {
        TravelDay d = day(2, List.of(
                spot("09:30", "象鼻山景区", XIANG_BI_SHAN[0], XIANG_BI_SHAN[1]),
                spot("14:30", "漓江竹筏漂流(马山码头)", MA_SHAN[0], MA_SHAN[1])),
                null);

        assertNotNull(PlanService.spotSpreadConflict(d, MAX_KM));
    }

    @Test
    @DisplayName("同一天里挨得近 → 闭嘴")
    void sameDayCloseIsSilent() {
        TravelDay d = day(1, List.of(
                spot("09:30", "象鼻山景区", XIANG_BI_SHAN[0], XIANG_BI_SHAN[1]),
                spot("14:30", "桂林独秀峰王城景区", DU_XIU_FENG[0], DU_XIU_FENG[1])),
                null);

        assertNull(PlanService.spotSpreadConflict(d, MAX_KM));
    }

    @Test
    @DisplayName("⚠️ 顺序被模型打乱了，也要按**时刻**算相邻 —— 否则报出来的数字是错的")
    void outOfOrderSpotsStillPairCorrectly() {
        // 模型实测就会把 14:00 写在 09:30 前面。若按列表顺序取"相邻"，
        // 会把"榕湖公园"和"马山码头"配成一对 —— 而它们现实中隔着 42 公里还是 1 公里，
        // 全看谁被写在了前面。**规则对、取值方式错**，是这个工程反复踩的那类坑。
        TravelDay d = day(2, List.of(
                spot("14:30", "漓江竹筏漂流(马山码头)", MA_SHAN[0], MA_SHAN[1]),
                spot("09:30", "象鼻山景区", XIANG_BI_SHAN[0], XIANG_BI_SHAN[1]),
                spot("11:30", "榕湖公园", RONG_HU[0], RONG_HU[1])),
                null);

        String warning = PlanService.spotSpreadConflict(d, MAX_KM);

        assertNotNull(warning, "按时刻排：象鼻山(1.1) → 榕湖(42+) —— 中间那一段才是真的跨了");
        assertTrue(warning.contains("榕湖公园") && warning.contains("漓江竹筏漂流"),
                "被点名的应该是**按时间相邻**的那一对：" + warning);
    }

    @Test
    @DisplayName("一天只有一个点 → 没有任何'相邻'可算，闭嘴")
    void singleSpotDayIsSilent() {
        TravelDay d = day(3, List.of(spot("10:00", "某个点", XIANG_BI_SHAN[0], XIANG_BI_SHAN[1])), null);

        assertNull(PlanService.spotSpreadConflict(d, MAX_KM));
    }

    /* ============================================================
     * 算不出来的时候，一句都别说
     * ============================================================ */

    @Test
    @DisplayName("⭐ 点没有坐标 → 静默跳过，绝不猜一个距离出来报假警告")
    void missingSpotCoordsIsSilent() {
        // 一条基于猜测的警告，会让真的那条一起被忽略 —— 和首尾两天那边的处理是同一条规矩。
        TravelDay d = day(2, List.of(
                spotWithoutCoords("09:30", "象鼻山景区"),
                spotWithoutCoords("14:30", "漓江竹筏漂流(马山码头)")),
                null);

        assertNull(PlanService.spotSpreadConflict(d, MAX_KM));
    }

    @Test
    @DisplayName("酒店没有坐标 → 早上的通勤那一项静默")
    void missingHotelCoordsIsSilent() {
        TravelDay day1 = day(1, List.of(spot("09:30", "象鼻山景区", XIANG_BI_SHAN[0], XIANG_BI_SHAN[1])),
                hotelWithoutCoords("某家没坐标的酒店"));
        TravelDay day2 = day(2, List.of(spot("09:30", "漓江竹筏漂流(马山码头)", MA_SHAN[0], MA_SHAN[1])),
                null);

        assertNull(PlanService.morningCommuteConflict(day2, day1, MAX_KM));
    }

    @Test
    @DisplayName("⭐ 前一晚没有住处（当天返程）→ 闭嘴，不能拿别的东西当'昨晚'")
    void noYesterdayHotelIsSilent() {
        TravelDay day1 = day(1, List.of(spot("09:30", "象鼻山景区", XIANG_BI_SHAN[0], XIANG_BI_SHAN[1])), null);
        TravelDay day2 = day(2, List.of(spot("09:30", "漓江竹筏漂流(马山码头)", MA_SHAN[0], MA_SHAN[1])),
                null);

        assertNull(PlanService.morningCommuteConflict(day2, day1, MAX_KM),
                "第 1 天没安排住宿（比如当天就返程了），那就没有'从住处出发'这件事");
    }

    @Test
    @DisplayName("阈值是参数，不是写死的 15 —— 距离本身没问题时，调小阈值也该报")
    void thresholdIsRespected() {
        // 象鼻山 → 独秀峰 实测约 1.59 公里。用 15 公里不该报（见上面的城市中心那条），
        // 但把阈值压到 1 公里就该报 —— 这证明**报与不报取决于阈值，而不是取决于哪段代码被走到**。
        TravelDay d = day(1, List.of(
                spot("09:30", "象鼻山景区", XIANG_BI_SHAN[0], XIANG_BI_SHAN[1]),
                spot("14:30", "桂林独秀峰王城景区", DU_XIU_FENG[0], DU_XIU_FENG[1])),
                null);

        assertNull(PlanService.spotSpreadConflict(d, MAX_KM));
        assertNotNull(PlanService.spotSpreadConflict(d, 1), "阈值压到 1 公里，1.59 公里就该被抓出来");
    }

    @Test
    @DisplayName("住得离景点近（0.7 公里）→ 早上通勤闭嘴，别为了刷存在感硬报一条")
    void hotelNextDoorIsSilent() {
        // 实测：地球村雅致漓江酒店 → 象鼻山景区 只有 0.70 公里。
        // 这一条守的还是"别乱报"：一个天天报的检查，用户第三天就不看了。
        TravelDay day1 = day(1, List.of(spot("09:30", "象鼻山景区", XIANG_BI_SHAN[0], XIANG_BI_SHAN[1])),
                hotel("地球村雅致漓江酒店（桂林两江四湖象鼻山店）", HOTEL_CITY[0], HOTEL_CITY[1]));
        TravelDay day2 = day(2, List.of(spot("09:30", "桂林独秀峰王城景区", DU_XIU_FENG[0], DU_XIU_FENG[1])),
                null);

        assertNull(PlanService.morningCommuteConflict(day2, day1, MAX_KM));
    }
}
