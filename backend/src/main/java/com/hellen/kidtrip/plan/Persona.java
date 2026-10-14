package com.hellen.kidtrip.plan;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * persona —— **我们对这个用户的判断**，以及每条判断的依据。
 *
 * 一句话说清它和 `TravelParams` 的分工：
 *   TravelParams = 用户**说过的**（事实，能拿原话核对）
 *   Persona      = 我们**推出来的**（推论，只能拿原话当依据）
 *
 * ── 它为什么必须独立成一个 record，而不是往 TravelParams 里加几个字段 ────
 *   这不是洁癖，是两个具体的后果：
 *
 *   ① `TravelParams.mergedWith` 的规则是「**patch 里没答的字段一律保留原值**」。
 *      这条规则对事实是对的 —— 模型这轮没提城市，不该把城市抹掉。
 *      但对推论是**灾难**：推论要能每轮被推翻重来，而"保留原值"会
 *      把上一轮推错的结论**永久粘住**，并且**没有任何机制能洗掉它**。
 *      （比如第一轮模型推出一条"她预算敏感"，用户后面说"这次想住好一点"，
 *       模型这轮只输出 walking —— 保留式合并会让那条错的预算结论原地不动。）
 *   ② `PlanComposer.renderParams()` 会把 TravelParams 的**每个字段当事实**渲染给模型看
 *      （"节奏：慢"）。推论混进去，模型会把推论当事实用 —— 于是"我们猜的"
 *      在下一轮就变成了"用户说的"，一路加固，再也回不来。
 *
 *   所以：独立类型、独立合并、独立渲染。三件都是必须的，只做一件都不够。
 *
 * ── 合并规则：同 key 覆盖，没提到的维度保留 ────────────────────────────
 *   方向是"**增量**"，不是"整份替换"。理由是这两件事在 JSON 里长得一模一样：
 *     模型这轮**没输出** walking  ≠  用户**撤回了**"怕走路"
 *   两者都是"没出现"，而我们的合并只能看见"没出现"。
 *   这种情况下，**保留**是代价小的那一边：
 *     保留错了 → 一条过时的偏好还在，影响一次排程；
 *     丢掉错了 → 用户前面聊的全都白聊，而且他看不见 —— 正是 TravelParams
 *                mergedWith 那个注释里说的"状态被静默地退回旧值"。
 *
 *   那"用户改主意了"怎么表达？**靠 value 覆盖，不靠缺失**。
 *   模型每轮都看得到全部对话历史，它该做的是**给一份完整的判断快照**：
 *   用户说"其实我不在乎走路"，就输出 `walking = 无所谓`（覆盖掉"少走路"），
 *   而不是**不输出** walking。
 *   ⚠️ 已知边界（写下来免得以后当 bug 修）：模型如果既不说"无所谓"、
 *      又不再输出这一条，那条旧判断会留着。要根治得给"明确作废"一个显式的词
 *      （像 CandidateCodes.STAY_NONE 那样），那是另一层复杂度，本轮不做。
 *      另外用户还有一条不依赖模型的路：卡片上那条可以**直接删**。
 *
 * ── 为什么 inference 一律是"过闸门之后"的才配进这里 ─────────────────────
 *   `Persona` 自己不校验任何东西（只做 strip 归一），因为它是个值对象，
 *   而校验是要**记账**的（哪条被丢了、为什么）—— 记账属于 {@link PersonaGuard}。
 *   两处都校验会出现"两个闸门，只有一个在记账"的分叉：被另一个闸门丢掉的条目
 *   会**无声消失**，排障时你只会看到"模型没输出"，而它其实输出了。
 */
