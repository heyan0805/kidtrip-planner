package com.hellen.kidtrip.candidate;

/**
 * 候选池里的一条。判别联合（discriminated union）：一个数组里装两种形状，靠 kind 区分。
 *
 * 用 sealed interface 而不是"一个大类塞所有字段"：
 *   sealed 把"只可能有这两种实现"写进了类型系统，编译器能穷尽检查
 *   （switch 漏了一个分支会直接报错，而不是运行时才发现）。
 *   前端那头对应的是 TS 的 `type Candidate = PoiCandidate | HotelCandidate` —— 同一个形状，两种语言各说一遍。
 *
 * 一个命名上的别扭之处要解释清楚：
 *   接口里**只有 getKind() 是 JavaBeans 命名**，其余全是 record 自动生成的短名（name()、lat()…）。
 *   为什么？因为 kind 不是 record 的组件，它是一个恒定值（"poi" / "hotel"），
 *   由各实现自己返回。而 Jackson 认一个方法是不是"属性"，看的就是 JavaBeans 约定（getXxx / isXxx）——
 *   写成 kind() 的话，序列化出来的 JSON 里**根本不会有 kind 字段**，前端拿不到判别依据。
 *   所以这一个方法必须叫 getKind()。这是被序列化框架"逼"成这样的，不是风格问题。
 */
public sealed interface Candidate permits PoiCandidate, HotelCandidate {

    /** JSON 里的判别字段。只有这两个取值 —— sealed + 这个常量，等于把"只有两种候选"钉死了。 */
    String getKind();

    String name();

    String address();

    /** 纬度。**缺坐标时是 null，不是 0** —— 0 是几内亚湾，会把距离算成几千公里 */
    Double lat();

    /** 经度 */
    Double lng();

    /**
     * 门票/房价（元）。null 的含义是"飞猪上没有可售的票/房商品"，
     * 可能是免费公园（真的不要钱），也可能是门票已含在母景区里 —— 这两种分不出来。
     * 所以规则里遇到 null 只能当"价格未知、不参与比价"，**绝不能当"不合格"筛掉**。
     */
    Double priceYuan();

    String imageUrl();

    /** 飞猪跳转短链。⚠️ 带追踪码，每次请求都重新生成 —— 只能当"点开看详情"用 */
    String bookUrl();

    /**
     * 稳定标识（景点 id / 酒店 id）。
     * 去重、追溯、缓存全用它。别用 bookUrl —— 实测每次请求都在变，拿它去重会一条都去不掉。
     */
    String stableId();

    /** 人可读的一行摘要，日志和排障用 */
    default String brief() {
        return getKind() + " " + stableId() + " " + name()
                + (priceYuan() == null ? "（无价格）" : "（¥" + priceYuan() + "）");
    }
}
