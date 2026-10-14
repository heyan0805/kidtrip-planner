package com.hellen.kidtrip.flyai.raw;

import tools.jackson.databind.JsonNode;

/**
 * 所有飞猪子命令共用的外层信封。实测两种形态（**别照直觉写**）：
 *
 * <pre>
 * 成功   → { "data": { "itemList": [...] }, "message": "success", "status": 0, "systemMessage": null }
 * 无结果 → { "data": null,                  "message": "empty",   "status": 0, "systemMessage": null }
 * </pre>
 *
 * 两个坑值得单独记住：
 * ① message / status / systemMessage 在**顶层**，不在 data 里（data 里只有 itemList）。
 * ② **无结果时 status 也是 0**，和成功一模一样 —— 不能靠状态码判断有没有数据。
 *    这跟自家 ApiResult 的 code = 0 表示成功撞在一起，很容易误判。
 *
 * 所以这里把 data 声明成 JsonNode 而不是泛型 List：**把"它可能是 null"变成类型上看得见的事实**。
 * itemList() 里那道 null 判断就是这条约束的落地点 —— 少了它，Java 侧就是一个 NPE。
 */
public record FlyaiEnvelope(
        JsonNode data,
        String message,
        int status,
        String systemMessage
) {

    /** 没有结果时返回 null（而不是空数组）——"没查到"和"查到了 0 条"在语义上是一回事，都归为没有候选。 */
    public JsonNode itemList() {
        if (data == null || data.isNull()) {
            return null;
        }
        return data.get("itemList");
    }
}
