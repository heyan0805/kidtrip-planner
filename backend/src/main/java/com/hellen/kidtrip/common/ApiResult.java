package com.hellen.kidtrip.common;

/**
 * 全站统一响应信封。
 *
 * 约定（与参照工程一致，前端 src/api/http.ts 已按它写好了）：
 *   HTTP 状态码**恒为 200**；业务成败看 code —— 0 = 成功，非 0 = 失败，
 *   且失败时 message 会被前端**直接显示给用户**，所以 message 必须是给人看的话，
 *   不能写 "NullPointerException at line 88"。
 *
 * 诚实地说一下这个约定的代价（你也应该知道自己在用什么）：
 *   ① 监控/网关看状态码永远 200，真实错误率得从响应体里挖；
 *   ② 浏览器 fetch 的 res.ok 永远 true，错误只能在业务层判。
 *   我们保留它，是因为前端 http.ts 已经围绕它写好了（成功拿 data、非 0 抛 ApiError）。
 *   如果哪天要改，改的是"三处一起"：这里的约定、http.ts、以及所有 Controller 的返回类型。
 *
 * 为什么用 record 不用 Lombok：
 *   record 是语言自带的（Java 16+），自动生成构造器、访问器、equals/hashCode/toString，
 *   编译期就可见，不需要注解处理器 —— 少一层"代码是被谁生成的"的困惑。
 *   类的形状是"不可变的数据"时，record 永远是对的默认选择。
 */
public record ApiResult<T>(int code, String message, T data) {

    private static final int OK = 0;

    public static <T> ApiResult<T> ok(T data) {
        return new ApiResult<>(OK, "ok", data);
    }

    public static <T> ApiResult<T> fail(int code, String message) {
        return new ApiResult<>(code, message, null);
    }
}
