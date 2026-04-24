package com.enterprise.ai.agent.mining;

import com.enterprise.ai.agent.skill.SubAgentSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 预留 LLM 反写入口。当前先用模板策略生成草稿，避免 2.1 冷启动期完全阻塞。
 */
@Component
public class SkillDraftLlmWriter {
    private final ObjectMapper objectMapper;

    public SkillDraftLlmWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public DraftContent write(PrefixSpanMiner.ChainPattern pattern) {
        List<String> sequence = pattern.sequence();
        String name = buildName(sequence);
        String desc = "自动挖掘链路：" + String.join(" -> ", sequence);
        SubAgentSpec spec = new SubAgentSpec(
                "你是一个子 Agent，请按给定工具链完成任务并返回结构化结果。",
                sequence,
                null,
                null,
                8,
                false
        );
        try {
            String specJson = objectMapper.writeValueAsString(spec);
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("support", pattern.support());
            extra.put("sequence", sequence);
            return new DraftContent(name, desc, specJson, extra);
        } catch (Exception ex) {
            throw new IllegalStateException("生成 SkillDraft 失败", ex);
        }
    }

    /**
     * 工具名可能含中文等非 ASCII 字符；直接 replaceAll 后得到一串下划线。
     * 这里保留 ASCII 片段，ASCII 为空时用 sequence hash 兜底，避免同质化命名冲突。
     */
    private static String buildName(List<String> sequence) {
        StringBuilder sb = new StringBuilder("skill");
        for (String t : sequence) {
            String sanitized = t == null
                    ? ""
                    : t.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
            if (sanitized.isBlank()) {
                continue;
            }
            sb.append('_').append(sanitized);
        }
        String base = sb.toString();
        // 加短 hash 既保证幂等（同序列 → 同名），又避免中文工具名退化到 "skill"。
        int h = Math.floorMod(String.join("|", sequence).hashCode(), 1_000_000);
        return base + "_" + String.format("%06d", h);
    }

    public record DraftContent(String name, String description, String specJson, Map<String, Object> extra) {}
}
