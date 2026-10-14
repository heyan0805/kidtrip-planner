package com.hellen.kidtrip.candidate;

/**
 * 规范化之后的景点候选 —— 校验层的输入。
 *
 * 和 FlyaiPoi 的差别就是"规范化"这三个字的全部含义：
 *   "¥198"     → 198.0     （能比大小了）
 *   "22.54159" → 22.54159  （能算距离了）
 *   "4"        → 4         （能比等级了）
 *   " "        → null      （空字符串的三种形态统一掉）
 * 从这一层往下，全工程再也不会遇到字符串数字。
 */
public record PoiCandidate(
        String poiId,
        String name,
        String address,
        String category,
        Double lat,
        Double lng,
        Double priceYuan,
        /** 1~5，原始可能是 null */
        Integer level,
        String rank,
        /** 门票商品 id。null = 这条景点在飞猪上没有可订的票 */
        String itemId,
        String ticketName,
        String imageUrl,
        String bookUrl
) implements Candidate {

    @Override
    public String getKind() {
        return "poi";
    }

    @Override
    public String stableId() {
        return poiId;
    }
}
