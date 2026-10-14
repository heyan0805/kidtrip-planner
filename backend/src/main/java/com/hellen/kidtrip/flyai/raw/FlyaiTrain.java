package com.hellen.kidtrip.flyai.raw;

import java.util.List;

/**
 * `search-train` 返回的一条**往返方案**（不只是单程 —— 见下）。
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
public record FlyaiTrain(
        List<Journey> journeys,
        /** "70.00" —— 带 --back-date 时是**往返总价** */
        String price,
        /** 整条方案的时长（分钟字符串）。单程=去程时长；往返=两段之和 */
        String totalDuration,
        /** 飞猪的购票跳转链接 —— 演示时"点这里就能买"比任何文案都直接 */
        String jumpUrl
) {

    public FlyaiTrain {
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
            /** "动车" / "高铁" / "城际" */
            String marketingTransportName,
            /** "D947" */
            String marketingTransportNo,
            /** "二等座" */
            String seatClassName,
            /** "火车" */
            String transportType
    ) {
    }
}
