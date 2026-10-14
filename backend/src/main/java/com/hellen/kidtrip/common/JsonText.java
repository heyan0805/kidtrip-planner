package com.hellen.kidtrip.common;

/**
 * 大模型输出文本的小工具。
 *
 * 为什么需要它：模型经常不听"只输出 JSON"这句话，会把 JSON 包在
 * ```json ... ``` 围栏里，或者前面加一句"好的，这是你的行程："。
 * 这是**已知会发生的常态**，不是异常 —— 所以清洗是必须的一步，不是防御性代码。
 */
public final class JsonText {

    private JsonText() {
    }

    /**
     * 剥掉 markdown 代码围栏和前后杂话，尽量留下一个纯 JSON 对象。
     *
     * 策略（从便宜到贵）：
     *   1. 去掉 ```json / ``` 围栏；
     *   2. 如果剥完还不是以 { 或 [ 开头，就**从第一个 { 截到最后一个 }** ——
     *      这一步是在处理"模型在 JSON 前后各说了一句话"的情况。
     *
     * 它不试图理解 JSON、也不做修复：真解析不了就让上层报"这次没返回可用数据"，
     * 而不是在这里硬凑一个能解析的字符串 —— 硬凑出来的东西能过解析，但内容可能已经错了，
     * 那比直接失败危险得多。
     */
    public static String stripFences(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.startsWith("```")) {
            s = s.replaceAll("^```[A-Za-z]*\\s*", "");
            s = s.replaceAll("```\\s*$", "");
        }
        s = s.trim();
        int open = indexOfFirst(s, '{', '[');
        if (open > 0) {
            int close = Math.max(s.lastIndexOf('}'), s.lastIndexOf(']'));
            if (close > open) {
                s = s.substring(open, close + 1);
            }
        }
        return s.trim();
    }

    /** 日志用：把长文本截断，避免一行日志打出几 KB 的 prompt。 */
    public static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…(共 " + s.length() + " 字)";
    }

    private static int indexOfFirst(String s, char a, char b) {
        int ia = s.indexOf(a);
        int ib = s.indexOf(b);
        if (ia < 0) {
            return ib;
        }
        if (ib < 0) {
            return ia;
        }
        return Math.min(ia, ib);
    }
}
