package com.hellen.kidtrip.flyai;

/**
 * 飞猪查询失败。
 *
 * 为什么单独定义一个异常，而不是随便抛 RuntimeException：
 *   它是**可预期的失败**（超时、退出码非 0、返回不是 JSON），不是程序 bug。
 *   把"可预期失败"单独标出来，GlobalExceptionHandler 就能对它做区别对待：
 *   它的 message 原样返回给用户（因为写的时候就是给人看的），
 *   而其它任何异常一律被替换成"服务开小差了"，堆栈只进日志、不出接口。
 *
 *   这也是 ApiResult 那条约定的落地点：message 会被前端直接显示，
 *   所以凡是能流到用户眼前的 message，都必须是给人看的话。
 *
 * ⚠️ 为什么还要细分 Reason（B3 加）：
 *   因为**不同的失败该有不同的处置**。超时 → 别重试，等也等不回来；
 *   被限流 → 必须等一会儿再试，立刻重试只会加重封锁。
 *   如果只有一个笼统的 FlyaiException，上层就只能靠去 match 错误消息里的字符串来猜，
 *   而错误消息是给人看的、随时可能改 —— 拿它当控制流是最脆的一种写法。
 *   把"该怎么处置"在抛出点就定死，上层才不用去猜。
 */
public class FlyaiException extends RuntimeException {

    public enum Reason {
        /** 起不来进程：node 路径错 / cli-path 错 */
        LAUNCH,
        /** 超过配置的 timeoutSeconds —— 重试没有意义 */
        TIMEOUT,
        /** 线程被中断（通常是服务在关闭） */
        INTERRUPTED,
        /** 退出码非 0，且不是限流 */
        EXIT_CODE,
        /** 退出码非 0，且错误消息里带 429 / 451 —— **这个值得等一会儿再试** */
        RATE_LIMITED,
        /** 退出码 0 但什么都没吐 */
        EMPTY,
        /** 吐出来的不是合法 JSON */
        BAD_JSON,
        /**
         * 请求跑通了，但**上游在信封里说参数不合法**（顶层 status != 0）。
         *
         * 2026-10-13 实测补上：过去日期的酒店查询返回
         *   { "data": null, "message": "[CICO_CHECK_PARAM_ERROR] AI hotel search fail: 入离时间校验失败", "status": 1 }
         * 而**退出码是 0** —— 所以它一路躲过了上面所有分支，最后被当成"没查到数据"。
         *
         * 为什么必须和别的失败分开：这类失败**重试和放宽都没有意义**。
         * 参数不改，结果就不会变 —— 而"没查到数据"的默认处置恰恰是放宽条件重查一次，
         * 那一次必然是白花的额度（实测就是这么白花了一次）。
         */
        PARAM,

        /**
         * 上游明确回答了"**按你给的条件，一条都没有**"（status = 1，但这是一句**结论**）。
         *
         * 2026-10-13 实测补上，来自城际车次查询：
         *   search-train --origin 珠海 --destination 桂林 --total-duration-hour 3
         *   → { "data": null, "message": "行程时长过滤结果为空", "status": 1 }
         *
         * ⚠️ 它和上面每一个 Reason 的关系都是**相反**的：
         *   TIMEOUT / PARAM / EXIT_CODE 说的是"这次查询没成功"，出路是重试或换条件；
         *   NO_RESULT 说的是"查询成功了，答案是空"，出路是**把这个答案告诉用户**。
         *   两者混起来的后果非常具体：把"珠海和桂林之间没有 3 小时内的直达"
         *   显示成"车次查询失败，请稍后再试" —— 用户会一直重试一件永远不会成功的事。
         *
         *   所以它必须是一个独立的 Reason，而不是借用 PARAM 或"空列表"来表达：
         *   "空列表"表达不了"为什么空"，而 PARAM 会把结论说成故障。
         */
        NO_RESULT
    }

    private final Reason reason;

    public FlyaiException(String message) {
        this(Reason.EXIT_CODE, message);
    }

    public FlyaiException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    /**
     * 是否值得"等一会儿再试"。
     * 注意 TIMEOUT 故意**不**算可重试 —— 一个已经跑满 120 秒的查询，
     * 立刻再来一次大概率还是 120 秒，用户等不起，而且它还会继续占用限流额度。
     */
    public boolean isRateLimited() {
        return reason == Reason.RATE_LIMITED;
    }
}
