package com.hellen.kidtrip.llm;

import com.hellen.kidtrip.common.JsonText;
import com.hellen.kidtrip.flyai.PoiCategories;
import com.hellen.kidtrip.plan.RetrievalPlan;
import com.hellen.kidtrip.plan.TravelParams;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * B5 第二步：**画像 → 检索条件**（`RetrievalPlan` 的真正产出者）。
 *
 * 在它存在之前，`RetrievalPlan` 一直靠 `fallback()` 顶着 —— 也就是说
 * "期望查什么"这件事**从来没有人真的想过**，一直是"不限类别 + 要酒店"这份万能答案。
 * 这个类补上的就是那一环：让模型看着画像说一句"这次该去找哪几类景点"。
 *
 * ── 它的输出为什么必须"受控" ────────────────────────────────────────
 * 模型在这一步的输出**会真的影响我们去查什么**。给它自由发挥的空间，
 * 它就会写出"深圳阿凡达悬浮山乐园"这种类别 —— 而那个词会在
 * `RetrievalService` 里变成一次 `--category 深圳阿凡达悬浮山乐园` 的调用，
 * 被飞猪拒绝，白白花掉一次额度、还多一次被限流的机会。
 *
 * 所以这里用的是和 CandidateCodes 同一套办法：**给它一份闭集，让它只能从里面选。**
 *   prompt 里列出全部 35 个类别（PoiCategories.renderForPrompt）
 *   → 模型选
 *   → `PoiCategories.keepValid` 过滤一遍，闭集外的**一律丢掉**
 * 这是"白名单"这个机制的第三次出现（候选编号、住宿 none、现在是类别）。
 * 一个机制在一份工程里出现三次，说明它已经不是技巧，而是这个架构的基本手法了：
 * **凡是模型要输出"一个受约束的取值"的地方，都给它一份闭集，然后在本地再过滤一遍。**
 *
 * ── 一个容易搞反的语义（prompt 里专门强调了）────────────────────────
 * `wantedCategories` 是"**期望覆盖**"，不是"**要发的查询命令**"。
 * 后端永远先发一次不带类别的宽查，只有当某一类**一条都没出现**时才为它补查（最多 2 次）。
 * 这件事必须让模型知道，否则它会"多列几个以防万一" —— 而列 4 个和列 2 个
 * 在补查次数上的效果是一样的（补查上限就是 2），多列只是在浪费它的注意力。
 *
 * ── 这里失败**不报错**，而是降级 ────────────────────────────────────
 * 和 ChatComposer（降级）、PlanComposer（报错）三者的对照值得放在一起看：
 *   这一步失败 → 降级成 `fallback()`（不限类别 + 要酒店）
 * 因为**宽查兜底是实测有效的**：一次宽查就能覆盖 7 个分类、10 条结果。
 * "没想清楚该查哪几类"这件事的代价，远小于"因为这一步失败所以用户拿不到行程"。
 * 而 PlanComposer 失败必须报错 —— 那边没有候选就真的无路可走。
 * **同一类操作（LLM 调用）的失败处理之所以不同，是因为失败之后的出路不同。**
 */
@Component
public class RetrievalPlanner {

    private static final Logger log = LoggerFactory.getLogger(RetrievalPlanner.class);

    /** 期望类别的上限。**超过 2 个也不会多补查**（补查上限就是 2），所以 4 已经偏宽松 */
    private static final int MAX_CATEGORIES = 4;

    /** 偏好词上限。它不参与检索（只作记录），纯粹是别让 prompt 和响应被撑大 */
    private static final int MAX_KEYWORDS = 4;

    private static final int MAX_ATTEMPTS = 2;

    /** 缓存容量。满了直接清空 —— 理由见 cache 字段的注释 */
    private static final int CACHE_LIMIT = 64;

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    /**
     * 同一份画像算出来的检索条件不重算。
     *
     * 这是这份工程里的**第二种缓存**，和 RetrievalService.cache 值得对照着看：
     *   那边缓存的是**飞猪的返回**（省的是额度 + 一次冷启动 Node 进程）；
     *   这边缓存的是**模型的输出**（省的是十几秒 + 一笔 token）。
     *   两者都是"同一个输入不花第二次钱"，但成本结构不同，所以 TTL 策略也不同 ——
     *   飞猪那份要过期（价格会变），这份不用（画像不变，选择就该不变，它是确定性的产物）。
     *
     * 容量满了直接 clear()，不做 LRU：
     *   因为"被清掉"的代价只是重算一次（十几秒），而写一个正确的 LRU 要引入顺序表、
     *   要处理并发下的读写竞态 —— 为了省一次重算而写一段可能带 bug 的代码，这个交易不划算。
     *   真到了缓存命中率成为问题的规模，再换 Caffeine 也不迟。
     */
    private final Map<String, Planned> cache = new ConcurrentHashMap<>();

    public RetrievalPlanner(ChatModel chatModel, ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
    }

