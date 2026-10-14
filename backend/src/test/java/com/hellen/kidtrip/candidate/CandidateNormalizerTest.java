package com.hellen.kidtrip.candidate;

import com.hellen.kidtrip.flyai.FlyaiException;
import com.hellen.kidtrip.flyai.raw.FlyaiTrain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规范化层里最容易被忽略、却决定"用户能不能看到真正原因"的一段：**上游 status 的处理**。
 *
 * 这两条用例是一对的，必须一起看：
 *   · status != 0 → 抛（上游说参数错）
 *   · status == 0 且 data == null → 空列表，不抛（上游说没数据）
 * 只写其中一条，就分不出"参数错了"和"没查到" —— 而飞猪恰恰把这两件事
 * 装得差不多（两者的 data 都是 null），唯一的分界线就是 status 和 message。
 */
@DisplayName("规范化：上游 status 不能被吞掉")
class CandidateNormalizerTest {

    private final CandidateNormalizer normalizer = new CandidateNormalizer(new ObjectMapper());

    @Test
    @DisplayName("⭐ 实测：过去日期 → status=1 +「入离时间校验失败」→ 必须抛出来，不能降级成「没查到」")
    void throwsWhenUpstreamRejectsParams() {
        // 这段 JSON 是 2026-10-13 真实抓到的响应，逐字抄下来的
        String json = """
                {"data":null,
                 "message":"[CICO_CHECK_PARAM_ERROR] AI hotel search fail: 入离时间校验失败",
                 "status":1,
                 "systemMessage":null}
                """;

        FlyaiException e = assertThrows(FlyaiException.class, () -> normalizer.hotels(json));

        assertEquals(FlyaiException.Reason.PARAM, e.reason(),
                "必须归类成 PARAM —— 上层靠它区分「参数错了别重试」和「限流了等会儿再试」");
        assertTrue(e.getMessage().contains("入离时间校验失败"),
                "上游那句真正有用的原因必须带出来，不能换成我们自己编的一句话：" + e.getMessage());
    }

    @Test
    @DisplayName("status=0 + data=null（就是「没查到」）→ 空列表且**不报错**，两件事必须分得开")
    void emptyResultIsNotAnError() {
        String json = """
                {"data":null,"message":"empty","status":0,"systemMessage":null}
                """;

        CandidateNormalizer.Normalized<PoiCandidate> r = normalizer.pois(json);

        assertTrue(r.items().isEmpty());
        assertEquals(0, r.failed());
    }

    @Test
    @DisplayName("上游没给 message 时，也不能吐出一句「…：null」")
    void missingMessageGetsAPlaceholder() {
        String json = """
                {"data":null,"message":null,"status":1,"systemMessage":null}
                """;

        FlyaiException e = assertThrows(FlyaiException.class, () -> normalizer.pois(json));

        assertFalse(e.getMessage().contains("null"), e.getMessage());
    }

    /* ============================================================
     * 车次（search-train）—— 这里 status=1 有**两种相反的含义**
     * ============================================================ */

    @Test
    @DisplayName("⭐⭐ 实测：车次查询的 status=1 +「行程时长过滤结果为空」→ NO_RESULT，不是 PARAM")
    void trainNoMatchIsAConclusionNotAFailure() {
        // 逐字抄自 2026-10-13 的实测响应：
        //   search-train --origin 珠海 --destination 桂林 --total-duration-hour 3
        // 它说的是一件**事实**：这两地之间没有 3 小时内的直达。
        // 如果归成 PARAM 或"空列表"，上层就只能理解成"查询没成功"或者"不知道为什么是空的"——
        // 而正确的处置完全不同：前者会让用户一直重试一件永远不会成功的事，
        // 后者则丢掉了一条他其实需要知道的信息（"这条路带娃太累"）。
        String json = """
                {"data":null,"message":"行程时长过滤结果为空","status":1,"systemMessage":null}
                """;

        FlyaiException e = assertThrows(FlyaiException.class, () -> normalizer.trains(json));

        assertEquals(FlyaiException.Reason.NO_RESULT, e.reason(),
                "这是结论，不是故障 —— LegService 靠 reason 区分「没有这种走法」和「查询失败」");
        assertTrue(e.getMessage().contains("行程时长过滤结果为空"),
                "上游的原话要留着，排障时它是唯一能说明'为什么空'的东西：" + e.getMessage());
    }

    @Test
    @DisplayName("同样是 status=1，但 message 是别的 → 仍然是 PARAM（故障），绝不能被归成 NO_RESULT")
    void trainOtherFailureStaysParam() {
        // 这一条和上一条必须成对看：它们唯一的区别就是 message。
        // 把"查询失败"误判成"没有这种走法"是**最坏的一种错**——
        // 用户会永远放弃一条其实存在的路线，而且不报错、查不出来。
        String json = """
                {"data":null,"message":"[CICO_CHECK_PARAM_ERROR] train search fail","status":1,"systemMessage":null}
                """;

        FlyaiException e = assertThrows(FlyaiException.class, () -> normalizer.trains(json));

        assertEquals(FlyaiException.Reason.PARAM, e.reason(),
                "message 不是那句结论 → 这就是一次失败，不能推断'没有车次'");
    }

    @Test
    @DisplayName("车次正常返回 → 解析出 journeys / segments / price（日期时间是**带日期的**）")
    void parsesTrainResult() {
        // 逐字抄自实测响应（截了第一条）。注意 depDateTime 是 "2026-11-17 08:36:00" ——
        // 带日期，而且用**空格**分隔，不是 ISO 的 'T'。这两点都由 LegService 负责翻译。
        String json = """
                {"data":{"itemList":[
                  {"journeys":[{"journeyType":"直达","segments":[
                     {"depCityName":"广州","depStationName":"广州南站","depStationShortName":"广州南",
                      "depDateTime":"2026-11-17 08:36:00",
                      "arrCityName":"珠海","arrStationName":"珠海站","arrStationShortName":"珠海",
                      "arrDateTime":"2026-11-17 09:29:00",
                      "duration":"53","marketingTransportName":"动车","marketingTransportNo":"D947",
                      "seatClassName":"二等座","transportType":"火车"}],
                   "totalDuration":"53","transferDuration":""}],
                   "price":"70.00","totalDuration":"53",
                   "jumpUrl":"https://router.feizhu.com/x"}
                ]},"message":"success","status":0,"systemMessage":null}
                """;
        CandidateNormalizer.Normalized<FlyaiTrain> r = normalizer.trains(json);

        assertEquals(1, r.items().size());
        assertEquals(0, r.failed());
        FlyaiTrain train = r.items().get(0);
        assertEquals("70.00", train.price());
        assertEquals("53", train.totalDuration());
        assertEquals(1, train.journeys().size());
        assertEquals("直达", train.journeys().get(0).journeyType());
        assertEquals("广州南", train.journeys().get(0).segments().get(0).depStationShortName());
        assertEquals("2026-11-17 08:36:00", train.journeys().get(0).segments().get(0).depDateTime(),
                "上游给的原文要原样保留 —— 翻译成 ISO 是 LegService 的事，这一层是镜子");
    }

    @Test
    @DisplayName("车次 status=0 但一条都没有 → 空列表，不报错（和景点/酒店同一条规矩）")
    void trainEmptyResultIsNotAnError() {
        String json = """
                {"data":null,"message":"empty","status":0,"systemMessage":null}
                """;

        assertTrue(normalizer.trains(json).items().isEmpty());
    }
}
