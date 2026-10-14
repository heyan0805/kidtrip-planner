package com.hellen.kidtrip.llm;

/**
 * 大模型输出的**原始草稿** —— 这一步的关键设计全在这个类型里。
 *
 * 盯住它和最终的 TravelSpot 有什么不同：
 *
 *   PlanDraft.Item   →   { candidateId: "C3", time: "09:30", tips: "..." }
 *   TravelSpot       →   { time, name, poiId, priceYuan, bookUrl, imageUrl, lat, lng, tips }
 *
 * **模型只写"挑哪个 + 什么时候 + 提示什么"，一个事实字段都不许它写。**
 * 地点名、价格、坐标、预订链接全部由后端从候选池里回填（见 PlanAssembler）。
 *
 * 为什么要这么绕？回想那次实测事故：
 *   模型编了"深圳儿童欢乐世界"，检索返回了真实存在的「儿童欢乐世界(66乐园店)」，
 *   于是假名字被贴上了真价格、真坐标，**全程零报错** —— 查到了不等于查对了。
 *
 * 而 candidateId 这个设计把这类错误**变得可检出**：
 *   模型只能填清单里给过的编号。它要是编一个 "C99"，后端一查索引就发现没有，直接丢弃 + 记账。
 *   幻觉从"一份看起来对的行程"退化成"一条被丢弃的记录"。
 *
 * 这就是"白名单"的全部含义：**不是相信模型不会编，而是让它编了也没用。**
 *
 * ⚠️ 一个实现细节：字段名（candidateId / time / tips）必须和 prompt 里给的 JSON 例子逐字一致。
 *    Jackson 是按名字映射的，模型写成 candidate_id 就会解析成 null ——
 *    而且**不会报错**，只会得到一份空行程。这类静默错位和前端那条 camelCase 规矩是同一个坑。
 */
public record PlanDraft(
        String summary,
        java.util.List<Day> days,
        java.util.List<String> warnings
) {

    /**
     * 第几天。「day」允许是 null —— 模型偶尔会漏，装配时会按位置补上。
     *
     * `hotelCandidateId` 是"**当晚住哪**"，它的取值空间只有三种，必须说清楚：
     *   "H3"    —— 住清单里第 3 家；
     *   "none"  —— 当天返程、不需要住宿（**这是明确回答，后端不会给它补一个**）；
     *   空/null —— 模型没回答（后端会沿用前一天的住处，第一天则从池子里补一家）。
     *
     * 为什么"没回答"和"说了 none"要分开，见 CandidateCodes.STAY_NONE 的注释。
     *
     * 还有一个和景点**正好相反**的规矩：酒店编号**允许重复**。
     *   items 里同一个 candidateId 出现两次是错误（一个景点只去一次）；
     *   hotelCandidateId 连续 3 天都写 H2 是**正确**的连住表达（带娃旅行本来就该少换酒店）。
     *   装配器里这两处的处理逻辑必须分开写，共用一套去重逻辑会毁掉连住。
     */
    public record Day(Integer day, String theme, String hotelCandidateId, String hotelTips,
                      java.util.List<Item> items) {
    }

    /** 一个点。注意这里**没有** name / price / address —— 那是设计，不是遗漏。 */
    public record Item(String candidateId, String time, String tips) {
    }
}
