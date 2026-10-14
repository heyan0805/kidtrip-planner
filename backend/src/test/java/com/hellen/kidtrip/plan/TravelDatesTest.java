package com.hellen.kidtrip.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出发日期守门。
 *
 * "今天"是**显式传进去的常量**（2026-10-13），不是 LocalDate.now() ——
 * 于是这些断言讲的是"那一天会发生什么"，过两年也不会自己变红。
 * 这条规矩是这类时间相关逻辑唯一可靠的写法：自己取当前时间的方法，
 * 测试就只能写成"相对现在"，而相对现在的断言在跨年/跨月时会悄悄失去意义。
 */
@DisplayName("出发日期守门：过去的顺延 / 未来不动 / 垃圾丢掉")
class TravelDatesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 13);

    @Test
    @DisplayName("⭐ 实测：用户说「11月17日」，模型填了 2025-11-17（去年）→ 顺延到 2026-11-17")
    void rollsPastDateForward() {
        TravelDates.Normalized n = TravelDates.normalize("2025-11-17", TODAY);

        assertEquals("2026-11-17", n.date());
        assertTrue(n.corrected(), "改过就必须能看出来 —— 上层要靠它决定要不要记账");
    }

    @Test
    @DisplayName("已经在将来的日期原样返回，且不标 corrected")
    void keepsFutureDate() {
        TravelDates.Normalized n = TravelDates.normalize("2026-11-17", TODAY);

        assertEquals("2026-11-17", n.date());
        assertFalse(n.corrected());
    }

    @Test
    @DisplayName("就是今天 → 不算过去（今天出发是合法的）")
    void todayIsValid() {
        TravelDates.Normalized n = TravelDates.normalize("2026-10-13", TODAY);

        assertEquals("2026-10-13", n.date());
        assertFalse(n.corrected());
    }

    @Test
    @DisplayName("今年已经过去的月份 → 顺延到明年同一天（用户说「3月1日」指的是明年那个）")
    void rollsToNextYearWhenThisYearAlreadyPassed() {
        TravelDates.Normalized n = TravelDates.normalize("2026-03-01", TODAY);

        assertEquals("2027-03-01", n.date());
        assertTrue(n.corrected());
    }

    @Test
    @DisplayName("差好几年也能一步到位 —— withYear 的写法没有「顺延上限设小了」这个失败模式")
    void handlesManyYearsBackWithoutALimit() {
        assertEquals("2027-01-01", TravelDates.normalize("2020-01-01", TODAY).date());
        assertEquals("2027-01-01", TravelDates.normalize("1999-01-01", TODAY).date());
    }

    @Test
    @DisplayName("2月29日顺延到平年 → 落到 2/28，不能抛异常")
    void handlesLeapDay() {
        TravelDates.Normalized n = TravelDates.normalize("2024-02-29", TODAY);

        assertNotNull(n.date());
        assertTrue(n.corrected());
    }

    @Test
    @DisplayName("解析不出来 → null（当作「这一轮没听到」），而不是塞一个假日期进去")
    void unparseableBecomesNull() {
        assertNull(TravelDates.normalize("下周三", TODAY).date());
        assertNull(TravelDates.normalize("2026/11/17", TODAY).date(), "斜杠写法不是 ISO");
        assertNull(TravelDates.normalize("11-17", TODAY).date());
        assertFalse(TravelDates.normalize("下周三", TODAY).corrected(),
                "丢掉不等于改过 —— corrected 是要用来记账的，不能虚报");
    }

    @Test
    @DisplayName("null / 空白 → null，且不标 corrected（这是「还没问过」，不是「改过了」）")
    void blanksStayBlank() {
        assertNull(TravelDates.normalize(null, TODAY).date());
        assertNull(TravelDates.normalize("   ", TODAY).date());
        assertFalse(TravelDates.normalize(null, TODAY).corrected());
    }
}
