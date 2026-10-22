package com.hellen.kidtrip.llm;

import com.hellen.kidtrip.common.JsonText;
import com.hellen.kidtrip.flyai.CityNames;
import com.hellen.kidtrip.plan.Message;
import com.hellen.kidtrip.plan.Persona;
import com.hellen.kidtrip.plan.PersonaGuard;
import com.hellen.kidtrip.plan.PersonaTrait;
import com.hellen.kidtrip.plan.PlanChatData;
import com.hellen.kidtrip.plan.TravelDates;
import com.hellen.kidtrip.plan.TravelParams;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * B5 第一步：**对话 → 画像**。
 *
 * 它是"上下文传递"里真正用到上下文的那一环：把**全部对话历史** + **已经攒出来的画像**
 * 一起交给模型，让它①说一句该说的话，②给出这一轮新听出来的字段。
 *
 * 这个类的骨架来自参照工程（kidtrip-planner-backend 的 TravelChatService，
 * 那份契约是前后端已经跑通的 —— 请求 `{messages, currentParams}` / 响应 `{reply, params}`），
 * 但有**四处刻意不同**，每一处都是那一版做不到或者做错的事：
 *
 * ① startDate —— 参照工程的画像里**没有这个字段**。我们加了（酒店要用它算入离日期），
 *    所以 prompt 里也得让模型去问，否则"出发日期"永远是空的。
 *
 * ② 三态 —— 参照工程用 `stroller: false` 表示"默认不需要"，于是"还没问过"和"明确不需要"
 *    被合并成一个值。这里改成 null=true 没问过 / true=需要 / false=不需要，
 *    **并且 prompt 里明确写了这三种值的区别** —— 模型不知道区别的话，代码写得再对也没用。
 *
 * ③ 增量输出 —— 参照工程的 prompt 要求模型"输出合并后的完整状态"。
 *    这里改成**只输出这一轮新听出来的字段**，合并交给代码（TravelParams.mergedWith）。
 *    理由是"让模型复述全部历史"本身就是个错误来源：它可能把上一轮的城市写错、漏掉一个偏好，
 *    而这些错误都会被当成"新信息"信以为真。
 *    ⚠️ 这条改动是安全的，因为合并逻辑对两种输出都成立 —— 模型要是仍然输出完整状态，
 *       mergedWith 一样会得到正确结果。**改 prompt 而不改数据流，前提是数据流本来就能容纳两种输入。**
 *
 * ④ 重试 —— 参照工程解析失败就降级回一句话。这里先重试一次（带上"上次不是合法 JSON"的提示），
 *    仍然失败才降级。理由和 PlanComposer 那边一样：模型输出不是合法 JSON 是**常态**，
 *    一次就放弃等于把一次本可以成功的对话白白浪费掉。
 *
 * ⑤ **洗净模型输出**（sanitize，2026-10-13 实测后补的）—— 见下面两个守门类。
 *    教训来自用户在界面上真实走的那一遍：他说"广东周边"，模型就填"广东周边"；
 *    他说"11月17日"，模型填了**去年**的 11-17。两件事模型都"没做错"，
 *    它只是照抄了它听到的、猜了一个它不可能知道的年份 —— 而我们的代码一条都没挡。
 *    修法分两头，缺一不可：
 *      · prompt 说清楚（模型不该犯）——但 prompt 是**优化**，不是保证；
 *      · sanitize 兜住  （犯了也没用）——这才是保证。
 *    和 PoiCategories / CandidateCodes 是同一套：**能机械校验的，一律不信模型。**
 *
 * ⑥ **顺带产出 persona**（2026-10-15 加）—— 同一轮对话里，除了"他说的"（params），
 *    还要产出"**我们从他话里看出来的**"（persona）。理由不是省一次调用（虽然确实省了），
 *    而是**它们必须来自同一段上下文**：这一轮他的话推出来的判断，和他这一轮说的字段，
 *    是同一件事的两面，分开两次调用去推，两边一定会出现不一致。
 *
 *    ⚠️ 但产出之后**立刻分道扬镳**：params 走 sanitize + mergedWith，
 *       persona 走 {@link PersonaGuard} + `mergedWith`（另一套合并规则）。
 *       两条路的规则**故意相反**，混用会把推错的结论永久粘住 —— 见 Persona 类注释。
 *       这个类只负责"拆开"，不负责"判断哪个规则对"。
 *
 * ── 和 PlanComposer 最重要的一处不同：**这里失败不抛异常** ──────────────
 * PlanComposer（排行程）失败必须抛 LlmException —— 没有候选就没有任何退路，报错是唯一诚实的选择。
 * 这里（聊天）失败**降级成一句话**："抱歉，我没太理解你的意思，能再说一遍吗？"，画像原样保留。
 * 判断标准是一条：**失败之后还有没有别的出路？**
 *   聊天有 —— 用户再说一遍就行，而且对话不能因为一次解析失败就断掉；
 *   排行程没有 —— 只能报错让用户重试。
 * 同一件事（LLM 调用失败）在两条路径上处理方式不同，不是不一致，是**出路不同**。
 */
