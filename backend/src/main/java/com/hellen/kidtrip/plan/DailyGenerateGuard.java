package com.hellen.kidtrip.plan;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 每日生成闸门 —— 只对「妈妈群入口」生效，评委主入口不受影响。
 *
 * ── 为什么会有这个东西 ─────────────────────────────────────────
 * 作品要同时服务两类访客：评委（链接发在参赛 Issue 里，必须零门槛）
 * 和妈妈群（微信群扩散，人群不可控）。后者唯一按量计费的环节是百炼 token，
 * 唯一可能被风控的环节是飞猪查询 —— 都发生在「生成」这一步。
 * 所以闸门只拦 generate：聊天（chat）不计费也不碰飞猪，随便聊。
 *
 * ── 入口怎么区分 ───────────────────────────────────────────────
 * nginx 给 8080（口令入口）的转发统一加 `X-Entry: mama` 头；
 * 80 主入口不带。后端只认这个头 —— 两个入口共用同一个后端进程，
 * 限流是"入口的属性"，不是"用户的属性"（没有用户体系，见 README 的边界声明）。
 *
 * ── 实现上的三个刻意 ───────────────────────────────────────────
 * ① 计数在内存里：单实例部署，没有第二台机器要同步；
 *    重启清零可接受 —— 这个闸门防的是"失控"，不是"精确计量"。
 * ② 跨天清零用"发现日期变了就重置"，不靠定时任务 —— 少一个会坏的东西。
 * ③ limit <= 0 一律放行：给运维留一个"关掉闸门"的开关，不用改代码。
 *
 * ⚠️ 限额是"尝试次数"不是"成功次数"：失败的生成（比如上游超时）也占名额。
 *    这是刻意的 —— 重试风暴恰恰是它要防的东西。
 */
@Component
public class DailyGenerateGuard {

    private final int limit;
    private final AtomicInteger count = new AtomicInteger();
    private volatile LocalDate day = LocalDate.now();

    /** 环境变量直读（与 deploy/kidtrip.env 的 KIDTRIP_* 命名一致）；缺省 30，0 = 不限 */
    public DailyGenerateGuard(@Value("${KIDTRIP_DAILY_GENERATE_LIMIT:30}") int limit) {
        this.limit = limit;
    }

    /**
     * 尝试占用一个名额。
     *
     * @return null = 放行；非 null = 拒绝文案（直接回给用户看的话）
     */
    public String tryAcquire(String entryHeader) {
        // 只对妈妈群入口生效；主入口（评委）永远放行
        if (!"mama".equals(entryHeader)) return null;
        if (limit <= 0) return null;
        rollDayIfNeeded();
        // incrementAndGet 先占坑再判断：并发时宁可多拒一个（见上面"尝试次数"的取舍）
        if (count.incrementAndGet() > limit) {
            return "今天的体验名额已用完（每天 " + limit + " 次），明天再来吧";
        }
        return null;
    }

    /** 已用名额（观测用，测试用） */
    public int usedToday() {
        rollDayIfNeeded();
        return count.get();
    }

    private void rollDayIfNeeded() {
        LocalDate today = LocalDate.now();
        if (!today.equals(day)) {
            synchronized (this) {
                if (!today.equals(day)) {
                    day = today;
                    count.set(0);
                }
            }
        }
    }
}
