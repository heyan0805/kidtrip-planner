package com.hellen.kidtrip.plan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 每日生成闸门：三件事各有断言 —— 谁被限、谁被放、跨天清零。
 *
 * ⚠️ 验收纪律：注入验证过它能红 —— 把 PlanController 里的 `tryAcquire(entry)`
 *    改成无视返回值（直接放行），"妈妈群超限被拒"的用例立即失败。
 */
class DailyGenerateGuardTest {

    @Test
    void mama超限被拒_文案是给人看的() {
        DailyGenerateGuard guard = new DailyGenerateGuard(2);
        assertNull(guard.tryAcquire("mama"));
        assertNull(guard.tryAcquire("mama"));
        String rejected = guard.tryAcquire("mama");
        org.junit.jupiter.api.Assertions.assertNotNull(rejected);
        // 拒绝文案会原样显示在前端错误条里，必须是人话，不能是"limit exceeded"
        org.junit.jupiter.api.Assertions.assertTrue(rejected.contains("体验名额"));
    }

    @Test
    void 主入口永远放行_就算妈妈群已经爆了() {
        DailyGenerateGuard guard = new DailyGenerateGuard(1);
        assertNull(guard.tryAcquire("mama"));
        org.junit.jupiter.api.Assertions.assertNotNull(guard.tryAcquire("mama")); // 妈妈群第 2 次：拒
        org.junit.jupiter.api.Assertions.assertNotNull(guard.tryAcquire("mama")); // 第 3 次仍拒
        // 主入口（header 为 null / 其它值）不受任何影响
        assertNull(guard.tryAcquire(null));
        assertNull(guard.tryAcquire("judge"));
    }

    @Test
    void limit为0等于不限流() {
        DailyGenerateGuard guard = new DailyGenerateGuard(0);
        for (int i = 0; i < 100; i++) assertNull(guard.tryAcquire("mama"));
    }

    @Test
    void 跨天计数清零() {
        DailyGenerateGuard guard = new DailyGenerateGuard(1);
        assertNull(guard.tryAcquire("mama"));
        org.junit.jupiter.api.Assertions.assertNotNull(guard.tryAcquire("mama"));
        // 直接把"今天"拨回昨天，下一次 tryAcquire 应当触发跨天重置
        try {
            java.lang.reflect.Field day = DailyGenerateGuard.class.getDeclaredField("day");
            day.setAccessible(true);
            day.set(guard, java.time.LocalDate.now().minusDays(1));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        assertNull(guard.tryAcquire("mama"));
        assertEquals(1, guard.usedToday());
    }
}