@Component
public class ChatComposer {

    private static final Logger log = LoggerFactory.getLogger(ChatComposer.class);

    /** 单次对话最多试几次。第 2 次带上"上次不是合法 JSON"的提示 */
    private static final int MAX_ATTEMPTS = 2;

    /** 解析不出来时的兜底话术。要能继续对话，不能是一句死胡同 */
    private static final String FALLBACK_REPLY = "抱歉，我没太理解你的意思，能再说一遍吗？";

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    public ChatComposer(ChatModel chatModel, ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
    }

    /**
     * 走一轮对话。
     *
     * @param messages 全部对话历史（不含本轮以外的加工），顺序即时间顺序，最后一条必须是用户说的
     * @param current  已经攒出来的画像。**不要传 orDefaults() 过的**，否则"还没问过"会变成"已答"
     * @throws LlmException 调用大模型本身失败（网络 / 超时 / 401 / 限流）
     */
    public PlanChatData chat(List<Message> messages, TravelParams current, Persona currentPersona) {
        TravelParams base = current == null
                ? TravelParams.empty()
                : current;
        Persona basePersona = currentPersona == null ? Persona.empty() : currentPersona;
        // 今天取一次就传下去，不在各处分别取 —— 否则一个请求跨过午夜时，
        // prompt 里的"今天"和校验用的"今天"会不是同一天，出现"模型填的日期被判成过去"这种鬼事。
        LocalDate today = LocalDate.now();
        String prompt = buildPrompt(messages, base, basePersona, today);
        Exception lastError = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            String raw;
            try {
                raw = chatModel.chat(attempt == 1 ? prompt : prompt + RETRY_HINT);
            } catch (Exception e) {
                // 调用本身失败不重试 —— 和 PlanComposer / FlyaiRunner 同一条规矩：
                // 这类失败重试大概率还是失败，而用户已经等着了。把"要不要再试一次"还给他。
                throw new LlmException("大模型调用失败：" + shortReason(e) + "，请稍后再试", e);
            }

            try {
                Parsed parsed = parseModelText(raw);
                // ⭐ 先洗净再合并：模型这一轮听出来的东西里，凡是"能机械判断对错"的，
                //    都不直接采信 —— 见 sanitize 的注释。
                TravelParams merged = base.mergedWith(sanitize(parsed.patch(), today));
                // ⭐⭐ persona 走**另一条路**：过 PersonaGuard 闸门（逐字回查原话），
                //     再按"同 key 覆盖、没提到的保留"并进去。**绝不能和画像共用 mergedWith。**
                //     理由见 Persona 类注释第 ① 条：画像那套"没答的字段保留原值"会把
                //     推错的结论永久粘住，而推论恰恰需要每轮能被推翻。
                //
                //     ⚠️ 回查用的是**这一轮实际送进 prompt 的那份历史**（messages 已被 ChatService
                //        按条数截断）。所以聊得特别久、早期原话已经被挤出窗口时，某条新推论的
                //        依据可能"查不中"而被丢。这是**刻意的**：我们只认模型看得见的东西 ——
                //        它没看到那句话却拿它当依据，本来就更可疑。已经并进 basePersona 的旧条目
                //        不受影响（合并只覆盖 patch 里出现的 key）。
                PersonaGuard.Screening screening = PersonaGuard.screen(parsed.persona(), messages);
                Persona mergedPersona = basePersona.mergedWith(screening.toPersona());
                log.info("对话一轮：原始输出 {} 字 → reply {} 字，画像 {} → {}，判断 {} → {}（本轮 {}/{} 条过闸门）",
                        raw == null ? 0 : raw.length(), parsed.reply().length(),
                        describe(base), describe(merged), basePersona.size(), mergedPersona.size(),
                        screening.accepted().size(),
                        screening.accepted().size() + screening.rejected().size());
                if (!screening.isClean()) {
                    // 被拦的**必须记下来**，不然"模型没推"和"推了但没拿出原话"就分不出来，
                    // 而这两个数字的差别决定了 prompt 该怎么改（见 PersonaGuard.Screening 的注释）。
                    for (PersonaGuard.Rejection r : screening.rejected()) {
                        log.info("persona 被闸门拦下：{}={}（依据「{}」）—— {}",
                                r.key(), r.value(), r.evidence(), r.reason());
                    }
                }
                return new PlanChatData(parsed.reply(), merged, mergedPersona);
            } catch (Exception e) {
                lastError = e;
                log.warn("第 {} 次尝试：对话输出不是合法 JSON，原始输出前 300 字：{}",
                        attempt, JsonText.truncate(raw, 300));
            }
        }

