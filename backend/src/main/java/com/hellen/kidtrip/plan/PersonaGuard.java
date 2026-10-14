package com.hellen.kidtrip.plan;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * persona 的闸门 —— **把"不可检出的幻觉"降级成"一次查表失败"**。
 *
 * ── 它挡的是什么（说清楚，不然会以为它只是数据清洗）──────────────────
 *   模型要输出的是 `{ key, value, evidence }` 三元组。前两个是它的**结论**，
 *   第三个是它的**凭据**。
 *
 *   结论不可校验 —— "我想放松" 到 "怕走路" 这一步是语义跳跃，
 *   没有任何机械方法能判断它跳得对不对。所以我们**不校验结论**。
 *
 *   但凭据可以：evidence 声称是"用户的原话"，那就去对话历史里**逐字找**。
 *   找得到 → 这条推论至少建立在一句真实的话上；找不到 → 整条丢弃、记账。
 *
 *   这一下就掐掉了两个东西：
 *     ① 凭空生成的偏好（用户什么都没说，它推出一条）；
 *     ② 更隐蔽的 —— **为了让自己看起来有洞察力而编一句用户没说过的话当依据**。
 *        这种最危险，因为它带着"证据"上市，看起来比真的还真的。
 *
 *   ⚠️ 诚实的边界，写在最显眼的地方：
 *      逐字回查只能证明「**用户说过这句话**」，不能证明「**这句话推出了这个结论**」。
 *      "我想有个放松的旅行" → "不想折腾" 是我们认可的推论；
 *      "我今天有点累"        → "她体力很差" 是过度推论。
 *      **机器眼里这两者长得一模一样。**
 *      所以闭集（PersonaTrait）那边负责"只能在这 5 个维度上推"，
 *      这里负责"每条都得有原话"——两道闸门各管一半，谁也不能少。
 *
 * ── 为什么只查 user 消息，不查 assistant ────────────────────────────
 *   拿模型自己说过的话当依据，等于**自证**：
 *   它上一轮随口说"看来您比较在意预算"，这一轮就能把这句话抄成 evidence，
 *   于是幻觉完成了一次自我确认，而且从此再也查不出来。
 *   assistant 的消息是**它的输出**，不能同时是**它的证据**。
 *
 * ── 为什么逐条查，不是拼起来查 ─────────────────────────────────────
 *   用户可能分两句说"我想放松"+"我不爱走路"，模型把它们拼成
 *   "我想放松我不爱走路" 当依据。**拼起来查的话它会命中**，听起来也挺合理。
 *   但拼接同时会让"用户说了 A、也说了 B、所以推出 C"这种**假的因果**
 *   一并放行 —— 而逐条查挡住了它。严的方向选"宁缺勿滥"：
 *   丢掉的是一条本可以留的推论（少一条偏好），放过的是一条编造的依据
 *   （一条静默生效的错误偏好）。后者代价大得多，因为它不报错。
 *   ⚠️ 这是**已知代价**，不是 bug：真遇到用户分两句说的情况，
 *      模型该做的是挑其中**最能支撑结论的那一句**当 evidence。
 *
 * ── 可测性是设计出来的 ──────────────────────────────────────────
 *   整个类没有实例、没有依赖、没有 Spring —— 全是 static 纯函数，
 *   输入 `List<Inference> + List<Message>`，输出 `Screening`。
 *   所以它能在毫秒级被单测钉死，包括那些"必须进不去"的反例
 *   （编造的 evidence / 自造的 key / assistant 说过的话）。
 *   照 CityNames / PoiCategories 的样子写，理由和它们一样。
 */
public final class PersonaGuard {

    /**
     * evidence 去空白标点后至少要这么长，才够格当"一句原话"。
     *
     * 挡的是 `"嗯"` `"好"` `"对"` 这类：它们**确实**在历史里（用户真说过），
     * 但拿它们当依据等于没有依据 —— 有了这个下限，任何推论都能挂上一句
     * "嗯" 来过关，逐字回查就退化成了一道形同虚设的关卡。
     *
     * 定 3 是因为「我怕累」正好 3 个字，而那是个真实的依据（哪怕它推得过头，
     * 挡过头推论不是这一步的职责）。再高会开始误杀真话。
     */
    public static final int EVIDENCE_MIN = 3;

    /**
     * value 的长度上限。
     *
     * persona 的每条是要**显示在卡片上的一行**。超过这个长度它就不是标签而是句子，
     * 而句子属于 evidence 那一栏。挡住它的实际意义是：防止模型把一整段推理
     * 塞进 value，那样卡片会变成一堵墙，用户根本看不过来 —— 而"能看见它记得什么"
     * 正是这个东西的全部价值。
     */
    public static final int VALUE_MAX = 16;

