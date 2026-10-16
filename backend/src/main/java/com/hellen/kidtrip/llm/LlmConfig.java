package com.hellen.kidtrip.llm;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 把 ChatModel 做成一个 Spring bean —— 全应用共用同一个实例。
 *
 * 为什么不加 langchain4j-spring-boot-starter 让它自动装配：
 *   自动装配能省这几行，但代价是"这个 bean 是怎么来的"从你的阅读路径里消失了 ——
 *   出问题时你要先去猜 starter 读了哪个前缀、按什么规则建的。
 *   我们要用的只有 4 个参数，写出来 8 行，值这点钱。
 *   （如果你以后要接 5 个模型、要按会话切模型，那时候再让 starter 接管也不迟。）
 *
 * 为什么用 OpenAiChatModel 连 DeepSeek：
 *   DeepSeek 提供的是 **OpenAI 兼容接口**（/v1/chat/completions），
 *   所以任何"OpenAI 客户端"只要改 base-url 和 api-key 就能用。
 *   这也意味着换成别的兼容厂商（月之暗面、通义、本地 vLLM…）只改配置、不改代码。
 */
@Configuration
public class LlmConfig {

    @Bean
    public ChatModel chatModel(LlmProperties props) {
        return OpenAiChatModel.builder()
                .baseUrl(props.baseUrl())
                .apiKey(props.apiKey())
                .modelName(props.modelName())
                .temperature(props.temperature())
                .timeout(Duration.ofSeconds(props.timeoutSeconds()))
                // 额外请求体字段（目前只有 enable_thinking，见 LlmProperties）。
                // 为什么不用 .reasoningEffort(...)：那是 OpenAI 的推理档位（low/medium/high），
                // 而通义这个网关认的是自己的 enable_thinking。名字看着像同一件事，
                // 其实是两个厂商各自的私有字段 —— 用谁的接口就发谁认的字段。
                .customParameters(props.extraBodyParams())
                // 打开请求/响应日志。
                // 在"模型不听话"这类问题面前，能看到**实际发出去的 prompt** 是唯一有效的调试手段 ——
                // 否则你只能对着自己的代码猜"是不是拼错了"。
                // 代价：日志里会有完整 prompt（含用户偏好），生产环境要按需要关掉。
                .logRequests(true)
                .logResponses(true)
                .build();
    }
}
