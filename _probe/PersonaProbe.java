package com.hellen.kidtrip.plan;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * persona 探针 —— **跑一次真实对话，看模型实际能推出什么、依据的命中率有多少**。
 *
 * 这一轮刻意只做了闸门（PersonaTrait / Persona / PersonaGuard），**没接链路**。
 * 因为 prompt 该怎么写、卡片要不要做、维度够不够，全取决于两个还没人知道的数字：
 *   ① 模型在那 4 个维度上**实际会输出什么**？会不会一条都不推？
 *      会不会在"我今天有点累"上推出一条"她体力差"？会不会硬凑满 4 条？
 *   ② 它抄出来的 evidence，**有多少能在用户原话里逐字找到**？
 *      这个比例就是"闸门会拦掉多少"。太低 = 证据要求太严，真推论在被误杀；
 *      太高（比如 100%）= 要求形同虚设，模型本来就会照抄。
 *
 * 先拿数据，再决定 prompt 怎么写。**猜着写 prompt 是最贵的一种做法** ——
 * 改一轮要重跑一次端到端，而每一次的差异都说不清是 prompt 的功劳还是抽样。
 *
 * ══ 为什么这个探针是 Java 的，不是 _probe 里那些 .mjs ══════════════════
 *   最初写成 Node：调 LLM 用 fetch、解析 JSON 都是原生能力，看起来更省事。
 *   但它必须在最后一步**把模型的输出喂给真的 PersonaGuard**，而 Node 起不了子进程
 *   （实测这台机器上 `spawnSync` 对**任何**命令都返回 EBUSY，连 `git --version` 都是，
 *   所以不是 java 的问题，是环境不允许 Node 创建子进程）。
 *
 *   换 Java 之后反而更好，有三个理由，不只是"没办法"：
 *     · 判定用的是**真闸门**，不是在探针里重写一遍规则。重写一遍的话，
 *       "命中率"就成了**探针在验自己** —— 规则一旦有偏差（Java 的
 *       Character.isWhitespace 不认 NBSP，而 JS 的 \s 认），数字是错的且不报错。
 *     · 探针声明在 `com.hellen.kidtrip.plan` 包里，于是连 `squeeze` 和
 *       `EVIDENCE_MIN` 都能直接用真货 —— **连"依据命中在哪一句"都不是复刻的**。
 *     · HTTP 客户端、Jackson、模型参数（temperature 0.3）**和生产完全同一套**，
 *       少一层"探针里能跑、生产里不灵"的可能性。
 *
 *   代价是 JSON 得自己拼请求体（20 行的 jsonString）。值得 ——
 *   它换来的是"这个探针的每一步都和被测代码是同一个东西"。
 *
 * ══ 怎么跑 ══════════════════════════════════════════════════════
 *   bash _probe/persona-probe.sh            （推荐，它帮你拼 classpath）
 *   或手工两步：
 *     CP="_probe/classes;backend/target/classes;$(sed 's|\\|/|g' _probe/cp.txt)"
 *     printf -- '-cp "%s"\n' "$CP" > _probe/.probe-args
 *     javac @_probe/.probe-args -encoding UTF-8 -d _probe/classes _probe/PersonaProbe.java
 *     java  @_probe/.probe-args com.hellen.kidtrip.plan.PersonaProbe
 *
 *   跑之前要 `mvn -o test-compile`（判定用 backend/target/classes 里的真类），
 *   并用 `mvn -o dependency:build-classpath -Dmdep.outputFile=_probe/cp.txt` 生成 cp.txt。
 *   环境变量：KIDTRIP_LLM_API_KEY（必填）/ KIDTRIP_LLM_BASE_URL / KIDTRIP_LLM_MODEL_NAME
 *             （也认历史名字 DEEPSEEK_API_KEY / DEEPSEEK_BASE_URL / DEEPSEEK_MODEL）、
 *             PERSONA_THINKING=false（默认关思考，千问必须）、PERSONA_TEMP、
 *             PERSONA_ONLY=S1,S4（只跑指定场景，改 prompt 时省时间）。
 *
 *   ⚠️ 报告同时写 stdout 和 `_probe/out/persona-probe-<时间戳>.md`。
 *      看 stdout 时如果中文是乱码，别以为探针坏了 —— 那是 Windows 控制台代码页的事
 *      （Probe.java 第 1 节专门实测过这一条）。**看文件那份，它一定是 UTF-8。**
 */
