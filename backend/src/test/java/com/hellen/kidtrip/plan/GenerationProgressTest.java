package com.hellen.kidtrip.plan;

import com.hellen.kidtrip.plan.GenerationProgress.Snapshot;
import com.hellen.kidtrip.plan.GenerationProgress.Stage;
import com.hellen.kidtrip.plan.GenerationProgress.Step;
import com.hellen.kidtrip.plan.GenerationProgress.StepState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生成进度登记表。
 *
 * 这个类看起来"只是个 Map"，但它承担的责任不小：它是界面在那 25~60 秒里**唯一的信息来源**。
 * 一旦它报错，界面就会一本正经地显示一条假流水线 ——
 * 而"看起来很像真的、其实是编的"恰好是这个产品最不能出的错。
 * 所以下面这些用例针对的全是**它会怎么撒谎**，不是它能不能跑通。
 */
@DisplayName("生成进度：让那几十秒里的每一步都经得起核对")
class GenerationProgressTest {

    private static final String RUN = "run-1";

    /* ============================================================
     * 第一组：它会怎么撒谎（这一组才是这个类存在的意义）
     * ============================================================ */

    @Test
    @DisplayName("⭐ 中途失败时，后面没跑过的两步必须还是『待办』—— 不能涂成已完成")
    void unfinishedStepsStayPendingOnFailure() {
        GenerationProgress p = new GenerationProgress();
        p.begin(RUN);
        p.mark(RUN, Stage.PLAN_QUERIES);
        p.mark(RUN, Stage.SEARCH);      // 假设检索这一步炸了
        p.finish(RUN, false);

        Snapshot snap = p.snapshot(RUN);
        assertNotNull(snap);

        assertEquals(StepState.DONE, state(snap, Stage.LEGS), "失败之前这一步确实走完了，不该一起涂红");
        assertEquals(StepState.DONE, state(snap, Stage.PLAN_QUERIES));
        assertEquals(StepState.FAILED, state(snap, Stage.SEARCH), "炸掉的就是这一步");

        // ⭐ 这两行是这个用例的全部意义：Compose 和 Validate **从来没有被调用过**。
        //    如果它们在这里显示成 DONE，界面就会画出一条完整漂亮的流水线，
        //    而其中有两步从未发生。那不是显示不准，那是显示在编造。
        assertEquals(StepState.PENDING, state(snap, Stage.COMPOSE));
        assertEquals(StepState.PENDING, state(snap, Stage.VALIDATE));

        assertTrue(snap.finished());
        assertFalse(snap.ok());
    }

    @Test
    @DisplayName("⭐ 每一步的耗时是真量出来的，不是按公式摊的")
    void stepDurationIsMeasuredNotAssumed() throws Exception {
        GenerationProgress p = new GenerationProgress();
        p.begin(RUN);
        Thread.sleep(40);                       // 真的让它慢一会儿
        p.mark(RUN, Stage.PLAN_QUERIES);

        Long legMs = tookMs(p.snapshot(RUN), Stage.LEGS);

        assertNotNull(legMs, "上一步走完了，就该有耗时");
        assertTrue(legMs >= 30,
                "这一步实际花了 40 毫秒上下，报出来的不该是个漂亮数字（比如均分出来的几千毫秒）");
    }

    @Test
    @DisplayName("各步耗时之和不会超过总耗时 —— 流水线的时间账必须自洽")
    void stepDurationsAddUpToNoMoreThanTotal() throws Exception {
        GenerationProgress p = new GenerationProgress();
        p.begin(RUN);
        Thread.sleep(15);
        p.mark(RUN, Stage.PLAN_QUERIES);
        Thread.sleep(15);
        p.mark(RUN, Stage.SEARCH);
        Thread.sleep(15);
        p.mark(RUN, Stage.COMPOSE);
        Thread.sleep(15);
        p.mark(RUN, Stage.VALIDATE);
        p.finish(RUN, true);

        Snapshot snap = p.snapshot(RUN);
        long sum = snap.steps().stream()
                .filter(s -> s.tookMs() != null)
                .mapToLong(Step::tookMs)
                .sum();

        assertTrue(sum <= snap.elapsedMs(),
                "五步加起来比总耗时还长，说明某一步的耗时算到了别的时间上 —— " +
                        "这类错不会抛异常，只会让界面上的数字长期地对不上");
        assertTrue(sum > 0, "每步都 sleep 过了，总和不该是 0");
    }

