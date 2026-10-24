package com.hellen.kidtrip.plan;

import com.hellen.kidtrip.common.ApiResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 规划相关的三个接口（B5 后补成一对，B12 又加了第三个：进度）。
 *
 * 它们的关系是**前半段和后半段**，不是两个独立功能：
 *   POST /api/plan/chat      —— 对话，一轮一轮把画像攒出来（无状态，每次带全量历史）
 *   POST /api/plan/generate  —— 拿攒好的画像去查、去排，产出行程
 * 中间那个"画像"就是两者的交接物：chat 的产物正好是 generate 的入参。
 * 所以前端不需要做什么转换，把 chat 返回的 params 原样递给 generate 就行 ——
 * **两个接口共用同一个类型的形状，是让它们能对接的唯一原因。**
 *
 * 一个真实请求的耗时（这个数字是**架构的真实成本**，不是调参能消掉的）：
 *   /chat             约 5~15 秒（一次模型调用）
 *   /generate         约 25~60 秒（查车次 + 规划检索条件 10~20 秒 + 检索 2~10 秒 + 排行程 10~30 秒）
 *   /progress/{runId} 几十字节，前端在 /generate 飞着的时候每秒问一次
 *
 * ── 那句「下一步该做的」已经落地了 ────────────────────────────────
 * 这里原来写着：下一步真正该做的不是让它变快，而是**让用户不用干等** —— 流式输出 / 分步返回。
 * 现在做的是它的**轮询版**：第三个接口 `/progress/{runId}`，把 /generate 那几十秒里
 * 走过的每一步都报出来 —— 哪几步真的走完了、每步真实花了多久。
 * 界面上那条流水线因此不是装饰，它是**流程的投影**。
 *
 * 为什么不选流式（流式看起来更"正"，但在这个产品里是错的）：
 *   ① 流式要把一个响应拆成"一串事件"，契约从"一个对象"变成"一个协议"，
 *      而这套系统别的地方全是"一次请求一个 ApiResult"；
 *   ② 更硬的理由：这条产品要跑在内网穿透上，**Cloudflare 免费隧道不支持 SSE** ——
 *      流式会在最要命的那一刻断掉。轮询在任何代理上都稳，代价只是进度最多晚 1 秒。
 */
@RestController
@RequestMapping("/api/plan")
public class PlanController {

    private final PlanService planService;
    private final ChatService chatService;
    private final GenerationProgress progress;
    private final DailyGenerateGuard dailyGuard;
    private final ProfileLog profileLog;

    public PlanController(PlanService planService, ChatService chatService, GenerationProgress progress,
                          DailyGenerateGuard dailyGuard, ProfileLog profileLog) {
        this.planService = planService;
        this.chatService = chatService;
        this.progress = progress;
        this.dailyGuard = dailyGuard;
        this.profileLog = profileLog;
    }

    /** 对话引导：把出行信息一轮一轮问清楚 */
    @PostMapping("/chat")
    public ApiResult<PlanChatData> chat(@RequestBody PlanChatRequest request,
            @RequestHeader(value = "X-Entry", required = false) String entry) {
        PlanChatData data = chatService.chat(request);
        // 画像留痕：params 是这轮合并后的累计画像 —— "妈妈们都在问哪"从这条日志统计
        profileLog.record("chat", entry, data.params(), data.persona(),
                request.messages() == null ? null : request.messages().size());
        return ApiResult.ok(data);
    }

    /**
     * 生成行程。
     *
     * `runId` 走**请求头**，不进请求体 —— 因为它不是这次生成要用的输入，
     * 只是"我想看它怎么跑"的旁路信息。放进 PlanGenerateRequest 的话，
     * 一个纯观测用的字段就混进了那份被契约测试和前端类型两头钉着的形状里，
     * 以后没人说得清"这个字段到底受哪份测试保护"。
     *
     * 不带这个头时一切照旧（runId 为 null → 所有打点都是空操作），
     * 老的调用方、脚本、curl 一行都不用改。
     */
    @PostMapping("/generate")
    public ApiResult<TravelPlan> generate(
            @RequestBody PlanGenerateRequest request,
            @RequestHeader(value = "X-Run-Id", required = false) String runId,
            @RequestHeader(value = "X-Entry", required = false) String entry) {
        // 画像留痕：记在闸门**之前** —— 被限额拒掉的请求同样是需求信号
        profileLog.record("generate", entry, request.params(), request.persona(), null);
        // 每日闸门只认 nginx 打的入口标记（8080 口令入口 = mama，主入口不带）。
        // 拒绝时 message 会原样显示在前端的错误条里，所以它必须是句人话。
        String rejected = dailyGuard.tryAcquire(entry);
        if (rejected != null) {
            return ApiResult.fail(4290, rejected);
        }
        return ApiResult.ok(planService.generate(request, runId));
    }

    /**
     * 看这一趟生成走到哪了。前端在 /generate 飞着的时候每秒问一次。
     *
     * 查到未知或已过期的 runId 时返回 `data: null`，**不是抛错、也不是造一份空进度**。
     * 前端把 null 读成"这趟已经不在场上了"（轮询的最后一拍总要落地），安静收工即可；
     * 要是这里回一份"五步全部待办"的空进度，界面会以为任务**回到了起点**，比报错更糟。
     */
    @GetMapping("/progress/{runId}")
    public ApiResult<GenerationProgress.Snapshot> progress(@PathVariable String runId) {
        return ApiResult.ok(progress.snapshot(runId));
    }
}