    /* ── 丢弃原因。文案是**给用户看的**，所以都是能读懂的短句，不是错误码 ── */

    /** key 不在闭集里 —— 模型想推出一个我们没打算记的维度 */
    public static final String REASON_UNKNOWN_KEY = "不是我们认识的维度";
    /** value 是空的 —— 有维度没判断，等于没说 */
    public static final String REASON_BLANK_VALUE = "没有给出判断";
    /** value 太长 —— 那不是标签，是句子 */
    public static final String REASON_LONG_VALUE = "判断写太长了，那不是标签是一句话";
    /** 同一个维度说了两遍 —— 只留第一遍 */
    public static final String REASON_DUPLICATE_KEY = "同一个维度说了两遍";
    /** evidence 是空的 —— 结论没有凭据 */
    public static final String REASON_NO_EVIDENCE = "没有给出依据";
    /** evidence 太短，够不上"一句原话" */
    public static final String REASON_SHORT_EVIDENCE = "依据太短，不足以当作一句原话";
    /** ⭐ 核心那条：evidence 里的那句话，逐字比对全部用户消息都找不到 */
    public static final String REASON_EVIDENCE_NOT_FOUND = "依据里的那句话，用户没有说过";

    /**
     * 匹配时当作"不存在"的字符 —— 全部空白 + 常见中英标点。
     *
     * 为什么要连标点一起去掉：
     *   失败方向是不对称的。模型把「我想放松。」写成「我想放松」只是少抄了一个句号，
     *   如果因此判成"用户没说过"，代价是**一条真推论被丢掉**，而且用户看不见 ——
     *   他只会觉得"我明明说了想放松，它怎么没记住"。
     *   反过来放松一点（少一个句号也算命中）**没有任何代价**：
     *   去掉标点后仍然逐字相同，说明内容确实是同一句，不构成造假的可能。
     *   **拿不准时，选代价小的那一边。**（和 Message.normalizedRole 同一条思路。）
     *
     * 连 `-` 也去掉，所以在带连字符的场景下比较会松一档 —— 无害，
     * evidence 是用户说的话，不是需要精确解析的标识符。
     */
    private static final String IGNORED = "，。！？；：、,.!?;:\"'“”‘’（）()【】[]{}…—－~～-";

    private PersonaGuard() {
    }

    /**
     * 一次闸门的结果。
     *
     * 必须把**被丢掉的也带出来**，不能只返回通过的那几个 ——
     * 这是"能排障"和"只能猜"的分界线：
     * 只返回通过项的话，`evidence` 命中率这个数字就永远测不出来
     * （你分不清"模型没推"和"模型推了但没拿出原话"），
     * 而那个数字恰好是决定后面 prompt 该怎么写的唯一依据。
     */
    public record Screening(List<Persona.Inference> accepted, List<Rejection> rejected) {

        public Screening {
            accepted = accepted == null ? List.of() : List.copyOf(accepted);
            rejected = rejected == null ? List.of() : List.copyOf(rejected);
        }

        /** 没有被闸门拦下任何东西 */
        public boolean isClean() {
            return rejected.isEmpty();
        }

        /** 通过的直接能拿去 mergedWith —— 闸门的输出就是 Persona 的合法输入 */
        public Persona toPersona() {
            return Persona.of(accepted);
        }
    }

    /**
     * 一条被拦下的推论。留着 key/value/evidence 原样，是为了让它**能被复盘**：
     * 以后要问"模型到底爱在哪儿编"，看这个列表就行，不用去翻日志。
     */
    public record Rejection(String key, String value, String evidence, String reason) {

        public Rejection {
            key = key == null ? "" : key;
            value = value == null ? "" : value;
            evidence = evidence == null ? "" : evidence;
            reason = reason == null ? "" : reason;
        }
    }