public class PersonaProbe {

    /* ══════════════════════════════════════════════════════════════
     * 场景 —— 每组是一段真实对话历史，外加"我预期什么"
     *
     * 设计原则：**每一组都在赌一个具体的失败方式**。
     * 只写"正常对话"的探针只能得出"模型表现正常"，那等于什么都没测。
     * ══════════════════════════════════════════════════════════════ */

    private static Message u(String s) {
        return new Message(Message.USER, s);
    }

    private static Message a(String s) {
        return new Message(Message.ASSISTANT, s);
    }

    private record Scenario(String id, String name, String expect, List<Message> history) {
    }

    private static final List<Scenario> SCENARIOS = List.of(
            new Scenario("S1", "放松（正例）",
                    "walking=少走路（依据「我想安排得放松一点」）、rest=要午睡（依据「他中午要睡一会儿」）。两条原话都在历史里，都该过闸门",
                    List.of(
                            u("我想带 6 岁的儿子去广州玩 3 天，这次我想安排得放松一点"),
                            a("好的，那我按节奏舒缓来给你安排广州 3 日游。你们大概什么时候出发？"),
                            u("下个月吧。他中午要睡一会儿"))),

            new Scenario("S2", "什么都没说（对照组）",
                    "应该输出 []。这三句里没有任何偏好线索，推出任何一条都是幻觉",
                    List.of(
                            u("想去北京玩 3 天"),
                            a("好呀，请问你们从哪个城市出发？孩子多大了？"),
                            u("从上海，7 岁"))),

            new Scenario("S3", "过度推论诱饵",
                    "理想是 []。若推出 walking=少走路 且依据「我今天上班有点累」——那句原话是真的，"
                            + "闸门拦不住它。这正是闸门的能力边界，必须看见",
                    List.of(
                            u("我今天上班有点累，国庆想带孩子出去转转"),
                            a("好的，国庆想去哪个城市呢？"),
                            u("还没想好，你帮我推荐一个吧"))),

            new Scenario("S4", "改主意（覆盖）",
                    "budget 要给出新判断（价格不敏感／想住好一点），而不是留着旧的「想省着花」",
                    List.of(
                            u("预算有限，想找个便宜点的地方玩"),
                            a("明白，那我优先给你安排免费和低价的景点。想去哪？"),
                            u("还是杭州吧。对了这次是结婚纪念，酒店想住好一点，不用太省"))),

            new Scenario("S5", "自述体力好（反向）",
                    "walking=无所谓（依据「一天走两万步很轻松」）。看它会不会因为维度名叫「怕走路」就只往「少走路」写",
                    List.of(
                            u("我们一家体力都挺好，一天走两万步很轻松，你放心安排，不用考虑走不动"),
                            a("好的，那我安排得紧凑一些。"),
                            u("对，孩子也特别能走"))),

            new Scenario("S6", "一句话两条（同依据）",
                    "rest=要午睡、crowd=避开人多；两条依据是同一句 —— 闸门按 key 去重、不按 evidence，两条都该留下",
                    List.of(
                            u("孩子中午必须睡午觉，所以我们不想安排太赶；另外尽量避开人多的网红点"),
                            a("好的，中午我会留出休息时间，也会优先挑人少的地方。请问去哪个城市？"),
                            u("成都吧"))),

            // ── 下面两组是**第二轮加的**：第一轮 6 组全绿（0 条被拦），说明那 6 组
            //    压根没给闸门出手的机会。闸门是给"不正常"准备的，只测正常对话等于没测。
            //    所以补两组专门冲着闸门去：一组诱它拿助手的话当依据（自证），
            //    一组诱它把口语改写成标准说法（改写）。这两件事才是它真正要挡的。

            new Scenario("S7", "自证诱饵（助手猜过、用户没确认）",
                    "理想是 []。助手说过「按轻松一点、预算中等的方向安排」，若模型拿这句当依据 —— "
                            + "闸门必须拦（依据只能来自用户说过的话，助手的话是它自己的输出，不能自证）",
                    List.of(
                            u("想带孩子出去玩几天"),
                            a("好的，请问去哪里、孩子多大？"),
                            u("还没定，你看着办"),
                            a("那我先按轻松一点、预算中等的方向给您安排，很多带娃家庭都偏好这样"),
                            u("行"))),

            new Scenario("S8", "改写诱惑（口语说法里没有标准词）",
                    "walking=少走路，依据应当**照抄**用户原话。若它改写成「不想走太多路」这类标准说法 —— "
                            + "闸门会拦，一条真偏好就丢了。这个场景量的正是模型的改写倾向（改写率高就得改 prompt，"
                            + "而不是放松闸门 —— 放松了逐字回查就不再是可校验的了）",
                    List.of(
                            u("走路这事儿我是真不行，一天下来腿要断，最好每个点都挨着近点儿"),
                            a("明白，那我尽量把这几个点安排在同一片区域，少让你来回跑。"))));

