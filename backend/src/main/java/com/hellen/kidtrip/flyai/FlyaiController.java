package com.hellen.kidtrip.flyai;

import com.hellen.kidtrip.common.ApiResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// ⚠️ 注意这里的包名不是 com.fasterxml.jackson —— Spring Boot 4 升到了 Jackson 3，
//    根包从 com.fasterxml.jackson.* 改成了 tools.jackson.*。
//    （网上 90% 的例子还是 Boot 3 时代的 com.fasterxml.jackson，照抄会编译不过。）
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 飞猪探针接口 —— 唯一职责是"把飞猪的原始返回原样透出来给你看"。
 *
 * 为什么 B2 阶段要专门做这样一个接口，而不是直接写业务：
 *   这一层的验收标准不是"功能对不对"，而是"**我看见的到底是不是真的**"。
 *   有了它，你在浏览器/curl 里看到的每个景点、每个价格，都带一条路径能追回飞猪，
 *   而不是我说"能拿到"你就得信。
 *   它是第 3 步在前端的"先让它跑起来"在后端的同一件事：先确认**管道是通的、数据是真的**。
 *
 * 它**不做**任何事：不规范字段、不校验、不筛选、不缓存。那些是 B3 的活。
 * 这条边界要守住 —— 探针一旦开始"顺手清洗数据"，你就再也分不清"飞猪给的就是这样"还是"我们改过了"。
 */
@RestController
@RequestMapping("/api/flyai")
public class FlyaiController {

    private final FlyaiRunner runner;
    private final ObjectMapper objectMapper;

    // 构造器注入，而不是字段上写 @Autowired：
    // ① 能声明成 final，编译期就保证"这个对象不可能没被赋值"；
    // ② 没有 Spring 也能 new 出来测（单元测试直接 new FlyaiController(fakeRunner, new ObjectMapper())）。
    public FlyaiController(FlyaiRunner runner, ObjectMapper objectMapper) {
        this.runner = runner;
        this.objectMapper = objectMapper;
    }

    /**
     * 探针的返回形状：命令 + 飞猪原始结构。
     *
     * 为什么要回显 command：调试时最让人误判的就是"我以为我查的是 A"。
     * 把真实命令摊开，"参数中文有没有被切碎""分类到底传了什么"这类问题一眼可判。
     */
    public record ProbeResult(List<String> command, JsonNode result) {
    }

    @GetMapping("/probe/poi")
    public ApiResult<ProbeResult> probePoi(
            @RequestParam String city,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String keyword) {

        List<String> args = new ArrayList<>(List.of("search-poi", "--city-name", city));
        addIfPresent(args, "--category", category);
        addIfPresent(args, "--keyword", keyword);
        return probe(args);
    }

    @GetMapping("/probe/hotel")
    public ApiResult<ProbeResult> probeHotel(
            @RequestParam String city,
            @RequestParam(required = false) String poiName,
            @RequestParam(required = false) String checkIn,
            @RequestParam(required = false) String checkOut) {

        List<String> args = new ArrayList<>(List.of("search-hotel", "--dest-name", city));
        addIfPresent(args, "--poi-name", poiName);
        addIfPresent(args, "--check-in-date", checkIn);
        addIfPresent(args, "--check-out-date", checkOut);
        return probe(args);
    }

    private ApiResult<ProbeResult> probe(List<String> args) {
        String raw = runner.run(args);
        return ApiResult.ok(new ProbeResult(runner.describe(args), parse(raw)));
    }

    /**
     * 把 CLI 吐的字符串解析成 JSON 树。
     *
     * 解析只是"把字节变成结构"，一个字段都没动 —— 所以 data.data 那个看着别扭的嵌套不是 bug，
     * 那是飞猪自己的信封（{itemList, message, status}）原样躺在我们的 data 里。
     * B3 规范化之后，外面这层嵌套就会被抹平。
     *
     * 解析失败时把原文前 300 字带上：CLI 在异常情况下确实可能吐出非 JSON 的东西，
     * 这时候"原文"才是最有用的信息。
     *
     * 顺带一个 Jackson 3 的变化：JacksonException 继承的是 RuntimeException，
     * 也就是说它是**非受检**异常 —— 不 catch 也能编译通过。
     * 这里仍然显式 catch，是因为我们要借这个时机做一件明确的事（把原文附上），而不是为了通过编译。
     */
    private JsonNode parse(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (JacksonException e) {
            throw new FlyaiException(FlyaiException.Reason.BAD_JSON, "飞猪返回的不是合法 JSON："
                    + raw.substring(0, Math.min(300, raw.length())));
        }
    }

    /** 可选参数为空时**不加这个 flag**，而不是加一个空值 —— CLI 收到空字符串会当成一个真实的筛选条件。 */
    private static void addIfPresent(List<String> args, String flag, String value) {
        if (value != null && !value.isBlank()) {
            args.add(flag);
            args.add(value);
        }
    }
}
