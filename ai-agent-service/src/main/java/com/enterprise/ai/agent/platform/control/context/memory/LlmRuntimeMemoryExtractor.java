package com.enterprise.ai.agent.platform.control.context.memory;

import com.enterprise.ai.agent.llm.LlmService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class LlmRuntimeMemoryExtractor implements RuntimeMemoryExtractor {

    private static final int MAX_CANDIDATES = 3;
    private static final BigDecimal MIN_CONFIDENCE = new BigDecimal("0.6000");

    private static final String SYSTEM_PROMPT = """
            You are ReachAI Context Governance Kernel's runtime memory extraction component.
            Extract only durable RUNTIME_USER memory candidates from the user's message and assistant reply.
            Return strict JSON only: {"candidates":[{"candidateType":"PREFERENCE|FACT|RULE|PAGE_CONTEXT|WORKFLOW_CONTEXT|API_CONTEXT|NOTE","title":"...","content":"...","summary":"...","reason":"...","confidence":0.0}]}
            Do not extract temporary tasks, one-off questions, secrets, credentials, tokens, passwords, or PROJECT_DEV knowledge.
            Do not write memory directly. You only propose candidates for later user approval.
            If nothing should be remembered, return {"candidates":[]}.
            """;

    private final LlmService llmService;
    private final ObjectMapper objectMapper;

    @Override
    public List<RuntimeMemoryExtraction> extract(RuntimeMemoryExtractionRequest request) {
        if (request == null || !StringUtils.hasText(request.getModelInstanceId())
                || !StringUtils.hasText(request.getUserMessage())) {
            return List.of();
        }
        String raw = llmService.chat(SYSTEM_PROMPT, buildUserPrompt(request), request.getModelInstanceId().trim());
        return parseCandidates(raw);
    }

    private String buildUserPrompt(RuntimeMemoryExtractionRequest request) {
        StringBuilder builder = new StringBuilder();
        appendLine(builder, "tenantId", request.getTenantId());
        appendLine(builder, "projectCode", request.getProjectCode());
        appendLine(builder, "userId", request.getUserId());
        appendLine(builder, "sessionId", request.getSessionId());
        appendLine(builder, "agentId", request.getAgentId());
        appendLine(builder, "workflowId", request.getWorkflowId());
        appendLine(builder, "pageInstanceId", request.getPageInstanceId());
        appendLine(builder, "origin", request.getOrigin());
        appendLine(builder, "traceId", request.getTraceId());
        builder.append("\n[User Message]\n").append(request.getUserMessage().trim()).append("\n");
        if (StringUtils.hasText(request.getAssistantReply())) {
            builder.append("\n[Assistant Reply]\n").append(request.getAssistantReply().trim()).append("\n");
        }
        return builder.toString();
    }

    private void appendLine(StringBuilder builder, String key, Object value) {
        if (value != null && StringUtils.hasText(String.valueOf(value))) {
            builder.append(key).append(": ").append(String.valueOf(value).trim()).append("\n");
        }
    }

    private List<RuntimeMemoryExtraction> parseCandidates(String raw) {
        if (!StringUtils.hasText(raw)) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(extractJsonObject(raw));
            JsonNode candidatesNode = root.path("candidates");
            if (!candidatesNode.isArray()) {
                return List.of();
            }
            List<RuntimeMemoryExtraction> candidates = new ArrayList<>();
            Set<String> seenContent = new HashSet<>();
            for (JsonNode node : candidatesNode) {
                if (candidates.size() >= MAX_CANDIDATES) {
                    break;
                }
                RuntimeMemoryExtraction extraction = toExtraction(node);
                if (extraction == null) {
                    continue;
                }
                String dedupeKey = extraction.getContent().trim().toLowerCase(Locale.ROOT);
                if (!seenContent.add(dedupeKey)) {
                    continue;
                }
                candidates.add(extraction);
            }
            return candidates;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid LLM memory extraction JSON", ex);
        }
    }

    private RuntimeMemoryExtraction toExtraction(JsonNode node) {
        String content = text(node, "content");
        if (!StringUtils.hasText(content)) {
            return null;
        }
        BigDecimal confidence = confidence(node.path("confidence"));
        if (confidence.compareTo(MIN_CONFIDENCE) < 0) {
            return null;
        }
        return RuntimeMemoryExtraction.builder()
                .candidateType(parseType(text(node, "candidateType")))
                .title(text(node, "title"))
                .content(content.trim())
                .summary(text(node, "summary"))
                .reason(text(node, "reason"))
                .confidence(confidence)
                .build();
    }

    private ContextMemoryCandidateType parseType(String value) {
        if (!StringUtils.hasText(value)) {
            return ContextMemoryCandidateType.NOTE;
        }
        try {
            return ContextMemoryCandidateType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return ContextMemoryCandidateType.NOTE;
        }
    }

    private BigDecimal confidence(JsonNode node) {
        double value = node == null || node.isMissingNode() || !node.isNumber()
                ? 0.7
                : node.asDouble();
        value = Math.max(0.0, Math.min(1.0, value));
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }

    private String text(JsonNode node, String fieldName) {
        JsonNode value = node == null ? null : node.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText(null);
        return StringUtils.hasText(text) ? text.trim() : null;
    }

    private String extractJsonObject(String raw) {
        String text = raw.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                text = text.substring(firstNewline + 1, lastFence).trim();
            }
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("missing JSON object");
        }
        return text.substring(start, end + 1);
    }
}