    /* ══════════════════════════════════════════════════════════════
     * 候选版 prompt —— **还不是生产代码**，跑出来的数据决定它怎么写
     * ══════════════════════════════════════════════════════════════ */

    /**
     * 维度表从**真代码**取（{@link PersonaTrait#renderForPrompt()}），不在探测里手抄一份。
     *
     * 手抄更快，但那样"prompt 里教的维度"和"闸门认的维度"就成了两处真相 ——
     * 哪天加第 5 个维度、只改了 Java 没改探针，症状会是"模型推出来了却全被拦掉"，
     * 而报告里只会看到一堆"不是我们认识的维度"，得反过来读代码才知道是 prompt 陈旧。
     * 这是本项目反复出现的那个坑（"两处真相，迟早打架"），一次调用就消掉了。
     */
    private static String buildPrompt(List<Message> history) {
        StringBuilder lines = new StringBuilder();
        for (Message m : history) {
            lines.append(m.isUser() ? "用户：" : "助手：").append(m.content()).append('\n');
        }
        return """
                你是"童行"的带娃旅行规划助手。你一边和用户对话，一边留意这个用户是个什么样的人。

                【可以记的维度 —— 只有这 %d 个，不要发明第 %d 个】
                %s

                【判断规矩 —— 这几条比什么都重要，请逐条遵守】
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
                   「无所谓 / 不介意」是一条**有用的判断**：它让系统知道可以排紧凑一些、
                   也可以选那些值得去但人多的地方。它和"用户没提到这个维度"是两件不同的事，
                   前者要记，后者不记。
                6. 用户改主意了，就用**新的判断覆盖旧的**（这一次输出新的那条）。
                7. value 要短，不超过 %d 个字。

                【对话历史】
                %s
                【输出要求】
                只输出一个 JSON 数组，不要任何解释文字，不要 markdown 代码块标记。
                数组里每个元素三个字段，字段名逐字是 key / value / evidence：
                [{"key":"walking","value":"少走路","evidence":"我想有一个放松的旅行"}]
                没有可记的就输出 []
                """.formatted(
                PersonaTrait.size(), PersonaTrait.size() + 1,
                PersonaTrait.renderForPrompt(),
                PersonaGuard.VALUE_MAX,
                lines.toString().stripTrailing());
    }

    /* ══════════════════════════════════════════════════════════════
     * 调模型
     * ══════════════════════════════════════════════════════════════ */

    // 变量名先读本项目的 KIDTRIP_LLM_*，读不到再回退到历史名字 DEEPSEEK_*（这个探针最初给 DeepSeek 写的）。
    // 换厂商只影响这几个值，判定逻辑一个字都不用动 —— 探针验的是"闸门在真模型上表现如何"，与厂商无关。
    private static final String MODEL = env2("KIDTRIP_LLM_MODEL_NAME", "DEEPSEEK_MODEL", "qwen-plus");
    private static final String BASE = stripV1(env2("KIDTRIP_LLM_BASE_URL", "DEEPSEEK_BASE_URL",
            "https://dashscope.aliyuncs.com/compatible-mode/v1"));
    private static final double TEMPERATURE = Double.parseDouble(env("PERSONA_TEMP", "0.3"));
    private static final String KEY = env2("KIDTRIP_LLM_API_KEY", "DEEPSEEK_API_KEY", null);
    private static final int TIMEOUT_SECONDS = Integer.parseInt(env("PERSONA_TIMEOUT", "120"));

