package com.enterprise.ai.agent.mining;

import com.enterprise.ai.agent.llm.LlmService;
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
    private final LlmService llmService;

    public SkillDraftLlmWriter(ObjectMapper objectMapper, LlmService llmService) {
        this.objectMapper = objectMapper;
        this.llmService = llmService;
    }

    public DraftContent write(PrefixSpanMiner.ChainPattern pattern) {
        List<String> sequence = pattern.sequence();
        String name = buildName(sequence);
        LlmDraft llmDraft = writeWithLlm(sequence);
        String desc = firstNonBlank(llmDraft.description(), "自动挖掘链路：" + String.join(" -> ", sequence));
        String systemPrompt = firstNonBlank(
                llmDraft.systemPrompt(),
                "你是一个子 Agent，请按给定工具链完成任务并返回结构化结果。");
        SubAgentSpec spec = new SubAgentSpec(
                systemPrompt,
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
            extra.put("draftWriter", llmDraft.usedLlm() ? "llm" : "template");
            return new DraftContent(name, desc, specJson, extra);
        } catch (Exception ex) {
            throw new IllegalStateException("生成 SkillDraft 失败", ex);
        }
    }

    private LlmDraft writeWithLlm(List<String> sequence) {
        String system = """
                你是企业 AI Agent 的 Skill 草稿撰写助手。只输出 JSON 对象，不要 markdown。
                你只能为已有工具链生成可读描述和子 Agent systemPrompt，不能新增、删除或改名工具。
                JSON 字段：description, systemPrompt。
                description 用一句中文说明该 Skill 的业务价值。
                systemPrompt 要约束子 Agent 严格按给定工具链处理，失败时返回原因，不要编造工具结果。
                """;
        String user = "工具链：\n" + String.join(" -> ", sequence);
        try {
            String raw = llmService.chat(system, user);
            Map<?, ?> obj = parseJsonObject(raw);
            String description = obj.get("description") == null ? null : String.valueOf(obj.get("description"));
            String systemPrompt = obj.get("systemPrompt") == null ? null : String.valueOf(obj.get("systemPrompt"));
            if (isBlank(description) && isBlank(systemPrompt)) {
                return LlmDraft.template();
            }
            return new LlmDraft(description, systemPrompt, true);
        } catch (Exception ignored) {
            return LlmDraft.template();
        }
    }

    private Map<?, ?> parseJsonObject(String raw) throws Exception {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        String s = raw.trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl > 0) {
                s = s.substring(nl + 1);
            }
            int end = s.lastIndexOf("```");
            if (end > 0) {
                s = s.substring(0, end).trim();
            }
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return Map.of();
        }
        return objectMapper.readValue(s.substring(start, end + 1), Map.class);
    }

    private static String firstNonBlank(String candidate, String fallback) {
        return isBlank(candidate) ? fallback : candidate.trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
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

    private record LlmDraft(String description, String systemPrompt, boolean usedLlm) {
        static LlmDraft template() {
            return new LlmDraft(null, null, false);
        }
    }
}
