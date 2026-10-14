package com.hellen.kidtrip.flyai.raw;

/**
 * search-hotel 返回的单条酒店。同样照实测原样声明。
 *
 * 实测价格覆盖是好的：深圳 10/10 全有价（¥25~¥121），珠海长隆附近 10/10（含 ¥998）。
 * 和景点不同，酒店这一侧不太会遇到"没有价格"的情况。
 */
public record FlyaiHotel(
        /** 酒店 id，如 "74895439" */
        String shId,
        String name,
        String address,
        String latitude,
        String longitude,
        String mainPic,
        String detailUrl,
        /** 同样是 "¥12" 这种字符串 */
        String price,
        /** ⚠️ 实测可能是 " "（**一个空格**）、"经济型"，也可能是 null。空格在 JS/Java 里都是真值！ */
        String star,
        Double rate,
        String brandName,
        String interestsPoi
) {
}