public record Persona(
        /** 我们对这个用户的判断，**每条都带依据**。可能为空（第 1 轮、或一句偏好都没推出来） */
        List<Inference> inferences
) {

    /**
     * 一条推论：我们在哪个维度上、做了个什么判断、**凭哪句话**。
     *
     * evidence 不是"备注"，它是这条推论**能不能活下来**的唯一凭据 ——
     * PersonaGuard 会拿它去逐字比对对话历史，比不中整条丢弃。
     * 这是把"不可检出的幻觉"降级成"一次查表失败"，和 candidateId 是同一个手法。
     */
    public record Inference(String key, String value, String evidence) {

        /**
         * 只做归一，**不做校验** —— 见 Persona 的类注释最后一段（记账属于闸门）。
         *
         * 三处都 strip 而不是只 strip key：模型很爱给 `"少走路 "` 这种尾随空格，
         * 而这条 value 最终要显示在卡片上，尾随空格会让它在界面上莫名其妙地缩进一格。
         * null 一律归一成空串，让下游只判空、不判 null —— 少一个三态就少一类漏判。
         */
        public Inference {
            key = key == null ? "" : key.strip();
            value = value == null ? "" : value.strip();
            evidence = evidence == null ? "" : evidence.strip();
        }

        /** 这个维度认不认识（查的是闭集，用途和 PersonaTrait.isValidKey 完全一样） */
        public boolean hasKnownKey() {
            return PersonaTrait.isValidKey(key);
        }
    }

    /**
     * 全部字段非空 —— 和 TravelParams 那套「所有字段都可缺省」**故意相反**。
     *
     * 画像逐轮补齐，缺省是常态；persona 每一条都是完整的三元组，
     * 少任何一半都不成立（没有 value 的判断、没有依据的判断都不该存在）。
     * 所以这里不接受 null，只接受**空列表**代表"还没推出任何东西"。
     */
    public Persona {
        inferences = inferences == null ? List.of() : List.copyOf(inferences);
    }

    /** 还没有任何判断。对话第 1 轮、或模型这轮一句偏好都没推出来时的起点 */
    public static Persona empty() {
        return new Persona(List.of());
    }

    /**
     * 全空 persona / 或从一份已有的推论建。
     *
     * 有了它，`new Persona(List.of(...))` 只出现在这一个地方 ——
     * 和 `TravelParams.empty()` 是同一个理由（现在只有一个调用点，
     * 但加字段/改签名时"只有一个定义处"的价值在改动当天就兑现）。
     */
    public static Persona of(Collection<Inference> items) {
        return items == null || items.isEmpty() ? empty() : new Persona(List.copyOf(items));
    }

    public boolean isEmpty() {
        return inferences.isEmpty();
    }

    public int size() {
        return inferences.size();
    }

    /** 这个维度上我们的判断是什么；没这个维度返回 null */
    public String valueOf(String key) {
        if (key == null) {
            return null;
        }
        String wanted = key.strip();
        for (Inference i : inferences) {
            if (i.key().equalsIgnoreCase(wanted)) {
                return i.value();
            }
        }
        return null;
    }

    /** 这个维度我们有没有判断过 */
    public boolean has(String key) {
        return valueOf(key) != null;
    }

    /**
     * 把模型这一轮的判断并进"截至目前为止"的 persona。**全部合并逻辑就在这里。**
     *
     * 规则两条，够用了：
     *   同 key      → patch 覆盖（含 evidence。新依据更贴近用户现在的说法）
     *   其余        → this 里 patch 没有的**原样保留**（见类注释"增量而不是替换"）
     *   两边都空    → 返回 this，不抛 NPE
     *
     * 用 LinkedHashMap 是因为顺序有意义：它决定卡片上条目的先后，
     * 也决定 prompt 里那段的先后。`put` 更新已存在的 key **不会改变它的位置** ——
     * 这正是想要的：用户熟悉的顺序不该因为某一条被更新而重排。
     */
    public Persona mergedWith(Persona patch) {
        if (patch == null || patch.inferences().isEmpty()) {
            return this;
        }
        Map<String, Inference> byKey = new LinkedHashMap<>();
        for (Inference i : inferences) {
            byKey.put(i.key(), i);
        }
        for (Inference i : patch.inferences()) {
            byKey.put(i.key(), i);
        }
        return new Persona(new ArrayList<>(byKey.values()));
    }

    /**
     * 渲染成给模型看的一段「我对这个用户的判断」。
     *
     * 每条都**必须带上依据**，这是刻意的：
     *   只给结论（"怕走路"）模型会把它当**事实**用（和 pace 混在一起）；
     *   带上依据（"怕走路 —— 他说'想有一个放松的旅行'"）它才知道
     *   这是我们**推的**，措辞上就会留有余地。
     *
     * 中文名拿不到时退回 key 本身，而不是丢掉这一行 ——
     * 渲染的职责是"把已有的说出来"，不是"顺便再校验一遍"（那是闸门的事）。
     * 万一真有一条没经过闸门的推论漏进来，让它**显示出来**比让它**安静消失**好。
     */
    public String renderForPrompt() {
        StringBuilder sb = new StringBuilder();
        for (Inference i : inferences) {
            PersonaTrait trait = PersonaTrait.ofKey(i.key());
            String name = trait == null ? i.key() : trait.label();
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append("- ").append(name).append("：").append(i.value())
                    .append("（依据：用户说过「").append(i.evidence()).append("」）");
        }
        return sb.toString();
    }
}