    /** 规划结果 + 需要告诉用户的实话（比如"模型给的类别里有两个飞猪不认，已忽略"） */
    public record Planned(RetrievalPlan plan, List<String> notes) {
    }

    /**
     * 按画像规划一次检索条件。
     *
     * **不会抛异常** —— 任何失败都降级成 fallback()，并附一条 note 说明降级了。
     */
    public Planned plan(TravelParams params) {
        String key = fingerprint(params);
        Planned hit = cache.get(key);
        if (hit != null) {
            // 命中要记日志。不记的话，排障时你会看到"这次怎么这么快就查完了"，
            // 然后花半小时去找那个并不存在的性能优化。
            log.info("检索条件命中缓存，跳过模型调用：{}", key);
            return hit;
        }

        String prompt = buildPrompt(params);
        Exception lastError = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            String raw;
            try {
                raw = chatModel.chat(attempt == 1 ? prompt : prompt + RETRY_HINT);
            } catch (Exception e) {
                // 调用失败：不重试，直接降级
                log.warn("规划检索条件时调用大模型失败，降级为宽查兜底", e);
                return degraded("规划检索条件时大模型调用失败（" + shortReason(e) + "），已按'不限类别 + 查酒店'兜底");
            }

            try {
                Planned planned = parseModelText(raw);
                log.info("检索条件规划完成：类别={} 关键词={} 要酒店={}",
                        planned.plan().wantedCategories(), planned.plan().keywords(), planned.plan().needHotel());
                put(key, planned);
                return planned;
            } catch (Exception e) {
                lastError = e;
                log.warn("第 {} 次尝试：检索条件输出不是合法 JSON，原始输出前 300 字：{}",
                        attempt, JsonText.truncate(raw, 300));
            }
        }

