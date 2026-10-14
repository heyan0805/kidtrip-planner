package com.hellen.kidtrip.health;

import com.hellen.kidtrip.common.ApiResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一个什么都不干的接口，唯一职责是回答"服务活着吗"。
 *
 * 为什么第一步值当做这个而不是直接写业务：
 *   后面每一步（子进程、缓存、重查循环）出问题时，你需要一个**确定能通**的接口来区分
 *   "是这次新加的东西坏了"还是"服务从一开始就没起来"。
 *   这就是前端的"先让它跑起来"在后端的对应物。
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    @GetMapping("/health")
    public ApiResult<Map<String, Object>> health() {
        // LinkedHashMap 而不是 Map.of()：Map.of 的迭代顺序是不保证的，
        // 用 LinkedHashMap 让返回的 JSON 字段顺序稳定 —— 人看的时候舒服，测试断言也好写。
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "up");
        body.put("java", System.getProperty("java.version"));
        body.put("time", OffsetDateTime.now().toString());
        return ApiResult.ok(body);
    }
}
