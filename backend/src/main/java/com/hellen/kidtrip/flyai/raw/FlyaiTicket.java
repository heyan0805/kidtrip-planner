package com.hellen.kidtrip.flyai.raw;

/**
 * 景点上挂的门票商品。实测**每个景点最多一条**，而且是成人票。
 *
 * 这对带娃工具是硬约束：飞猪给不出儿童票 / 亲子套票的价格
 * （keyword-search 能搜到标题含"儿童票"的商品，但 price 恒为 null）。
 * 所以"两大一小"的准确门票成本**算不出来**，预算只能按成人票估并显式标注。
 */
public record FlyaiTicket(
        String itemId,
        /** "¥198" —— 带货币符号的**字符串**，不是数字 */
        String price,
        /** "大门票 成人票" */
        String ticketName,
        String priceDate,
        Double rate
) {
}
