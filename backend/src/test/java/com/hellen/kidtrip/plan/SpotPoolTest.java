package com.hellen.kidtrip.plan;

import com.hellen.kidtrip.candidate.Candidate;
import com.hellen.kidtrip.candidate.HotelCandidate;
import com.hellen.kidtrip.candidate.PoiCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 备选池（「换一个」的来源）的规则。
 *
 * 这一组断言的存在理由：`spotPool` 是**直接送给前端的原始事实**，
 * 它出错的方式和别的字段不一样 —— 它不会让请求失败、不会让行程排不出来，
 * 只会让用户在「换一个」的列表里看到不该出现的东西（一家酒店、一个 0 元的门票）。
 * 这类错在真跑一次的时候也不会被注意到，因为要展开列表才看得见。
 */
class SpotPoolTest {

    private static final HotelCandidate A_HOTEL = new HotelCandidate(
            "H-1", "广州奥园喜来登酒店", "番禺区", 23.0, 113.3, 943.0,
            "豪华型", null, "", "https://example.invalid/h");

    private static PoiCandidate poi(String id, String name, String category, Double price,
                                    Double lat, Double lng) {
        return new PoiCandidate(id, name, "某路 1 号", category, lat, lng, price,
                null, null, null, null, "", "https://example.invalid/" + id);
    }

    @Test
    @DisplayName("⭐ 酒店不能混进备选池 —— 池子是混装的，两种候选只隔一个 instanceof")
    void hotelsNeverLeakIntoThePool() {
        List<Candidate> pool = List.of(
                poi("C-1", "广州动物园", "动物园", 29.9, 23.1, 113.3),
                A_HOTEL,
                poi("C-2", "白云山", "山湖田园", null, 23.2, 113.3));

        List<SpotOption> options = PlanService.spotPoolOf(pool);

        assertEquals(2, options.size(), "池子里有 2 个景点 1 家酒店，备选池应该只有 2 条");
        assertTrue(options.stream().noneMatch(o -> o.name().contains("酒店")),
                "「换一个」的列表里出现一家酒店是编译得过、跑得起来、而且不报错的错");
    }

    @Test
    @DisplayName("顺序原样保留 —— 那是飞猪的相关性顺序，不是我们排的")
    void keepsUpstreamOrder() {
        List<Candidate> pool = List.of(
                poi("C-9", "最后一条", "博物馆", null, null, null),
                poi("C-3", "第一条", "动物园", null, null, null));

        List<SpotOption> options = PlanService.spotPoolOf(pool);

        assertEquals(List.of("C-9", "C-3"), options.stream().map(SpotOption::poiId).toList(),
                "有二次排序的话要说清是谁排的；这里一条都不该动");
    }

    @Test
    @DisplayName("poiId 和 category 必须原样带过去 —— 前者是去重与追溯的键，后者决定界面怎么分组")
    void keepsTheTwoFieldsTheFrontendDependsOn() {
        List<SpotOption> options = PlanService.spotPoolOf(List.of(
                poi("C-1", "广州动物园", "动物园", 29.9, 23.1, 113.3)));

        assertEquals("C-1", options.get(0).poiId(), "前端要靠它把已用的点从备选里剔掉");
        assertEquals("动物园", options.get(0).category(), "用户换点常常是想换个类型（一天全是动物园）");
    }

    @Test
    @DisplayName("category 可能是空串（上游就有）—— 原样带过去，不要在这里编一个'其它'")
    void blankCategoryStaysBlank() {
        List<SpotOption> options = PlanService.spotPoolOf(List.of(
                poi("C-1", "某个点", "", null, null, null)));

        assertEquals("", options.get(0).category(),
                "编一个类别出来，界面就会拿它去做分组、而且分出来的组是假的");
    }

    @Test
    @DisplayName("没有票价是 null，**不是 0** —— 0 会在界面上显示成「¥0」，那是'免费'的意思")
    void missingPriceStaysNull() {
        List<SpotOption> options = PlanService.spotPoolOf(List.of(
                poi("C-1", "白云山", "山湖田园", null, null, null)));

        assertNull(options.get(0).priceYuan(),
                "null 的含义是'飞猪上没有可订的票'，可能是免费公园也可能是票含在母景区里 —— "
                        + "两种分不出来，所以只能显示'票价未知'");
    }

    @Test
    @DisplayName("没有坐标是 null，**不是 0** —— 0 是几内亚湾，会让'附近'算成几千公里")
    void missingCoordinateStaysNull() {
        List<SpotOption> options = PlanService.spotPoolOf(List.of(
                poi("C-1", "某个点", "动物园", null, null, null)));

        assertNull(options.get(0).lat(), "前端要拿它算'离当天其它点多近'，0 会让这个点永远排最后");
        assertNull(options.get(0).lng());
    }

    @Test
    @DisplayName("空池子回空列表，不回 null —— 界面要能直接 .length，不用先判空")
    void emptyPoolGivesEmptyList() {
        assertTrue(PlanService.spotPoolOf(List.of()).isEmpty());
    }
}
