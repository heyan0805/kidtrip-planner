package com.hellen.kidtrip.retrieval;

import java.util.List;

/**
 * 一次检索请求。字段名与前端 `types/plan.ts` 里的 `RetrievalPlan` **逐字一致**
 * （wantedCategories / keywords / needHotel），另外带上检索必需的上下文。
 *
 * city / startDate / days 三个是**检索**需要的（不是画像里"想不想"的字段）：
 *   city 决定查哪儿；startDate + days 决定酒店的入住/退房日期 ——
 *   不带日期也能查到价格，但那是"某个日期"的价格，拿来当预算会不准。
 *
 * 所有字段都可空：这个接口要能被"没有画像"的场景直接调用（排障、脚本）。
 * 服务里会先把空值补齐成默认值，之后全程只用补齐后的对象 ——
 * 也就是"把 null 挡在入口"，而不是让每个下游方法都去判一次 null。
 */
public record RetrievalRequest(
        String city,
        String startDate,
        Integer days,
        List<String> wantedCategories,
        List<String> keywords,
        Boolean needHotel,
        /**
         * 门票价格上限（元）。这是**规则覆盖**，不是画像字段 ——
         * 以后画像里有了 budget，就是把它填进来。
         * 现在放在请求里，是为了让"规则校验"这件事能被看见、能被验（否则默认不限价，看不出它在干活）。
         */
        Integer maxTicketPriceYuan
) {
}
