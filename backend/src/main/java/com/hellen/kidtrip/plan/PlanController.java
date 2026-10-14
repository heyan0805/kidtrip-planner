package com.hellen.kidtrip.plan;

import com.hellen.kidtrip.common.ApiResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 规划相关的两个接口（B5 后补齐为一对）。
 *
 * 它们的关系是**前半段和后半段**，不是两个独立功能：
 *   POST /api/plan/chat      —— 对话，一轮一轮把画像攒出来（无状态，每次带全量历史）
 *   POST /api/plan/generate  —— 拿攒好的画像去查、去排，产出行程
 * 中间那个"画像"就是两者的交接物：chat 的产物正好是 generate 的入参。
 * 所以前端不需要做什么转换，把 chat 返回的 params 原样递给 generate 就行 ——
 * **两个接口共用同一个类型的形状，是让它们能对接的唯一原因。**
 *
 * 一个真实请求的耗时（这个数字是**架构的真实成本**，不是调参能消掉的）：
 *   /chat      约 5~15 秒（一次模型调用）
 *   /generate  约 25~60 秒（检索 2~10 秒 + 规划检索条件 10~20 秒 + 排行程 10~30 秒）
 * 所以下一步真正该做的不是"让它变快"，而是**让用户不用干等** ——
 * 流式输出 / 分步返回。那是 B6 之后的事，现在先把链路跑通。
 */
@RestController
@RequestMapping("/api/plan")
public class PlanController {

    private final PlanService planService;
    private final ChatService chatService;

    public PlanController(PlanService planService, ChatService chatService) {
        this.planService = planService;
        this.chatService = chatService;
    }

    /** 对话引导：把出行信息一轮一轮问清楚 */
    @PostMapping("/chat")
    public ApiResult<PlanChatData> chat(@RequestBody PlanChatRequest request) {
        return ApiResult.ok(chatService.chat(request));
    }

    @PostMapping("/generate")
    public ApiResult<TravelPlan> generate(@RequestBody PlanGenerateRequest request) {
        return ApiResult.ok(planService.generate(request));
    }
}