    /**
     * 要不要让模型"思考"。
     *
     * <p>默认 **false**，因为千问 3.x 默认开着思考：思考期间链路上零字节流动，非流式请求会被
     * 网关当空闲连接在 ~30 秒掐掉（现象是 {@code EOF reached while reading}）。这个探针是非流式，
     * 所以必须显式关掉才跑得通。厂商不认这个字段时可以设 {@code PERSONA_THINKING=omit}。
     */
    private static final String THINKING = env("PERSONA_THINKING", "false");

    /** 配置里的 base-url 带 {@code /v1}（{@code …/compatible-mode/v1}），而下面拼的是 {@code /v1/chat/completions}。 */
    private static String stripV1(String url) {
        String u = url.replaceAll("/+$", "");
        return u.endsWith("/v1") ? u.substring(0, u.length() - 3) : u;
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v.strip();
    }

    /** 先读主名字，再回退到历史名字。两个都没配就返回 fallback。 */
    private static String env2(String primary, String legacy, String fallback) {
        String v = env(primary, null);
        return v != null ? v : env(legacy, fallback);
    }

    /** 一次调用的结果。`error != null` = 调用失败；`error == null && parsed == null` = 返回了但解析不出数组 */
    private record Attempt(String raw, String error, long ms, String usage, List<Persona.Inference> parsed) {
    }

