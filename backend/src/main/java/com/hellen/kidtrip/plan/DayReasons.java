package com.hellen.kidtrip.plan;

import com.hellen.kidtrip.retrieval.Transfer;
import com.hellen.kidtrip.rule.PlanRules;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 每天那句「**为什么这么排**」。
 *
 * ══ 一句话说清它和 persona 的分工 ═══════════════════════════════════
 *   persona（Persona）说的是「**我们怎么理解这个人**」—— 一句我们从原话里推出来的判断。
 *   这个类说的是「**今天这一天，为什么是这个形状**」—— 一句由本地算得出的事实拼出来的话。
 *   两句都要，但它们不是一件事：
 *     只有 persona   → 用户看到"你说过想放松"，却不知道**这份行程哪一点体现了它**；
 *     只有事实       → 用户看到"今天 3 个点都在 1.2 公里内"，却不知道**你在不在乎这件事**。
 *   所以这里的每条理由都是"**事实 + 可选的一句原话引用**"。
 *
 * ══ 为什么这句理由**不交给模型写**（这是本类存在的唯一理由）═══════════
 *   理由里几乎每一句都有一个数字："都在 1.2 公里内""排到 11:00 就收尾"。
 *   数字是这份行程里**唯一可核实**的部分，而模型写理由时会写"这里不需要走很多路"这类
 *   **没法核实也没有数字**的句子 —— 听起来完全正常，却没有一个字能被验证。
 *   这和 PlanAssembler 回填名字价格、CandidateCodes 让模型只写编号是**同一个手法**：
 *   **把这件工作从"生成"降级成"从已有的事实里挑一句"。**
 *
 *   ⚠️ 所以这里的每个片段都必须是**本地已经算出来的量**：
 *      跨度 → {@link Geo}（后端唯一算距离的地方）；空档 → 相邻两点的时刻差；
 *      点数 → 排了几个；车次时刻 → {@link Transfer}（真实班次）；连住 → 相邻两天的 hotelId。
 *      一个数字都不是新引入的，全都是别处已经算过、只是以前没露给用户看的东西。
 *
 * ══ 为什么理由里可以引用用户原话 ═══════════════════════════════════
 *   因为那句话是**逐字验证过的** —— 它进得来就是因为 {@link PersonaGuard} 在对话历史里
 *   找到过它（见 {@link Persona#inferences()}）。所以引用它**不需要新的验证机制**：
 *   我们只是把已经过了闸门的东西拿出来给用户看。
 *   这比引用 value（"少走路"）好得多：value 是我们概括的，evidence 是他自己说的。
 *
 *   ⚠️ 诚实的边界（和 PersonaGuard 里那句是同一条）：
 *      引用能证明的是「**他说过这句话**」＋「**今天排成这样是事实**」，
 *      不能证明"这句话导致了今天排成这样"。我们知道的是：**这个人在这个维度上有判断，
 *      而这一天的形状正好和那个判断一致**。不一致的时候我们一个字都不引用（见 leansRelaxed）。
 *
 * ══ 可测性是设计出来的 ═══════════════════════════════════════════
 *   全是 static 纯函数，不碰 Spring、不碰时钟、不调模型 —— 单测毫秒级能钉死。
 *   照 firstDayConflict / spotSpreadConflict 那一套写，理由和它们一样。
 */
public final class DayReasons {

    /**
     * 当天最远的一段 ≤ 它，"走两步就到"这句话才成立。
     *
     * ⚠️ 它和 PlanRules.maxDistanceKm（15 公里）**不是一回事，故意不共用**：
     *   15 是"该提醒你了"的红线（超了要写警告），3 是"值得夸一句"的线。
     *   拿 15 当这个阈值的话，"今天几个点都在 14.6 公里内"也会被写成"走两步就到"——
     *   而 14.6 公里带着孩子是要打车的一段路。**两个判据关心的是不同的事，
     *   合用一个数字只会让其中一件事说错话。**
     */
    static final double WALKABLE_KM = 3.0;

    /**
     * 相邻两个点之间 ≥ 它，才谈得上"中午留了空档"。
     *
     * 100 分钟 ≈ 一顿饭（40 分）+ 一次小睡（40 分）+ 一点余量。
     * 定低了会把"两点的正常间隔"也说成"特地留了空档"，那是在替一份普通行程邀功。
     */
    static final int MIDDAY_GAP_MINUTES = 100;

    /**
     * 一句理由最多说两条。
     *
     * 三条以上就不再是"理由"了，是一段说明文 —— 而这一行要回答的只有一句
     * "今天为什么是这个形状"。**说不完的就不说**：用户真关心的话，那些事实
     * （哪几个点、几点、住哪）就在上面几行里摆着。
     */
    private static final int MAX_CLAUSES = 2;

    /** 引用的原话最多留这么长。用户真说过 77 个字，全抄进来会把这一行撑成一堵墙 */
    static final int QUOTE_MAX = 24;

    /**
     * 模型表达"**相反方向**"时用的说法。
     *
     * 见 {@link #leansRelaxed}：判据是**排除法**，而且这是刻意的。
     */
    private static final String[] OPPOSITE_WORDS = {"无所谓", "不介意", "不用", "不担心", "都行"};

    private DayReasons() {
    }

    /* ============================================================
     * 对外只有两件事：给整份行程挂上，和算单独一天
     * ============================================================ */

    /**
     * 给每一天挂上理由。
     *
     * ⚠️ 它必须排在**所有其它后处理之后**（attachLegs / attachHotelAlternatives 后面）——
     *    因为理由里要用到它们挂上去的东西：车次时刻、以及"昨晚住哪"。
     *    排在前面的症状不是报错，而是**理由集体变少**（"今天要赶 14:16 的车"那句
     *    永远不出现，因为那一刻 transfer 还是 null）—— 一句都不报，只是少。
     *    PlanService 里那几行的顺序就是它唯一的保障。
     *
     * @param persona 可以是 null（还没有任何判断，或调用方压根没传）—— 那就没有原话可引用，
     *                理由是纯事实的那几句。这是完全正常的输入，不是缺参数
     */
    public static List<TravelDay> attachWhy(List<TravelDay> days, Persona persona,
                                            PlanRules rules, TravelParams params) {
        if (days == null || days.isEmpty()) {
            return days;
        }
        Persona p = persona == null ? Persona.empty() : persona;
        PlanRules r = rules == null ? PlanRules.DEFAULT : rules;
        // "常规几个点"这个基准来自规则表 + 用户说的节奏 —— 和 PlanComposer 排程时用的是同一个数。
        // 用一个自己的数字（比如写死 3）会让"今天少排了一个"这句话和实际的排程依据对不上。
        int perDay = r.spotsPerDay(
                params == null ? null : params.pace(),
                params == null ? null : params.youngestChildAge());

        List<TravelDay> out = new ArrayList<>(days.size());
        for (int i = 0; i < days.size(); i++) {
            TravelDay d = days.get(i);
            out.add(new TravelDay(d.day(), d.theme(), d.spots(), d.transfer(), d.alternatives(),
                    d.hotel(), d.hotelAlternatives(),
                    whyOf(d, i == 0 ? null : days.get(i - 1), p, perDay, params)));
        }
        return out;
    }

    /**
     * 算单独一天的理由。没有可说的事实就返回 **null** —— "没有理由"是一个合法答案。
     *
     * ⚠️ 刻意**不兜底**一句"今天这样安排是为了让你轻松"：那种句子对任何一天都成立，
     *    所以它一个字的解释力都没有，还会把真正有内容的理由挤掉（用户读到的第一句
     *    永远是那句废话）。**宁可这一行空着。**
     */
    static String whyOf(TravelDay day, TravelDay yesterday, Persona persona, int perDay,
                        TravelParams params) {
        if (day == null) {
            return null;
        }
        // 按**时刻**排，不按列表顺序 —— 和 PlanService 那几处是同一条讲究：
        // "列表第一个"是位置，"最早的点"是时间语义。理由里要报数字（跨度、空档），
        // 顺序一乱，报出来的"相邻两点"其实是两个根本不挨着的点，**那个数字就是错的**。
        List<TravelSpot> ordered = PlanService.sortedByTime(day);

        List<String> clauses = new ArrayList<>();
        addIfPresent(clauses, arrivalClause(day, ordered, params));
        addIfPresent(clauses, departureClause(day, ordered, params));
        addIfPresent(clauses, walkClause(ordered, persona));
        addIfPresent(clauses, middayClause(ordered, persona));
        addIfPresent(clauses, fewerClause(ordered, perDay));
        addIfPresent(clauses, sameHotelClause(day, yesterday));

        if (clauses.isEmpty()) {
            return null;
        }
        return String.join("；", clauses.subList(0, Math.min(MAX_CLAUSES, clauses.size())));
    }

    /* ============================================================
     * 六个片段 —— 每一个都只回答"这一天的哪一个形状值得解释"
     * ============================================================ */

    /**
     * 「去程 10:39 到站，所以今天第一个点排在 12:00」
     *
     * 这一句解释的是**第 1 天为什么看起来"起步晚"** —— 没有它，用户看到下午才开始玩，
     * 会以为行程排得松，而真实原因是那趟车的到达时刻。
     */
    private static String arrivalClause(TravelDay day, List<TravelSpot> ordered, TravelParams params) {
        Transfer t = day.transfer();
        if (!isOutbound(t, params)) {
            return null;
        }
        String arrive = hhmm(t.arrDateTime());
        if (arrive == null) {
            // 时刻读不出来就整句不说。**宁可不解释，也不要解释错** ——
            // 这一句里唯一的数字就是那个时刻，编不出来就不该有这一句。
            return null;
        }
        LocalTime first = ordered.isEmpty() ? null : PlanService.timeOfSpot(ordered.get(0).time());
        return first == null
                ? "去程 " + arrive + " 到站，今天从到站之后才开始安排"
                : "去程 " + arrive + " 到站，所以今天第一个点排在 " + first;
    }

    /**
     * 「回程 14:16 发车，今天排到 11:00 就收尾」
     *
     * 这一句解释的是**最后一天为什么"收得早"** —— 和上一条对称。
     * 两句合起来正好是 B7 那条"排程必须知道两个真实时刻"在界面上的投影：
     * 那条约束以前只存在于 prompt 和警告里，用户从来看不见它**兑现**成了什么。
     */
    private static String departureClause(TravelDay day, List<TravelSpot> ordered, TravelParams params) {
        Transfer t = day.transfer();
        if (!isInbound(t, params)) {
            return null;
        }
        String depart = hhmm(t.depDateTime());
        if (depart == null) {
            return null;
        }
        LocalTime last = ordered.isEmpty()
                ? null
                : PlanService.timeOfSpot(ordered.get(ordered.size() - 1).time());
        return last == null
                ? "回程 " + depart + " 发车，今天要留出赶车的时间"
                : "回程 " + depart + " 发车，今天排到 " + last + " 就收尾";
    }

    /**
     * 「今天 3 个点都在 1.2 公里内，走两步就到」／「你说过「…」，所以…」
     *
     * ⚠️ 只要有一段距离**算不出来**（缺坐标），整句就不说。
     *    "都在 X 公里内"这个结论只有在**每一段都算得出**时才成立 ——
     *    拿算得出的那几段下结论，恰好会在"有一处缺坐标"时给出一个**偏小**的数字，
     *    而那正是最容易让人放心的方向。这跟 Geo 里"缺坐标回 null 不回 0"是同一条规矩。
     */
    private static String walkClause(List<TravelSpot> ordered, Persona persona) {
        if (ordered.size() < 2) {
            return null;
        }
        double worst = 0;
        for (int i = 1; i < ordered.size(); i++) {
            TravelSpot a = ordered.get(i - 1);
            TravelSpot b = ordered.get(i);
            Double km = Geo.between(a.lat(), a.lng(), b.lat(), b.lng());
            if (km == null) {
                return null;
            }
            worst = Math.max(worst, km);
        }
        if (worst > WALKABLE_KM) {
            return null;
        }
        return cite(persona, PersonaTrait.WALKING)
                + "今天 " + ordered.size() + " 个点都在 " + kmInside(worst) + "内，走两步就到";
    }

    /**
     * 「中午留了 2 小时的空档」（配上 rest=要午睡 时会变成「你说过「…」，所以中午留了…」）
     *
     * 取的是**最长的一段间隔**，不是第一段：一天里最值得解释的是"那一段空得最明显的地方"。
     */
    private static String middayClause(List<TravelSpot> ordered, Persona persona) {
        if (ordered.size() < 2) {
            return null;
        }
        int best = 0;
        for (int i = 1; i < ordered.size(); i++) {
            LocalTime from = PlanService.timeOfSpot(ordered.get(i - 1).time());
            LocalTime to = PlanService.timeOfSpot(ordered.get(i).time());
            if (from == null || to == null) {
                continue;
            }
            int gap = (int) java.time.Duration.between(from, to).toMinutes();
            best = Math.max(best, gap);
        }
        if (best < MIDDAY_GAP_MINUTES) {
            return null;
        }
        // 复用 PlanService 那个"分钟 → 人话"的格式化 —— 同一个数字在界面别处已经这么写了，
        // 两处各写一份的话，同一天的空档会在两个地方显示成不同的说法。
        return cite(persona, PersonaTrait.REST) + "中午留了 " + PlanService.humanMinutes(best) + "的空档";
    }

    /**
     * 「今天只排了 2 个点（常规 3 个），留了余量」
     *
     * 基准 `perDay` 就是排程时真正用的那个数（来自 pace），所以"少了一个"这句话是真的少了一个。
     * ⚠️ 这一句**不引用 persona**：少排一个点可能是怕走路、要午睡、也可能只是池子里没得排 ——
     *    我们分不出是哪一个，就**不替它认领功劳**。
     */
    private static String fewerClause(List<TravelSpot> ordered, int perDay) {
        if (ordered.isEmpty() || ordered.size() >= perDay) {
            return null;
        }
        return "今天只排了 " + ordered.size() + " 个点（常规 " + perDay + " 个），留了余量";
    }

    /**
     * 「今晚还住同一家，不用收拾行李」
     *
     * 这一条在带娃旅行里的分量比看上去大（换一次酒店 ≈ 废掉半天），而它此前
     * 只以"连住 2 晚"这三个字出现在住宿那行 —— 那是**描述**，不是**理由**。
     */
    private static String sameHotelClause(TravelDay day, TravelDay yesterday) {
        if (yesterday == null || day.hotel() == null || yesterday.hotel() == null) {
            return null;
        }
        if (!day.hotel().hotelId().equals(yesterday.hotel().hotelId())) {
            return null;
        }
        return "今晚还住同一家，不用收拾行李";
    }

    /* ============================================================
     * 引用用户原话 —— 这一层是"理由"和"废话"的分界线
     * ============================================================ */

    /**
     * 引用一句用户原话当前缀；拿不到就返回**空串**（调用方直接拼，不用判 null）。
     *
     * 返回形如 `你说过「我想有一个放松的旅行」，所以`，拼上后面的片段就成一句话。
     *
     * ⚠️ 两个条件都满足才引用，缺一个都返回空串：
     *    ① 这个维度上真有判断（persona 里有，而且有依据）；
     *    ② 那条判断**方向对得上**（见 leansRelaxed）。
     *    ②是必须的，而且是最容易漏的一个：如果模型推的是 `walking = 无所谓`
     *    （"我们体力好，一天走两万步很轻松"），却把这句话当成"今天排得轻松"的理由，
     *    就成了一句**引用着相反意思的假话** —— 而且它引用的原话是真的，
     *    所以从界面上完全看不出来。这正是这个项目最忌讳的那种错。
     */
    static String cite(Persona persona, PersonaTrait trait) {
        if (persona == null) {
            return "";
        }
        for (Persona.Inference i : persona.inferences()) {
            if (!trait.key().equals(i.key())) {
                continue;
            }
            if (i.evidence().isEmpty() || !leansRelaxed(i.value())) {
                return "";
            }
            return "你说过「" + shorten(i.evidence()) + "」，所以";
        }
        return "";
    }

    /**
     * 这条判断是不是"**往省事那边**"的 —— 只有它才配当"今天排得轻松"的理由。
     *
     * ⚠️ 判据是**排除法**，这是刻意的，不是偷懒：
     *   模型表达"省事"的说法是开放的（少走路 / 不想走太多路 / 尽量少走 / 别太累…），
     *   穷举不完；而它表达"**相反方向**"的说法是个很小的闭集（无所谓 / 不介意 / 不用…）。
     *   所以只排掉后面那一小撮，剩下的都当"往省事那边"。
     *
     *   两个方向的代价不对称：
     *     排错了（该引用没引用）→ 用户少看到一句解释，那一行照样是句真话；
     *     放错了（不该引用却引用了）→ 用户看到一句**引用着他自己原话的假话**。
     *   **拿不准时选代价小的那一边**（和 PersonaGuard 的 squeeze 是同一条取舍）。
     */
    static boolean leansRelaxed(String value) {
        if (value == null) {
            return false;
        }
        String v = value.strip();
        if (v.isEmpty()) {
            return false;
        }
        for (String opposite : OPPOSITE_WORDS) {
            if (v.contains(opposite)) {
                return false;
            }
        }
        return true;
    }

    /** 太长的原话截一段加省略号 —— 它**仍然是原话**（没有改写、没有润色），只是没抄全 */
    private static String shorten(String evidence) {
        return evidence.length() <= QUOTE_MAX ? evidence : evidence.substring(0, QUOTE_MAX) + "…";
    }

    /* ============================================================
     * 小工具
     * ============================================================ */

    /**
     * 这一段是"从家出发"吗。
     *
     * ⚠️ 判据是**数据本身**（车次的 from == 画像里的出发地），不是"第 1 天" ——
     *    和 TravelDay 那份注释里说的是同一条：以后支持多城市串联时，
     *    "第 1 天就是去程"这个规则当场失效，而这条不会。
     */
    private static boolean isOutbound(Transfer t, TravelParams params) {
        return t != null && params != null
                && !blank(params.origin()) && !blank(t.from())
                && params.origin().strip().equals(t.from().strip());
    }

    /** 这一段是"回家"吗。同上，读 from 和画像里的目的地 */
    private static boolean isInbound(Transfer t, TravelParams params) {
        return t != null && params != null
                && !blank(params.city()) && !blank(t.from())
                && params.city().strip().equals(t.from().strip());
    }

    /** ISO 到分钟 → "10:39"；形状不对返回 **null**（不返回原串 —— 这一句是拼给用户看的） */
    private static String hhmm(String isoMinutes) {
        LocalTime t = PlanService.timeOf(isoMinutes);
        return t == null ? null : t.toString();
    }

    /**
     * "1.2 公里" —— 专门给"…都在 X 公里内"这个句式用。
     *
     * 不用 {@link Geo#kmText}：它那句"不到 1 公里"塞进这个句式会读成"在不到 1 公里内"。
     * 而这里说的一直是**上界**（"都在 X 以内"），所以一个小数点后一位的近似值是合适的说法。
     */
    static String kmInside(double km) {
        return String.format(Locale.ROOT, "%.1f 公里", Math.max(km, 0.1));
    }

    private static void addIfPresent(List<String> clauses, String clause) {
        if (clause != null && !clause.isBlank()) {
            clauses.add(clause);
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
