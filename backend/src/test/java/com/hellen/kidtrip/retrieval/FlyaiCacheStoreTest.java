package com.hellen.kidtrip.retrieval;

import com.hellen.kidtrip.flyai.FlyaiProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预跑缓存 —— 它存在的唯一理由是**演示当天上游不可用时还能跑**。
 *
 * 所以这个测试里最要紧的不是"能不能存下来"，而是那些**退让行为**：
 *   ① 没配路径时完全退回原状态（不许悄悄开始写文件）；
 *   ② 文件坏了 / 版本对不上 → 从空缓存起来，**不能让整个应用起不来**；
 *   ③ 过期只是"这次不用"，**不是把磁盘上的预跑结果删掉**。
 *
 * 为什么 ③ 要专门钉住：预跑是把额度提前花掉换来的，很可能只有一次机会
 * （上游限流，不是想跑几遍就跑几遍）。如果一次 TTL 设短了的运行把文件清了，
 * 到了现场就再也补不回来了 —— 而这个失效**完全静默**，只是"今天怎么全要重新查"。
 *
 * ── 造数据的方式（刻意的）────────────────────────────────────
 *   这里**不 sleep 等 TTL 过期**，而是直接手写一份 atMs 很旧的缓存文件。
 *   一来测试是确定性的、不依赖时钟精度；二来它顺带验证了"外部工具改过的文件也能读"这条真实场景。
 */
class FlyaiCacheStoreTest {

    /** 一个远未来的时间戳（约 2286 年）。用它表示"绝不过期" */
    private static final String NEVER_EXPIRES = "9999999999999";

    @TempDir
    Path dir;

    private FlyaiCacheStore store(String file, long ttlSeconds) {
        return new FlyaiCacheStore(new FlyaiProperties(
                "node", "C:/x/f.cjs", 0, 0, ttlSeconds, null, 180, file));
    }

    private Path cacheFile() {
        return dir.resolve("flyai-cache.json");
    }

    private void writeCache(Path file, String json) throws IOException {
        Files.writeString(file, json, StandardCharsets.UTF_8);
    }

    /* ============================================================
     * ① 没配路径 = 完全退回原状态
     * ============================================================ */

    @Test
    @DisplayName("没配路径 → 不落盘，但内存缓存照常工作（升级不该改变没配它的人的行为）")
    void withoutPathItStaysInMemory() {
        FlyaiCacheStore s = store(null, 600);

        assertFalse(s.persistent(), "空路径必须表示'不落盘'");
        assertTrue(s.get("k").isEmpty());

        s.put("k", List.of("search-train", "--origin", "广州"), "RAW");

        assertEquals("RAW", s.get("k").orElse(null), "内存那一层还是要生效的");
        s.shutdown();   // 不落盘也要关得掉，且不能抛
    }

    @Test
    @DisplayName("空串和空白串也算'不落盘'（不能被当成一个叫 '  ' 的文件名）")
    void blankPathIsNotAPath() {
        assertFalse(store("", 600).persistent());
        assertFalse(store("   ", 600).persistent());
    }

    /* ============================================================
     * ② 存下来 / 再读回来 —— 这才是"预跑"能不能成立
     * ============================================================ */

    @Test
    @DisplayName("⭐ 重启后还在：写进去的条目能跨进程读回来（预跑的整个前提）")
    void survivesRestart() throws IOException {
        Path file = cacheFile();
        List<String> args = List.of("search-train", "--origin", "广州", "--destination", "珠海");
        String key = FlyaiCacheStore.key(args);

        FlyaiCacheStore before = store(file.toString(), 600);
        assertTrue(before.persistent());
        before.put(key, args, "{\"data\":{\"itemList\":[]}}");
        before.shutdown();      // 模拟关停：最后一批必须刷出去

        assertTrue(Files.exists(file), "关停后文件就该在了");

        FlyaiCacheStore after = store(file.toString(), 600);
        assertEquals(1, after.size(), "新进程应该把预跑结果载进来");
        assertEquals("{\"data\":{\"itemList\":[]}}", after.get(key).orElse(null));
        after.shutdown();
    }

    @Test
    @DisplayName("落盘是原子的：不会留下一个写到一半的 .tmp 残骸")
    void leavesNoTempFileBehind() throws IOException {
        Path file = cacheFile();
        FlyaiCacheStore s = store(file.toString(), 600);
        s.put("k", List.of("a"), "RAW");
        s.shutdown();

        try (Stream<Path> files = Files.list(dir)) {
            List<String> names = files.map(p -> p.getFileName().toString()).toList();
            assertEquals(List.of("flyai-cache.json"), names,
                    "临时文件必须已经被换走，否则现场用错文件是查不出来的");
        }
    }

    /* ============================================================
     * ③ 过期 ≠ 删除（最容易顺手写错的一条）
     * ============================================================ */

