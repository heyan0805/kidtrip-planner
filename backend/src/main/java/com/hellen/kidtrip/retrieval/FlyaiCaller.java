package com.hellen.kidtrip.retrieval;

import com.hellen.kidtrip.flyai.FlyaiException;
import com.hellen.kidtrip.flyai.FlyaiProperties;
import com.hellen.kidtrip.flyai.FlyaiRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * **所有**飞猪调用的唯一出口。存在的理由只有一条：**限流闸门必须是同一把锁。**
 *
 * ── 为什么这件事必须靠"同一个对象"来保证，而不能靠"两个类都记得串行" ─────────
 *   实测（2026-10-13）：
 *     并发 6 个 → 3 个 429；连续串行 6 个不间隔 → 后 3 个 451（风控）；
 *     冷却 90 秒后，串行 + 间隔 7 秒的 4 次调用**全部成功**。
 *   所以"串行 + 留间隔"不是优化，是**这套上游能不能用**的前提。
 *
 *   而串行只在一个进程里、一把锁下才成立。如果车次查询另起一个类、各自持一把锁，
 *   那么"景点检索"和"车次检索"就在结构上允许同时发出 —— 单看每个类它都是对的，
 *   合起来就破了这条唯一的保命机制。**这类失效查起来极贵**：日志里每个请求都正常，
 *   只有偶发的 429，而且只在"那一轮恰好两个都走到"时出现。
 *
 *   把它抽成一个共享 bean，"同一把锁"就从**约定**变成了**构造上不可能违反**。
 *   （和"编号空间必须分离（C / H）"是同一种修法：把纪律变成类型/对象结构，而不是注释。）
 *
 * ── 它**不**负责的：记账与存储 ───────────────────────────────────
 *   缓存命中算不算额度、这次查询该记在哪张账上 —— 各调用方的账目口径本来就不一样
 *   （景点检索要分 queries / upstreamCalls，溯源面板要看"这次到底花了多少"）。
 *   所以这里只回一个 `Result(raw, fromCache)`，把"怎么记账"留在知道账本长什么样的一侧。
 *   这和"每个字段在哪一层被加工，取决于那一层是不是唯一知道该怎么做判断的人"是同一条。
 *
 *   缓存本身也搬走了（见 {@link FlyaiCacheStore}）："存哪儿、存多久、要不要落盘"
 *   是另一件会独立变化的事 —— Demo 之前需要它落盘，平时不需要。
 *   于是这个类只剩下"按 key 查一下，没有就问上游"，换存储策略不用碰限流这份逻辑。
 */
@Component
public class FlyaiCaller {

    private static final Logger log = LoggerFactory.getLogger(FlyaiCaller.class);

    /** 被限流后的退避基数：第 1 次等 15 秒，第 2 次等 30 秒 */
    private static final long RATE_LIMIT_BACKOFF_MS = 15_000;
    private static final int MAX_RATE_LIMIT_RETRIES = 2;

    private final FlyaiRunner runner;
    private final FlyaiProperties props;
    private final FlyaiCacheStore cache;

    /**
     * 限流闸门。
     * 它同时干两件事：① 保证两次调用之间有最小间隔；② **把所有调用串起来**（同一时刻只有一个在跑）。
     * 用一把锁而不是用"计算间隔"的写法，是因为后者有竞态：两个线程同时读到旧的 lastCallAtMs，
     * 就都会认为自己不用等 —— 于是"串行"这件事在最需要它的时刻失效。
     */
    private final Object gate = new Object();
    private long lastCallAtMs = 0L;

    public FlyaiCaller(FlyaiRunner runner, FlyaiProperties props, FlyaiCacheStore cache) {
        this.runner = runner;
        this.props = props;
        this.cache = cache;
    }

    /**
     * 一次调用的结果。
     *
     * `fromCache` 必须回传，因为"这次有没有花额度"是调用方**唯一**算不出来的信息 ——
     * 额度账（upstreamCalls）和查询账（queries）必须分开，混起来会让人以为还有余量。
     */
    public record Result(String raw, boolean fromCache) {
    }

    /**
     * 跑一次飞猪 CLI，带缓存与限流退避。
     *
     * @throws FlyaiException 起不来 / 超时 / 退出码非 0 / 输出为空 —— 退避重试用尽后原样抛出，
     *                        由调用方决定这是"这次查询失败"还是"整轮失败"（两条路径的出路不同）
     */
    public Result call(List<String> args, Deadline deadline) {
        String key = FlyaiCacheStore.key(args);

        Optional<String> hit = cache.get(key);
        if (hit.isPresent()) {
            return new Result(hit.get(), true);
        }

        int attempt = 0;
        while (true) {
            FlyaiException rateLimited = null;
            synchronized (gate) {
                try {
                    long gap = lastCallAtMs + props.minGapMs() - System.currentTimeMillis();
                    if (gap > 0) {
                        sleep(Math.min(gap, Math.max(0, deadline.remainingMs())));
                    }
                    String raw = runner.run(args);
                    lastCallAtMs = System.currentTimeMillis();
                    // 放进这把锁里是**故意**的，而且安全：
                    //   ① `FlyaiCacheStore.put` 只写内存 + 标脏，**不做任何 IO** ——
                    //      真正的落盘在它的后台线程里（见那个类的类注释 ③）。
                    //      所以在锁里调它不会让上游调用为磁盘等待。
                    //   ② 更重要的是：缓存动作留在这里，"查过就记住"就是**结构上必然发生**的，
                    //      而不是"调用方得记得多调一次 remember()"。
                    //      后者是这个工程反复踩过的形状 —— 纪律一旦写成注释，
                    //      就总有一个新增的调用方会漏掉，而且漏了不报错、只是永远缓存不上。
                    cache.put(key, args, raw);
                    return new Result(raw, false);
                } catch (FlyaiException e) {
                    // 失败的调用**也占用了上游额度**（实测：被 429 拒绝后紧接着再发，还是被拒）。
                    // 所以时间戳照样要更新，否则下一次会立刻又撞上去。
                    lastCallAtMs = System.currentTimeMillis();
                    if (!e.isRateLimited() || attempt >= MAX_RATE_LIMIT_RETRIES) {
                        throw e;
                    }
                    rateLimited = e;
                }
            }
            attempt++;
            long backoff = RATE_LIMIT_BACKOFF_MS * attempt;
            log.warn("被飞猪限流，等 {} 毫秒后重试（第 {}/{} 次）：{}",
                    backoff, attempt, MAX_RATE_LIMIT_RETRIES, rateLimited.getMessage());
            sleep(Math.min(backoff, Math.max(0, deadline.remainingMs())));
            if (deadline.expired()) {
                throw rateLimited;
            }
        }
    }

    private static void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FlyaiException("检索被中断");
        }
    }
}
