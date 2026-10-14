package com.hellen.kidtrip.plan;

import com.hellen.kidtrip.retrieval.LegService;
import com.hellen.kidtrip.retrieval.Transfer;
import com.hellen.kidtrip.retrieval.TransportMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 机票那一层唯一的两条**产品判断**：什么时候值得多花一次调用、以及查回来的东西怎么摆。
 *
 * 为什么要为一个"加机票"单独建一组测试 —— 因为它**花的是真钱（上游额度）**：
 *   · 触发条件松一格 → 每次生成都多发一两次调用 → 实测那套上游会限流（429/451），
 *     演示那天最可能的表现是"某个用户没见过的错"；
 *   · 触发条件紧一格 → 该给备选的时候不给 → 用户看着"这段火车 6.5 小时"却不知道
 *     飞机只要 2 小时、还更便宜（实测 12-10 那条线机票 ¥610 vs 白天高铁 ¥848）。
 *   两边都不是崩溃式的错，**都是不报错的错** —— 所以只能靠这里钉住。
 *
 * 另一个理由：这两条规则以前会埋在 PlanService.searchLeg 那个要联网的方法里，
 * 而"要联网"就意味着没人会为它写测试。抽成 static 纯函数之后，判据本身可以被直接钉住 ——
 * **可测性不是顺手得到的，是设计出来的**（和 PlanAssembler / Geo 是同一种做法）。
 */
@DisplayName("机票：什么时候查 / 查回来的怎么摆")
class PlanServiceFlightTest {

    /* ============================================================
     * 造数据：两个载体各一个真的 Transfer
     * ============================================================ */

    private static Transfer train(int minutes) {
        return new Transfer("深圳", "上海", TransportMode.TRAIN, "深圳北", "上海虹桥",
                "2026-11-17T07:43", "2026-11-17T14:17", minutes, 848.0,
                "高铁G270", "二等座", true, null, "https://router.feizhu.com/t");
    }

    private static Transfer plane(int minutes) {
        return new Transfer("深圳", "上海", TransportMode.FLIGHT, "宝安", "虹桥",
                "2026-11-17T08:20", "2026-11-17T10:25", minutes, 610.0,
                "深航ZH9501", "经济舱", true, null, "https://router.feizhu.com/f");
    }

    private static LegService.Leg found(Transfer t, boolean overLimit) {
        return new LegService.Leg(LegService.Outcome.FOUND, t, "flyai …", null, overLimit);
    }

    private static LegService.Leg noEdge() {
        return new LegService.Leg(LegService.Outcome.NO_EDGE, null, "flyai …",
                "深圳 到 上海 12 小时内没有查到直达走法", false);
    }

    private static LegService.Leg failed() {
        return new LegService.Leg(LegService.Outcome.UPSTREAM_ERROR, null, "flyai …",
                "MCP HTTP 451", false);
    }

    /* ============================================================
     * ① 什么时候多花一次调用
     * ============================================================ */

    @Test
    @DisplayName("⭐ 火车超限 → 查（用户正想问'那坐飞机呢'）")
    void flightWantedWhenTrainIsOverLimit() {
        assertTrue(PlanService.wantsFlight(found(train(394), true)),
                "394 分钟远超 180 的上限 —— 这正是机票唯一有价值的时刻");
    }

    @Test
    @DisplayName("⭐ 火车根本没有走法 → 查（这一段本来就不指望火车）")
    void flightWantedWhenThereIsNoTrainAtAll() {
        assertTrue(PlanService.wantsFlight(noEdge()));
    }

    @Test
    @DisplayName("火车在上限内 → **不查**（短途本来就不需要飞机这个备选，一次调用都不该花）")
    void flightNotWantedWhenTrainIsComfortable() {
        assertFalse(PlanService.wantsFlight(found(train(53), false)),
                "广州→珠海 53 分钟这种路，查机票纯属浪费额度");
    }

