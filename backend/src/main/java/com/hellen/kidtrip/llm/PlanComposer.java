package com.hellen.kidtrip.llm;

import com.hellen.kidtrip.candidate.HotelCandidate;
import com.hellen.kidtrip.candidate.PoiCandidate;
import com.hellen.kidtrip.common.JsonText;
import com.hellen.kidtrip.plan.CandidateCodes;
import com.hellen.kidtrip.plan.Legs;
import com.hellen.kidtrip.plan.Persona;
import com.hellen.kidtrip.plan.TravelParams;
import com.hellen.kidtrip.retrieval.Transfer;
import com.hellen.kidtrip.retrieval.TransportMode;
import com.hellen.kidtrip.rule.PlanRules;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 第六段：让大模型**从真实候选里挑与排**，产出一份行程草稿。
 *
 * 这个类存在的理由，是三条被实测撞出来的事实：
 *
 * ① 模型一旦产出"事实"，就再也纠不回来。
 *    实测：给它一个不存在的景点名去检索，飞猪返回了一个**名字很像的真实景点**，
 *    于是假名字被贴上了真价格真坐标，全程零报错。查到了 ≠ 查对了。
 *    → 对策：事实只能来自飞猪。模型只许写 candidateId，其余字段由后端回填。
 *
 * ② 模型的输出**在契约上就不可靠**。
 *    "只输出 JSON"是一句请求，不是一个保证。它会加 ```json 围栏、会在前面说"好的"、
 *    偶尔会漏字段。所以解析失败是**常态**，要有重试。
 *    → 对策：剥围栏 + 失败重试一次（并在重试时把失败反馈给它）。
 *
 * ③ 机械可校验的东西，交给代码，不交给模型。
 *    "每天最多几个点""天数对不对""有没有重复"——这些一行代码就能算准。
 *    让模型去遵守，等于把确定性交给概率。
 *    → 对策：prompt 里说清楚（省 token、提高一次成功率），但**本地再强制一遍**（见 PlanAssembler）。
 *
 * 换句话说：prompt 里的每一条要求都是"优化"，不是"保证"。
 * 保证永远在代码里。这个分工想清楚了，跟大模型协作的绝大部分坑就没了。
 */
@Component
public class PlanComposer {

    private static final Logger log = LoggerFactory.getLogger(PlanComposer.class);

    /** 单次生成最多试几次。第 2 次会带上"上次不是合法 JSON"的提示 */
    private static final int MAX_ATTEMPTS = 2;

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    public PlanComposer(ChatModel chatModel, ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
    }

    /**
     * 让模型出一份草稿。
     *
     * @param pois   候选景点（**已经过规则校验、已经被编排层截过条数**）。
     *               编号 C1..Cn 由这个列表的顺序决定 —— 和 PlanAssembler 用的是同一套（CandidateCodes）。
     * @param persona **我们对这个用户的判断**（`/chat` 那一步产出、原样带过来的），每条带他的原话。
     *                **可以是空的**（没做过对话、或一句偏好都没推出来）——
     *                那时的效果只是"这份行程没有在为你做取舍"，行程照样排得出来。
     *                它只影响**怎么挑、怎么排**，**不进检索条件、不覆盖硬规则**。
     * @param hotels 候选酒店，编号 H1..Hm。**可以是空的**（needHotel=false，或飞猪没返回）——
     *               空的时候 prompt 里会明说"本次没有酒店数据"，让模型一律写 none，
     *               而不是让它对着一个不存在的清单瞎编（那会变成一条看不见的假告警）。
     * @param legs   已经查到的两段真实车次。**它决定第 1 天几点能开始、最后一天几点必须收摊** ——
     *               是这个 prompt 里唯一会"收窄"模型自由度的外部事实（见 renderLegs）。
     * @throws LlmException 调用失败，或两次都没返回可解析的 JSON
     */
    public PlanDraft draft(TravelParams params, Persona persona, List<PoiCandidate> pois,
                           List<HotelCandidate> hotels, PlanRules rules, Legs legs) {
        if (pois == null || pois.isEmpty()) {
            // 这一步值得单独挡：不挡的话我们会发一个"候选清单是空的"的 prompt 给模型，
            // 它必然会开始编景点 —— 而这是我们花了一整轮设计要避免的事。
            // 与其让模型有机会编，不如根本不给它这个机会。
            throw new LlmException("没有可用的候选景点，无法排行程");
        }

        String prompt = buildPrompt(params, persona, pois, hotels, rules.spotsPerDay(params.pace()), legs);
        Exception lastError = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            String raw;
            try {
                raw = chatModel.chat(attempt == 1 ? prompt : prompt + RETRY_HINT);
            } catch (Exception e) {
                // 调用本身失败（网络、超时、401、限流）—— **不重试**。
                // 理由和 FlyaiException 那边一样：这类失败重试一次大概率还是失败，
                // 而用户已经等了十几秒。宁可立刻告诉他"再点一次"，把等待的决定权还给他。
                log.error("调用大模型失败（第 {} 次尝试）", attempt, e);
                throw new LlmException("大模型调用失败：" + shortReason(e) + "，请稍后再试", e);
            }

            try {
                return objectMapper.readValue(JsonText.stripFences(raw), PlanDraft.class);
            } catch (Exception e) {
                lastError = e;
                log.warn("第 {} 次尝试：模型输出不是合法 JSON，原始输出前 300 字：{}",
                        attempt, JsonText.truncate(raw, 300));
            }
        }

        throw new LlmException("模型这两次都没返回可用的行程数据，请再点一次生成", lastError);
    }

    /* ============================================================
     * Prompt 构造
     * ============================================================ */

    private String buildPrompt(TravelParams params, Persona persona, List<PoiCandidate> pois,
                               List<HotelCandidate> hotels, int perDay, Legs legs) {
        String candidates = String.join("\n", CandidateCodes.renderLines(pois));
        List<HotelCandidate> hotelPool = hotels == null ? List.of() : hotels;
        boolean hasHotel = !hotelPool.isEmpty();
        String hotelSection = hasHotel
                ? "【候选酒店】共 " + hotelPool.size() + " 家，**只能从这里挑**，编号是 H 开头：\n"
                        + String.join("\n", CandidateCodes.renderHotelLines(hotelPool, pois))
                : "【候选酒店】本次**没有查到酒店数据**（所以下面的 hotelCandidateId 一律写 \"none\"，"
                        + "不要写任何 H 编号 —— 写了也会被丢弃）。";

        return """
                你是"童行"的亲子行程编排助手。
                你唯一的工作：从下面给定的【候选清单】里挑选景点与住处，排进每一天，
                并为每天写一个主题、为每个点写一句提示。

                【旅行参数】
                %s

                %s

                【候选景点】共 %d 条，**只能从这里挑**，编号是 C 开头：
                %s

                %s

                %s

                【硬性要求】
                1. days 的个数必须正好是 %d，day 从 1 到 %d，不能多也不能少。
                2. 每天最多 %d 个点（由上面的"节奏"决定）。
                3. **同一个 candidateId 在整份行程里只能出现一次** —— 一个景点只去一次，不要重复安排。
                4. 每个点只写 candidateId，**绝对不要写景点名、价格、地址** —— 那些由系统按编号回填。你写了也会被忽略。
                5. candidateId 必须原样来自上面的清单（形如 C1、C2）。清单里没有的编号一律不要写。
                6. time 用 HH:mm 格式；一天里第一个点不早于 09:00，最后一个不晚于 18:00
                   （但**第 1 天和最后一天要按第 13、14 条收窄**，那两条优先）。
                7. theme 是当天的一句话主题，不超过 15 个字。
                8. tips 一句话，结合孩子年龄和推车需求，给一个具体可操作的提醒（例如带什么、注意什么）。
                9. summary 一句话概括整体安排和适合人群。
                10. warnings 写 2~4 条真实注意事项（预约、门票、天气、体力等）。
                11. 如果候选不够排满，**宁可少排，也不要编造清单以外的地点**。少排是允许的，编造是不允许的。
                12. 尽量让每一天都有安排 —— 空着的那天对用户没有价值。
                13. 【第 1 天】如果前面给了去程车次，第一个点的 time 必须在**到达时刻之后至少 1 小时**
                    （例：10:39 到站 → 第一个点不早于 11:39），把出站和进城的时间留出来。这一天最多 2 个点。
                14. 【最后一天】如果前面给了回程车次，**所有点都要赶得上车**：
                    最后一个点的 time 必须比发车时刻早至少 3 小时
                    （例：14:16 发车 → 最后一个点不晚于 11:16）。
                    排不下就少排 —— 宁可这一天只安排一个上午的点，也不要排一个去不成的点。
                15. 同一天里的几个点要**挨得近**。候选清单里有的景点在几十公里外（跨了区甚至跨了市），
                    **不要把这种点和其它点排进同一天** —— 带着孩子，这段路会把那一天吃掉。
                    想玩另一片，就换一天；确认要整天待在那一片，那就顺便换住处（见第 19 条）。

                【住宿要求】（和景点那套不一样，仔细看）
                16. 每天都要填 hotelCandidateId，取值只有两种：
                    · 住某一家 → 写它的 H 编号，例如 "H2"；
                    · 当天返程或不需要住宿 → 写 "none"。
                    不要留空。
                17. **同一个 H 编号可以连续多天重复写，而且鼓励这样** ——
                    带小孩旅行换一次酒店就等于废掉半天（收拾、退房、入住、孩子适应环境）。
                    所以"连续三天都写 H1"是一个很好的答案，不要为了每天都不同而硬换酒店。
                    ⚠️ 注意这条和景点正好相反：景点绝不能重复，酒店重复是正常的。
                18. hotelTips 用一句话说清**为什么今晚住这儿**。
                    要提距离，**只能引用酒店行末尾"近：…"那一列里给出的数字**
                    （例如"离 C1 只有 0.7 公里，早上不用赶路"）。
                    ⚠️ **绝对不要自己写一个我们没给过的距离** —— 你写的公里数我们无从核实，
                    而用户会把它当成真事。不含距离的说法（"同一家住着，不用收拾行李"）永远是对的。
                    如果当天不住，hotelTips 写空字符串。
                19. **挑酒店时必须看行尾的"近：…"那一列** —— 那是系统按真实坐标算好的距离：
                    这家酒店到你那几个候选景点的直线距离（越靠前越近）。
                    判断标准只有一条：**今晚住的这家，要紧贴今天和明天要去的点**。
                    最忌讳的一种排法是"白天在一片玩、晚上住到几十公里外的另一片" ——
                    带娃来回这一趟，半天就没了。
                20. 上面【我对这个用户的判断】那一段（如果给了）是系统从**他的原话**里看出来的，
                    每条都附了他自己说的那句话。请**据此做取舍**：
                    比如"怕走路"就优先挑挨得近的几个点、宁可少排一个；
                    "要午睡"就把中午那段留空；"预算敏感"就少选贵的。
                    ⚠️ 但**绝对不要在 theme / tips / summary 里替我们解释理由** ——
                    不要写"因为您想放松，所以今天只安排了两个点"这类句子，
                    也**不要写任何我们没给过的数字**（距离、耗时、排队时间、价格）。
                    理由由系统自己算、自己写（它算得出真实数字）；你写的解释，
                    用户会当成真事，而我们一个字都核实不了。

                【输出格式】
                只输出一个 JSON 对象，不要任何解释文字，不要 markdown 代码块标记。
                {
                  "summary": "一句话总结",
                  "days": [
                    {
                      "day": 1,
                      "theme": "当天主题",
                      "hotelCandidateId": "H1",
                      "hotelTips": "为什么住这儿",
                      "items": [
                        { "candidateId": "C3", "time": "09:30", "tips": "小提示" }
                      ]
                    }
                  ],
                  "warnings": ["注意事项"]
                }
                """.formatted(
                renderParams(params),
                renderPersona(persona),
                pois.size(),
                candidates,
                hotelSection,
                renderLegs(legs),
                params.days() == null || params.days() <= 0 ? 3 : params.days(),
                params.days() == null || params.days() <= 0 ? 3 : params.days(),
                perDay);
    }

    /**
     * 把"我们看出来的这个用户"渲染给模型。**每个维度都带着他自己的原话** —— 这不是装饰。
     *
     * ── 为什么必须带依据 ───────────────────────────────────────────
     *   只给结论（"怕走路"）模型会把它**和【旅行参数】里的字段当成同一类东西**，
     *   也就是当成用户明确声明过的需求去执行 —— 于是"我们猜的"在它的输出里
     *   变成了"用户要的"，再往后就再也分不开了。
     *   带上依据（"怕走路 —— 他说「想有一个放松的旅行」"）它才知道这是我们**推的**，
     *   措辞上才会留余地（至少在它想写理由的时候会犹豫一下）。
     *   **真正兜底的是第 20 条**（不许自己写理由）：prompt 里说清楚是优化，
     *   而"理由由后端算"才是保证 —— 见 {@link com.hellen.kidtrip.plan.DayReasons}。
     *
     * ── 为什么空的时候要写一句"没有" ──────────────────────────────
     *   留白会被读成"漏拼了一段"。写明"还没有对他的判断"，模型才会老老实实
     *   按【旅行参数】排一份通用行程 —— 那正是没有 persona 时该有的行为。
     */
    private static String renderPersona(Persona p) {
        if (p == null || p.isEmpty()) {
            return """
                    【我对这个用户的判断】
                    目前还没有从他的对话里看出什么特别的偏好。就按上面的【旅行参数】正常安排。""";
        }
        return """
                【我对这个用户的判断】
                下面这些是系统从**他的原话**里看出来的（每条都附了他说过的那句话），
                请据此调整你的取舍 —— 但**不要在输出里解释理由**（见硬性要求第 20 条）：
                """ + p.renderForPrompt();
    }

    /**
     * 把已查到的往返车次渲染成给模型看的一段。
     *
     * ── 为什么必须有这一段（实测撞出来的）──────────────────────────
     *   在此之前，模型完全不知道用户哪天几点到、哪天几点走。它按"每天 09:00~18:00
     *   随便排"这套一般规则干活，于是排出了第 1 天 10:00 逛动物园（可车 10:39 才到）、
     *   最后一天 15:00 珠江夜游（可返程车 06:18 就走了）—— 单看每个点都正常，
     *   拼起来是一份根本走不成的行程。
     *
     *   这个错法和"模型编景点"不同：它没有编任何东西，它只是**缺一个前提**。
     *   对付幻觉要用白名单，对付这种要用**把前提给它** —— 两者的解法不一样，
     *   混为一谈就会去加一堆没用的约束条款。
     *
     * ⚠️ 但它仍然只是"优化"，不是"保证"：模型可能不听。所以：
     *    ① 这一段里的时刻是**真实的**，写错一个字它就排错了 —— 所以只用 Transfer 里
     *       已经规范化过的那几个字段，不自己拼车站名；
     *    ② 本地还有一道检查盯着最后一天（见 PlanService），模型真不听时用户会看到一句实话。
     *    这和整个工程的分工一致：**prompt 负责让它大概率对，代码负责让它不可能错得太离谱。**
     */
    static String renderLegs(Legs legs) {
        if (legs == null || !legs.hasAny()) {
            return """
                    【已查到的往返交通】本次**没有查到往返车次**（没有问出发地，或者这一次没查成）。
                    所以：每一天都按一般情况安排时间（第一个点不早于 09:00），
                    并且**不要**在 summary / tips 里提到任何具体的车次、车站或时刻 —— 那些你并不知道，
                    写了就会被当成我们查到的真事。""";
        }
        StringBuilder sb = new StringBuilder("""
                【已查到的往返交通】下面是系统从飞猪查到的**真实班次**（不是估计值）。
                第 1 天和最后一天的安排**必须**和它们对得上 —— 见硬性要求第 13、14 条：

                """);
        // ⚠️ 只渲染 **primary**（主选），不渲染 alternatives。
        //    段路同时有火车和飞机时，模型要做的判断只有一个："这一天几点能开始、几点必须收摊"，
        //    而那件事只有一个答案。把"另外还可以飞"也喂进来，它大概率会在 tips 里补一句
        //    "也可以考虑飞机"—— 一句我们无法核对的话（价格和时刻都可能被它改写），
        //    却一个字都不会让排程更准。备选走法是给**用户**看的，在界面上并排显示，见 TravelDay。
        if (legs.outboundTransfer() != null) {
            sb.append("- 去程：").append(legLine(legs.outboundTransfer())).append('\n');
        }
        if (legs.inboundTransfer() != null) {
            sb.append("- 回程：").append(legLine(legs.inboundTransfer())).append('\n');
        }
        sb.append("（车次不用你写进行程里 —— 系统会自己贴到对应那一天的交通行上。"
                + "你要做的只是按这两个时刻来安排当天第一个点和最后一个点。）");
        return sb.toString();
    }

    /**
     * "去程：11-17 10:10 深圳北 → 10:39 广州南（高铁G390，29 分钟）"
     *
     * ⚠️ 文案跟着载体走：飞机那边"车站未知"是个错词。这不是吹毛求疵 ——
     *    这段文字是给模型看的，而模型会把看到的词原样写进 tips 给用户看。
     */
    static String legLine(Transfer t) {
        boolean flight = t.mode() == TransportMode.FLIGHT;
        String place = flight ? "机场" : "车站";
        return renderMoment(t.depDateTime()) + " " + blankTo(t.depStation(), place + "未知")
                + " → " + renderMoment(t.arrDateTime()) + " " + blankTo(t.arrStation(), place + "未知")
                + "（" + blankTo(t.serviceNo(), flight ? "航班未知" : "车次未知") + "，"
                + (t.durationMinutes() == null ? "耗时未知" : t.durationMinutes() + " 分钟") + "）";
    }

    /**
     * "2026-11-17T10:10" → "11-17 10:10"。
     *
     * 去掉年份是有意的：对"安排当天几点"这件事，年份一点信息量都没有，
     * 而它每天都在 prompt 里重复占位。取不出形状就**原样返回** ——
     * 宁可让模型看到一串 ISO，也不要因为解析失败显示成空白（那等于这个约束凭空消失了）。
     */
    static String renderMoment(String isoMinutes) {
        if (isoMinutes == null) {
            return "时刻未知";
        }
        return isoMinutes.length() >= 16
                ? isoMinutes.substring(5, 10) + " " + isoMinutes.substring(11, 16)
                : isoMinutes;
    }

    /**
     * 把画像渲染成给模型看的一段文本。
     *
     * 为什么不直接 `objectMapper.writeValueAsString(params)` 扔 JSON 进去：
     * JSON 是给机器看的 —— `"stroller": null` 对一个模型来说是"一个叫 stroller 的字段值是空的"，
     * 而"**还没问过**"这个语义只有人话能表达。这两者差别很大：
     * 前者模型可能当成"不需要"，后者它才知道该在 tips 里提醒一句"推车问题待确认"。
     *
     * 这条规律通用：**凡是要模型理解语义的地方，写人话；凡是要机器精确解析的地方，写 JSON。**
     * 混用是很多 prompt 效果不稳定的根源。
     */
    private static String renderParams(TravelParams p) {
        StringBuilder sb = new StringBuilder();
        // 出发地和目的地要分两行写清楚，不能只给一个"城市"。
        // 因为后面那段【已查到的往返交通】说的就是"从出发地到目的地"这件事 ——
        // 模型得知道哪一头是家，才明白"去程"和"回程"分别对应行程的第几天。
        sb.append("出发地：").append(blankTo(p.origin(), "未提供（所以本次没有往返车次信息）")).append('\n');
        sb.append("目的地：").append(blankTo(p.city(), "未提供")).append('\n');
        sb.append("天数：").append(p.days() == null || p.days() <= 0 ? "未提供（按 3 天）" : p.days() + " 天").append('\n');
        sb.append("出发日期：").append(blankTo(p.startDate(), "未提供")).append('\n');
        sb.append("孩子年龄：").append(p.childAge() == null ? "未提供" : p.childAge() + " 岁").append('\n');
        sb.append("节奏：").append(blankTo(p.pace(), "未指定（按每天 3 个点）")).append('\n');
        // 三态在这里落地：null 必须渲染成"未确认"，绝不能被渲染成"不需要"。
        sb.append("是否需要推车：").append(
                p.stroller() == null ? "未确认" : (p.stroller() ? "需要" : "不需要")).append('\n');
        sb.append("用户偏好：").append(
                p.preferences() == null || p.preferences().isEmpty()
                        ? "无特别偏好"
                        : String.join("、", p.preferences()));
        return sb.toString();
    }

    /** 重试时追加的话。要**具体说清上次错在哪**，只说"请重试"是没用的 */
    private static final String RETRY_HINT = """


            【注意】你上一次的输出不是合法 JSON，系统无法解析。
            这次请**只输出一个 JSON 对象**：不要任何解释文字，不要 markdown 代码块标记（不要 ```）。
            """;

    private static String blankTo(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s.strip();
    }

    /** 从异常里取一句能给用户看的话，别把整个堆栈吐到接口上 */
    private static String shortReason(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return JsonText.truncate(m.lines().findFirst().orElse(m), 120);
    }
}
