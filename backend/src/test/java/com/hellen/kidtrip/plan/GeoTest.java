package com.hellen.kidtrip.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 距离计算。
 *
 * 这里用的坐标**全部是从飞猪缓存里抄下来的真值**，不是手编的整数 ——
 * 理由和别处一样：一组好看的数字（"北京到上海 1000 公里"）只能证明公式没写反，
 * 证明不了它在我们真正拿到的这批数据上是准的。
 * 下面每个期望值旁边都写着它是从哪两个真实坐标算出来的。
 */
@DisplayName("距离：把'近不近'从猜测变成算术")
class GeoTest {

    /* ============================================================
     * 真实坐标对 —— 期望值是照着实测数据复算的
     * ============================================================ */

    @Test
    @DisplayName("⭐ 桂林那次排错的那段路：市区酒店 → 阳朔竹筏码头 = 42.4 公里")
    void theKuilinGapThatStartedAllThis() {
        // 地球村雅致漓江酒店（象山区民主路，实测 25.267589, 110.289587）
        // → 漓江竹筏漂流马山码头（阳朔兴坪，实测 24.932489, 110.490624）
        // 模型让用户第 1 天晚上住前者的位置、第 2 天 09:30 到后者。它全程不知道有这 42 公里。
        Double km = Geo.between(25.267589, 110.289587, 24.932489, 110.490624);

        assertEquals(42.4, km, 0.05, "这段路是这整件事的起因，它必须算出 42 公里上下");
    }

    @Test
    @DisplayName("模型靠酒店名字蒙对的那次：象鼻山 ↔ 独秀峰 = 1.59 公里")
    void theTwoSpotsItGuessedRight() {
        // 象鼻山景区（25.267579, 110.296573）↔ 桂林独秀峰王城景区（25.281648, 110.299118）
        // 这两个点在模型的第 1 天里挨着排（09:30 和 14:30），实际距离确实近 —— 所以那次是对的。
        assertEquals(1.59, Geo.between(25.267579, 110.296573, 25.281648, 110.299118), 0.05);
    }

    @Test
    @DisplayName("『紧邻海洋王国』这句是真话：企鹅酒店 ↔ 海洋王国 = 0.33 公里")
    void theHotelThatReallyIsNextDoor() {
        // 珠海长隆企鹅酒店（22.101716, 113.53857）↔ 长隆海洋王国（22.09875, 113.538507）
        assertEquals(0.33, Geo.between(22.101716, 113.53857, 22.09875, 113.538507), 0.05);
    }

    @Test
    @DisplayName("⭐ 42 公里和 0.33 公里要能被同一个函数分开 —— 这才是它存在的意义")
    void itSeparatesNearFromFar() {
        Double near = Geo.between(22.101716, 113.53857, 22.09875, 113.538507);
        Double far = Geo.between(25.267589, 110.289587, 24.932489, 110.490624);

        assertTrue(near < 1, "0.33 公里得算成『近』");
        assertTrue(far > 40, "42 公里得算成『远』");
    }

    /* ============================================================
     * 缺坐标 —— 返回 null，绝不返回 0
     * ============================================================ */

    @Test
    @DisplayName("⭐ 缺坐标一律 null。写成 0 等于把『不知道』排到最优先")
    void missingCoordsYieldNullNotZero() {
        // 0 公里的含义是"就在旁边"。一个坐标未知的候选要是被算成 0，
        // 它会在"换一个"清单里冲到第一行 —— 而我们对它其实一无所知。
        assertNull(Geo.between(null, 110.29, 25.26, 110.29), "缺纬度");
        assertNull(Geo.between(25.26, null, 25.26, 110.29), "缺经度");
        assertNull(Geo.between(25.26, 110.29, null, 110.29), "第二个点缺纬度");
        assertNull(Geo.between(25.26, 110.29, 25.26, null), "第二个点缺经度");
        assertNull(Geo.between(null, null, null, null), "两个点都没坐标");
    }

    @Test
    @DisplayName("⭐ 赤道上的 lat = 0 是**真坐标**，不能当成缺值")
    void equatorIsNotMissingData() {
        // 这条防的是 `if (lat != 0)` / `if (lat)` 那类写法 —— 它看起来完全正常，
        // 只是会把赤道上的点当成"没有坐标"。前端那份实现专门留了同样的注释。
        // (0,0) → (0,1) 正好是一个经度，赤道上 = 111.19 公里。
        Double km = Geo.between(0.0, 0.0, 0.0, 1.0);

        assertEquals(111.19, km, 0.1, "赤道不是缺值，它是个地方");
        assertTrue(Geo.has(0.0, 0.0), "has() 也必须承认 (0,0) 有坐标");
    }

    @Test
    @DisplayName("has()：只有两个都在才算有")
    void hasNeedsBoth() {
        assertTrue(Geo.has(25.26, 110.29));
        assertTrue(Geo.has(0.0, 0.0));
        assertFalse(Geo.has(null, 110.29));
        assertFalse(Geo.has(25.26, null));
        assertFalse(Geo.has(null, null));
    }

    @Test
    @DisplayName("同一个点 = 0 公里（这个 0 是真的 0，和缺坐标的 null 不是一回事）")
    void samePointIsZero() {
        assertEquals(0.0, Geo.between(25.267589, 110.289587, 25.267589, 110.289587), 1e-9);
    }

    @Test
    @DisplayName("距离是对称的 —— 谁在前谁在后都一样")
    void symmetric() {
        Double ab = Geo.between(25.267589, 110.289587, 24.932489, 110.490624);
        Double ba = Geo.between(24.932489, 110.490624, 25.267589, 110.289587);

        assertEquals(ab, ba, 1e-9);
    }

    /* ============================================================
     * 给模型看的那串文字
     * ============================================================ */

    @Test
    @DisplayName("label：一位小数 + km，缺坐标写『未知』")
    void labelFormat() {
        assertEquals("0.3km", Geo.label(0.33));
        assertEquals("42.4km", Geo.label(42.4052));
        assertEquals("15.0km", Geo.label(15.0));
        assertEquals("未知", Geo.label(null), "缺坐标要老实说不知道，不能写成 0.0km");
    }
}
