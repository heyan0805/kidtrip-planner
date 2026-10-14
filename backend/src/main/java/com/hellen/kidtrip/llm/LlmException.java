package com.hellen.kidtrip.llm;

/**
 * 大模型这一步的失败。和 FlyaiException 是同一个思路：
 * 它是**可预期的失败**（超时、返回的不是合法 JSON、被限流），不是程序 bug。
 *
 * 把它单独标出来，GlobalExceptionHandler 才能区别对待：
 * 它的 message 原样给用户看，其余异常一律换成"服务开小差了"。
 *
 * 为什么"解析不出 JSON"也算可预期：
 *   因为大模型的输出**在契约上就是不可靠的**（它是个概率模型，不是编译器）。
 *   你的代码里凡是出现"模型一定会按我说的格式返回"这个假设，那里就一定是个 bug。
 */
public class LlmException extends RuntimeException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
