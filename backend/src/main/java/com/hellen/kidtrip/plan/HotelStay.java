package com.hellen.kidtrip.plan;

/**
 * 一个晚上的住处。逐字镜像前端 `types/plan.ts` 的 `HotelStay`。
 *
 * 为什么它是**独立的一个字段**，而不是塞进 `TravelDay.spots` 里当第三种 kind：
 *   ① 一天最多只能有一家酒店，而一天有好几个游玩点 —— 数量语义不同；
 *   ② 它和景点**不共享字段**（有 star / rating，没有 time），塞在一起等于让两边都带一堆 null；
 *   ③ 界面上它是两种完全不同的卡片（景点是列表，住宿是一块"今晚住这儿"的落脚点）。
 *
 * ⚠️ 命名上最容易搞混的一点：这里的 `priceYuan` 是**每晚房价**，
 *   而 `TravelSpot.priceYuan` 是**单人门票**。两者数值量级接近（都是几百元），
 *   所以一旦在前端求和时写错一个变量名，会得出一份"看起来合理"的错误预算 —— 静默错误。
 *   前端做费用汇总时，必须分开算：门票 × 人数，房价 × 晚数。
 *
 * `tips` 是模型唯一的活（和 TravelSpot.tips 一样）：那是"表达"，不是"事实"。
 * 事实（名称、价格、坐标、预订链接）全部由后端从候选池回填。
 */
public record HotelStay(
        String name,
        /** 稳定酒店标识。连住判定和追溯靠它 —— 别用 bookUrl，那个每次请求都在变 */
        String hotelId,
        /** **每晚**房价（元）。null = 飞猪上没有可订的房型，不是"免费" */
        Double priceYuan,
        /** 已 trim。实测是**中文档次词**，不是数字："豪华型" / "高档型" / "四星级" / "舒适型" / "经济型"。
         *  ⚠️ 只有带上入离日期查询时上游才回传它，不带日期一律是 null。
         *  前端想用它做筛选/标记时，请按**字符串**比较（"经济型" = 青旅/日租公寓那一档），别去 parseInt。 */
        String star,
        /** ⚠️ 实测**恒为 null** —— 上游压根不下发评分（原始 rate 字段一直是 null）。
         *  保留这个字段是为了契约稳定，不是"暂时没填上"。不要拿它做排序或筛选，那样只会永远得到同一个顺序。 */
        Double rating,
        String address,
        Double lat,
        Double lng,
        String bookUrl,
        String imageUrl,
        /** 模型的表达：为什么今晚住这儿（离第二天第一个点近 / 连住不用收拾行李）。可能是 null */
        String tips
) {
}
