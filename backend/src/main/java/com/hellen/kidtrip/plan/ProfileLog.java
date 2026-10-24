package com.hellen.kidtrip.plan;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/**
 * 画像留痕 —— 每一轮 chat 和每一次 generate 都打一行结构化 JSON（前缀 {@code PROFILE_LOG}），
 * 供运营侧统计分析：哪个城市最热、几天行程最多、几个孩子、从哪出发……
 *
 * ── 为什么是"日志里的一行 JSON"而不是一张数据库表 ───────────────
 *   没有用户体系（README 的边界声明），也不想在主链路里引入存储依赖。
 *   journald 已经在收集、轮转、可 grep —— 统计的全部成本就是一行命令：
 *   {@code journalctl -u kidtrip-backend --since today | grep PROFILE_LOG}
 *
 * ── 为什么不记用户消息原文 ──────────────────────────────────────
 *   统计只需要画像字段。消息原文在 DEBUG 日志里本来就有，
 *   INFO 级别只留结构化字段，这条日志才可以放心长期开着不担心膨胀。
 *
 * ── entry 字段 ─────────────────────────────────────────────────
 *   沿用 DailyGenerateGuard 的入口语义：nginx 的 /mama 口令入口 = "mama"，
 *   直接打开主站的评委/散客 = "main"。看不同入口的人群差别就靠它。
 */
@Component
public class ProfileLog {

    private static final Logger log = LoggerFactory.getLogger(ProfileLog.class);

    private final ObjectMapper mapper;

    public ProfileLog(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 打一条画像留痕。
     *
     * @param event        "chat"（params 是这轮合并后的累计画像）或 "generate"（用户按下生成那一刻的画像）
     * @param entryHeader  nginx 打的 X-Entry 头；null/空 = 主入口
     * @param messageCount 仅 chat 传：这是对话的第几轮消息（分析"聊了几轮才生成"用），generate 传 null
     */
    public void record(String event, String entryHeader, TravelParams params, Persona persona,
                       Integer messageCount) {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("event", event);
            m.put("entry", entryHeader == null || entryHeader.isBlank() ? "main" : entryHeader);
            if (messageCount != null) {
                m.put("messages", messageCount);
            }
            if (params != null) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("origin", params.origin());
                p.put("city", params.city());
                p.put("days", params.days());
                p.put("startDate", params.startDate());
                p.put("preferences", params.preferences());
                p.put("pace", params.pace());
                p.put("childAges", params.childAges());
                p.put("stroller", params.stroller());
                m.put("params", p);
            }
            if (persona != null && !persona.isEmpty()) {
                // 只留 key=value（evidence 是大段用户原话，对统计没用还占行宽）
                m.put("persona", persona.inferences().stream()
                        .map(i -> i.key() + "=" + i.value()).toList());
            }
            log.info("PROFILE_LOG {}", mapper.writeValueAsString(m));
        } catch (RuntimeException e) {
            // 留痕绝不能打断主链路：这一条序列化失败就放弃，业务照常
            log.warn("PROFILE_LOG serialize failed: {}", e.toString());
        }
    }
}
