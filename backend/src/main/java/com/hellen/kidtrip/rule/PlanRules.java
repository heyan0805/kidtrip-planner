package com.hellen.kidtrip.rule;

/**
 * 校验规则表 —— 这是"**策略**"，不是"**数据**"。
 *
 * 为什么单独一张表，不塞进 TravelParams：
 *   TravelParams 是**对话里问出来的**（用户说的）；
 *   PlanRules 是**我们定的产品策略**（系统说的）。
 *   混在一起，以后想把"每天最多 3 个景点"改成 4，你得重新跑一遍对话 —— 因为那个数字躺在"用户输入"里。
 *   分开之后，还能顺手做"按孩子年龄给不同默认值"（3 岁和 9 岁的 maxSpotsPerDay 显然不一样）。
 *
 * 规矩：**每一个阈值都要能指着真实数据说出为什么是这个数。**
 * 下面每个字段的注释里都写了它的来历 —— 没有来历的数字就是拍的，早晚会咬你。
 */
public record PlanRules(
        /** 单人门票上限（元）。null = 不限 */
        Integer maxTicketPriceYuan,
        /** 最低景点等级 1~5。null = 不限 */
        Integer minPoiLevel,
        /** 每天最多几个景点。3 个/天是"带娃"的现实上限：上午一个、下午一个、晚上一个 */
        int maxSpotsPerDay,
        /**
         * 相邻两个点最大距离（公里）—— **现在是校验层的阈值**（以前它是空的）。
         *
         * ⚠️ 这条注释原来写的是「它属于**排期**阶段的约束（要拿到顺序才算得了），校验层不用它」。
         *    问题在于排期阶段是那个大模型，而**它算不了经纬度距离** ——
         *    于是这个约束从写下那天起就没人执行过。后果实测过：模型把"第 1 天住桂林市区、
         *    第 2 天一早去阳朔"排进了一份行程，两地直线 **42.4 公里**，全程零报错。
         *
         *    现在由 `PlanService.warnGeoConflicts` 接住：当天相邻两点之间、
         *    以及"昨晚的住处 → 今天第一个点"超过它，就在 warnings 里说出来
         *    （只说不改 —— 擅自删点换酒店比这更伤人）。
         *
         * ── 15 这个数的来历 ──────────────────────────────────────
         *   它是三个阈值里唯一没有精算依据的 —— 但数据支持它站在一个安全的位置：
         *     正常的那一天：桂林第 1 天在市区，三个点之间最远 **1.8 公里**；
         *                   第 2 天在阳朔，两点之间 **4.4 公里**。
         *     该报警的：    第 1 天住市区 → 第 2 天去阳朔，**42.4 公里**；
         *                   广州候选酒店到长隆跨 **3.3 ~ 45.3 公里**。
         *   正常值在个位数、异常值在 40 以上，15 落在中间，**离两边都很远**。
         *   所以它不是一个精算出来的数字，是一个"不太可能误伤"的分界。
         *   真要调，挪到 10 或 20 都不会立刻出问题；真正不能做的是**让它继续空着** ——
         *   一个看着有约束、实际没人执行的字段，比没有这个字段更危险。
         */
        int maxDistanceKm,
        /** 校验不通过时最多回退几轮 */
        int maxRounds,
        /** 整个生成过程的总耗时上限（毫秒） */
        long budgetMs
) {

    /**
     * 默认规则。
     *
     * 两个 null 是**故意的**，而且理由不同于"懒得填"：
     *   实测（2026-10-13）主流售票景点都带票价（欢乐谷 ¥227 / 世界之窗 ¥219 /
     *   野生动物园 ¥198 / 长隆海洋王国 ¥362.88），所以"没数据所以不限价"是错的。
     *   真正的原因是：**价格上限应该来自画像里的预算**（用户说的"想控制在 500 以内"），
     *   在那之前我们是无依据的，拍一个数字只会莫名其妙地筛掉候选。
     */
    public static final PlanRules DEFAULT = new PlanRules(null, null, 3, 15, 2, 30_000);

    /**
     * 一天排几个点的**硬上限**。
     *
     * 它是产品底线，不是用户偏好：带小孩一天 5 个点，不是"行程丰富"，
     * 是"这一天会毁掉"。所以它不受 pace 影响，也刻意不放进 yaml 配置 ——
     * 配置能改的东西，早晚会被改成一个不合理的值。
     */
    public static final int HARD_MAX_SPOTS_PER_DAY = 4;

    /**
     * 每天几个点。**用户说的节奏优先，没说才用规则表的默认值。**
     *
     * 为什么不让 rules.maxSpotsPerDay 一统到底：
     *   用户明确说了"慢"，那就是 2 个 —— 这不是我们替他决定的。
     *   规则表那个 3 是"用户没说时我们猜的默认"，两者是不同性质的信息，
     *   混成一个数字会让"用户说了慢"这件事失效。
     *
     * 最后仍然夹在 [1, HARD_MAX_SPOTS_PER_DAY] 之间：用户偏好不能突破产品底线。
     */
    public int spotsPerDay(String pace) {
        int byPace = switch (pace == null ? "" : pace.strip()) {
            case "慢" -> 2;
            case "快" -> HARD_MAX_SPOTS_PER_DAY;
            case "中" -> 3;
            default -> maxSpotsPerDay;
        };
        return Math.max(1, Math.min(HARD_MAX_SPOTS_PER_DAY, byPace));
    }

    /** 用画像里的预算覆盖价格上限。以后 budget 出现在 TravelParams 里，就是这一行的调用点。 */
    public PlanRules withMaxTicketPriceYuan(Integer cap) {
        return new PlanRules(cap, minPoiLevel, maxSpotsPerDay, maxDistanceKm, maxRounds, budgetMs);
    }

    /** 还有能松的东西吗？—— 宽松本身没意义，得有实际收紧过的项才谈得上放宽。 */
    public boolean canRelax() {
        return maxTicketPriceYuan != null || minPoiLevel != null;
    }

    /**
     * 放宽一档：去掉价格上限和等级下限，其余不动。
     *
     * ⚠️ 看清楚它的**成本是零** —— 放宽只是把已经拿到的候选重新过一遍规则，
     * **不重新查飞猪**。这一点很关键：上游有限流，凡是"能靠本地重算解决的事"就绝不该花一次调用。
     * 对照之下，"池子里确实没有这个类别"才是必须花调用才能解决的问题（那就是补查）。
     *
     * 诚实说明：用当前默认规则（两个 null）调用它，结果是**和原来一样**，什么都没发生。
     * 它现在存在的意义是"等预算从画像进来之后立刻有用"。
     */
    public PlanRules relaxed() {
        return new PlanRules(null, null, maxSpotsPerDay, maxDistanceKm, maxRounds, budgetMs);
    }
}