    /**
     * 过闸门。返回通过的 + 被拦下的，两边都保序。
     *
     * 关卡顺序是刻意的，从"最便宜、最不需要看历史"的排起：
     *   key 闭集 → value 非空 → value 不超长 → evidence 非空 → evidence 不短
     *   → evidence 逐字回查 → 同 key 去重
     *
     * 最后那道"同 key 去重"排在**所有检查之后**，因为 `seen` 只记
     * **已经被接受**的 key：先记后查的话，"第一条是编的（被丢）、
     * 第二条是真的（被当重复丢掉）"就会把一条好推论连坐掉。
     * 保序 + 第一条赢（和 PoiCategories.keepValid 的 LinkedHashSet 同一个选择）：
     * 模型给了两条同 key 的判断，说明它自己都没定下来，
     * 而它先写的那条通常是它更确信的。
     *
     * @param raw     模型这一轮输出的原始三元组，可能为 null / 含 null 元素
     * @param history 完整对话历史。为 null 时**所有** evidence 都回查不中 ——
     *                这是对的：没有历史就没有依据，推论一条都不该留下
     */
    public static Screening screen(List<Persona.Inference> raw, List<Message> history) {
        if (raw == null || raw.isEmpty()) {
            return new Screening(List.of(), List.of());
        }
        List<Persona.Inference> accepted = new ArrayList<>();
        List<Rejection> rejected = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (Persona.Inference item : raw) {
            if (item == null) {
                continue;
            }
            // 认不出的 key 直接返回 null，所以这里同时兼了"key 是不是空的"这个判断
            PersonaTrait trait = PersonaTrait.ofKey(item.key());
            if (trait == null) {
                rejected.add(new Rejection(item.key(), item.value(), item.evidence(), REASON_UNKNOWN_KEY));
                continue;
            }
            if (item.value().isEmpty()) {
                rejected.add(new Rejection(item.key(), item.value(), item.evidence(), REASON_BLANK_VALUE));
                continue;
            }
            if (item.value().length() > VALUE_MAX) {
                rejected.add(new Rejection(item.key(), item.value(), item.evidence(), REASON_LONG_VALUE));
                continue;
            }
            if (item.evidence().isEmpty()) {
                rejected.add(new Rejection(item.key(), item.value(), item.evidence(), REASON_NO_EVIDENCE));
                continue;
            }
            if (squeeze(item.evidence()).length() < EVIDENCE_MIN) {
                rejected.add(new Rejection(item.key(), item.value(), item.evidence(), REASON_SHORT_EVIDENCE));
                continue;
            }
            if (!saidByUser(item.evidence(), history)) {
                rejected.add(new Rejection(item.key(), item.value(), item.evidence(), REASON_EVIDENCE_NOT_FOUND));
                continue;
            }
            if (!seen.add(trait.key())) {
                rejected.add(new Rejection(item.key(), item.value(), item.evidence(), REASON_DUPLICATE_KEY));
                continue;
            }
            // 进来的用 trait.key()（规范写法），不是模型原样给的那个 ——
            // 否则 " Walking " 和 "walking" 会在 Persona 里变成两个不同的 key，
            // 同 key 覆盖的合并规则当场失效。归一在这一行完成，只在闸门里做一次。
            accepted.add(new Persona.Inference(trait.key(), item.value(), item.evidence()));
        }
        return new Screening(accepted, rejected);
    }

    /**
     * 这句话是不是用户说过的。**整条防线的核心就这个方法。**
     *
     * 逐条查 user 消息（不查 assistant —— 理由见类注释），
     * 两边都先 {@link #squeeze} 归一，然后 contains：
     *   evidence 是用户某句话的**一段**就算命中。为什么要允许"一段"而不是整句相等：
     *   用户说的常常很长（"我想有一个放松的旅行，不要每天走很多路"），
     *   而 prompt 要求 evidence 抄**支撑这个结论的那一小段**。
     *   要求整句相等会把这条正确的用法判成非法。
     *
     * 空 evidence 在这里也返回 false（squeeze 后长度必然 < 3），
     * 所以调用方即使不查长度也是安全的 —— 但 screen 仍然单独查了一次，
     * 因为那两种情况该报**不同的原因**，混在一起排障时就分不出来了。
     */
    static boolean saidByUser(String evidence, List<Message> history) {
        if (history == null || history.isEmpty()) {
            return false;
        }
        String needle = squeeze(evidence);
        if (needle.length() < EVIDENCE_MIN) {
            return false;
        }
        for (Message m : history) {
            if (m == null || !m.isUser()) {
                continue;
            }
            if (squeeze(m.content()).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 归一：去掉所有空白与常见标点，英文转小写。
     *
     * 换行是最容易吃亏的一个 —— 用户在输入框里打字，回车换行是**随手**的
     * （"我想有个\n放松的旅行"），而 `Message` 的构造器只 strip 首尾。
     * 不归一的话这一条会被判成"用户没说过"，而它明明就原样躺在历史里。
     * 这个坑只有在真的往 UI 里敲多行文字时才会撞上 —— 也就是，一定会撞上。
     */
    static String squeeze(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isWhitespace(c) || IGNORED.indexOf(c) >= 0) {
                continue;
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }
}
