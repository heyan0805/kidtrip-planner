package com.hellen.kidtrip.retrieval;

import com.hellen.kidtrip.flyai.FlyaiProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 飞猪调用的缓存 —— 内存一份，**可选地**再落一份到磁盘。
 *
 * ── 它为什么比"一个 Map"多出这么多东西 ───────────────────────────────
 *   纯内存缓存解决的是"同一轮里别重复花钱"。而这里要解决的是**另一个问题**：
 *   Demo 当天，上游调不通或者被限流时，整个演示还能不能继续。
 *   内存缓存救不了这个 —— 重启就没了，而重启恰恰是现场最常见的操作。
 *
 *   实测的上游脾气（2026-10-13）：并发 6 个 → 3 个 429；连续串行 6 个不间隔 →
 *   后 3 个 451（风控）；冷却 90 秒后串行 + 间隔 7 秒才全部成功。
 *   也就是说"多查几次"这件事本身是有额度上限的，**预跑把额度提前花掉，现场就不用花了**。
 *
 * ── 三个刻意的"退让"，每一个都对应一种现场事故 ─────────────────────
 *   ① **没配路径 = 完全不落盘**（保持加入这个类之前的行为）。
 *      不能因为"加了个缓存"就让所有人的开发环境开始往磁盘上写文件 ——
 *      升级一个组件不该改变它没说要改变的行为。
 *   ② **文件读不出来不能拦住启动。** 预跑结果坏掉是"这次的加速没了"，
 *      而启动失败是"整套东西没了"。两者代价不在一个量级，所以宁可空着起来 + 打一行日志。
 *      损坏的形态很多（写到一半断电、被编辑器改坏、手工编错），
 *      逐个识别不如统一"读失败就当没有"。
 *   ③ **写盘绝不发生在限流闸门里。** 见 {@link FlyaiCaller}：那把锁保护的是"同一时刻只有
 *      一个上游调用在跑"，而写一个几百 KB 的 JSON 要几十毫秒 —— 塞进锁里就等于
 *      每次查询都多几十毫秒的**串行**开销，把所有调用拖慢一截。
 *      所以这里是"标脏 + 后台定期落盘"，而不是 put 的时候就写。
 *
 * ── 文件格式为什么是"数组"而不是"以命令为 key 的对象" ──────────────────
 *   缓存的 key 是 `\u0001` 连接的参数列表（见 FlyaiCaller.key）。那个字符在 JSON 里
 *   能转义、能往返，但**人读不了** —— 而这份文件恰恰是人要检查的东西
 *   （"预跑到底有没有把 Demo 那几趟查进去？"）。所以存成显式的 args 数组，
 *   加载时再拼回 key。多花几百字节，换"打开文件就能看懂"。
 *
 * ── 读取用 JsonNode 手工遍历，而不是让 Jackson 直接反序列化成 record ──────
 *   反序列化 record 依赖编译时保留了参数名；而这份文件的正确性关系到 Demo 能不能跑，
 *   我不想让它依赖一个"构建配置没被改过"的隐含前提。
 *   手工遍历还有一个好处：**一条坏记录只丢那一条**，不会把整个文件判死。
 */
@Component
public class FlyaiCacheStore {

    private static final Logger log = LoggerFactory.getLogger(FlyaiCacheStore.class);

    /** 文件格式版本。以后改结构时靠它判断该怎么读，而不是靠猜 */
    private static final int FORMAT_VERSION = 1;

    /** key 的分隔符。用一个正常参数里绝不会出现的控制字符，避免 `--a b --c` 和 `--a --b c` 撞 key */
    static final String KEY_SEPARATOR = "\u0001";

    /** 落盘的节流周期。取 1 秒：一次 /generate 大约 4~8 次调用，能合并成一两次写盘 */
    private static final long FLUSH_INTERVAL_MS = 1_000;

    /** @param args 原始参数列表。留着它只为了能写回一个**人能看懂**的文件（见类注释） */
    private record Entry(List<String> args, String raw, long atMs) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    /** null = 不落盘 */
    private final Path file;
    private final long ttlMs;
    private final ObjectMapper mapper = new ObjectMapper();

    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final ScheduledExecutorService flusher;

