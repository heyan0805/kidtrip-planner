package com.hellen.kidtrip.candidate;

import com.hellen.kidtrip.flyai.FlyaiException;
import com.hellen.kidtrip.flyai.raw.FlyaiEnvelope;
import com.hellen.kidtrip.flyai.raw.FlyaiHotel;
import com.hellen.kidtrip.flyai.raw.FlyaiPoi;
import com.hellen.kidtrip.flyai.raw.FlyaiTrain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 规范化：把飞猪那堆"脏"字段翻译成能直接参与计算的值。
 *
 * 为什么这一步是**必需的中间层**，不是优化：
 *   飞猪给的价格是 "¥227"，坐标是 "22.54159"，等级是 "4" —— 全是字符串。
 *   不翻译的话，`"¥227" > 200` 这种字符串比较会给你一堆意外结果（按字典序比），
 *   而"景点间距离"根本算不出来。校验层建的是一堆假规则。
 *
 * 一条不变的规矩：**空值只有一种表示法**。
 *   飞猪那边"空"有四种形态：null、""、" "（一个空格）、字段整个不存在。
 *   在这一层统一成 null，从这往下所有人只需要判一次 null。
 *   如果留给业务代码逐处判断，总有一天会漏掉 " " —— 而它在 Java 里是真值，不报错。
 */
@Component
public class CandidateNormalizer {

    private static final Logger log = LoggerFactory.getLogger(CandidateNormalizer.class);

    private final ObjectMapper mapper;

    /**
     * 读第三方数据，用一个**本地说得清**的 mapper，而不是默默依赖 Spring 容器的默认行为。
     *
     * 具体是关掉 FAIL_ON_UNKNOWN_PROPERTIES：飞猪随时可能加字段，
     * 我们不能"它加一个字段，我们这边就抛 UnrecognizedPropertyException"。
     * （Spring Boot 其实默认也把它关了，但那是**外部假设** —— 换个容器、换个配置就变。
     *  第三方解析这块的风险集中在这里，值得写死。）
     */
    public CandidateNormalizer(ObjectMapper springMapper) {
        ObjectMapper tolerant = springMapper.rebuild()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        this.mapper = tolerant;
    }

    /**
     * 解析结果 + **失败条数**。
     *
     * 为什么要带这个计数，而不是默默跳过坏数据：
     *   单条解析失败意味着"飞猪改了这个字段的类型"，这是个信号。
     *   吞掉它，你只会看到"候选少了几条"，然后去怀疑自己的筛选规则。
     *   计数上报，这个信号就能出现在溯源面板里。
     */
    public record Normalized<T>(List<T> items, int failed) {
    }

    /**
     * 上游用来表达"**按条件筛完一条都没有**"的那句话。
     *
     * 实测（`search-train --total-duration-hour 3`，珠海↔桂林）：
     *   { "data": null, "message": "行程时长过滤结果为空", "status": 1 }
     *
     * ⚠️ 这是一句**结论**，不是故障 —— 所以它在这里被翻译成
     *   {@link FlyaiException.Reason#NO_RESULT} 而**不是** PARAM。
     *   为什么非要在这一层翻：如果让它带着"status=1 + 一段中文"继续往上走，
     *   上层就只能去 match 这句话来区分"没这种走法"和"查询失败了"，
     *   而错误消息是给人看的、随时可能改 —— 拿它当控制流是整个工程里最脆的一种写法。
     *   把这句话**只在一个地方**认识掉，往上传的是枚举，控制流就与文案解耦了。
     */
    private static final String NO_MATCH_MARKER = "过滤结果为空";

    public Normalized<PoiCandidate> pois(String rawJson) {
        JsonNode list = itemListOf(rawJson);
        List<PoiCandidate> out = new ArrayList<>();
        int failed = 0;
        for (JsonNode node : list) {
            try {
                FlyaiPoi poi = mapper.treeToValue(node, FlyaiPoi.class);
                out.add(toCandidate(poi));
            } catch (JacksonException e) {
                failed++;
                log.warn("一条景点解析失败，已跳过：{}", e.getMessage());
            }
        }
        return new Normalized<>(out, failed);
    }

    public Normalized<HotelCandidate> hotels(String rawJson) {
        JsonNode list = itemListOf(rawJson);
        List<HotelCandidate> out = new ArrayList<>();
        int failed = 0;
        for (JsonNode node : list) {
            try {
                FlyaiHotel hotel = mapper.treeToValue(node, FlyaiHotel.class);
                out.add(toCandidate(hotel));
            } catch (JacksonException e) {
                failed++;
                log.warn("一条酒店解析失败，已跳过：{}", e.getMessage());
            }
        }
        return new Normalized<>(out, failed);
    }

