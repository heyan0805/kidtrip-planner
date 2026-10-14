package com.hellen.kidtrip.retrieval;

/**
 * 一段**真实的**城际交通：从 `from` 到 `to`，坐哪趟车、几点发几点到、多久、多少钱。
 *
 * 为什么要有这个东西（它解决的问题）：
 *   在这之前，行程里"怎么去"这一行**根本没有**，或者由模型编。
 *   而"带孩子不能长途"是这个项目唯一的身份约束 —— 一个约束如果没有任何真实数字支撑，
 *   它就只是一句宣传语。`durationMinutes` 就是那句话的落点。
 *
 * ── 两个字段为什么是日期时间而不是"08:36" ────────────────────────
 *   `depDateTime` / `arrDateTime` 是 ISO（"2026-11-17T08:36"），不是纯时刻。
 *   看着像冗余（哪一天不是已经在 TravelDay.day 里了吗），但**夜车会跨过午夜**：
 *   23:50 发、次日 00:40 到，只存 "23:50" / "00:40" 的话，前端会把它画成"提前到了"。
 *   这类"看不出来但确实错"的字段，正是 TravelDay 那边拒绝加 `nights` 的同一条标准 ——
 *   只不过那边的结论是"能算出来的不要存"，这边的结论是"**算不出来的必须存**"。
 *
 * ── 为什么 `direct` / `transferCity` 要显式存，而不是让前端去数 ────
 *   上游一条 journey 的 `segments` 在中转时是 N 段，到达站是**中转站**而不是目的地。
 *   把 first/last 的挑选规则放前端，等于把"哪些字段能信"的知识复制一份到另一门语言里。
 *   选好了再传下去，前端只负责显示。
 *
 * `priceYuan` 是**这一段的**价格，不是往返总价。
 *   飞猪的往返查询（`--back-date`）给的是一个合计价（实测 ¥140.00 = 单程 ¥70 × 2），
 *   拆不出来 —— 所以取单程查询，让每个数字都能对得上。
 */
public record Transfer(
        /** 出发城市（画像里的 origin） */
        String from,
        /** 到达城市（画像里的 city） */
        String to,
        /** "广州南" —— 上车车站短名 */
        String depStation,
        /** "珠海" —— 下车车站短名；中转时是**最终**到达站 */
        String arrStation,
        /** ISO 本地日期时间 "2026-11-17T08:36" —— 带日期，因为夜车会跨午夜 */
        String depDateTime,
        String arrDateTime,
        /** 整段耗时（分钟）。中转时含候车，所以它 ≥ 各段 duration 之和 */
        Integer durationMinutes,
        /** 这一段的车票价格（元）。取不到时为 null —— 不要填 0，"不知道"和"免费"不是一回事 */
        Double priceYuan,
        /** "动车D947" —— 车次，已把车型和编号拼好（上游是分开两个字段，单看编号分不出高铁还是动车） */
        String trainNo,
        /** "二等座" */
        String seatClass,
        /** true = 一趟直达；false = 中途换乘（换乘站见 transferCity） */
        boolean direct,
        /** 换乘站名，直达时为 null */
        String transferCity,
        /** 飞猪的购票跳转链接 —— 演示时"点这里就能买"比任何文案都直接 */
        String jumpUrl
) {
}
