package com.hellen.kidtrip.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * 大模型接入配置，对应 application.yaml 里的 kidtrip.llm.*。
 *
 * 和 FlyaiProperties 是同一套写法（record + 紧凑构造器校验/补默认值），
 * 但有一处**故意不同**：apiKey **没有默认值**，缺了就直接抛异常。
 *
 * 为什么这里要"启动就失败"，而 FlyaiProperties 也是这样：
 *   一个规划服务连不上模型，就没有任何一件它能做的事。
 *   让它起来、然后在用户点"生成"的那一刻才报错，只会浪费用户一次等待。
 *   失败要趁早，而且要带着"该怎么修"一起失败。
 *
 * ⚠️ 这条原则**不是无脑套用的**。判断标准是"缺了它，这个服务还能不能干活"：
 *   缺 apiKey → 完全不能干活 → 启动失败；
 *   缺某个可选功能（比如以后加的天气源）→ 其余功能照用 → 启动只打个 WARN。
 *   拿不准的时候问自己：这个配置缺失时，哪些接口还能正常工作？
 */
@ConfigurationProperties(prefix = "kidtrip.llm")
public record LlmProperties(
        String apiKey,
        String baseUrl,
        String modelName,
        /** 抽样温度。null → 0.3 */
        Double temperature,
        /** 单次调用超时（秒）。<= 0 → 90 */
        Integer timeoutSeconds,
        /**
         * 要不要带 `enable_thinking` 这个请求体参数。**三态，null 也是一个有意义的值**：
         *   null  → 请求体里完全不出现这个字段（"不干预，用厂商默认"）；
         *   true  → 显式打开思考；
         *   false → 显式关闭思考。
         *
         * ── 为什么必须做成三态，而不是"默认 false" ──────────────────────
         *   因为这个参数**不是 OpenAI 规范的一部分**，是通义/百炼的扩展字段。
         *   DeepSeek、月之暗面那些兼容厂商看到一个自己不认识的字段会怎么反应，
         *   取决于它们各自的实现（有的直接 400，有的静默忽略）——
         *   我们没有任何证据，也不该拿"换个 base-url 就能跑"这条设计去赌。
         *   所以：**想关思考就显式写 false，不写就一个字段都不发。**
         *   拿不准时不给对方发它没要求的东西，和 Message.normalizedRole 里
         *   "拿不准当 user" 是同一种取舍：选代价小的那一边。
         *
         * ── 为什么这个开关是必须的（实测，2026-10-15）───────────────────
         *   千问 3.x 系列**默认开着思考**，而思考 token 是白等的时间：
         *   同样的 1500 字请求，思考开 → 9843 个 token（其中 8866 是思考），
         *   而**非流式**请求在模型思考期间线上一个字节都不流动 ——
         *   通义那个专用网关（*.maas.aliyuncs.com）会把它当空闲连接掐掉，
         *   客户端只看到 `IOException: EOF reached while reading`。
         *   停掉思考之后同一个请求只用 519 个 token、11 秒，稳定通过。
         *   这一项不是"优化"，是"能不能用"。
         */
        Boolean enableThinking
) {

    /**
     * 要额外塞进请求体的字段。null → 空 map（一个字节都不加）。
     */
    public Map<String, Object> extraBodyParams() {
        return enableThinking == null ? Map.of() : Map.of("enable_thinking", enableThinking);
    }

    public LlmProperties {
        if (apiKey == null || apiKey.isBlank()) {
            // 把"怎么修"写进异常消息里。启动失败的信息是这个类最有价值的部分：
            // 它决定了你是在 5 秒内修好，还是去搜半天。
            throw new IllegalStateException("""
                    缺少配置 kidtrip.llm.api-key（大模型密钥）。
                    请在启动前注入环境变量，例如：
                      Windows(cmd):  set KIDTRIP_LLM_API_KEY=sk-xxxx && mvn spring-boot:run
                      bash/git-bash: export KIDTRIP_LLM_API_KEY=sk-xxxx
                    本项目还有两个配套变量（不配就用配置文件里的默认值）：
                      KIDTRIP_LLM_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
                      KIDTRIP_LLM_MODEL_NAME=qwen-plus
                    配置文件里写死密钥等于把密钥提交进仓库，所以这里故意不留默认值。""");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        }
        if (modelName == null || modelName.isBlank()) {
            modelName = "qwen-plus";
        }
        if (temperature == null || temperature < 0 || temperature > 2) {
            temperature = 0.3;
        }
        if (timeoutSeconds == null || timeoutSeconds <= 0) {
            timeoutSeconds = 90;
        }
    }
}