        log.warn("两轮都没解析出检索条件，降级为宽查兜底", lastError);
        return degraded("没能规划出检索条件（模型两次都没返回可解析的结果），已按'不限类别 + 查酒店'兜底");
    }

    /* ============================================================
     * 解析与过滤 —— 纯函数，能单测
     * ============================================================ */

    /**
     * 把模型输出解析成检索条件，并**执行白名单过滤**。
     *
     * 这是这个类最该被测的一段：它决定"模型乱写的时候会发生什么"。
     * 而它是纯函数 —— 测试里可以喂任何鬼东西进去，不用花一次调用。
     *
     * 三件事：
     *   ① 类别过白名单（闭集外的丢掉，最多 MAX_CATEGORIES 个）
     *   ② 关键词清洗（去空白、去重、限个数）
     *   ③ needHotel 缺省为 true —— **缺省值的方向要和 fallback() 一致**，
     *      否则"模型忘了写"和"模型调用失败"两个降级路径会给出相反的答案，
     *      排障时你会以为是同一个原因，其实是两个。
     *
     * @throws Exception JSON 不合法（调用方决定重试或降级）
     */
    Planned parseModelText(String raw) throws Exception {
        RawPlan node = objectMapper.readValue(JsonText.stripFences(raw), RawPlan.class);

        List<String> offered = node.wantedCategories() == null ? List.of() : node.wantedCategories();
        List<String> categories = PoiCategories.keepValid(offered, MAX_CATEGORIES);
        List<String> keywords = clean(node.keywords());
        boolean needHotel = node.needHotel() == null || node.needHotel();

        // 只数"飞猪不认的"，不数"被上限截掉的"：
        //   飞猪不认 → 说明模型想找的东西这个平台根本没有，用户该知道（他可能会换个说法再试）；
        //   超过 4 个被截 → 那只是"少列了两个次要的"，prompt 里本来就要求按重要程度排序，不值得报。
        // 也不数重复项：模型写两遍同一个类别，去重之后是无害的，报出来只是噪音。
        // **记账的分寸就在这儿：只说"用户可能想改"的那些。**
        long unknown = offered.stream()
                .filter(s -> s != null && !PoiCategories.isValid(s))
                .count();

        List<String> notes = new ArrayList<>();
        if (unknown > 0) {
            notes.add("模型给出的 " + unknown + " 个检索类别不在飞猪支持的 "
                    + PoiCategories.size() + " 个类别里，已忽略");
        }

        return new Planned(new RetrievalPlan(categories, keywords, needHotel), notes);
    }

    /** 模型输出的原始形状。字段名必须和 prompt 里的 JSON 例子逐字一致 */
    record RawPlan(List<String> wantedCategories, List<String> keywords, Boolean needHotel) {
    }

    /* ============================================================
     * 内部工具
     * ============================================================ */

    /** 降级：任何"没能规划出来"的情况都走这里，保证**只有一条降级路径** */
    private static Planned degraded(String note) {
        return new Planned(RetrievalPlan.fallback(), List.of(note));
    }

    private void put(String key, Planned planned) {
        if (cache.size() >= CACHE_LIMIT) {
            log.info("检索条件缓存已达 {} 条上限，整体清空（不做 LRU 的理由见字段注释）", CACHE_LIMIT);
            cache.clear();
        }
        cache.put(key, planned);
    }

    /**
     * 画像指纹 —— 缓存的 key。
     *
     * 用竖线拼一个字符串，而不是给 TravelParams 加 hashCode：
     *   ① record 自动生成的 hashCode 是**基于 List 内容**的，理论上够用，
     *      但它会把字段顺序、类型都编进去 —— key 长得没法看，排障时读不懂；
     *   ② 更重要的是**语义**：这里要表达的正是"人读懂的那几个字段变没变"，
     *      那么 key 长成人能读懂的样子，恰好就是对的。
     *   ⚠️ 代价：以后给 TravelParams 加字段，**必须记得来这里加一位**，
     *      否则新字段不影响缓存 key —— 改了参数却命中旧结果，这是最难查的一类 bug。
     */
    private static String fingerprint(TravelParams p) {
        if (p == null) {
            return "(null)";
        }
        return String.join("|",
                String.valueOf(p.city()),
                String.valueOf(p.days()),
                String.valueOf(p.startDate()),
                String.valueOf(p.childAge()),
                String.valueOf(p.pace()),
                String.valueOf(p.stroller()),
                p.preferences() == null ? "" : String.join(",", p.preferences()));
    }

    /** 去空白、去重、限个数 */
    private static List<String> clean(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String s : raw) {
            if (s != null && !s.isBlank()) {
                out.add(s.strip());
            }
            if (out.size() >= MAX_KEYWORDS) {
                break;
            }
        }
        return List.copyOf(out);
    }

    private String buildPrompt(TravelParams p) {
        return """
                你是"童行"的**检索条件规划助手**。
                你的唯一工作：读下面的【旅行画像】，说出"这次该去飞猪上找哪几类景点、要不要找酒店"。

                【旅行画像】
                %s

                【可选类别】
                只能从下面这些里选，**必须逐字照抄，不要自己造词、不要写景点名**：
                %s

                【输出要求】
                只输出一个 JSON 对象，不要任何解释文字，不要 markdown 代码块标记。
                {
                  "wantedCategories": ["动物园", "海洋馆"],
                  "keywords": ["室内", "亲子"],
                  "needHotel": true
                }

                【规则】
                1. wantedCategories 选 2~4 个，**必须来自上面的【可选类别】**，按重要程度排序。
                   选不够就少选，**宁可少选也不要凑数**。
                2. ⚠️ 这些类别是"**期望覆盖**"，不是"要发的查询命令"：
                   后端会先发一次**不带类别**的宽查，只有当某一类一条都没出现时才为它单独补查，
                   而且**最多补查 2 次**。所以列 4 个和列 2 个的实际效果是一样的 ——
                   列多了只是在浪费注意力，不是在多查东西。
                3. keywords 给 2~4 个偏好词（"室内"、"雨天"、"免排队"这类筛选倾向），
                   不要写景点名。它**不会**变成实际的检索参数，只作记录用。
                4. needHotel：**默认写 true**。只有用户明确说了"不用安排住宿 / 住亲戚家 / 当天往返"
                   才写 false。
                5. 孩子的年龄和推车需求要影响你的选择：
                   3 岁以下偏向"公园乐园 / 动物园"这类平坦、推车方便的地方；
                   学龄孩子可以加上"博物馆 / 主题乐园"。
                   但这只是选择倾向 —— **仍然只能从【可选类别】里选，一个自造的词都不许出现**。
                """.formatted(renderParams(p), PoiCategories.renderForPrompt());
    }

    /** 画像渲染成人话。这里可以放心补默认值 —— 反正它只影响"模型怎么选"，不影响画像本身 */
    private static String renderParams(TravelParams p) {
        List<String> lines = new ArrayList<>();
        lines.add("城市：" + blankTo(p.city(), "未提供"));
        lines.add("天数：" + (p.days() == null || p.days() <= 0 ? "未确定" : p.days() + " 天"));
        lines.add("出发日期：" + blankTo(p.startDate(), "未提供"));
        lines.add("孩子年龄：" + (p.childAge() == null || p.childAge() <= 0 ? "未确定" : p.childAge() + " 岁"));
        lines.add("节奏：" + blankTo(p.pace(), "未指定"));
        lines.add("是否需要推车：" + (p.stroller() == null ? "未确认" : (p.stroller() ? "需要" : "不需要")));
        lines.add("用户偏好：" + (p.preferences() == null || p.preferences().isEmpty()
                ? "无特别偏好" : String.join("、", p.preferences())));
        return String.join("\n", lines);
    }

    private static String blankTo(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s.strip();
    }

    private static final String RETRY_HINT = """


            【注意】你上一次的输出不是合法 JSON，系统无法解析。
            这次请**只输出一个 JSON 对象**：不要任何解释文字，不要 markdown 代码块标记（不要 ```）。
            """;

    private static String shortReason(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return JsonText.truncate(m.lines().findFirst().orElse(m), 120);
    }
}
