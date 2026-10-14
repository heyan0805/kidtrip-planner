package com.hellen.kidtrip.plan;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 生成进度登记表 —— 让那 25~60 秒**看得见**。
 *
 * ── 它解决的问题 ──────────────────────────────────────────────
 * 在这之前，一次 /generate 期间界面上只有一句「正在生成（要几十秒）…」。
 * 那是全流程体验最差的一段：用户不知道系统在干活还是卡死了，
 * 而这几十秒里后端其实走过了五段完全可以报出来的真实步骤。
 *
 * ── 四条设计决定，每条都是排除法排出来的 ────────────────────────
 *
 * ① **为什么是轮询，不是 SSE / WebSocket。**
 *    流式看起来更"正",但它要求把一个响应拆成多段，契约从"一个对象"变成
 *    "一串事件" —— 那是接口复杂度的一次跃升；而且这条产品要跑在内网穿透上
 *    （Cloudflare 免费隧道**明确不支持 SSE**），流式会在最要命的那一刻断掉。
 *    轮询只是"每隔一秒多问一件很小的事"，几十字节，任何代理都稳。
 *    **代价是进度最多晚 1 秒，换来的是它在任何链路上都不会挂。**
 *
 * ② **为什么进度挂在 runId 上，而不是一个"当前任务"的单例。**
 *    单实例部署下"当前任务"看着够用。但只要有两个人同时点生成，
 *    他们就会互相看到对方的进度 —— 而且这种错**不会报任何异常**，
 *    只会表现成"步骤来回乱跳"，是最难查的一类。
 *    由调用方带一个 runId 进来，成本是一个 UUID，换来的是天然隔离。
 *
 * ③ **为什么 runId 通过请求头传，而不是加进请求体。**
 *    请求体是 `PlanGenerateRequest`，它的形状被契约测试和前端类型**两头钉着**。
 *    进度是**旁路信息**：它不是这次生成要用的输入，只是"我想看它怎么跑"。
 *    把旁路信息塞进业务契约，会让"这个字段到底受哪份测试保护"变得说不清。
 *    请求头天然是"这次调用的元信息"，语义正好。
 *
 * ④ **为什么不做百分比。**
 *    五段的耗时完全不可预测（模型那两段随负载在 10~30 秒之间浮动），
 *    所以任何"已完成 68%"都只能是一个编出来的数字 ——
 *    而这个产品最不能容忍的就是"看起来很像真的、其实是编的"东西。
 *    这里只报**已经发生过的事实**：哪几步真的走完了、每步真实花了多久。
 *    已经走完的部分是真的，没走完的不猜。界面上因此也没有进度条，只有流水线。
 *
 * ── 它刻意不知道的事 ─────────────────────────────────────────
 * 它不知道每一段具体在干什么（那是 PlanService 的事），也不参与任何判断。
 * 编排层调它的方式只有三行：begin / mark / finish —— 打点是一行旁路，
 * **不改变流程里的任何一个决定**。这一点必须守住，否则"看进度的代码"
 * 迟早会开始影响"跑流程的代码"。
 */
@Component
public class GenerationProgress {

    /**
     * 一趟生成真正会走的五段，**顺序即执行顺序**。
     *
     * 它们不是"为了做进度条而切出来的" —— 每一个都是 PlanService 里本来就有的独立步骤，
     * 有自己的边界和失败语义。所以这里报出去的进度是**流程的投影**，不是装饰。
     *
     * label 直接写在这里，而不是留给前端映射：**同一份阶段定义只能有一处**。
     * 前端要是自己维护一份 key→文案的表，改了后端顺序而忘了改前端，
     * 界面就会按错的顺序打勾 —— 不报错，只是长期地错。
     */
    public enum Stage {
        LEGS("查城际车次"),
        PLAN_QUERIES("规划这次要查什么"),
        SEARCH("查飞猪的景点和酒店"),
        COMPOSE("挑景点、排日程"),
        VALIDATE("校验行程");

        private final String label;

