package com.hellen.kidtrip.flyai.raw;

/**
 * search-poi 返回的单条景点。**照着实测原样声明，不做任何美化。**
 *
 * 为什么类型要故意写得"丑"（latitude 是 String、price 是 String、poiLevel 是 String）：
 *   类型不是许愿池，是现状的快照。写 `double latitude` 不是"我希望它是数字"，
 *   而是**撒谎** —— 运行时的 JSON 里它就是 `"22.54159"`。
 *   而且 Java 的类型检查只在编译期有效，拦不住反序列化时的实际数据，
 *   唯一的结果是"读代码的人以为坐标是数字"。
 *
 *   写成 String 之后换来一条真护栏：规范化那一层必须显式 parse，
 *   谁也不能拿它直接去算距离。
 *
 * 注意：这里**没有**声明飞猪返回的全部字段（commissionMoneyYuan、skuCommissionStruct 等）。
 * 我们不用的字段就不声明 —— 依赖的字段越少，飞猪改字段时被弄坏的面积越小。
 * （前提是反序列化允许未知字段，见 CandidateNormalizer 里的说明。）
 */
public record FlyaiPoi(
        /** 景点 id，如 "149220"。**字符串**，不能当数字用 */
        String id,
        String name,
        String address,
        /** 实测宽查时可能是 null */
        String category,
        String description,
        /** "22.54159" */
        String latitude,
        /** "113.980295" */
        String longitude,
        String mainPic,
        /** ⚠️ 追踪短链，每次请求都重新生成（尾段是随机码）。不是稳定 URL，别当标识用 */
        String jumpUrl,
        /** "4" 或 null —— 同一个字段两种状态，且 4 是字符串 */
        String poiLevel,
        /** "深圳4A/5A景点榜第1名" 或 null */
        String listRank,
        String freePoiStatus,
        /** 可能整个是 null —— 含义是"这条景点在飞猪上没有可售门票商品" */
        FlyaiTicket ticketInfo
) {
}
