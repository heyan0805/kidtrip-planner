package com.hellen.kidtrip.retrieval;

import com.hellen.kidtrip.candidate.HotelCandidate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检索层里那几个"只改记账、不改数据"的决策方法。
 *
 * 为什么要给它们写测试 —— 这类方法有两个特点：
 *   ① 写错了**不会报错**，只会让用户看到一份"说不出哪里不对"的结果；
 *   ② 平时根本不会走到（哨兵只在"上游行为变了"时才响）。
 * 两者叠加的后果是：它可能从写下的那天起就再没被执行过一次。
 * 所以边界必须用测试钉住，而不是靠"上线了没问题"。
 */
class RetrievalServiceTest {

    private static HotelCandidate hotel(String name, String star) {
        return new HotelCandidate("h-" + name, name, "地址", 22.5, 114.0, 200.0, star, null, "", "");
    }

    private static RetrievalService.Run freshRun() {
        return new RetrievalService.Run();
    }

    @Test
    @DisplayName("哨兵：经济型占一半 → 告警（这条是'星级筛失效'的唯一信号）")
    void warnsWhenHalfAreBudgetTier() {
        var run = freshRun();
        RetrievalService.warnIfBudgetHeavy(List.of(
                hotel("舒坦时光青年公寓", "经济型"),
                hotel("百联住宿公寓", "经济型"),
                hotel("皇冠假日", "豪华型"),
                hotel("希尔顿花园", "高档型")), run);

        assertEquals(1, run.warnings.size(), "4 家里 2 家经济型 = 一半，该报");
        assertTrue(run.warnings.get(0).contains("2 家是经济型"));
    }

    @Test
    @DisplayName("哨兵：经济型只占少数 → 不告警（筛生效时的正常状态，别没事找事）")
    void staysQuietWhenFewAreBudgetTier() {
        var run = freshRun();
        RetrievalService.warnIfBudgetHeavy(List.of(
                hotel("皇冠假日", "豪华型"),
                hotel("希尔顿花园", "高档型"),
                hotel("卡瑞酒店", "高档型"),
                hotel("某青旅", "经济型")), run);

        assertEquals(0, run.warnings.size(), "1/4 不该报警 —— 报多了用户会当成噪音忽略掉");
    }

    @Test
    @DisplayName("哨兵：全部经济型 → 告警（上游完全忽略筛选的情形）")
    void warnsWhenAllAreBudgetTier() {
        var run = freshRun();
        RetrievalService.warnIfBudgetHeavy(List.of(
                hotel("365青年旅舍", "经济型"),
                hotel("悦梦小舍青年旅舍", "经济型"),
                hotel("半日闲全女青年公寓", "经济型")), run);

        assertEquals(1, run.warnings.size());
        assertTrue(run.warnings.get(0).contains("3 家"));
    }

    @Test
    @DisplayName("哨兵：空列表 → 不告警也不崩（0/0 不能当成'占比 100%'）")
    void handlesEmptyList() {
        var run = freshRun();
        RetrievalService.warnIfBudgetHeavy(new ArrayList<>(), run);
        assertEquals(0, run.warnings.size());
    }

    @Test
    @DisplayName("哨兵：star 全为 null（没带日期查）→ 不告警，因为'未知'不等于'经济型'")
    void nullStarIsNotBudgetTier() {
        var run = freshRun();
        RetrievalService.warnIfBudgetHeavy(List.of(
                hotel("某酒店", null),
                hotel("某公寓", null)), run);

        assertEquals(0, run.warnings.size(),
                "null 是'上游没告诉我们'，不是'经济型' —— 把它当经济型会让这条告警在小城市天天误报");
    }
}
