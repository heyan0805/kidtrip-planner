package com.hellen.kidtrip.retrieval;

/**
 * 时间预算。
 *
 * 它约束的不只是"查多久"，还有**退避能等多久** ——
 * 一个被限流的请求如果傻等 30 秒 × 2 次，用户体验比"直接告诉用户稍后再试"差得多。
 *
 * 从 RetrievalService 的私有嵌套类提成包级顶层类型（2026-10-13，接入 search-train 时）：
 * 因为现在**有两个**服务要用它（景点/酒店检索、城际车次），而它本身没有任何状态，
 * 谁都可以造一个。留在某一个类里当私有嵌套类，另一个就只能自己再造一份 ——
 * 两份"时间预算"的定义迟早会分叉。
 */
record Deadline(long atMs) {

    static Deadline after(long budgetMs) {
        return new Deadline(System.currentTimeMillis() + budgetMs);
    }

    long remainingMs() {
        return atMs - System.currentTimeMillis();
    }

    boolean expired() {
        return remainingMs() <= 0;
    }
}
