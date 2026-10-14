package com.hellen.kidtrip.retrieval;

import com.hellen.kidtrip.common.ApiResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 检索接口：给一个"期望"，拿回一个**可追溯的候选池**。
 *
 * 它和 B2 那个探针接口（/api/flyai/probe/*）的分工要分清楚：
 *   探针 = 我只想看飞猪**原样**吐了什么（不清洗、不筛、不缓存）
 *   这个 = 我要一份**能直接拿去做行程**的干净候选，外加"它是怎么来的"
 * 两者都要留着：出了问题，探针证明"飞猪给的就是这样"，这个接口证明"我们没把它改坏"。
 *
 * 它是 POST 不是 GET，因为请求体里有数组（wantedCategories / keywords）——
 * 塞进 query string 会变成 `?wantedCategories=动物园&wantedCategories=海洋馆` 这种要靠约定去解析的东西，
 * 不如直接用一个 JSON 对象把"期望"整个说清楚。而且它带副作用（花上游额度、写缓存），语义上也更接近 POST。
 */
@RestController
@RequestMapping("/api/retrieval")
public class RetrievalController {

    private final RetrievalService retrievalService;

    public RetrievalController(RetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    @PostMapping("/pool")
    public ApiResult<RetrievalResult> pool(@RequestBody RetrievalRequest request) {
        return ApiResult.ok(retrievalService.retrieve(request));
    }
}