        Stage(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 某一步此刻的状态。FAILED 只可能出现在当前正在跑的那一步上 */
    public enum StepState { PENDING, RUNNING, DONE, FAILED }

    /**
     * 进度接口的响应体。
     *
     * `tookMs` 为 null 的含义是**这一步还没走完**，不是"花了 0 毫秒" ——
     * 和契约里那条"缺坐标是 null 不是 0"是同一条规矩。
     */
    public record Step(String key, String label, StepState state, Long tookMs) {}

    public record Snapshot(List<Step> steps, long elapsedMs, boolean finished, boolean ok) {}

    private static final Stage[] ORDER = Stage.values();

    /**
     * 登记条的存活时间。5 分钟远大于任何一次生成，
     * 而它保证了一件事：**这个 Map 不会因为"没人来删"而慢慢长胖。**
     */
    static final long DEFAULT_TTL_MS = 5 * 60_000L;

    private final long ttlMs;
    private final Map<String, Entry> byRun = new ConcurrentHashMap<>();

    public GenerationProgress() {
        this(DEFAULT_TTL_MS);
    }

    /**
     * TTL 可注入的构造器，只为测试而存在 —— 有了它，"过期清理"这条能在几十毫秒里验完，
     * 而不是让测试去 sleep 五分钟（那种测试没人会跑，于是它保护不了任何东西）。
     * 生产永远走上面那个无参的，Spring 也只会选它。
     */
    GenerationProgress(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    private static final class Entry {
        final long startedAt = System.currentTimeMillis();
        /** 每一步的**开始**时刻。某一步的耗时 = 下一步的开始 - 这一步的开始 */
        final Map<Stage, Long> stepStart = new EnumMap<>(Stage.class);
        volatile Stage current;
        volatile long updatedAt = System.currentTimeMillis();
        volatile boolean finished;
        volatile boolean ok = true;
    }

    /**
     * 开始登记一趟生成。runId 为空时**什么都不做** ——
     * 脚本和单测直接调 PlanService.generate 时不该被迫先造一个 UUID。
     */
    public void begin(String runId) {
        if (blank(runId)) return;
        sweep();
        Entry e = new Entry();
        // 第一步的起点就是"开始干活"的时刻。generate 一进来紧接着就是查车次，
        // 中间只隔一次日期规范化（毫秒级），这个近似值得 ——
        // 它让第一秒就有东西可显示，而不是先黑一下。
        e.current = ORDER[0];
        e.stepStart.put(ORDER[0], e.startedAt);
        byRun.put(runId, e);
    }

    /**
     * 标记"现在开始做这一步"。
     *
     * entry 不存在时**顺手建一个**而不是静默丢弃：漏调 begin 只会让起点晚一点点，
     * 而静默丢弃会让整个进度面板莫名其妙地空着 —— 那种"没坏但没反应"的故障最难查。
     */
    public void mark(String runId, Stage stage) {
        if (blank(runId)) return;
        long now = System.currentTimeMillis();
        Entry e = byRun.computeIfAbsent(runId, k -> {
            Entry created = new Entry();
            created.current = stage;
            return created;
        });
        synchronized (e) {
            e.stepStart.putIfAbsent(stage, now);
            if (stage.ordinal() >= (e.current == null ? 0 : e.current.ordinal())) {
                e.current = stage;
            }
            e.updatedAt = now;
        }
    }

    /** 收尾。ok=false 表示这一趟没排出来（界面上当前步会标成失败，而不是假装走完了） */
    public void finish(String runId, boolean ok) {
        if (blank(runId)) return;
        Entry e = byRun.get(runId);
        if (e == null) return;
        synchronized (e) {
            e.finished = true;
            e.ok = ok;
            e.updatedAt = System.currentTimeMillis();
        }
    }

    /**
     * 读当前进度。**未知（或已过期）的 runId 返回 null**，不是抛异常也不是造一份空进度。
     *
     * 为什么这个区别重要：前端在生成结束后还会多问一次（轮询的最后一拍总要落地），
     * 那一刻拿到 null 正是它需要的信号 —— "这趟已经不在场上了"，安静停掉轮询即可。
     * 如果这里返回一份"全部待办"的空进度，前端会以为任务**回到了起点**，比报错更糟。
     */
    public Snapshot snapshot(String runId) {
        if (blank(runId)) return null;
        Entry e = byRun.get(runId);
        if (e == null) return null;

        synchronized (e) {
            long now = System.currentTimeMillis();
            if (now - e.updatedAt > ttlMs) {
                byRun.remove(runId);
                return null;
            }
            return new Snapshot(stepsOf(e), now - e.startedAt, e.finished, e.ok);
        }
    }

    private static List<Step> stepsOf(Entry e) {
        List<Step> out = new ArrayList<>(ORDER.length);
        int currentIdx = e.current == null ? -1 : e.current.ordinal();

        for (int i = 0; i < ORDER.length; i++) {
            Stage s = ORDER[i];
            Long start = e.stepStart.get(s);

            StepState state;
            if (start == null) {
                // ⚠️ 这一条必须排在最前面，它是**先判"有没有开始过"**而不是先判"结束没有"。
                //    顺序反了就会出一个很具体的错：在第 3 步失败时，第 4、5 步根本没被调用过，
                //    却会因为"整趟已结束"被标成走完了 —— 界面上会出现一条完整漂亮的流水线，
                //    而它描述的事情有两步从来没有发生。**进度显示自己成了编造源，是这个类唯一不能犯的错。**
                //    （这条由 GenerationProgressTest.unfinishedStepsStayPendingOnFailure 钉住，
                //      顺序调换后它会立刻红。）
                state = StepState.PENDING;
            } else if (e.finished) {
                // 失败时**只有当前那一步**标失败，前面的照常算走完了 ——
                // 它们确实完成过，把它们一起涂红会让用户以为整趟都白跑了。
                state = (i == currentIdx && !e.ok) ? StepState.FAILED : StepState.DONE;
            } else if (i < currentIdx) {
                state = StepState.DONE;
            } else {
                state = StepState.RUNNING;
            }

            Long took = null;
            if (start != null && state != StepState.RUNNING && state != StepState.PENDING) {
                Long next = (i + 1 < ORDER.length) ? e.stepStart.get(ORDER[i + 1]) : null;
                // 最后一步没有"下一步的开始"可减，用收尾时刻兜底。
                if (next == null && e.finished) next = e.updatedAt;
                if (next != null) took = Math.max(0, next - start);
            }

            out.add(new Step(s.name(), s.label(), state, took));
        }
        return out;
    }

    private void sweep() {
        long now = System.currentTimeMillis();
        byRun.entrySet().removeIf(en -> now - en.getValue().updatedAt > ttlMs);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