        // 两次都没解析出来 —— 降级，不抛错（理由见类注释最后一段）
        log.error("两轮都没解析出可用的对话结果，降级回一句兜底话术", lastError);
        return new PlanChatData(FALLBACK_REPLY, base, basePersona);
    }

    /* ============================================================
     * 洗净 —— 对模型这一轮听出来的字段做机械校验
     * ============================================================ */

    static TravelParams sanitize(TravelParams patch) {
        return sanitize(patch, LocalDate.now());
    }

    /**
     * 把模型这一轮听出来的字段里**能机械判断对错**的部分修正掉。
     *
     * 为什么要有这一层，而不是只把 prompt 写清楚：
     *   2026-10-13 用户在界面上真实走了一遍，两个 bug 都不是"模型不听话"——
     *   模型是**严格照我们说的做的**：prompt 里的示例写着 `city："珠海"、"深圳周边"`，
     *   它就输出了"广东周边"；prompt 里没提今天几号，它就猜了去年的年份。
     *   这类错误的共同点：**它不可能靠"更努力地思考"避免**（模型不知道今天几号），
     *   所以只能靠代码兜。而且 prompt 改好之后这一层也不能删 ——
     *   因为 prompt 里的每条要求都是优化，不是保证。
     *
     * 只处理两个字段，而且都是"改掉比留着强"的那种：
     *   city       —— 区域说法（"广东周边"）下游一条都查不到，留着等于给了一份注定空的检索。
     *                 改成 null = "这个字段还没答"，于是模型下一轮会再问一次，用户有机会纠正。
     *                 这比"替他猜一个城市"好：猜错了要重来一遍，问一次只要一句话。
     *   origin     —— **和 city 完全同一类错**，所以用同一个判据处理。
     *                 模型听到"从广东出发"、或者把"广东周边"同时填进两个字段，都是一样的事。
     *                 为什么要单独想一遍而不是"顺手也做一样的事"：origin 是 2026-10-13 才加的，
     *                 而那天的教训正是**加字段的时候最容易漏掉守门** ——
     *                 city 有防线而 origin 没有，症状会是"换了个说法，同一个 bug 又回来了"。
     *   startDate  —— 过去的日期顺延到最近的一次（用户说"11月17日"不可能指去年）。
     *
     * ⚠️ 为什么**不**在这里顺手过滤 preferences（比如只留下飞猪认得的类别）：
     *   那是 RetrievalPlanner 的活，而且它的输入本来就该是用户的如实偏好 ——
     *   preferences 是"用户想要什么"的记录，不是"我们要查什么"的命令。
     *   在这里提前过滤，溯源面板上就会少掉"用户其实说过 X"这条信息。
     *   **每个字段在哪一层被加工，取决于那一层是不是唯一知道该怎么做判断的人。**
     *
     * @param today 显式传，让它成为纯函数（能测"2026-10-13 那天会发生什么"）
     */
    static TravelParams sanitize(TravelParams patch, LocalDate today) {
        if (patch == null) {
            return null;
        }
        boolean cityUnusable = unusableCity(patch.city());
        boolean originUnusable = unusableCity(patch.origin());

        String rawDate = patch.startDate();
        TravelDates.Normalized date = TravelDates.normalize(rawDate, today);
        boolean dateDropped = rawDate != null && !rawDate.isBlank() && date.date() == null;

        if (!cityUnusable && !originUnusable && !date.corrected() && !dateDropped) {
            // 没问题就原样返回，连对象都不重建 —— 让"这一轮什么都没改"在引用上也是可判断的
            return patch;
        }
        if (cityUnusable) {
            log.info("模型把「{}」当城市填了 —— 它不是一个具体城市，已按'这个字段还没答'处理，"
                    + "下一轮会让它重新问", patch.city());
        }
        if (originUnusable) {
            log.info("模型把「{}」当出发地填了 —— 它不是一个具体城市，已按'这个字段还没答'处理",
                    patch.origin());
        }
        if (date.corrected()) {
            log.info("模型给的出发日期 {} 已经过去了，已顺延到 {}", rawDate, date.date());
        }
        if (dateDropped) {
            log.info("模型给的出发日期 {} 解析不出来，已当作'还没听到'", rawDate);
        }
        return new TravelParams(
                originUnusable ? null : patch.origin(),
                cityUnusable ? null : patch.city(),
                patch.days(),
                date.date(),
                patch.preferences(),
                patch.pace(),
                patch.childAges(),
                patch.stroller()
        );
    }

    /**
     * "这个值不能拿去查飞猪"。
     *
     * 空值不算"不能用" —— 空的含义是"还没问过"，那是合法状态，不该在这里被判死。
     * 判死的只有一种：**非空、但不是一个具体城市**（"广东周边""珠三角"）。
     * 抽成一个方法是因为 city 和 origin 用的是同一条判断，而它们必须**永远**是同一条 ——
     * 各写一遍的话，某天改了其中一句，症状会是"目的地挡住、出发地漏过"这种半截防线。
     */
    private static boolean unusableCity(String value) {
        return value != null && !value.isBlank() && !CityNames.isSingleCity(value);
    }

    /* ============================================================
     * 解析 —— 纯函数部分，单独抽出来就是为了能测
     * ============================================================ */

    /**
     * 一次对话的解析结果。
     *
     * 三个字段分两类：`reply` + `patch` 是**用户说过的**（照抄、校验后就合并），
     * `persona` 是**模型推的**（要先过逐字回查的闸门）。**它们在合并那一步分道扬镳**，
     * 所以这里也不合并成一个"合并后的画像" —— 解析这一层只负责"把模型说的拆开"。
     *
     * `persona` 允许是 null（模型这次没写这个字段）—— 那等价于"这一轮没有新判断"，
     * 由 PersonaGuard.screen 处理成空结果。
     */
    record Parsed(String reply, TravelParams patch, List<Persona.Inference> persona) {
    }

    /**
     * 把模型的原始输出解析成 { 话术, 画像补丁, 判断补丁 }。
     *
     * 抽成包级可见的方法 + 不碰 chatModel，就使它成为**纯函数**：
     * 测试里可以喂各种"模型可能会写出来的鬼东西"进去，
     * 而不用真的花一次调用、等十几秒。
     * 这和 PlanAssembler 能被单测是同一个道理 —— **可测性是设计出来的。**
     *
     * ⚠️ 这里**不校验** persona（不看 key 认不认识、不查依据在不在历史里）——
     *    那是 PersonaGuard 的职责，而且它要**记账**。在解析里顺手筛一遍的话，
     *    被筛掉的条目会无声消失，排障时只会看到"模型没输出"（见 Persona 类注释最后一段）。
     *
     * @throws Exception JSON 不合法（由调用方决定重试还是降级）
     */
    Parsed parseModelText(String raw) throws Exception {
        RawChat node = objectMapper.readValue(JsonText.stripFences(raw), RawChat.class);

        String reply = node.reply() == null || node.reply().isBlank()
                ? FALLBACK_REPLY
                : node.reply().strip();

        return new Parsed(reply, node.params(), node.persona());
    }

    /**
     * 模型输出的**原始形状**。
     *
     * 刻意不复用 TravelParams 直接反序列化 —— 因为要能容忍 `params` 整个缺失。
     * 模型在"信息已经齐了"这一类回复里，有时会把 params 省略掉；
     * 直接映射到 TravelParams 会得到 null，虽然也处理得了，但语义上分不清
     * "模型说没有新信息"和"模型忘了写" —— 而这两件事在合并时**都是保留原值**，
     * 所以结果一致，但把它显式写出来，读代码的人就不用自己去推这一层。
     *
     * ⚠️ `persona` 这里是 `List<Inference>` 而不是 `Persona` —— 因为 prompt 里让它输出的是
     *    **一个数组**（`"persona":[{...}]`），而 `Persona` 这个 record 只有一个 components
     *    `inferences`，直接映射会要求写成 `{"inferences":[…]}`。用一个裸数组更自然，
     *    而且"还没过闸门的原始条目"本来就不该被包成 Persona（Persona 的每一条按约定都是过了闸门的）。
     *
     * ⚠️ 字段名 params / reply / persona 必须和 prompt 里给的 JSON 例子逐字一致。
     *    写成 params_ 或 data 都会解析成 null —— 而且**不会报错**，
     *    只会得到一份"什么都没听出来"的对话。这类静默错位整份工程里出现过三次了。
     */
    record RawChat(String reply, TravelParams params, List<Persona.Inference> persona) {
    }

    /* ============================================================
     * Prompt 构造
     * ============================================================ */

    private String buildPrompt(List<Message> messages, TravelParams current, Persona persona, LocalDate today) {
        return """
                你是"童行"的带娃旅行规划助手。你的任务：**通过对话把出行信息问清楚**，并输出结构化结果。

                【今天的日期】
                今天是 %s。
                用户说"11月17日""下个月""国庆去"这类**没带年份**的日期时，一律按这个日期去推年份，
                推出的一定是**还没到来的那一天**。出发日期不可能在过去。
                （推算示例：今天是 2026-10-13，用户说"11月17日"→ 2026-11-17；
                  用户说"3月1日"→ 2027-03-01，因为今年的 3 月 1 日已经过了。）

                【需要收集的字段】
                - origin：**出发地城市**（用户从哪个城市走，"广州"、"深圳"）
                - city：目的地**城市**（"珠海"、"广州"、"深圳"）
                - days：出行天数（整数）
                - startDate：出发日期，格式 YYYY-MM-DD（用户说"国庆去"，就填今年国庆的第一天）
                - childAges：孩子年龄，**数组**（每个孩子一项，从小到大，如 [5] 或 [5, 8]）
                  · 用户只说了"孩子 5 岁"→ [5]
                  · 说了两个孩子 → 两个都要列，[5, 8]
                  · **如果系统已给出已知年龄，输出时必须带上全部孩子**（不是只输出这一轮新提到的那个）
                  · 用户说了"两个孩子"却只给了一个年龄 → 在 reply 里追问另一个（会改变行程节奏）
                - preferences：偏好，数组（["动物园","海边","室内"]）
                - pace：节奏（"慢" / "中" / "快"）
                - stroller：是否需要推车（true / false）

                【为什么要问出发地 —— 这是这个产品的核心，请认真问】
                用户是**带孩子出行，受不了长途奔波**。所以系统要给每一段城际交通设一个时长上限，
                超了就会提醒他"这段路带娃太累"。而"一段"是「出发地→目的地」和「目的地→出发地」，
                **没有出发地，这两段就不存在，这条最重要的约束就完全失效了。**
                所以 origin 必须问到，一个字都不能省 —— 它和城市、天数一样是必答项。

                【对话原则】
                1. 每轮回复不超过两句话，口语化、温暖，像朋友聊天。
                2. 每轮只问 1~2 个**还缺**的参数，不要一次问完，也不要重复问已经有的。
                   问出发地时可以自然地连着问目的地："你们从哪个城市出发，想去哪儿玩呀？"
                3. 用户表达了偏好就提炼成简短关键词（"孩子喜欢看鱼"→ preferences 里加"海洋馆"）。
                4. 信息齐全时，回复："信息已经齐了，点「生成行程」就可以了"。
                5. ⚠️ **不要编造用户没说过的信息。** 没听出来的字段一律写 null。
                   特别注意 stroller 的三种写法含义完全不同，不要混用：
                     · null   = 还没问到（**大多数情况下用这个**）
                     · true   = 用户明确说需要
                     · false  = 用户明确说不需要
                   把"还没问"写成 false，会让系统以为用户已经答过了，从此再不问他推车的事。
                6. 用户在纠正之前的说法（"不是 3 天，是 4 天"）时，按新的来。
                7. ⚠️ **origin 和 city 都只能填"一个具体城市"，区域说法一律不能填进任何一个。**
                   用户说"广东周边""珠三角""深圳附近""海边"这类**一片地方**的时候：
                     · **不要**把它抄进 city 或 origin —— 系统拿它去查，一条结果都查不到，
                       整个行程会排不出来。
                     · 目的地那一侧：根据他的偏好**替他挑一个最合适的城市**填进 city，
                       并在回复里用半句话说清你按哪儿安排（例："我先按珠海给你安排，那边海边和长隆都合适"）。
                     · 出发地那一侧：**不要替他挑**。出发地是"他家在哪"，这不可能猜得出来，
                       必须问。只有他明确说了城市名才填。
                     · 两个字段都**只能有一个城市**，不要写"珠海、广州"或"珠海/广州"这种。
                   选目的地的参考：亲子+海边 → 珠海；亲子+动物园+大城市 → 广州。
                8. 出发地和目的地**相同**是合法的（本地游），不要为了不同而改掉其中一个。

                【除了上面那些字段，你还要留意：这个用户是个什么样的人】
                上面的字段是**他说的**。这一段要的是**你从他的话里看出来的** ——
                比如他说"这次想放松一点"，那是在说他不想每天折腾。
                只有下面这 %d 个维度可以记，**不要发明第 %d 个**：
                %s

                【记这些判断的规矩 —— 这几条比什么都重要，请逐条遵守】
                1. 每一条判断都必须**拿得出用户的原话**。把支撑这条判断的那一小段**原样抄下来**当依据：
                   一个字都不要改，不要改写、不要润色、不要补充、不要拼接两句。
                2. 依据只能来自**用户说过的话**，不能来自你自己说过的话。
                3. 宁可不记，也不要硬凑。用户没表达出这个维度上的偏好，就**不要输出这一条**。
                   输出一个空数组是完全可以的，甚至是好的。
                4. 注意区分"这个人的特点"和"他今天的状态"：
                     "我想有一个放松的旅行" → 怕走路=少走路   ✓ 他说的是他想要什么
                     "我今天有点累"         → 怕走路=少走路   ✗ 他说的是今天，不是这个人
                   拿不准的，不记。
                5. 用户**明确说了相反方向**的时候，**也要记** —— 那不是"没得记"：
                     "我们体力好，不用考虑走不动" → 怕走路=无所谓    ✓
                     "我们不怕挤"                 → 怕人多=不介意    ✓
                   「无所谓 / 不介意」是一条**有用的判断**：它让系统知道可以排紧凑一些。
                   它和"用户没提到这个维度"是两件不同的事，前者要记，后者不记。
                6. 用户改主意了，就用**新的判断覆盖旧的**（这一次输出新的那条）。
                7. 判断要短，不超过 %d 个字。依据抄一小段就够，不要抄整句长句。

                【已经收集到的信息】
                %s

                【你已经推出来的判断】
                下面这些是你在**前几轮**看出来的，供你参考（不要复述它们，只输出这一轮的新发现）：
                %s

                【对话历史】
                %s

                【输出要求】
                只输出一个 JSON 对象，不要任何解释文字，不要 markdown 代码块标记。
                {
                  "reply": "你要对用户说的话",
                  "params": {
                    "origin": "出发城市名" 或 null,
                    "city": "目的地城市名" 或 null,
                    "days": 天数 或 null,
                    "startDate": "YYYY-MM-DD" 或 null,
                    "childAges": [年龄, 年龄] 或 null,
                    "preferences": ["偏好"] 或 [],
                    "pace": "慢/中/快" 或 null,
                    "stroller": true/false 或 null
                  },
                  "persona": [
                    {"key": "walking", "value": "少走路", "evidence": "我想有一个放松的旅行"}
                  ]
                }

                ⚠️ params 里**只写这一轮新听出来的字段**，其余一律写 null ——
                系统会自动和已有信息合并，你不需要复述历史。
                ⚠️ persona 里写**这一轮你新看出来的判断**，一条都没有就写 `[]`。
                key 只能用上面列出的那几个英文词，原样照抄；evidence 必须是用户的原话。
                """.formatted(todayText(today),
                PersonaTrait.size(), PersonaTrait.size() + 1, PersonaTrait.renderForPrompt(),
                PersonaGuard.VALUE_MAX,
                renderCollected(current), renderPersona(persona), renderHistory(messages));
    }

    /**
     * 把今天渲染成"2026-10-13（星期二）"。
     *
     * 用 ISO 格式而不是"2026年10月13日"：因为**输出的格式就是我们要它写的格式**。
     * 让 prompt 里示范的写法 == 契约要求的写法，模型就不用做一次格式转换 ——
     * 而"多做一次转换"就是多一个写错的机会（它可能写成"2026/10/13"或"10月13日"）。
     */
    private static String todayText(LocalDate today) {
        String[] week = {"星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"};
        return today + "（" + week[today.getDayOfWeek().getValue() - 1] + "）";
    }

    /**
     * 把已收集的画像渲染成人话。
     *
     * 为什么不直接扔 JSON：`"stroller": null` 对模型来说是"一个叫 stroller 的字段值是空的"，
     * 而"**还没问过**"这个语义只有人话能表达 —— 前者模型可能读成"不需要"，
     * 后者它才知道该继续问。这是 PlanComposer.renderParams 用的同一条规矩。
     *
     * 和 PlanComposer 那份的唯一区别：那边天数已经有默认值（3 天），所以能写"未提供（按 3 天）"；
     * 这边**必须诚实地说"还没问过"**，一个默认值都不能提前给。
     */
    private static String renderCollected(TravelParams p) {
        List<String> lines = new ArrayList<>();
        lines.add("出发地：" + answer(p.origin()));
        lines.add("目的地：" + answer(p.city()));
        lines.add("天数：" + (p.days() == null || p.days() <= 0 ? "还没问过" : p.days() + " 天"));
        lines.add("出发日期：" + answer(p.startDate()));
        lines.add("孩子年龄：" + (p.childAgesText() == null ? "还没问过" : p.childAgesText()));
        lines.add("节奏：" + answer(p.pace()));
        lines.add("是否需要推车：" + (p.stroller() == null ? "**还没问过**" : (p.stroller() ? "需要" : "不需要")));
        lines.add("用户偏好：" + (p.preferences() == null || p.preferences().isEmpty()
                ? "还没问过" : String.join("、", p.preferences())));
        return String.join("\n", lines);
    }

    /** 还没问到 —— 用一个明确的词，而不是空白。空白会让模型以为这一行是排版错误 */
    private static String answer(String s) {
        return s == null || s.isBlank() ? "还没问过" : s.strip();
    }

    /**
     * 把已经推出来的判断渲染给模型看。
     *
     * 为什么不复用 `Persona.renderForPrompt()` 直接渲染、而要在外面包一层判空：
     * 因为**空 persona 渲染出来是空字符串**，而 prompt 里那个位置留白会被模型读成
     * "这一段的标题下面是空的" —— 它可能以为是我们漏拼了，也可能干脆不去填 persona。
     * 写明"还没有推出任何判断"才是一个**确定的信息**。
     *
     * ⚠️ 这里是**唯一**该出现"已经推出来的判断"的地方 —— 绝不要把它并进
     *    `renderCollected` 那一段。那一段的每一行都会被模型当**事实**用
     *    （"节奏：慢"），而判断是**我们推的**，混进去它就会把猜测当事实加固，
     *    下一轮再拿它当依据 —— 见 Persona 类注释第 ② 条。
     */
    private static String renderPersona(Persona p) {
        if (p == null || p.isEmpty()) {
            return "（还没有推出任何判断）";
        }
        return p.renderForPrompt();
    }

    /**
     * 把对话历史渲染成文本。
     *
     * 用"用户：/助手："而不是 JSON：这段是要模型**顺着话头接下去**的，
     * 人话的角色前缀比 `{"role":"user"}` 更接近它预训练时见过的对话格式。
     * （真要给它原生多轮结构，确实更"正规"，但那需要按角色拆成多条消息发给 API，
     * 而我们现在只有 chat(String) 这一个入口 —— 不值得为这一点点收益换掉整个调用方式。）
     */
    private static String renderHistory(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return "（还没有对话，这是第一轮）";
        }
        StringBuilder sb = new StringBuilder();
        for (Message m : messages) {
            sb.append(m.isUser() ? "用户：" : "助手：").append(m.content()).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** 重试时追加的话 —— 必须说清上次错在哪，只说"请重试"没有用 */
    private static final String RETRY_HINT = """


            【注意】你上一次的输出不是合法 JSON，系统无法解析。
            这次请**只输出一个 JSON 对象**：不要任何解释文字，不要 markdown 代码块标记（不要 ```）。
            """;

    /** 日志里用一句话描述画像，别把整个对象打出来 */
    private static String describe(TravelParams p) {
        return "{origin=" + p.origin() + ", city=" + p.city() + ", days=" + p.days() + ", startDate=" + p.startDate()
                + ", childAges=" + p.childAges() + ", pace=" + p.pace()
                + ", stroller=" + p.stroller() + ", prefs=" + p.preferences() + "}";
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
