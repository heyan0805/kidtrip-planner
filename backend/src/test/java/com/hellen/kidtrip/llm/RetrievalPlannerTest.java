package com.hellen.kidtrip.llm;

import com.hellen.kidtrip.flyai.PoiCategories;
import com.hellen.kidtrip.plan.RetrievalPlan;
import com.hellen.kidtrip.plan.TravelParams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 画像 → 检索条件（受控输出一次）。
 *
 * 重点是那个"受控"：**模型写的类别只要不在飞猪的 35 个里，就必须被丢掉**，
 * 而且丢掉之后要么用剩下的继续、要么整体降级 —— 两种都不能把非法值放进检索。
 */
@DisplayName("画像 → 检索条件：白名单过滤 + 降级")
class RetrievalPlannerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final TravelParams PARAMS =
            new TravelParams(null, "珠海", 3, "2026-10-24", List.of("动物", "海洋"), "慢", List.of(4), true);

    private static RetrievalPlanner planner(FakeChatModel model) {
        return new RetrievalPlanner(model, MAPPER);
    }

    @Test
    @DisplayName("正常输出 → 类别 / 关键词 / 要酒店")
    void parsesNormalOutput() {
        FakeChatModel model = new FakeChatModel("""
                {"wantedCategories":["动物园","海洋馆"],"keywords":["室内"],"needHotel":true}
                """);

        RetrievalPlanner.Planned planned = planner(model).plan(PARAMS);

        assertEquals(List.of("动物园", "海洋馆"), planned.plan().wantedCategories());
        assertEquals(List.of("室内"), planned.plan().keywords());
        assertTrue(planned.plan().needHotel());
        assertTrue(planned.notes().isEmpty(), "一切正常就不该报警：" + planned.notes());
    }

    @Test
    @DisplayName("⭐ 白名单：模型自己造的词（'科技馆'）被丢掉，并如实记账")
    void dropsCategoriesFlyaiDoesNotKnow() {
        FakeChatModel model = new FakeChatModel("""
                {"wantedCategories":["动物园","科技馆","遛娃圣地"],"keywords":[],"needHotel":true}
                """);

        RetrievalPlanner.Planned planned = planner(model).plan(PARAMS);

        assertEquals(List.of("动物园"), planned.plan().wantedCategories(),
                "闭集外的两个必须消失 —— 它们会变成两次必然失败的 --category 调用");
        assertEquals(1, planned.notes().size());
        assertTrue(planned.notes().get(0).contains("2 个"), planned.notes().toString());
        assertTrue(planned.notes().get(0).contains("已忽略"), planned.notes().toString());
    }

    @Test
    @DisplayName("类别全部非法 → 退化成'不限类别'（纯宽查），仍然能用")
    void allInvalidCategoriesDegradeToWideQuery() {
        FakeChatModel model = new FakeChatModel("""
                {"wantedCategories":["迪士尼","环球影城"],"needHotel":true}
                """);

        RetrievalPlanner.Planned planned = planner(model).plan(PARAMS);

        assertTrue(planned.plan().wantedCategories().isEmpty(),
                "空列表是**合法**的：它表示'不补查'，也就是纯宽查 —— 实测这条路本来就能用");
        assertEquals(1, planned.notes().size());
    }

    @Test
    @DisplayName("超过 4 个类别 → 按顺序取前 4 个，且**不报警**（多列不是错）")
    void capsCategoryCountWithoutWarning() {
        FakeChatModel model = new FakeChatModel("""
                {"wantedCategories":["动物园","海洋馆","主题乐园","公园乐园","博物馆","植物园"]}
                """);

        RetrievalPlanner.Planned planned = planner(model).plan(PARAMS);

        assertEquals(List.of("动物园", "海洋馆", "主题乐园", "公园乐园"), planned.plan().wantedCategories());
        assertTrue(planned.notes().isEmpty(), "只是列多了，用户不需要知道：" + planned.notes());
    }

    @Test
    @DisplayName("重复类别会去重 —— 同一个'期望'不能算两次")
    void dedupesCategories() {
        FakeChatModel model = new FakeChatModel("""
                {"wantedCategories":["动物园","动物园","海洋馆"],"needHotel":true}
                """);

        RetrievalPlanner.Planned planned = planner(model).plan(PARAMS);

        assertEquals(List.of("动物园", "海洋馆"), planned.plan().wantedCategories());
        assertTrue(planned.notes().isEmpty(), "重复是无害的，不该报警：" + planned.notes());
    }

    @Test
    @DisplayName("needHotel 缺失 → 缺省为 true（方向和 fallback 一致，不能相反）")
    void needHotelDefaultsToTrue() {
        FakeChatModel model = new FakeChatModel("{\"wantedCategories\":[\"动物园\"]}");

        assertTrue(planner(model).plan(PARAMS).plan().needHotel());

        FakeChatModel explicitNo = new FakeChatModel(
                "{\"wantedCategories\":[\"动物园\"],\"needHotel\":false}");
        assertFalse(planner(explicitNo).plan(PARAMS).plan().needHotel(),
                "模型明确说不安排住宿时必须尊重");
    }

    @Test
    @DisplayName("关键词清洗：去空白、去重、限 4 个")
    void cleansKeywords() {
        FakeChatModel model = new FakeChatModel("""
                {"wantedCategories":["动物园"],
                 "keywords":[" 室内 ","室内","","雨天","免排队","亲子","日落"]}
                """);

        List<String> kw = planner(model).plan(PARAMS).plan().keywords();

        assertEquals(4, kw.size());
        assertEquals("室内", kw.get(0), "要去掉首尾空白并去重");
    }

    @Test
    @DisplayName("⭐ 两次都解析不出来 → 降级成 fallback（不限类别 + 要酒店），**不抛错**")
    void degradesToFallbackOnParseFailure() {
        FakeChatModel model = new FakeChatModel("我不太确定", "还是不确定");

        RetrievalPlanner.Planned planned = planner(model).plan(PARAMS);

        assertEquals(2, model.calls());
        assertEquals(RetrievalPlan.fallback().wantedCategories(), planned.plan().wantedCategories());
        assertTrue(planned.plan().needHotel(), "降级后仍然要酒店");
        assertEquals(1, planned.notes().size());
        assertTrue(planned.notes().get(0).contains("兜底"), planned.notes().toString());
    }

    @Test
    @DisplayName("⭐ 模型调用失败 → 同样降级成 fallback，让整个生成流程能继续")
    void degradesOnCallFailure() {
        RetrievalPlanner p = planner(FakeChatModel.failing(new RuntimeException("429 too many requests")));

        RetrievalPlanner.Planned planned = p.plan(PARAMS);

        assertTrue(planned.plan().needHotel());
        assertEquals(1, planned.notes().size());
        assertTrue(planned.notes().get(0).contains("429"), "要把失败原因带上，否则排障时无从下手："
                + planned.notes().toString());
    }

    @Test
    @DisplayName("缓存：同一份画像只花一次模型调用")
    void cachesByProfile() {
        FakeChatModel model = new FakeChatModel("{\"wantedCategories\":[\"动物园\"],\"needHotel\":true}");
        RetrievalPlanner p = planner(model);

        RetrievalPlanner.Planned first = p.plan(PARAMS);
        RetrievalPlanner.Planned second = p.plan(PARAMS);

        assertEquals(1, model.calls(), "同一份画像不该问第二遍");
        assertEquals(first.plan().wantedCategories(), second.plan().wantedCategories());
    }

    @Test
    @DisplayName("缓存按画像区分：改一个字段就要重新规划（否则会拿到过期条件）")
    void cacheKeyIncludesEveryField() {
        FakeChatModel model = new FakeChatModel("{\"wantedCategories\":[\"动物园\"],\"needHotel\":true}");
        RetrievalPlanner p = planner(model);

        p.plan(PARAMS);
        p.plan(new TravelParams(null, "深圳", 3, "2026-10-24", List.of("动物", "海洋"), "慢", List.of(4), true));

        assertEquals(2, model.calls(), "城市变了必须重算 —— 缓存 key 漏字段是这里最难查的 bug");
    }

    @Test
    @DisplayName("prompt 里必须列全 35 个类别 —— 闭集不给全，模型就必然要猜")
    void promptListsEveryCategory() {
        CapturingModel model = new CapturingModel();
        planner(model).plan(PARAMS);

        for (String c : PoiCategories.ALL) {
            assertTrue(model.lastPrompt.contains(c), "prompt 里缺类别：" + c);
        }
        assertTrue(model.lastPrompt.contains("期望覆盖"), "必须告诉模型这些是'期望'不是'命令'");
        assertTrue(model.lastPrompt.contains("最多补查 2 次"), "必须告诉它列多了没有用");
    }

    private static final class CapturingModel extends FakeChatModel {
        private String lastPrompt;

        CapturingModel() {
            super("{\"wantedCategories\":[\"动物园\"],\"needHotel\":true}");
        }

        @Override
        public String chat(String prompt) {
            this.lastPrompt = prompt;
            return super.chat(prompt);
        }
    }
}