    private static Attempt ask(String prompt) {
        long t0 = System.currentTimeMillis();
        String body = "{\"model\":" + jsonString(MODEL)
                + ",\"temperature\":" + TEMPERATURE
                + ("omit".equalsIgnoreCase(THINKING) ? "" : ",\"enable_thinking\":" + Boolean.parseBoolean(THINKING))
                + ",\"messages\":[{\"role\":\"user\",\"content\":" + jsonString(prompt) + "}]}";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + "/v1/chat/completions"))
                    .header("content-type", "application/json")
                    .header("authorization", "Bearer " + KEY)
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long ms = System.currentTimeMillis() - t0;
            if (response.statusCode() != 200) {
                return new Attempt(null, "HTTP " + response.statusCode() + "：" + truncate(response.body(), 300), ms, null, null);
            }
            JsonNode root = MAPPER.readTree(response.body());
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            JsonNode usage = root.path("usage");
            String usageText = usage.isMissingNode() ? ""
                    : "in " + usage.path("prompt_tokens").asInt() + " / out " + usage.path("completion_tokens").asInt();
            return new Attempt(content, null, ms, usageText, parseInferences(content));
        } catch (Exception e) {
            return new Attempt(null, e.getClass().getSimpleName() + "：" + truncate(String.valueOf(e.getMessage()), 300),
                    System.currentTimeMillis() - t0, null, null);
        }
    }

    /**
     * 把模型输出解析成三元组列表。
     *
     * 宽松是刻意的：模型输出不是干净 JSON 是**常态**（prompt 里已经求它别加围栏，
     * 它时不时还是会加）。解析失败**不算探针失败** —— 原始文本会完整打进报告，
     * 那本身就是一条要看的结论（"它压根没按格式回"）。
     */
    private static List<Persona.Inference> parseInferences(String raw) {
        String text = raw == null ? "" : raw.replace("```json", "").replace("```", "").strip();
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        try {
            if (start >= 0 && end > start) {
                JsonNode arr = MAPPER.readTree(text.substring(start, end + 1));
                if (!arr.isArray()) {
                    return null;
                }
                List<Persona.Inference> out = new ArrayList<>();
                for (JsonNode n : arr) {
                    out.add(new Persona.Inference(
                            n.path("key").asText(""), n.path("value").asText(""), n.path("evidence").asText("")));
                }
                return out;
            }
            // 退化情况：它回了一个不带数组的对象 —— 也收下，当成一条
            int os = text.indexOf('{');
            int oe = text.lastIndexOf('}');
            if (os >= 0 && oe > os) {
                JsonNode n = MAPPER.readTree(text.substring(os, oe + 1));
                return List.of(new Persona.Inference(
                        n.path("key").asText(""), n.path("value").asText(""), n.path("evidence").asText("")));
            }
        } catch (Exception ignored) {
            // 落到下面返回 null
        }
        return null;
    }

    /* ══════════════════════════════════════════════════════════════
     * 小工具
     * ══════════════════════════════════════════════════════════════ */

    /** 手工拼 JSON 字符串字面量。就为这一个方法拉 Jackson 的写 API 不值得，而且手写完全可控 */
    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /**
     * evidence 命中的那句用户原话（没命中返回 null）。
     *
     * 用的是**真 squeeze**（同包可见），不是复刻 —— 报告里"命中：用户第 N 句"这一列
     * 如果和闸门的判断不是一个规则，两边一旦不一致就会互相打脸，而且没人知道信谁。
     */
    private static String hitSource(String evidence, List<Message> history) {
        String needle = PersonaGuard.squeeze(evidence);
        if (needle.length() < PersonaGuard.EVIDENCE_MIN) {
            return null;
        }
        for (Message m : history) {
            if (m.isUser() && PersonaGuard.squeeze(m.content()).contains(needle)) {
                return m.content();
            }
        }
        return null;
    }

    private static int userIndex(String content, List<Message> history) {
        int n = 0;
        for (Message m : history) {
            if (m.isUser()) {
                n++;
                if (m.content().equals(content)) {
                    return n;
                }
            }
        }
        return -1;
    }

    /* ══════════════════════════════════════════════════════════════
     * 主流程
     * ══════════════════════════════════════════════════════════════ */

    private static final StringBuilder LOG = new StringBuilder();
    private static PrintStream out;

    private static void emit(String s) {
        LOG.append(s).append('\n');
        out.println(s);
    }

    public static void main(String[] args) throws Exception {
        // 显式 UTF-8：Windows 下 System.out 的编码跟控制台代码页走，
        // 不指定的话写进重定向文件里的是 GBK 字节，读出来全乱（Probe.java 第 1 节实测过）
        out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);

        if (KEY == null || KEY.isBlank()) {
            System.err.println("缺大模型密钥。用法："
                    + "set -a; source deploy/kidtrip.env; set +a && bash _probe/persona-probe.sh"
                    + "（或 export KIDTRIP_LLM_API_KEY=sk-xxxx）");
            System.exit(2);
        }
        String only = System.getenv("PERSONA_ONLY");
        List<Scenario> list = new ArrayList<>();
        for (Scenario s : SCENARIOS) {
            if (only == null || only.isBlank() || List.of(only.split(",")).contains(s.id)) {
                list.add(s);
            }
        }
        if (list.isEmpty()) {
            System.err.println("PERSONA_ONLY=" + only + " 没匹配到任何场景");
            System.exit(2);
        }

        emit("# persona 探针报告");
        emit("");
        emit("模型 `" + MODEL + "` · 温度 " + TEMPERATURE + " · 场景 " + list.size() + " 个 · 判定用真 `PersonaGuard`");
        emit("跑的时间：" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        emit("");
        emit("跑之前先说清这一轮要验什么：**闸门已经单独验过了**（335/335 + 三组反证），");
        emit("所以这里不是验收闸门，是**拿真实模型输出当输入**，看两个还没人知道的数字 ——");
        emit("模型实际会推什么、它的依据有多少能在用户原话里逐字找到。");
        emit("");

        List<Attempt> attempts = new ArrayList<>();
        for (Scenario sc : list) {
            Attempt at = ask(buildPrompt(sc.history));
            attempts.add(at);
            String flag = at.error() != null ? "调用失败"
                    : at.parsed() == null ? "解析失败"
                    : at.parsed().size() + " 条";
            out.println("  " + sc.id() + " " + sc.name() + " → " + flag + "（" + at.ms() + " ms）");
        }
        out.println();

        int totalOut = 0;
        int totalAccepted = 0;
        int totalRejected = 0;
        int parsedScenarios = 0;
        List<String> reasonTally = new ArrayList<>();
        List<String> lines = new ArrayList<>();

        for (int i = 0; i < list.size(); i++) {
            Scenario sc = list.get(i);
            Attempt at = attempts.get(i);
            emit("---");
            emit("");
            emit("## " + sc.id() + " · " + sc.name());
            emit("");
            emit("**预期**：" + sc.expect());
            emit("");
            if (at.error() != null) {
                emit("⚠️ **调用失败**：" + at.error());
                emit("");
                continue;
            }
            emit("模型原始输出（" + at.raw().length() + " 字"
                    + (at.usage().isEmpty() ? "" : "，token " + at.usage()) + "，"
                    + at.ms() + " ms）：");
            emit("");
            emit("```");
            emit(at.raw().strip());
            emit("```");
            emit("");
            if (at.parsed() == null) {
                emit("⚠️ **这一段解析不出 JSON 数组** —— 它本身是一条结论（格式都没守住）。");
                emit("");
                continue;
            }
            parsedScenarios++;
            totalOut += at.parsed().size();

            PersonaGuard.Screening screening = PersonaGuard.screen(at.parsed(), sc.history());
            totalAccepted += screening.accepted().size();
            totalRejected += screening.rejected().size();

            if (screening.accepted().isEmpty() && screening.rejected().isEmpty()) {
                if (at.parsed().isEmpty()) {
                    emit("（空数组 —— 一条都没推）");
                    emit("");
                }
                continue;
            }

            emit("**过闸门**（真 `PersonaGuard`）：");
            emit("");
            for (Persona.Inference acc : screening.accepted()) {
                PersonaTrait trait = PersonaTrait.ofKey(acc.key());
                String label = trait == null ? acc.key() : trait.label();
                String src = hitSource(acc.evidence(), sc.history());
                emit("- ✅ `" + acc.key() + "` = **" + acc.value() + "**（" + label + "）");
                emit("  - 依据：「" + acc.evidence() + "」");
                if (src != null) {
                    emit("  - 命中：用户第 " + userIndex(src, sc.history()) + " 句 —— 「" + src + "」");
                }
            }
            for (PersonaGuard.Rejection rej : screening.rejected()) {
                String src = hitSource(rej.evidence(), sc.history());
                emit("- ❌ `" + rej.key() + "` = " + (rej.value().isEmpty() ? "（空）" : rej.value()));
                emit("  - 依据：「" + rej.evidence() + "」");
                emit("  - 原因：**" + rej.reason() + "**" + (src != null ? "（⚠️ 但这句话确实在历史里）" : ""));
                reasonTally.add(rej.reason());
            }
            // 模型给了、闸门却没回执的（比如元素是 null）—— 报出来，免得静默丢掉
            int accounted = screening.accepted().size() + screening.rejected().size();
            int nulls = 0;
            for (Persona.Inference raw : at.parsed()) {
                if (raw == null) {
                    nulls++;
                }
            }
            if (accounted + nulls < at.parsed().size()) {
                emit("");
                emit("  ⚠️ 有 " + (at.parsed().size() - accounted - nulls) + " 条模型给了但闸门没有回执，要查。");
            }
            if (nulls > 0) {
                emit("");
                emit("  ⚠️ 模型输出里有 " + nulls + " 个 `null` 元素（闸门按设计跳过了它们）。");
            }
            emit("");
        }

        emit("---");
        emit("");
        emit("## 汇总");
        emit("");
        emit("| 指标 | 值 |");
        emit("|---|---|");
        emit("| 场景 | " + list.size() + " 个（其中 " + parsedScenarios + " 个解析成功） |");
        emit("| 模型输出条目 | " + totalOut + " 条 |");
        emit("| 过闸门 | " + totalAccepted + " 条 |");
        emit("| 被拦 | " + totalRejected + " 条 |");
        double hitRate = totalOut == 0 ? 0 : totalAccepted * 100.0 / totalOut;
        emit("| evidence 命中率 | **" + String.format("%.1f%%", hitRate) + "**（" + totalAccepted + "/" + totalOut + "） |");
        emit("");
        if (!reasonTally.isEmpty()) {
            emit("被拦的原因分布：");
            emit("");
            for (String r : reasonTally.stream().distinct().toList()) {
                emit("- " + reasonTally.stream().filter(x -> x.equals(r)).count() + " × " + r);
            }
            emit("");
        }

        Path dir = Path.of("_probe", "out");
        Files.createDirectories(dir);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss"));
        Path file = dir.resolve("persona-probe-" + stamp + ".md");
        Files.writeString(file, LOG.toString(), StandardCharsets.UTF_8);
        out.println("报告已落盘：" + file);
    }
}
