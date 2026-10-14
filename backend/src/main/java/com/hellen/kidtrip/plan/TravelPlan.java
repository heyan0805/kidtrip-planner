package com.hellen.kidtrip.plan;

import java.util.List;

/**
 * `POST /api/plan/generate` 的 data 字段。逐字镜像前端 `types/plan.ts` 的 `TravelPlan`。
 *
 * 注意 warnings 里有**三种来源**，装配时会合成一个数组（顺序：先我们的、后模型的）：
 *   ① 检索层的告警（没查到酒店、补查过一轮、有数据解析失败…）
 *   ② 装配层的告警（模型多排了几个点被截断、引用了不存在的编号被丢弃、同一景点排了两次…）
 *   ③ 模型自己写的注意事项（预约、天气、体力…）
 * 前两类是"系统对你说实话"，第三类是"模型给你建议"。它们混在一个字段里是妥协 ——
 * 更干净的做法是分开，但那要动契约；等界面上真要区别展示时再拆。
 */
public record TravelPlan(
        String summary,
        List<TravelDay> days,
        List<String> warnings,
        PlanProvenance provenance,
        /**
         * 这次检索到的**全部合格景点**，也就是界面上「换一个」的备选来源。
         *
         * ⚠️ 三条必须说清的性质，每一条都被误用过就会出问题：
         *
         * ① **它是"合格"的，不是"全部"的。** 被规则筛掉的（太贵、等级不够）不在这里 ——
         *    那些在 provenance.rejected 里躺着，理由也写着。两处混起来的话，
         *    用户会以为自己能把一个"因为太贵被筛掉"的点换回来。
         *
         * ② **它包含已经排进日程的那些点。** 乍看冗余（前端得自己减一遍），
         *    但这正是对的：用户把 A 换成 B 之后，A 就重新变成一个可选项了。
         *    如果后端发的是"减掉已用的"，那 A 就永远回不来了 ——
         *    而"换回来"是用户最需要的一个动作。
         *    所以这里的语义是"这次查到的池子"，切片的活交给前端（它才知道当前用了哪些）。
         *
         * ③ **它的顺序是飞猪的相关性顺序，我们没有重排。** 和挑景点用的是同一条规则。
         *    前端会按"和当天其它点近不近"再排一次 —— 那是**展示**顺序，不是数据的顺序，
         *    所以它留在前端；这里一旦排过，前端再排就会看到一份已经被加工过的输入。
         *
         * 它**不是**给模型看的（送进 prompt 的那份有 30 条上限，是另一件事）。
         * 这一份是给**用户**的：他换的时候要看的是全部选择，不是模型看过的那部分。
         */
        List<SpotOption> spotPool
) {

    public TravelPlan {
        days = days == null ? List.of() : List.copyOf(days);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        spotPool = spotPool == null ? List.of() : List.copyOf(spotPool);
    }
}
