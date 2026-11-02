package com.enterprise.ai.runtime.supervisor;

import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;
import java.util.List;
import java.util.Map;

/** Recognizes only a deterministic, read-only REQUIRED knowledge no-evidence path. */
final class SupervisorKnowledgeTerminalAnswer {
    private SupervisorKnowledgeTerminalAnswer() { }

    static String unavailableFrom(RuntimeGraphSpecExecutionResult result, boolean readOnly) {
        if (!readOnly || result == null || result.success() || result.uiRequest() != null
                || !("RUNTIME_KNOWLEDGE_FAILED".equals(result.code())
                || "RUNTIME_KNOWLEDGE_CLIENT_UNAVAILABLE".equals(result.code()))
                || !"KNOWLEDGE_RETRIEVAL".equals(result.nodeType()) || !StringUtils.hasText(result.nodeId())) return null;
        Object raw = result.metadata().get("workflowNodeTraces");
        if (!(raw instanceof List<?> traces) || traces.isEmpty() || traces.size() > 2) return null;
        if (traces.size() == 2 && !node(traces.get(0), "USER_INPUT")) return null;
        Object last = traces.get(traces.size() - 1);
        if (!(last instanceof Map<?, ?> trace) || !"KNOWLEDGE_RETRIEVAL".equals(trace.get("nodeType"))
                || !"FAILED".equals(trace.get("status")) || !result.nodeId().equals(trace.get("nodeId"))) return null;
        return "知识检索暂时不可用，请稍后重试。";
    }

    static String from(RuntimeGraphSpecExecutionResult result, boolean readOnly, String graphJson, ObjectMapper mapper) {
        if (!readOnly || result == null || !result.success() || result.uiRequest() != null
                || !"ANSWER".equals(result.nodeType()) || !StringUtils.hasText(result.answer())
                || result.answer().length() > 4000 || !StringUtils.hasText(graphJson) || mapper == null) return null;
        Object raw = result.metadata().get("workflowNodeTraces");
        if (!(raw instanceof List<?> traces) || traces.size() < 2 || traces.size() > 3) return null;
        int offset = traces.size() - 2;
        if (offset == 1 && !node(traces.get(0), "USER_INPUT")) return null;
        if (!node(traces.get(offset), "KNOWLEDGE_RETRIEVAL") || !node(traces.get(offset + 1), "ANSWER")) return null;
        Map<?, ?> knowledge = (Map<?, ?>) traces.get(offset);
        Map<?, ?> answer = (Map<?, ?>) traces.get(offset + 1);
        if (!(knowledge.get("nodeId") instanceof String knowledgeId)
                || !(answer.get("nodeId") instanceof String answerId)
                || !answerId.equals(result.nodeId()) || !answerId.equals(knowledge.get("nextNodeId"))) return null;
        Object rawOutput = result.resumeCheckpoint().get("nodeOutput." + knowledgeId);
        if (!(rawOutput instanceof Map<?, ?> output) || !"NO_EVIDENCE".equals(output.get("outcome"))
                || !Boolean.TRUE.equals(output.get("empty"))
                || !(output.get("hits") instanceof List<?> hits) || !hits.isEmpty()) return null;
        try {
            JsonNode nodes = mapper.readTree(graphJson).path("nodes");
            for (JsonNode node : nodes) {
                if (!knowledgeId.equals(node.path("id").asText())
                        || !"KNOWLEDGE_RETRIEVAL".equals(node.path("type").asText())) continue;
                JsonNode config = node.path("config");
                JsonNode policy = config.has("evidencePolicy") ? config.path("evidencePolicy")
                        : config.path("knowledgeConfig").path("evidencePolicy");
                return "REQUIRED".equalsIgnoreCase(policy.asText().trim()) ? result.answer() : null;
            }
        } catch (Exception ignored) {
            // An unreadable published snapshot cannot establish a direct-delivery contract.
        }
        return null;
    }

    private static boolean node(Object raw, String type) {
        return raw instanceof Map<?, ?> trace && type.equals(trace.get("nodeType"))
                && "SUCCESS".equals(trace.get("status"));
    }
}
