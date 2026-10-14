package com.hellen.kidtrip.plan;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * persona 能说的**全部维度** —— 闭集，只有这 5 个。
 *
 * ── 先说 persona 是什么（不然这个闭集没法理解）──────────────────────
 *   `TravelParams` 里那 8 个字段是用户**说过的**：他说了 3 天，就写 3 天，
 *   写错了拿原话就能核对。
 *   persona 装的是我们**推出来的**：用户从没说过"我怕走路"，
 *   他说的是"我想有一个放松的旅行"，是我们把它读成了"少走路"。
 *
 *   两者最要命的差别不是准不准，而是**错了会不会被发现**：
 *     事实错了 → 用户会说"我说的是 4 天" → 自愈；
 *     推论错了 → 用户以为你懂他，其实你懂错了 → **不报错，一直错下去**。
 *   所以推论这一侧必须自带两道闸门：**维度闭集**（这个文件）和
 *   **依据逐字回查**（PersonaGuard）。这里管第一道。
 *
 * ── 为什么维度的决定权必须在我们手里，而不是交给模型 ──────────────────
 *   模型很擅长"看起来合理的推论"，问题是"看起来合理"和"真的合理"它分不出来：
 *     "我想有个放松的旅行" → 不想折腾      ← 我们认可的推论
 *     "我今天有点累"       → 她体力很差    ← 过度推论，机器眼里两者长得一模一样
 *   闭集不是"防模型写错"，是**把"什么算一个值得记住的偏好"这件事从模型手里拿回来**。
 *   它只能在 5 个已经想清楚的维度上做判断，想不出第 6 个，也就没机会过度推论。
 *   这和 PoiCategories / CityNames / CandidateCodes 是同一套机制换了张脸：
 *   **不是相信模型不会写错，而是让它写错了也没有用。**
 *
 * ── 为什么只放这 5 个：凡 TravelParams 装得下的，这里一个都不放 ─────────
 *   `pace: "慢"` 已经在画像里了。如果 persona 里再存一条"节奏慢"，
 *   就是**两处真相**，迟早打架 —— 而打架时没人知道该信哪一边。
 *   所以这个闭集只装画像那 8 个字段**表达不了、而且真的会改变怎么排**的维度：
 *     怕走路   → 每天排几个点、点与点的距离、要不要为省一步路换酒店
 *     怕人多   → 优先冷门时段/冷门点，少排网红点
 *     要午睡   → 中午留空档、住宿要靠近当天活动区
 *     预算敏感 → 优先免票/低价候选、酒店往下取一档
 *     孩子兴趣 → 同类景点优先，哪怕它更远一点
 *   这 5 条现在**一个字都存不下**，这才是 persona 不可替代的地方。
 *   （"不想折腾"这种说法不设成第 6 个维度 —— 它本来就是 pace，见上一条。
 *     模型该做的是把"放松"填进 pace，再在 walking 上推出"少走路"，两件事分开。）
 *
 * ── 为什么 key 是英文、标签是中文 ─────────────────────────────────
 *   key 是**机器标识**，要跨轮比对、要去重、要在前端当 Map 的键；
 *   标签是**给人看的**，出现在卡片和 prompt 里。
 *   如果 key 也用中文，模型会写"怕走路 / 怕累 / 不爱走路"三种 ——
 *   同义不同串，闭集就挡不住了（三串都合法，但它们其实是同一条偏好）。
 *   英文 key 的同义风险低得多，而且一眼能看出它不是给人读的。
 *
 * ── 加一个维度要做的事 ─────────────────────────────────────────
 *   只改这个文件。**这就是闭集的全部好处**：维度定义只有一个地方，
 *   漏不掉、也不会出现"prompt 里教了 6 个、闸门只认 5 个"这种分叉。
 *   顺带一提，enum 是编译期的闭集 —— 想加第 6 个，必须在这里写下来，
 *   而不是在某次 prompt 修改里悄悄冒出来。
 *
 * ── 它为什么在 plan 包而不是 flyai 包 ─────────────────────────────
 *   参照 PoiCategories 那句判据：**它因为什么而变化？**
 *   那边因为飞猪改了接口而变，所以在 flyai 包；
 *   这 5 个维度是我们改产品时才变的 —— 我们决定"值得记哪些偏好"，
 *   和飞猪一点关系都没有，所以在 plan 包。
 */
public enum PersonaTrait {

