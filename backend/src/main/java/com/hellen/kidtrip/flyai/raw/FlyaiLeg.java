package com.hellen.kidtrip.flyai.raw;

import java.util.List;

/**
 * 一条**城际走法**：`search-train` 和 `search-flight` 都回这个形状。
 *
 * 名字从 `FlyaiTrain` 改过来的（2026-10-14 加机票时）—— 这里原本只服务火车，
 * 而实测**机票返回的就是同一个结构、同一批字段名**（逐字相同，见下面那段），
 * 所以再叫 Train 就是一句会误导人的名字：读代码的人会以为航班走的是另一条路。
 * **一个类型服务两种载体时，名字不能站在其中一种那边。**
 *
 * ── 两个载体唯一的形状差异（实测 2026-10-14，深圳→上海）──────────
 *   外层价格：火车叫 `price`、机票叫 `ticketPrice`（字段名不同，含义相同）。
 *   所以这里**两个都声明**，"读哪个"由 {@code CandidateNormalizer.parsePrice} 决定 ——
 *   而不是让上游的命名习惯漏到调用方（那会让"哪个字段是价格"变成每个调用方都要想一遍的问题）。
 *   机票另外多一个 `tags`（本次实测恒为 null），不声明。
 *   其余（`journeys` / `segments` / `totalDuration` / `jumpUrl` / `transportType`…）
 *   两条链路**完全一致** —— 这就是没必要为机票再写一个 record 的理由。
 *
 * 实测结构（2026-10-13，`--origin 广州 --destination 珠海 --dep-date … --sort-type 4`）：
 * <pre>
 * { "journeys": [ { "journeyType": "直达",
 *                   "segments": [ { "depStationShortName": "广州南", "depDateTime": "2026-11-17 08:36:00",
 *                                   "arrStationShortName": "珠海",   "arrDateTime": "2026-11-17 09:29:00",
 *                                   "duration": "53", "marketingTransportName": "动车",
 *                                   "marketingTransportNo": "D947", "seatClassName": "二等座" } ],
 *                   "totalDuration": "53", "transferDuration": "" } ],
 *   "price": "70.00", "totalDuration": "53" }
 * </pre>
 *
 * ── 三个必须记住的实测事实 ────────────────────────────────────────
 *
 * ① **单位是"分钟"，而且是字符串。** `duration` / `totalDuration` 都是 `"53"`。
 *    没有秒、没有 "53min"、没有 "0:53"—— 就是分钟数的十进制字符串。
 *    （这一点值得写死，因为它是唯一一个"能拿来和 3 小时比大小"的量。）
 *
 * ② **带上 `--back-date` 时，一条 item 里是两条 journey**：去程在前，回程在后，
 *    而顶层的 `price` 是**往返总价**（实测 70+50 分的往返 = ¥140.00 = 2 × 单程 ¥70）。
 *    所以这个 record 的 `journeys` 是**列表**而不是单个 —— 别照单程的直觉写死成一条。
 *
 * ③ `journeyType` 是中文（"直达" / "中转"），`segments` 在直达时只有 1 个元素、
 *    在中转时是 N 个。**不要用 `segments[0]` 代表整段** —— 中转时它的到达站是中转站，
 *    不是目的地。取整段要用 first + last（见 LegService）。
 *
 * ⚠️ 为什么字段全用 String 而不是解析成数字/时间：
 *    这一层是**上游原样的镜子**，翻译在 LegService / CandidateNormalizer 里做。
 *    在这里提前 `Integer.parseInt` 的话，将来某天上游给个 `"53分"`，
 *    抛出的 NumberFormatException 会出现在"解析上游数据"这一层之外的某个地方，
 *    而且是一条会把整批结果带走的异常。镜子就是镜子。
 */
public record FlyaiLeg(
        List<Journey> journeys,
        /** "70.00" —— 火车的价格字段。带 --back-date 时是**往返总价** */
        String price,
        /** "610.00" —— **机票**的价格字段。和 price 是同一样东西，只是上游换了个名字 */
        String ticketPrice,
        /** 整条方案的时长（分钟字符串）。单程=去程时长；往返=两段之和 */
        String totalDuration,
        /** 飞猪的购票跳转链接 —— 演示时"点这里就能买"比任何文案都直接 */
        String jumpUrl
) {

    public FlyaiLeg {
        journeys = journeys == null ? List.of() : List.copyOf(journeys);
    }

    public record Journey(
            /** "直达" / "中转" —— 中文 */
            String journeyType,
            List<Segment> segments,
            String totalDuration,
            String transferDuration
    ) {
        public Journey {
            segments = segments == null ? List.of() : List.copyOf(segments);
        }
    }

    /**
     * 一段行程。中转方案下会有多个，用 `segments[0]` 当整段是错的（见类注释 ③）。
     *
     * 只声明我们真的会用到的字段：飞猪还回传了 arrTerm / miles / quantity / stopInfos 等等，
     * 它们全是 null 或与"带娃能不能坐"无关的东西。多声明一个字段就多一处将来会失配的地方，
     * 而 CandidateNormalizer 那边的 mapper 已经关掉了 FAIL_ON_UNKNOWN_PROPERTIES，
     * 所以少声明不会有任何代价。
     */
    public record Segment(
            String depCityName,
            String depStationName,
            /** "广州南" —— 短名，展示用。全名在 depStationName（"广州南站"） */
            String depStationShortName,
            /** "2026-11-17 08:36:00" —— **带日期的**，跨午夜的车次靠它才看得出来 */
            String depDateTime,
            String arrCityName,
            String arrStationName,
            String arrStationShortName,
            String arrDateTime,
            /** 这一段自己的耗时（分钟字符串）。中转时各段相加 ≠ totalDuration（还有候车） */
            String duration,
            /** "动车" / "高铁" / "城际"；机票是航司名（"深航"、"春秋"） */
            String marketingTransportName,
            /** "D947"；机票是航班号（"ZH9529"）——**不带航司前缀**，要自己拼 */
            String marketingTransportNo,
            /** "二等座"；机票是舱位（"经济舱"） */
            String seatClassName,
            /**
             * **"火车" / "飞机"** —— 上游直接给了载体，不用按有没有 `stationShortName` 去猜。
             *
             * ⚠️ 但这一层**不用它**做任何判断（它是上游的镜子）。真正定载体的是
             *    "我们查的是哪个子命令"（见 LegService）—— 因为那件事我们自己知道，
             *    而且比下游字段更可靠（字段哪天变了，我们是不知道自己查了什么了吗？）
             */
            String transportType
    ) {
    }
}
