package com.hellen.kidtrip.common;

import com.hellen.kidtrip.flyai.FlyaiException;
import com.hellen.kidtrip.llm.LlmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常出口 —— 一个 Controller 都不写 try/catch 的代价，由这个类统一承担。
 *
 * 它存在的根本原因，是 ApiResult 那条约定的直接后果：
 *   HTTP 状态码恒为 200，所以异常**不能**靠 500 这种状态码表达，
 *   必须被翻译成一个 code 非 0、message 给人看的 ApiResult。不翻译，用户看到的就是一份 Tomcat 的默认错误页。
 *
 * @RestControllerAdvice 覆盖范围内所有 Controller 抛出的异常；
 * 匹配顺序是"最具体的先中"，所以 FlyaiException 会走第一个方法，其余走兜底那个。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 可预期的业务失败：message 是给人看的，原样传给前端。 */
    @ExceptionHandler(FlyaiException.class)
    public ApiResult<Void> handleFlyai(FlyaiException e) {
        log.warn("飞猪查询失败：{}", e.getMessage());
        return ApiResult.fail(2001, e.getMessage());
    }

    /**
     * 大模型这一步的失败（B4 新增）。
     *
     * 给 5001 而不是复用 2001：这两个失败**用户能做的事不一样** ——
     *   2001（飞猪）多半是他查的城市/条件太窄，改一下条件就有结果；
     *   5001（模型）是"再点一次生成"就行。
     *   前端以后可以据此决定按钮上是显示"重试"还是"换个城市试试"。
     * 错误码的用途不是分类，是**让调用方知道该怎么办**。
     */
    @ExceptionHandler(LlmException.class)
    public ApiResult<Void> handleLlm(LlmException e) {
        log.warn("大模型调用失败：{}", e.getMessage());
        return ApiResult.fail(5001, e.getMessage());
    }

    /**
     * 入参不合法（比如 city 没传）。
     *
     * 为什么单独拎出来：这类错误的 message 是**我们自己写的**（"city 不能为空 —— 不知道查哪座城市"），
     * 它天然是给人看的、也是能指导用户改的。
     * 如果让它掉进下面那个兜底分支，用户看到的就会是"服务开小差了" ——
     * 明明是他少填了一个字段，却被告知服务坏了，然后去重试，白等一轮。
     *
     * 代价说清楚：这样做等于把整个应用里所有 IllegalArgumentException 都当成"用户的错"。
     * 现在只有校验会在参数层面抛它，所以成立；哪天有别的库用它表达内部错误，要回头收窄。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResult<Void> handleBadRequest(IllegalArgumentException e) {
        log.warn("请求不合法：{}", e.getMessage());
        return ApiResult.fail(4000, e.getMessage());
    }

    /**
     * 请求体读不出来（B5 新增）。
     *
     * 什么情况会走到这里：body 不是合法 JSON、字段类型对不上（`"days": "三天"`）、
     * 或者压根没带 body。Spring 在**进入 Controller 之前**就抛这个异常，
     * 所以它到不了我们自己的校验代码。
     *
     * 为什么必须单独接住：不接的话它会掉进下面的兜底分支，
     * 用户看到的是"服务开小差了，请稍后重试" —— 而实际上他应该看到的是
     * "请求格式不对"。**这两种 message 指向完全不同的下一步动作**：
     * 前者他会去重试（永远失败），后者他会去看自己发的东西（一次修好）。
     * 错误码的用途不是分类，是让调用方知道该怎么办 —— 这句话在这里又用了一次。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ApiResult<Void> handleUnreadableBody(HttpMessageNotReadableException e) {
        log.warn("请求体无法解析：{}", e.getMessage());
        return ApiResult.fail(4000, "请求体格式不对，无法解析成 JSON");
    }

    /**
     * 兜底。
     * 这里**故意**不把 e.getMessage() 返回出去：那里面可能是
     * "java.lang.NullPointerException" 或者某个数据库字段名 —— 用户看不懂，也没必要看懂。
     * 堆栈进日志（我们自己排障用），接口只给一句人话。
     */
    @ExceptionHandler(Exception.class)
    public ApiResult<Void> handleUnexpected(Exception e) {
        log.error("未预期的错误", e);
        return ApiResult.fail(5000, "服务开小差了，请稍后重试");
    }
}
