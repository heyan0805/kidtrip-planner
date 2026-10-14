package com.hellen.kidtrip.plan;

import java.util.List;

/**
 * 画像 —— 对话问出来的"用户想要什么"。
 *
 * 它是前端 `types/plan.ts` 里 `TravelParams` 的**逐字镜像**，
 * 包括"所有字段都可缺省"这一点：对话是逐轮补齐的，第 1 轮只知道 city，
 * 第 3 轮才知道 childAge。所以这里全部用包装类型（Integer / Boolean），
 * 而不是 int / boolean —— 后者在 JSON 缺字段时会变成 0 / false，
 * 把"还没问过"悄悄变成"答案是 0"。
 *
 * 这个区别在 stroller 上最要命：
 *   null = 还没问过   |   true = 明确需要   |   false = 明确不需要
 *   三态。用 boolean 就把前两种合并了，界面上"待确认"会错显示成"不需要"。
 *
 * ── origin 是 2026-10-13 加的，理由和别的字段都不一样 ────────────────
 *   它不是"多收集一个信息"，而是**产品约束能不能成立的前提**：
 *   这个项目的身份是"带孩子出行，每一段移动都不能太长"，而"一段"是
 *   「出发地 → 目的地」和「目的地 → 出发地」这两段。**没有出发地，这两段根本不存在**，
 *   那条约束就只是一句宣传语。
 *
 *   所以 origin 和 city 是一对，缺一个这条约束就是空的：
 *     origin = 家（从哪走）    city = 去哪儿
 *     origin == city  →  同城，没有城际交通（本地出行不该被判"没有车次"）
 *     origin 缺失     →  不查车次、也不报错，行程照常出，只是没有交通那一行
 *   ⚠️ 最后那条很重要：**缺信息不等于出错。** 我们会让它安静地空着，
 *      而不是编一段交通、也不是拒绝生成行程。
 *
 *   它和 city 走同一套规矩（"空值即未答"，不是三态）——
 *   因为没有"明确说不要出发地"这种表达。
 */
