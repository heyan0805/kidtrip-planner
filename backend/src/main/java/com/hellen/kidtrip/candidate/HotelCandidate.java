package com.hellen.kidtrip.candidate;

/**
 * 规范化之后的酒店候选。
 *
 * 特别注意 star 这一栏：实测原始值可能是 " "（**一个空格**）、"经济型"，也可能是 null。
 * 空格在 Java 里是**真值**（`if (star != null)` 会把它放过去），
 * 于是"这家酒店没有星级信息"被当成了"这家酒店有星级" —— 界面上的"待确认"会错成"有星级"。
 * 所以规范化那层必须 trim 之后再判空，把三种"空"（"" / " " / null）统一成 null。
 * 这类坑的共同特征是：**不报错**。只能在定义数据的那一刻就统一掉，不能指望业务代码逐处判断。
 */
public record HotelCandidate(
        String hotelId,
        String name,
        String address,
        Double lat,
        Double lng,
        Double priceYuan,
        /** 已 trim。空 / 空白 / null 一律是 null。
         *  实测是中文档次词："豪华型"/"高档型"/"四星级"/"舒适型"/"经济型" —— 不是数字。
         *  且**只有带上入离日期查才有值**，不带日期查全是 null。 */
        String star,
        /** 实测恒为 null（上游不下发评分） */
        Double rating,
        String imageUrl,
        String bookUrl
) implements Candidate {

    @Override
    public String getKind() {
        return "hotel";
    }

    @Override
    public String stableId() {
        return hotelId;
    }
}
