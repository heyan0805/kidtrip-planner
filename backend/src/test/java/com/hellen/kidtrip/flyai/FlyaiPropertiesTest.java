package com.hellen.kidtrip.flyai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 配置绑定的边界 —— 只钉一件事，但这件事很容易被"顺手优化"掉：
 *
 *   **`hotelStars` 为 null（配置里没写）和为空串（配置里写了 `""`）必须是两种不同的结果。**
 *
 * 看起来很吹毛求疵，但它是"我能不能做实测"的分界线：
 *   想验证"不带星级筛是什么样"，就得能把筛选**关掉**；
 *   如果空串也被当成"没配"而填回默认值 "4,5"，那这个开关就永远关不上，
 *   以后谁想复现"10 条里 6 条青旅"那个现象，只能去改代码。
 *
 * 这也是这个项目里少数几个**故意不把空值统一**的地方 ——
 * 规范化那层把四种空统一成 null 是为了少判空；这里是反过来，
 * 因为这两种空在语义上真的不一样。判据永远是语义，不是形式。
 */
class FlyaiPropertiesTest {

    /** maxLegMinutes 传 0 = "配置里没写"，让紧凑构造器去补默认值 */
    private static FlyaiProperties with(String hotelStars) {
        return new FlyaiProperties("node", "C:/x/flyai-bundle.cjs", 0, 0, 0, hotelStars, 0, null);
    }

    @Test
    @DisplayName("配置里没写 hotel-stars → 用默认 4,5（不能变成'不筛'）")
    void missingKeyFallsBackToDefault() {
        assertEquals("4,5", with(null).hotelStars());
    }

    @Test
    @DisplayName("配置里显式写空串 → 保持空串，表示'关掉星级筛'（不能填回默认）")
    void explicitEmptyMeansDisabled() {
        assertEquals("", with("").hotelStars());
    }

    @Test
    @DisplayName("配置里的值会 strip（yaml 里多打一个空格不该变成一个坏参数）")
    void valueIsTrimmed() {
        assertEquals("3,4,5", with(" 3,4,5 ").hotelStars());
    }

    @Test
    @DisplayName("cli-path 缺失 → 启动即失败，而不是运行到一半才报 No such file")
    void cliPathIsRequired() {
        assertThrows(IllegalStateException.class,
                () -> new FlyaiProperties("node", " ", 0, 0, 0, null, 0, null));
    }

    /* ============================================================
     * maxLegMinutes —— 和上面那条**正好相反**的取舍，所以单独说清
     * ============================================================ */

    @Test
    @DisplayName("max-leg-minutes 缺失/为 0 → 补成 180（3 小时）")
    void maxLegMinutesFallsBackTo180() {
        assertEquals(180, with(null).maxLegMinutes());
        assertEquals(180, new FlyaiProperties("node", "C:/x/f.cjs", 0, 0, 0, null, 0, null).maxLegMinutes());
    }

    @Test
    @DisplayName("max-leg-minutes 配了就照用 —— 想收紧到 90 分钟不用改代码")
    void maxLegMinutesIsRespected() {
        assertEquals(90, new FlyaiProperties("node", "C:/x/f.cjs", 0, 0, 0, null, 90, null).maxLegMinutes());
    }

    @Test
    @DisplayName("max-leg-minutes 传入负数 → 也补成默认值，不会变成一个'所有路线都不合格'的配置")
    void negativeMaxLegMinutesIsRejected() {
        // 这一条不是洁癖：如果放一个 -1 过去，LegService 那句 `minutes <= maxLegMinutes`
        // 会判定**每一段都不合格**，于是每份行程都会多一句"这段路带娃偏累"。
        // 用户会以为系统坏了，而根因只是一个写错的配置值 —— 静默地全线告警。
        assertEquals(180, new FlyaiProperties("node", "C:/x/f.cjs", 0, 0, 0, null, -1, null).maxLegMinutes());
    }

    @Test
    @DisplayName("它**没有**'关掉'这个状态 —— 空串在 hotelStars 上是'关掉'，在这里不是")
    void maxLegMinutesHasNoDisabledState() {
        // 记录一个刻意的设计不对称：hotelStars 可以关（那是"品味"，用户有权不要），
        // 而 maxLegMinutes 不能关 —— "不限时长"等于取消这个产品唯一的核心约束。
        // 想放宽就把数字调大。用 int 而不是 String 就是为了让"关掉"这件事表达不出来。
        assertEquals(180, new FlyaiProperties("node", "C:/x/f.cjs", 0, 0, 0, null, 0, null).maxLegMinutes());
    }

    /* ============================================================
     * cacheFile —— 唯一的要求是"别自己长出一个默认值"
     * ============================================================ */

    @Test
    @DisplayName("cache-file 没配 → 保持 null，**不给默认路径**")
    void cacheFileHasNoDefault() {
        // 这一条守的是"加一个功能不许改变没配它的人的行为"：
        // 一旦这里冒出一个默认路径（哪怕是个合理的路径），所有人的开发环境
        // 都会开始往磁盘上写文件，而他们并没有要求过这件事。
        // 落盘的真正判断（null / 空串 / 空白 都算不落盘）在 FlyaiCacheStore 里，
        // 这里只钉住"配置层不替用户做主"。
        assertNull(with(null).cacheFile());
    }

    @Test
    @DisplayName("空串和空白串照原样传下去，由缓存那层统一判成'不落盘'")
    void cacheFileEmptyIsPassedThrough() {
        assertEquals("", withCache("").cacheFile());
        // 不在这里 strip：路径的规范化只该有**一处**，
        // 而它属于真正使用这个路径的那个类（否则两个地方各 strip 一次，
        // 以后加个前缀拼接的规则就要改两处）。
        assertEquals("  ", withCache("  ").cacheFile());
    }

    @Test
    @DisplayName("配了路径就照用（原样，不做任何加工）")
    void cacheFilePathIsRespected() {
        assertEquals("D:/demo/flyai-cache.json", withCache("D:/demo/flyai-cache.json").cacheFile());
    }

    private static FlyaiProperties withCache(String cacheFile) {
        return new FlyaiProperties("node", "C:/x/f.cjs", 0, 0, 0, null, 180, cacheFile);
    }
}