    /**
     * 取值域写成"提示"而不是硬枚举，是一条刻意的选择 —— 见 {@link #domain()} 上的注释。
     */
    WALKING("walking", "怕走路", "少走路 / 无所谓"),
    CROWD("crowd", "怕人多", "避开人多 / 不介意"),
    REST("rest", "要午睡", "要午睡 / 不用午睡"),
    BUDGET("budget", "预算敏感", "想省着花 / 价格不敏感"),
    INTEREST("interest", "孩子的兴趣", "一个具体的兴趣词，如 恐龙 / 火车 / 挖沙");

    private final String key;
    private final String label;
    private final String domain;

    PersonaTrait(String key, String label, String domain) {
        this.key = key;
        this.label = label;
        this.domain = domain;
    }

    /** 机器标识。跨轮比对、去重、前端当 Map 键都用它 —— 永远不要拿 label 干这些事 */
    public String key() {
        return key;
    }

    /** 给人看的中文名。出现在「我这样理解你」卡片和 prompt 里 */
    public String label() {
        return label;
    }

    /**
     * 这个维度上**大概**能取什么值 —— 只进 prompt，**不做硬校验**。
     *
     * 为什么不硬校验（比如给 walking 定死 {少走路, 无所谓} 两个合法值）：
     *   INTEREST 这类维度根本枚举不完（恐龙 / 火车 / 挖沙 / 只要是有轨道的……），
     *   而硬枚举一旦遇上枚举不了的值，模型会挑**最近的那个**塞进去 ——
     *   于是"孩子超爱恐龙"被写成"孩子的兴趣：恐龙"，看着没错；
     *   但换个例子就露馅了：用户说"他就喜欢在小区里挖沙子"，
     *   模型被迫在 {恐龙, 火车, 挖沙} 里选，选了挖沙，**碰巧对了**；
     *   而如果表里没有挖沙，它会选恐龙 —— **静默地错**。
     *   枚举的失败方式是从"没记住"退化成"记错了"，比不记更糟。
     *
     *   所以：维度（key）硬闭集，取值（value）只给方向 + 长度上限。
     *   真正兜底的是 evidence 那条闸门 —— value 怎么写都得拿得出原话。
     */
    public String domain() {
        return domain;
    }

    /** 查表用。用 Map 而不是每次 values() 遍历 —— 它会被逐条调用 */
    private static final Map<String, PersonaTrait> BY_KEY = buildIndex();

    private static Map<String, PersonaTrait> buildIndex() {
        Map<String, PersonaTrait> byKey = new LinkedHashMap<>();
        for (PersonaTrait t : values()) {
            byKey.put(t.key, t);
        }
        return Map.copyOf(byKey);
    }

    /**
     * 模型给的那个 key 认不认。认不出返回 **null**（不是抛异常、也不是给个默认维度）。
     *
     * 返回 null 而不是兜底一个默认值，是因为**没有安全的默认值**：
     * 任何兜底都会把"我们没打算记的东西"记成某个真实维度 ——
     * 那比丢掉它危险得多，因为丢掉的只是"没记住"，兜底出来的是一条**错的偏好**。
     * 调用方拿到 null 就该整条丢弃 + 记账（见 PersonaGuard）。
     *
     * 做了 strip + 转小写：模型偶尔会给 `" WALKING "` 或 `"Walking"`。
     * 不归一的话它们会被判成非法 key 丢掉，而用户那边看到的是
     * "它明明听懂了怎么没记住" —— 又是**我们自己**把一个正确值判成了非法。
     * （和 PoiCategories.isValid 里的 trim 是同一条教训。）
     */
    public static PersonaTrait ofKey(String raw) {
        if (raw == null) {
            return null;
        }
        return BY_KEY.get(raw.strip().toLowerCase());
    }

    /** 这个 key 认不认。等价于 `ofKey(raw) != null`，给只需要判定的地方用 */
    public static boolean isValidKey(String raw) {
        return ofKey(raw) != null;
    }

    public static int size() {
        return values().length;
    }

    /**
     * 渲染成给模型看的维度表，一行一个维度。
     *
     * 和 PoiCategories.renderForPrompt 是**两种排版，同一条规矩**：
     *   那边是一份可选值清单，要嵌在一句话里，所以压成一行用顿号分隔；
     *   这边是一张维度定义表（key + 中文名 + 取值方向），逐行读才知道哪个是哪个。
     *   两边都是"要模型**理解**的文本"，都不是给它解析的 JSON。
     *
     * 带上 key 和中文名两个：key 是它必须原样回填的值，
     * 中文名是它理解"这个维度在问什么"的依据。只给 key 它得猜 walking 是什么意思。
     */
    public static String renderForPrompt() {
        StringBuilder sb = new StringBuilder();
        for (PersonaTrait t : values()) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append("- ").append(t.key).append("（").append(t.label).append("）：取值 ").append(t.domain);
        }
        return sb.toString();
    }
}
