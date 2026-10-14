package com.hellen.kidtrip.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

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
        Integer timeoutSeconds
) {

    public LlmProperties {
        if (apiKey == null || apiKey.isBlank()) {
            // 把"怎么修"写进异常消息里。启动失败的信息是这个类最有价值的部分：
            // 它决定了你是在 5 秒内修好，还是去搜半天。
            throw new IllegalStateException("""
                    缺少配置 kidtrip.llm.api-key（大模型密钥）。
                    请在启动前注入环境变量，例如：
                      Windows(cmd):  set DEEPSEEK_API_KEY=sk-xxxx && mvn spring-boot:run
                      bash/git-bash: export DEEPSEEK_API_KEY=sk-xxxx
                    配置文件里写死密钥等于把密钥提交进仓库，所以这里故意不留默认值。""");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.deepseek.com";
        }
        if (modelName == null || modelName.isBlank()) {
            modelName = "deepseek-chat";
        }
        if (temperature == null || temperature < 0 || temperature > 2) {
            temperature = 0.3;
        }
        if (timeoutSeconds == null || timeoutSeconds <= 0) {
            timeoutSeconds = 90;
        }
    }
}