    /* ============================================================
     * 第二组：调用方可以指望的保证
     * ============================================================ */

    @Test
    @DisplayName("没有 runId 时全部是空操作 —— 脚本和单测不该被迫先造一个 UUID")
    void blankRunIdIsANoOp() {
        GenerationProgress p = new GenerationProgress();

        // 这三种写法都不能抛异常，也不能在内部留下任何登记条
        p.begin(null);
        p.mark(null, Stage.SEARCH);
        p.finish(null, false);
        p.begin("");
        p.mark("  ", Stage.LEGS);
        p.finish("", true);

        assertNull(p.snapshot(null));
        assertNull(p.snapshot(""));
    }

    @Test
    @DisplayName("没登记过的 runId 返回 null，不是抛异常、也不是造一份空进度")
    void unknownRunReturnsNull() {
        GenerationProgress p = new GenerationProgress();
        assertNull(p.snapshot("从来没见过的-run"));

        // finish 一个不存在的 runId 同样不能炸 —— 上游可能在任何时刻放弃一趟生成
        p.finish("从来没见过的-run", false);
    }

    @Test
    @DisplayName("刚 begin：第一步在跑且没有耗时，其余全部待办")
    void freshRunShowsFirstStepRunning() {
        GenerationProgress p = new GenerationProgress();
        p.begin(RUN);

        Snapshot snap = p.snapshot(RUN);
        assertNotNull(snap);

        assertEquals(StepState.RUNNING, state(snap, Stage.LEGS));
        assertNull(tookMs(snap, Stage.LEGS), "还没走完的步，耗时必须是 null —— 0 会被读成『瞬间完成』");
        assertEquals(StepState.PENDING, state(snap, Stage.PLAN_QUERIES));
        assertEquals(StepState.PENDING, state(snap, Stage.VALIDATE));
        assertFalse(snap.finished());
        assertTrue(snap.ok());
    }

    @Test
    @DisplayName("走到第三步：前面打勾且有耗时，当前在跑且无耗时，后面待办")
    void middleOfTheRunLooksRight() {
        GenerationProgress p = new GenerationProgress();
        p.begin(RUN);
        p.mark(RUN, Stage.PLAN_QUERIES);
        p.mark(RUN, Stage.SEARCH);

        Snapshot snap = p.snapshot(RUN);

        assertEquals(StepState.DONE, state(snap, Stage.LEGS));
        assertEquals(StepState.DONE, state(snap, Stage.PLAN_QUERIES));
        assertNotNull(tookMs(snap, Stage.LEGS));
        assertNotNull(tookMs(snap, Stage.PLAN_QUERIES));

        assertEquals(StepState.RUNNING, state(snap, Stage.SEARCH));
        assertNull(tookMs(snap, Stage.SEARCH));

        assertEquals(StepState.PENDING, state(snap, Stage.COMPOSE));
        assertEquals(StepState.PENDING, state(snap, Stage.VALIDATE));
    }

    @Test
    @DisplayName("正常收尾：五步全部打勾，finished 且 ok")
    void successfulRunMarksEveryStepDone() {
        GenerationProgress p = new GenerationProgress();
        p.begin(RUN);
        p.mark(RUN, Stage.PLAN_QUERIES);
        p.mark(RUN, Stage.SEARCH);
        p.mark(RUN, Stage.COMPOSE);
        p.mark(RUN, Stage.VALIDATE);
        p.finish(RUN, true);

        Snapshot snap = p.snapshot(RUN);

        for (Step s : snap.steps()) {
            assertEquals(StepState.DONE, s.state(), s.label() + " 应该已经走完了");
            assertNotNull(s.tookMs(), s.label() + " 走完了就该有真实耗时");
        }
        assertTrue(snap.finished());
        assertTrue(snap.ok());
    }