    public FlyaiCacheStore(FlyaiProperties props) {
        this.ttlMs = props.cacheTtlSeconds() * 1000L;
        String configured = props.cacheFile();
        if (configured == null || configured.isBlank()) {
            this.file = null;
            this.flusher = null;
            log.info("飞猪缓存：仅在内存中（未配置 kidtrip.flyai.cache-file）");
            return;
        }
        this.file = Paths.get(configured.strip()).toAbsolutePath();
        int loaded = load();
        this.flusher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "flyai-cache-flush");
            // 守护线程：它不该拦住 JVM 退出。真正需要落盘的那一刻由 @PreDestroy 兜底。
            t.setDaemon(true);
            return t;
        });
        this.flusher.scheduleWithFixedDelay(this::flushIfDirty,
                FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
        log.info("飞猪缓存：已从 {} 载入 {} 条预跑结果（TTL {} 秒）", this.file, loaded, props.cacheTtlSeconds());
    }

    /**
     * 取一条**还没过期**的缓存。
     *
     * 过期只是"这次不用它"，而**不是**"把它删掉"：
     * 删掉的话，一次 TTL 设短的运行会把磁盘上的预跑结果清空 ——
     * 而那正是我们最不想丢的东西（现场可能只有那一次机会去预跑）。
     * 所以这里只判不删，文件里的内容始终保留到被同 key 的新结果覆盖。
     */
    public Optional<String> get(String key) {
        Entry hit = entries.get(key);
        if (hit == null) {
            return Optional.empty();
        }
        if (System.currentTimeMillis() - hit.atMs() >= ttlMs) {
            return Optional.empty();
        }
        return Optional.of(hit.raw());
    }

    /** 记一条。只标脏，真正的写盘交给后台线程（见类注释 ③） */
    public void put(String key, List<String> args, String raw) {
        entries.put(key, new Entry(List.copyOf(args), raw, System.currentTimeMillis()));
        dirty.set(true);
    }

    /** 当前缓存条数。给启动日志和预跑脚本用 —— "预跑有没有生效"要看得出数字 */
    public int size() {
        return entries.size();
    }

    /** 是不是落了盘。预跑脚本靠它判断"现在这个进程会不会把结果留下来" */
    public boolean persistent() {
        return file != null;
    }

    /* ============================================================
     * 磁盘读写 —— 全部"失败就退让"，绝不抛到调用方
     * ============================================================ */

    /**
     * 从磁盘读。
     *
     * @return 成功载入的条数；文件不存在 / 读坏 / 整个解析不出来都返回 0，
     *         并且**不抛异常** —— 见类注释 ②。
     */
    private int load() {
        if (!Files.exists(file)) {
            log.info("预跑缓存文件 {} 还不存在，从空缓存开始（跑一次预跑就会生成）", file);
            return 0;
        }
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("预跑缓存文件 {} 读不出来（{}），这次就从空缓存开始 —— 不影响启动", file, e.getMessage());
            return 0;
        }
        if (text.isBlank()) {
            return 0;
        }

        JsonNode root;
        try {
            root = mapper.readTree(text);
        } catch (JacksonException e) {
            log.warn("预跑缓存文件 {} 不是合法 JSON（{}），整份忽略 —— 不影响启动。"
                    + "跑一次预跑脚本就会覆盖成新的", file, e.getMessage());
            return 0;
        }

        int version = root.path("version").asInt(0);
        if (version != FORMAT_VERSION) {
            // 版本对不上宁可不用：结构变了之后按老结构读，读出来的东西"看起来对但不对"，
            // 而缓存错位是最难查的一类问题（不报错，只是数字不对）。
            log.warn("预跑缓存文件 {} 的格式版本是 {}，本程序认的是 {} —— 整份忽略，跑一次预跑即可重建",
                    file, version, FORMAT_VERSION);
            return 0;
        }

        int loaded = 0;
        int skipped = 0;
        for (JsonNode node : root.path("entries")) {
            List<String> args = new ArrayList<>();
            for (JsonNode a : node.path("args")) {
                args.add(a.asString(""));
            }
            String raw = node.path("raw").asString("");
            long atMs = node.path("atMs").asLong(0L);
            if (args.isEmpty() || raw.isEmpty() || atMs <= 0L) {
                skipped++;      // 一条坏记录只丢那一条，不牵连整份文件
                continue;
            }
            entries.put(key(args), new Entry(args, raw, atMs));
            loaded++;
        }
        if (skipped > 0) {
            log.warn("预跑缓存里有 {} 条记录不完整，已跳过（其余 {} 条照常可用）", skipped, loaded);
        }
        return loaded;
    }

    private void flushIfDirty() {
        if (!dirty.compareAndSet(true, false)) {
            return;
        }
        write();
    }

    /**
     * 写盘。先写 `.tmp` 再原子替换 —— 中途断电时，磁盘上要么是**旧的完整文件**，
     * 要么是**新的完整文件**，不会出现一个被写了一半的。
     * （这正是类注释 ② 里"写到一半断电"那种损坏的预防，比事后识别便宜得多。）
     */
    private void write() {
        if (file == null) {
            return;
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", FORMAT_VERSION);
        List<Map<String, Object>> list = new ArrayList<>();
        for (Entry e : entries.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("args", e.args());
            item.put("raw", e.raw());
            item.put("atMs", e.atMs());
            list.add(item);
        }
        root.put("entries", list);

        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(tmp, mapper.writeValueAsString(root), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // 有些文件系统（部分网络盘）不支持原子替换。退化成普通替换：
                // 丢掉一点原子性，好过完全存不下来。
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | JacksonException e) {
            // 写不进去也**不能影响这次查询** —— 调用方已经在用真实结果了，
            // 缓存没存上只是"下次还得再查一遍"，而抛出去会变成"这次就失败了"。
            log.warn("预跑缓存写盘失败（{}）：{} —— 本次查询不受影响", file, e.getMessage());
            dirty.set(true);    // 没存上，下个周期再试
        }
    }

    /**
     * 关停时把最后一批刷出去。
     *
     * 为什么需要它：后台线程是 1 秒一次，而"最后一次查询"离关停可能不到 1 秒 ——
     * 那一条就丢了。对一个**专门用来抗现场故障**的缓存来说，"最后一条没存上"
     * 是最讽刺的一种失效：预跑跑了 20 条，Demo 就用最后那一条，而它偏偏不在文件里。
     */
    @PreDestroy
    void shutdown() {
        if (flusher != null) {
            flusher.shutdownNow();
        }
        if (file != null && dirty.get()) {
            write();
        }
    }

    static String key(List<String> args) {
        return String.join(KEY_SEPARATOR, args);
    }
}
