package com.hellen.kidtrip.plan;

import com.hellen.kidtrip.candidate.HotelCandidate;
import com.hellen.kidtrip.candidate.PoiCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 酒店候选行末尾那串真实距离 —— `| 近：C1(0.7km) C24(0.9km) C21(1.8km)`。
 *
 * 在它之前，模型挑酒店时手里只有酒店名和 22 个字的地址。实测它靠"长隆企鹅酒店"
 * 猜对了 0.33 公里、靠"地球村雅致漓江酒店（…象鼻山店）"猜对了 0.70 公里 ——
 * 但那是因为**酒店名字里恰好带着景区名**。同一次行程里它让用户第 1 天住桂林市区、
 * 第 2 天一早去阳朔（42.4 公里），全程不知道。
 *
 * 所以这串字要担起的事很具体：**让'离得近不近'从猜变成读。**
 * 下面的坐标都是飞猪缓存里的真值。
 */
@DisplayName("候选行渲染：把'近不近'从猜测变成可读的数字")
class CandidateCodesTest {

    /* 桂林的真实坐标（飞猪缓存） */
    private static final double[] HOTEL = {25.267589, 110.289587};        // 地球村雅致漓江酒店（象山店）
    private static final double[] XIANG_BI_SHAN = {25.267579, 110.296573}; // 象鼻山景区 —— 0.7 公里
    private static final double[] RONG_HU = {25.275941, 110.29039};       // 榕湖公园 —— 0.9 公里
    private static final double[] DU_XIU_FENG = {25.281648, 110.299118};  // 独秀峰王城 —— 1.8 公里
    private static final double[] MA_SHAN = {24.932489, 110.490624};      // 漓江竹筏漂流(马山码头) —— 42 公里

    private static PoiCandidate poi(String id, String name, Double lat, Double lng) {
        return new PoiCandidate(id, name, "某地址", "山湖田园",
                lat, lng, null, null, null, null, null, null, null);
    }

    private static HotelCandidate hotel(String name, Double lat, Double lng) {
        return new HotelCandidate("h-" + name, name, "桂林市象山区民主路万寿巷33号综合楼7楼",
                lat, lng, 242.0, "高档型", null, null, null);
    }

    private static String hotelLine(List<HotelCandidate> hotels, List<PoiCandidate> pois) {
        return CandidateCodes.renderHotelLines(hotels, pois).get(0);
    }

    /* ============================================================
     * 核心主张：给的是"最近的"，不是"清单最前面的"
     * ============================================================ */

    @Test
    @DisplayName("⭐ 最远的点恰好排在清单第一位时，也要挑出最近的那三个")
    void picksTheNearestOnesNotTheFirstOnes() {
        // 候选清单的顺序来自飞猪的相关性排名，**和地理位置毫无关系**。
        // 直接取前三条，等于给模型看了一个与距离无关的采样 —— 那比不给还糟。
        List<PoiCandidate> pois = List.of(
                poi("p1", "漓江竹筏漂流(马山码头)", MA_SHAN[0], MA_SHAN[1]),       // C1 —— 42 公里外
                poi("p2", "象鼻山景区", XIANG_BI_SHAN[0], XIANG_BI_SHAN[1]),      // C2 —— 0.7 公里
                poi("p3", "榕湖公园", RONG_HU[0], RONG_HU[1]),                      // C3 —— 0.9 公里
                poi("p4", "桂林独秀峰王城景区", DU_XIU_FENG[0], DU_XIU_FENG[1]));   // C4 —— 1.8 公里

        String line = hotelLine(List.of(hotel("地球村雅致漓江酒店（桂林两江四湖象鼻山店）",
                HOTEL[0], HOTEL[1])), pois);

        assertTrue(line.endsWith("近：C2(0.7km) C3(0.9km) C4(1.8km)"),
                "最近的三个是 C2/C3/C4，按距离升序排列；清单第一位的 C1（42 公里）不该出现。实际：" + line);
    }

    @Test
    @DisplayName("⭐ 行尾引用的 C2 必须就是清单里的第 2 条 —— 编号错位一位是最恶心的那类 bug")
    void nearbyCodePointsAtTheSameSpotAsTheList() {
        List<PoiCandidate> pois = List.of(
                poi("p1", "漓江竹筏漂流(马山码头)", MA_SHAN[0], MA_SHAN[1]),
                poi("p2", "象鼻山景区", XIANG_BI_SHAN[0], XIANG_BI_SHAN[1]),
                poi("p3", "榕湖公园", RONG_HU[0], RONG_HU[1]));

        String line = hotelLine(List.of(hotel("某酒店", HOTEL[0], HOTEL[1])), pois);

        // 用同一套 Geo 现算一遍再比对 —— 这比把 "0.7km" 写死在断言里更能证明
        // "引用的编号"和"清单里的位置"说的是同一个点。写死的话，两处一起错也照样绿。
        String expected = Geo.label(Geo.between(HOTEL[0], HOTEL[1],
                pois.get(1).lat(), pois.get(1).lng()));
        assertTrue(line.contains("C2(" + expected + ")"),
                "C2 指的是清单第 2 条（象鼻山），距离必须按它算。实际：" + line);
    }