    @Test
    @DisplayName("阶段顺序就是 Stage 的声明顺序 —— 界面靠它打勾，顺序错了界面就按错的顺序走")
    void stepsFollowEnumOrder() {
        GenerationProgress p = new GenerationProgress();
        p.begin(RUN);
        p.mark(RUN, Stage.SEARCH);

        List<Step> steps = p.snapshot(RUN).steps();
        Stage[] order = Stage.values();

        assertEquals(order.length, steps.size());
        for (int i = 0; i < order.length; i++) {
            assertEquals(order[i].name(), steps.get(i).key(),
                    "第 " + i + " 个报出来的步和 Stage 的声明顺序对不上");
            assertEquals(order[i].label(), steps.get(i).label(),
                    "文案由后端给，前端不该自己维护一份 key→文案 的表");
        }
    }

    @Test
    @DisplayName("迟到的打点不会让当前步倒退")
    void lateMarkDoesNotRewind() {
        GenerationProgress p = new GenerationProgress();
        p.begin(RUN);
        p.mark(RUN, Stage.SEARCH);
        p.mark(RUN, Stage.LEGS);        // 乱序/迟到的上报

        assertEquals(StepState.RUNNING, state(p.snapshot(RUN), Stage.SEARCH),
                "已经跑到第三步了，不该被一个迟到的第一步上报拽回去");
    }

    @Test
    @DisplayName("漏调 begin 时打点仍然生效，只是起点晚一点 —— 不能静默地什么都不显示")
    void markWithoutBeginStillRegisters() {
        GenerationProgress p = new GenerationProgress();
        p.mark(RUN, Stage.SEARCH);      // 故意不调 begin

        Snapshot snap = p.snapshot(RUN);
        assertNotNull(snap, "漏调 begin 不该让整个进度面板空着 —— 那种『没坏但没反应』最难查");
        assertEquals(StepState.RUNNING, state(snap, Stage.SEARCH));
    }

    /* ============================================================
     * 第三组：不会慢慢长胖，也不会答非所问
     * ============================================================ */

    @Test
    @DisplayName("过期后返回 null（前端据此安静收工），登记条也被清掉")
    void staleRunIsSweptAway() throws Exception {
        GenerationProgress p = new GenerationProgress(50);   // 50 毫秒就当它是"很久以前"
        p.begin(RUN);
        assertNotNull(p.snapshot(RUN));

        Thread.sleep(90);

        assertNull(p.snapshot(RUN), "过期的登记条该返回 null —— 前端会把它读成『这趟已经不在场上了』");
        assertNull(p.snapshot(RUN), "第一次读到过期时就该顺手清掉，而不是每次都重新判一遍");
    }

    @Test
    @DisplayName("生成的每一趟互不串台 —— 两个 runId 各自的进度是分开的")
    void twoRunsDoNotBleedIntoEachOther() {
        GenerationProgress p = new GenerationProgress();
        p.begin("run-a");
        p.begin("run-b");

        p.mark("run-a", Stage.COMPOSE);

        assertEquals(StepState.RUNNING, state(p.snapshot("run-a"), Stage.COMPOSE));
        assertEquals(StepState.RUNNING, state(p.snapshot("run-b"), Stage.LEGS),
                "另一个人的生成还停在第一步，不该看到别人的进度");
    }

    /* ============================================================
     * 小工具
     * ============================================================ */

    private static StepState state(Snapshot snap, Stage stage) {
        return stepOf(snap, stage).state();
    }

    private static Long tookMs(Snapshot snap, Stage stage) {
        return stepOf(snap, stage).tookMs();
    }

    private static Step stepOf(Snapshot snap, Stage stage) {
        return snap.steps().stream()
                .filter(s -> s.key().equals(stage.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("进度里没有 " + stage + " 这一步"));
    }
}