    @Test
    @DisplayName("⭐ 条目过期 → 不用它，但**绝不把它从缓存里删掉**（预跑结果只有一次机会）")
    void expiredEntryIsIgnoredNotDeleted() throws IOException {
        Path file = cacheFile();
        // atMs = 1 毫秒 → 1970 年，无论 TTL 配多少都过期
        writeCache(file, "{\"version\":1,\"entries\":[{\"args\":[\"a\"],\"raw\":\"RAW\",\"atMs\":1}]}");

        FlyaiCacheStore s = store(file.toString(), 600);

        assertTrue(s.get("a").isEmpty(), "过期了就不该拿它去充当结果");
        assertEquals(1, s.size(),
                "⭐ 但它必须还在 —— 过期是'这次不用'，不是'删掉'。"
                        + "删掉的话，一次 TTL 设短的运行会把磁盘上的预跑成果清空，而那是补不回来的");
        s.shutdown();
    }

    @Test
    @DisplayName("过期的条目也不会因为一次关停就被写没（关停只写、不清）")
    void expiredEntrySurvivesShutdown() throws IOException {
        Path file = cacheFile();
        writeCache(file, "{\"version\":1,\"entries\":[{\"args\":[\"a\"],\"raw\":\"RAW\",\"atMs\":1}]}");

        store(file.toString(), 600).shutdown();

        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(text.contains("\"RAW\""), "关停之后磁盘上还得有它：" + text);
    }

    /* ============================================================
     * ④ 坏文件绝不能拦住启动
     * ============================================================ */

    @Test
    @DisplayName("⭐ 缓存文件不是合法 JSON → 当成空缓存起来，不抛异常（启动失败比丢缓存严重得多）")
    void corruptFileDoesNotBlockStartup() throws IOException {
        Path file = cacheFile();
        writeCache(file, "这不是 JSON {{{ 写到一半断电了");

        FlyaiCacheStore s = store(file.toString(), 600);

        assertEquals(0, s.size(), "读不出来就从空缓存开始 —— 只是这次没有加速，不是不能用");
        assertTrue(s.get("a").isEmpty());
        s.shutdown();
    }

    @Test
    @DisplayName("格式版本对不上 → 整份忽略（按老结构读出来的东西会'看起来对但不对'）")
    void versionMismatchIsIgnoredWhole() throws IOException {
        Path file = cacheFile();
        writeCache(file, "{\"version\":99,\"entries\":[{\"args\":[\"a\"],\"raw\":\"RAW\",\"atMs\":"
                + NEVER_EXPIRES + "}]}");

        assertEquals(0, store(file.toString(), 600).size(),
                "宁可不用，也不能拿一份结构不明白的数据去冒充事实");
    }

    @Test
    @DisplayName("⭐ 一条记录坏了只丢那一条，不牵连其余（整份判死会让一条脏数据毁掉整个预跑）")
    void oneBadEntryDoesNotKillTheRest() throws IOException {
        Path file = cacheFile();
        writeCache(file, "{\"version\":1,\"entries\":["
                + "{\"args\":[\"good\"],\"raw\":\"RAW\",\"atMs\":" + NEVER_EXPIRES + "},"
                + "{\"args\":[],\"raw\":\"X\",\"atMs\":" + NEVER_EXPIRES + "},"          // 没有 args
                + "{\"args\":[\"c\"],\"raw\":\"\",\"atMs\":" + NEVER_EXPIRES + "},"      // 没有内容
                + "{\"args\":[\"d\"],\"raw\":\"Y\",\"atMs\":0}"                          // 没有时间
                + "]}");

        FlyaiCacheStore s = store(file.toString(), 600);

        assertEquals(1, s.size(), "只有第一条是完整的");
        assertEquals("RAW", s.get("good").orElse(null));
        s.shutdown();
    }

    @Test
    @DisplayName("文件为空 → 从空缓存开始（编辑器新建的空文件不该被当成损坏）")
    void emptyFileIsFine() throws IOException {
        Path file = cacheFile();
        writeCache(file, "   ");

        assertEquals(0, store(file.toString(), 600).size());
    }

    @Test
    @DisplayName("文件不存在 → 静默从空开始（第一次跑预跑之前就是这个状态）")
    void missingFileIsFine() {
        assertEquals(0, store(dir.resolve("nope.json").toString(), 600).size());
    }

    /* ============================================================
     * ⑤ key 的构造
     * ============================================================ */

    @Test
    @DisplayName("⭐ 参数里含空格也不会撞 key（即使用空格当分隔符就能区分这两种参数）")
    void keyDoesNotCollideOnSpaces() {
        // 用普通空格当分隔符的话，下面这两组参数会拼成一模一样的 key，
        // 于是"查广州"的结果会被当成"查 广州"的结果返回 —— 静默串号。
        assertNotEquals(
                FlyaiCacheStore.key(List.of("a b", "c")),
                FlyaiCacheStore.key(List.of("a", "b c")));
    }

    @Test
    @DisplayName("参数顺序不同就是不同的 key（--origin A --destination B ≠ --origin B --destination A）")
    void keyIsOrderSensitive() {
        assertNotEquals(
                FlyaiCacheStore.key(List.of("--origin", "广州", "--destination", "珠海")),
                FlyaiCacheStore.key(List.of("--origin", "珠海", "--destination", "广州")));
    }
}