    /**
     * 解析 `search-train` 的结果。
     *
     * 和上面两个**唯一的、也是本质的**不同：它对 `status = 1` 的处理不是"一律当失败"。
     * 上游用 status = 1 表达了**两种完全相反的事**：
     *   "行程时长过滤结果为空" → 这是一句答案（这两个地方之间没有这么快的走法）；
     *   其它                   → 这才是失败（参数被拒 / 上游异常）。
     * 上面两个方法可以一刀切，是因为景点和酒店那边没遇到过"用 status=1 说结论"的情况 ——
     * 而车次这边遇到了，所以这里必须分。**同一层的不同数据，处置可以不同，前提是说得出为什么。**
     *
     * 注意它**不解析** `journeys[0]` 就是去程这件事，也不挑班次 ——
     * 那些是 LegService 的判断（它才知道"带娃要上午出发"）。这里只做字段翻译。
     */
    public Normalized<FlyaiTrain> trains(String rawJson) {
        FlyaiEnvelope envelope = envelopeOf(rawJson);
        if (envelope.status() != 0) {
            String message = envelope.message();
            if (message != null && message.contains(NO_MATCH_MARKER)) {
                throw new FlyaiException(FlyaiException.Reason.NO_RESULT,
                        "按条件筛完没有可用的车次：" + message.strip());
            }
            throw new FlyaiException(FlyaiException.Reason.PARAM,
                    "飞猪拒绝了这次车次查询：" + reasonOf(message));
        }
        List<FlyaiTrain> out = new ArrayList<>();
        int failed = 0;
        for (JsonNode node : listOrEmpty(envelope)) {
            try {
                out.add(mapper.treeToValue(node, FlyaiTrain.class));
            } catch (JacksonException e) {
                failed++;
                log.warn("一条车次解析失败，已跳过：{}", e.getMessage());
            }
        }
        return new Normalized<>(out, failed);
    }

    /**
     * 取出信封里的 itemList。
     *
     * 这里就是 FlyaiEnvelope 那个 nullable data 的落地点：
     * **无结果时 data 是 null，而 status 仍然是 0** —— 长得跟成功一模一样。
     * 顺手避开的是 Java 侧最容易犯的那个错：`data.itemList()` 在无结果时直接 NPE。
     *
     * ⚠️ 但"无结果时 status 是 0"这句话**只说对了一半**，我们因此吃过一次大亏：
     *    它反过来不成立 —— **status != 0 的时候 data 也是 null**，
     *    于是"参数被上游拒绝"和"这个地方没数据"在只看 data 的代码里完全是同一个分支。
     *    实测（过去日期的酒店查询）：
     *      { "data": null, "message": "…入离时间校验失败", "status": 1 }
     *    我们把它当成"没查到酒店"，于是 ① 上游那句真正的原因被彻底吞掉；
     *    ② 还照着"没查到数据"的规矩白花一次额度去放宽重查 —— 而参数错的重查必然还是错。
     *    **先看 status，再看 data** —— 顺序反过来，这里就永远读不出真相。
     */
    private JsonNode itemListOf(String rawJson) {
        FlyaiEnvelope envelope = envelopeOf(rawJson);
        if (envelope.status() != 0) {
            throw new FlyaiException(FlyaiException.Reason.PARAM,
                    "飞猪拒绝了这次查询：" + reasonOf(envelope.message()));
        }
        return listOrEmpty(envelope);
    }

    /** 只负责"把一段文本读成信封"。"读不出来"是 BAD_JSON，和 status 的语义无关。 */
    private FlyaiEnvelope envelopeOf(String rawJson) {
        try {
            return mapper.readValue(rawJson, FlyaiEnvelope.class);
        } catch (JacksonException e) {
            throw new FlyaiException(FlyaiException.Reason.BAD_JSON,
                    "飞猪返回的不是合法 JSON：" + head(rawJson));
        }
    }

    /** "没查到" → 空列表，而不是 null，让上层少一个判空。注意它**不**看 status。 */
    private JsonNode listOrEmpty(FlyaiEnvelope envelope) {
        JsonNode data = envelope.data();
        JsonNode list = envelope.itemList();
        if (list == null || list.isNull()) {
            // ⚠️ 这里要能区分两种"没有 itemList"：
            //    data 本身就是 null  → 上游说的是"这次没有结果"，是正常的；
            //    data 有内容但没有 itemList  → 上游**改了信封结构**。
            // 后者如果不吭声，就会被一路读成"没查到"，而车次那边"没查到"等于
            // "这两个城市之间没有 3 小时内的直达" —— 一句**我们并不知道的结论**。
            // 一次日志不能修好上游，但它能把"静默走偏"变成"日志里有踪迹"。
            if (data != null && !data.isNull()) {
                log.warn("上游信封里有 data 但没有 itemList —— 可能是它改了返回结构。data 前 200 字：{}",
                        head(data.toString()));
            }
            return mapper.createArrayNode();
        }
        return list;
    }