    @Test
    @DisplayName("⭐ 只认传进来的那批候选 —— 传两条就绝不会冒出 C3")
    void onlyCodesFromTheGivenList() {
        // 这个约束靠**参数**保证：调用方必须传"这次真的送进了 prompt 的那批"，
        // 而不是让这个方法自己去翻全量池。翻全量池会标出模型压根没见过的编号，
        // 而一个它用不上的提示比没有提示更糟 —— 它会以为自己漏看了什么，然后开始编。
        List<PoiCandidate> twoOnly = List.of(
                poi("p1", "象鼻山景区", XIANG_BI_SHAN[0], XIANG_BI_SHAN[1]),
                poi("p2", "榕湖公园", RONG_HU[0], RONG_HU[1]));

        String line = hotelLine(List.of(hotel("某酒店", HOTEL[0], HOTEL[1])), twoOnly);

        assertTrue(line.endsWith("近：C1(0.7km) C2(0.9km)"), line);
        assertTrue(!line.contains("C3("), "只给了两条候选，不可能冒出 C3：" + line);
    }

    /* ============================================================
     * 算不出来的时候
     * ============================================================ */

    @Test
    @DisplayName("酒店没坐标 → 写「未知」，绝不写 0.0km")
    void hotelWithoutCoordsSaysUnknown() {
        // 0 公里的含义是"就在旁边"。缺坐标写成 0，等于把一家我们一无所知的酒店
        // 说成"紧邻景点" —— 而模型会把这句话原样写进 hotelTips 交给用户。
        String line = hotelLine(List.of(hotel("某酒店", null, null)),
                List.of(poi("p1", "象鼻山景区", XIANG_BI_SHAN[0], XIANG_BI_SHAN[1])));

        assertTrue(line.endsWith("近：未知"), line);
    }

    @Test
    @DisplayName("个别景点没坐标 → 跳过它，不能让整行变成「未知」")
    void spotsWithoutCoordsAreSkippedNotPoisoningTheLine() {
        List<PoiCandidate> pois = List.of(
                poi("p1", "象鼻山景区", XIANG_BI_SHAN[0], XIANG_BI_SHAN[1]),
                poi("p2", "没有坐标的点", null, null),
                poi("p3", "榕湖公园", RONG_HU[0], RONG_HU[1]));

        String line = hotelLine(List.of(hotel("某酒店", HOTEL[0], HOTEL[1])), pois);

        assertTrue(line.endsWith("近：C1(0.7km) C3(0.9km)"),
                "没坐标的 C2 跳过，有坐标的照排 —— 一个坏点不该毁掉整行。实际：" + line);
    }

    @Test
    @DisplayName("一条候选景点都没有（或直接传 null）→ 「未知」，不抛异常")
    void emptyPoiListIsHandled() {
        List<HotelCandidate> hotels = List.of(hotel("某酒店", HOTEL[0], HOTEL[1]));

        assertTrue(hotelLine(hotels, List.of()).endsWith("近：未知"));
        assertTrue(hotelLine(hotels, null).endsWith("近：未知"));
    }

    /* ============================================================
     * 别把原有的那几栏弄坏
     * ============================================================ */

    @Test
    @DisplayName("名称 / 星级 / 评分 / 房价 / 地址那几栏一个字都没变")
    void theOriginalColumnsAreUntouched() {
        String line = hotelLine(
                List.of(hotel("地球村雅致漓江酒店（桂林两江四湖象鼻山店）", HOTEL[0], HOTEL[1])),
                List.of(poi("p1", "象鼻山景区", XIANG_BI_SHAN[0], XIANG_BI_SHAN[1])));

        assertTrue(line.startsWith("H1 | 地球村雅致漓江酒店（桂林两江四湖象鼻山店）"
                + " | 高档型 | 暂无评分 | ¥242/晚 | "), "前面几栏的格式和顺序都没动。实际：" + line);
        assertTrue(line.contains("民主路万寿巷"), "地址还在：" + line);
    }
}