public record TravelParams(
        /** 出发地城市（"广州"）。缺失 = 还没问过，此时不查车次 */
        String origin,
        String city,
        Integer days,
        /** ISO 日期字符串（"2026-10-24"）。不是时间戳 —— 见前端契约里的理由 */
        String startDate,
        List<String> preferences,
        /** 慢 / 中 / 快 */
        String pace,
        Integer childAge,
        Boolean stroller
) {

    /**
     * 全空画像 —— 对话第 1 轮、或者调用方没传 params 时的起点。
     *
     * 为什么要给一个命名工厂，而不是让各处写 `new TravelParams(null, null, …)`：
     *   这句话在工程里有**六处**（对话、生成、两个 data/request 包装、两个降级分支）。
     *   用位置参数写，每加一个字段就要把这六处的括号数改一遍，而且**漏改一处是编译错误、
     *   但把两个 null 插错位置就不是** —— 那种错误编译器不会说话，只会让某个字段悄悄串位。
     *   （origin 这次加进来，正好验证了这个担心：六处全都要动。）
     * 命名之后，"全空画像"只有一个定义，加字段也只需要改这里一行。
     */
    public static TravelParams empty() {
        return new TravelParams(null, null, null, null, null, null, null, null);
    }

    /**
     * 补齐默认值，让下游不用到处判 null。空对象（没传 params）也能安全地走一遍。
     *
     * ⚠️ **只在准备生成行程时调用，不要在对话里调用。** 它会把 days=null 补成 3，
     *    而在对话里那等于替用户答了"玩几天"这道题（详见 PlanChatRequest.currentOrEmpty）。
     *
     * 注意 origin **没有默认值可选**，只能原样传 —— 我们无法假设用户的出发地。
     * 这也正是它在缺失时"安静地空着"而不是被补一个值的原因。
     */
    public TravelParams orDefaults() {
        return new TravelParams(
                origin,
                city,
                days == null || days <= 0 ? 3 : Math.min(days, 10),
                startDate,
                preferences == null ? List.of() : preferences,
                pace,
                childAge,
                stroller
        );
    }

    /**
     * 用模型这一轮**新听出来的**字段，合并出"截至目前为止"的完整画像。
     * 这是"对话逐轮把画像攒起来"这个机制的全部实现 —— 它是个纯函数，所以能被单测。
     *
     * 规则只有一条：**patch 里"没答"的字段，一律保留原值。**
     *
     * 为什么不直接用 patch 覆盖：模型每一轮只会写它这一轮听出来的那几个字段，
     * 其余的它会留空。直接覆盖等于把用户前几轮说过的城市、天数全抹掉 ——
     * 而且**用户看不见**，他会在点了"生成行程"之后才发现"怎么又要重新填一遍"。
     * 这和前端那条"异步里读 state 会拿到过期闭包"是同一类失败：状态被静默地退回旧值。
     *
     * ── 什么算"没答"，是这个方法里唯一需要想清楚的事 ──────────────────
     * 四类都要挡住，缺一个就会漏：
     *   null        —— 模型的自然表达方式；
     *   空串 ""     —— 模型被要求输出字符串时，经常用空串表示"没有"；
     *   0 / 负数     —— **这个最阴**：参照工程的 prompt 是教模型"用 0 表示未确定"的，
     *                  模型的这个习惯会一直带着。只看 null 会让 days=0 覆盖掉 days=3，
     *                  于是 orDefaults() 又把它补回 3 —— 恰好掩盖了问题，直到某天用户
     *                  说"我明明说了 5 天"，你才发现 0 曾经把 5 擦掉过。
     *   空数组 []   —— preferences 的对应形态。
     *
     * ⚠️ 说清代价：这样就没法表达"把某个字段清空"（比如用户说"不去珠海了"，
     *    模型给新城市是没问题的；但"把城市清成空"做不到）。
     *    真需要清空时，应该给"明确清空"一个**显式的词**（像 CandidateCodes.STAY_NONE 那样），
     *    而不是让 null 同时兼职表达"没回答"和"要清掉" —— 那就又回到了
     *    "模型写了 none 却被兜底补上"的那个坑。
     *
     * stroller 那一行值得单独看：它**只判 null**，不判别的。
     * 因为 Boolean 的三态在这里是货真价实的语义 —— false 是"明确不需要"，
     * 是一个有效答案，绝不能被当成"没答"而被原值盖掉。
     * 其余字段是"空值即未答"，只有它是"null 即未答"。
     */
    public TravelParams mergedWith(TravelParams patch) {
        if (patch == null) {
            return this;
        }
        return new TravelParams(
                answered(patch.origin()) ? patch.origin().strip() : origin,
                answered(patch.city()) ? patch.city().strip() : city,
                patch.days() != null && patch.days() > 0 ? patch.days() : days,
                answered(patch.startDate()) ? patch.startDate().strip() : startDate,
                patch.preferences() != null && !patch.preferences().isEmpty() ? patch.preferences() : preferences,
                answered(patch.pace()) ? patch.pace().strip() : pace,
                patch.childAge() != null && patch.childAge() > 0 ? patch.childAge() : childAge,
                // 三态：只有模型**真的答了**（true / false）才覆盖，null 是"还没问过"
                patch.stroller() != null ? patch.stroller() : stroller
        );
    }

    private static boolean answered(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * 换掉出发日期，其余字段原样。
     *
     * 单独给一个方法而不是到处 `new TravelParams(...)` 重列八个字段：
     * 那样每加一个字段就要改所有构造点，而且**漏改一处不会报错**（顺序错了还是能编译）——
     * record 的位置参数在这里是个陷阱，不是便利。
     * （origin 这次加进来，这个方法一处都不用动，就是这个设计的回报。）
     */
    public TravelParams withStartDate(String newStartDate) {
        return new TravelParams(origin, city, days, newStartDate, preferences, pace, childAge, stroller);
    }

    /**
     * 出发地和目的地是不是同一座城市。
     *
     * 判"要不要查城际交通"用。抽成方法而不是让调用方各写一遍 equals：
     * 缺了哪一边该算"同城"还是"不知道"，两种判断会分叉 —— 而这个分叉的后果很具体，
     * 就是给一次本地出行报"没有查到车次"。两边的判空规则只写一次，就不会分叉。
     */
    public boolean sameCity() {
        return answered(origin) && answered(city) && origin.strip().equals(city.strip());
    }
}