    /**
     * ⭐ 这一条是整组里最要紧的：**"不知道"不等于"不行"。**
     *
     * 车次查询失败时我们并不知道这条路长不长 —— 多查一次飞机，要么白花钱，
     * 要么更糟：给出一份"这段路只能飞"的交通行，而真实的火车可能又快又便宜。
     * 那时的正确话术是"车次这次没查成，重新生成一次就会补上"（现有的 UPSTREAM_ERROR 分支），
     * 而多出来的那次航班查询**恰好会把这句话变成假的**。
     */
    @Test
    @DisplayName("⭐ 车次查询失败 → 不查（'不知道'不能当成'这条路很长'）")
    void flightNotWantedWhenTheTrainQueryFailed() {
        assertFalse(PlanService.wantsFlight(failed()),
                "把'查询失败'读成'这条腿不行'，是这个工程反复踩的那类错");
    }

    @Test
    @DisplayName("压根没查过（null）→ 不查，也不抛异常")
    void nullLegIsSafe() {
        assertFalse(PlanService.wantsFlight(null));
    }

    /* ============================================================
     * ② 查回来的东西怎么摆
     * ============================================================ */

    @Test
    @DisplayName("⭐ 火车超限 + 有飞机 → 火车仍是主选，飞机进备选（并排看，谁也不替谁做决定）")
    void trainStaysPrimaryAndFlightBecomesTheAlternative() {
        Legs.Options options = PlanService.optionsOf(found(train(394), true), found(plane(125), false));

        assertEquals(TransportMode.TRAIN, options.primary().mode(),
                "超限只是超出我们替他设的默认上限，不是'不能坐' —— 排程仍按火车排");
        assertEquals(1, options.alternatives().size());
        assertEquals(TransportMode.FLIGHT, options.alternatives().get(0).mode());
        assertEquals(610.0, options.alternatives().get(0).priceYuan());
    }

    @Test
    @DisplayName("⭐ 火车没有、飞机有 → **飞机顶上来当主选**（否则第 1 天只能瞎排）")
    void flightBecomesPrimaryWhenThereIsNoTrain() {
        Legs.Options options = PlanService.optionsOf(noEdge(), found(plane(125), false));

        assertEquals(TransportMode.FLIGHT, options.primary().mode(),
                "排程需要'几点到'这个事实，而飞机是手里唯一的真实时刻");
        assertTrue(options.alternatives().isEmpty(),
                "它已经是主选了，再在备选里放一份就是同一条走法出现两次");
    }

    @Test
    @DisplayName("火车有、飞机没查到 → 只有火车，备选为空（不是 null）")
    void trainAloneHasEmptyAlternatives() {
        Legs.Options options = PlanService.optionsOf(found(train(394), true), noEdge());

        assertEquals(TransportMode.TRAIN, options.primary().mode());
        assertTrue(options.alternatives().isEmpty(), "空 list，调用方不必判空");
    }

    @Test
    @DisplayName("两个都没有 → 主选和备选都空（'这一段什么都没有'要能和'有一段飞机'分开）")
    void bothMissingIsEmpty() {
        Legs.Options options = PlanService.optionsOf(noEdge(), noEdge());

        assertNull(options.primary());
        assertTrue(options.alternatives().isEmpty());
        assertTrue(options.isEmpty());
    }

    @Test
    @DisplayName("飞机查询抛错（传进来 null）等同于没查到 —— 不能把火车那条已经算好的结论弄丢")
    void nullFlightMeansNothingFound() {
        Legs.Options options = PlanService.optionsOf(found(train(394), true), null);

        assertEquals(TransportMode.TRAIN, options.primary().mode());
        assertTrue(options.alternatives().isEmpty());
    }

    /* ============================================================
     * ③ 主选 ≠ 全部：prompt 只看主选
     * ============================================================ */

    /**
     * ⭐ "进 prompt 的只有一段"这件事**必须**被钉住。
     *
     * 模型要做的事只有一件：按主选的时刻决定"第 1 天最早几点开始、最后一天最晚几点收摊"。
     * 把备选也喂进去，它大概率会在 tips 里写一句"也可以考虑飞机"—— 一句我们**无法核对**的话
     * （价格和时刻都可能被它改写），却一个字都不会让排程更准。
     */
    @Test
    @DisplayName("⭐ 只查到一段时也只有一个 primary —— Legs 的 hasAny 看的是 primary")
    void onlyPrimaryCountsAsHavingALeg() {
        assertTrue(new Legs(new Legs.Options(plane(125), List.of()), Legs.Options.empty()).hasAny());
        assertFalse(new Legs(Legs.Options.empty(), Legs.Options.empty()).hasAny());
    }
}