    private static PoiCandidate toCandidate(FlyaiPoi poi) {
        FlyaiTicketFields ticket = FlyaiTicketFields.of(poi.ticketInfo());
        return new PoiCandidate(
                orEmpty(poi.id()),
                orEmpty(poi.name()),
                orEmpty(poi.address()),
                // category 实测宽查时可能是 null —— 类别缺失是真事，不要用"其他"这类假值去填，
                // 因为"补查判断"靠的就是"某个类别一条都没出现"，填假值会让它判断失效。
                stripToNull(poi.category()),
                parseCoord(poi.latitude()),
                parseCoord(poi.longitude()),
                ticket.priceYuan(),
                parseInt(poi.poiLevel()),
                stripToNull(poi.listRank()),
                ticket.itemId(),
                ticket.ticketName(),
                orEmpty(poi.mainPic()),
                orEmpty(poi.jumpUrl())
        );
    }

    private record FlyaiTicketFields(Double priceYuan, String itemId, String ticketName) {
        static FlyaiTicketFields of(com.hellen.kidtrip.flyai.raw.FlyaiTicket t) {
            if (t == null) {
                // 实测含义：这条景点在飞猪上没有可售门票商品。
                // 注意它**不等于免费** —— 也可能是门票含在母景区里（鲸鲨馆、海洋天地这类子景点）。
                return new FlyaiTicketFields(null, null, null);
            }
            return new FlyaiTicketFields(parseYuan(t.price()), stripToNull(t.itemId()), stripToNull(t.ticketName()));
        }
    }

    private static HotelCandidate toCandidate(FlyaiHotel hotel) {
        return new HotelCandidate(
                orEmpty(hotel.shId()),
                orEmpty(hotel.name()),
                orEmpty(hotel.address()),
                parseCoord(hotel.latitude()),
                parseCoord(hotel.longitude()),
                parseYuan(hotel.price()),
                // 这一行是"空格坑"的落地点，见 HotelCandidate 的注释
                stripToNull(hotel.star()),
                hotel.rate(),
                orEmpty(hotel.mainPic()),
                orEmpty(hotel.detailUrl())
        );
    }

    /* ============================================================
     * 下面四个是这一层唯一允许"猜"的地方，每个都写了为什么这么猜
     * ============================================================ */

    /**
     * "¥227" → 227.0。
     *
     * 那行正则不是随手写的：把**除数字和小数点以外的一切**剃掉。
     * 于是 "¥227" → 227，"¥1,299" → 1299（千分位逗号顺手解决），
     * "¥227 起" → 227，"面议" → ""（空 → null，正确地表示"拿不到价格"）。
     * 这是故意留的容错，不是巧合 —— 因为飞猪的格式我们控制不了。
     *
     * ⚠️ 包外可见（2026-10-13 改成 public）：车次的票价是同一个形状的字符串
     *    （"70.00"，没有货币符号），LegService 要用同一条规则。
     *    在那里再抄一份正则的话，哪天飞猪把格式改成"¥70 起"，两份就只会改一份 ——
     *    而"哪种字符串算价格"这件事，本来就该只有一个答案。
     */
    public static Double parseYuan(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("[^0-9.]", "");
        if (digits.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(digits);
        } catch (NumberFormatException e) {
            // 剃完仍不是数字（比如 "1.2.3"），当作拿不到，而不是硬凑一个值
            return null;
        }
    }

    /**
     * "22.54159" → 22.54159。
     *
     * 注意这里**不把 0 当成"缺失"**：0 是合法坐标（赤道、本初子午线）。
     * "缺失"的唯一表示法是 null —— 这也是为什么候选里的坐标类型是 Double 而不是 double：
     * 用基本类型的话，缺坐标会静默变成 0，然后距离算出来是几千公里，还不报错。
     */
    static Double parseCoord(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(raw.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** "4" → 4；null / " " / "" → null */
    static Integer parseInt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(raw.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 把"空"统一成 null。用 strip() 而不是 trim()：
     * trim() 只认 ASCII 空白（<= ' '），而全角空格 U+3000 这类字符 `Character.isWhitespace` 才认。
     * 中文数据里全角空格很常见，用 trim() 会漏。
     */
    static String stripToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.strip();
        return t.isEmpty() ? null : t;
    }

    /** 反过来：有些字段在前端契约里声明为 string（非空），这里把 null 变成空串，别让前端去判 null。 */
    static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String head(String raw) {
        return raw.length() <= 300 ? raw : raw.substring(0, 300);
    }

    /** 上游的 message 可能为 null —— 直接拼进用户会看到的句子里会变成"飞猪拒绝了这次查询：null"。 */
    private static String reasonOf(String message) {
        return message == null || message.isBlank() ? "（上游没有说明原因）" : message.strip();
    }
}
