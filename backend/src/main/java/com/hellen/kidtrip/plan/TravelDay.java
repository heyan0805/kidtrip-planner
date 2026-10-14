package com.hellen.kidtrip.plan;

import com.hellen.kidtrip.retrieval.Transfer;

import java.util.List;

/**
 * 一天的行程。逐字镜像前端 `types/plan.ts` 的 `TravelDay`。
 *
 * ── `transfer` 和 `hotel` 是一对，回答的是同一类问题 ──────────────
 *   transfer —— **今天怎么到的**（城际交通，真实车次）
 *   hotel    —— **今晚住哪**
 *
 * 两者都是"每一天都有落点"，而且都各有三种合法状态：
 *   transfer：① 有值（真实车次）；② null 且不是首/末日（理论上不会出现，单城市行程只有两段）；
 *             ③ null 且是首/末——**没查**或**同城**（出发地==目的地），见 warnings / 画像
 *   hotel   ：① 有值 ② null 且后面还有行程（没查到）③ null 且是最后一天（当天返程）
 *
 * ⚠️ 方向**不靠天数推断，而是写在 Transfer 自己的 from / to 里** ——
 *    因为"第 1 天就是去程、最后一天就是回程"这个规则只在单城市行程下成立。
 *    等以后真支持多城市串联，中间几天也会有 transfer，那时方向只能来自数据本身。
 *    现在就把方向放进数据结构，是为了让"以后加多城市"**不用改契约**。
 *
 * ── 为什么允许相邻天是同一家酒店（"连住"）──────────────────────
 *   这是带娃旅行最反直觉的一条 —— 出发前觉得"每天换个地方更有新鲜感"，
 *   实际换一次酒店等于废掉半天（收拾、退房、寄存、入住、孩子重新适应环境）。
 *   所以正确的形状是"每天有落点"而**不是**"每天换一家"。
 *   前端渲染时比较相邻两天的 hotelId，相同就合并显示成"连住"。
 *
 * ⚠️ 这里刻意**没有** `nights` / `sameAsPrevious` 这类冗余字段：
 *   它们和 hotelId 是同一个真相的两种说法，多一个就多一个能对不上的地方。
 *   前端一行比较就能算出来的东西，不要塞进契约。
 *   —— 但这条规矩的**边界**要看清楚：`Transfer.depDateTime` 里的日期就不是冗余
 *   （夜车跨午夜时，它是唯一能说清"到底哪天到"的东西）。**能算出来的不存，算不出来的必须存。**
 */
public record TravelDay(int day, String theme, List<TravelSpot> spots, Transfer transfer, HotelStay hotel) {

    public TravelDay {
        spots = spots == null ? List.of() : List.copyOf(spots);
    }
}
