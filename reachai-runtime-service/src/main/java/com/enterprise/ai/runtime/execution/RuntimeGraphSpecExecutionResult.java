package com.enterprise.ai.runtime.execution;

import com.enterprise.ai.runtime.execution.interaction.WorkflowInteractionCodes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GraphSpec execution outcome. {@link #contextSnapshot()} is an internal resume state and must not be
 * copied into public API / SSE / uiRequest payloads.
 */
public record RuntimeGraphSpecExecutionResult(boolean success,
                                              String code,
                                              String answer,
                                              String nodeId,
                                              String nodeType,
                                              List<Map<String, Object>> steps,
                                              Map<String, Object> metadata,
                                              Map<String, Object> contextSnapshot) {

    public RuntimeGraphSpecExecutionResult {
        steps = steps == null ? List.of() : List.copyOf(steps);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        contextSnapshot = contextSnapshot == null ? Map.of() : copyContext(contextSnapshot);
    }

    /** Node-level / failure constructors without a graph context snapshot. */
    public RuntimeGraphSpecExecutionResult(boolean success,
                                           String code,
                                           String answer,
                                           String nodeId,
                                           String nodeType,
                                           List<Map<String, Object>> steps,
                                           Map<String, Object> metadata) {
        this(success, code, answer, nodeId, nodeType, steps, metadata, Map.of());
    }

    public boolean isWaitingUser() {
        return WorkflowInteractionCodes.WAITING.equals(code);
    }

    /**
     * Canonical execution status: COMPLETED / SUSPENDED / FAILED / CANCELLED.
     */
    public String executionStatus() {
        if (success) {
            return "COMPLETED";
        }
        if (isWaitingUser()) {
            return "SUSPENDED";
        }
        if ("RUNTIME_GRAPH_CANCELLED".equals(code)) {
            return "CANCELLED";
        }
        return "FAILED";
    }

    public Object uiRequest() {
        return metadata == null ? null : metadata.get("uiRequest");
    }

    public String interactionId() {
        if (metadata == null) {
            return null;
        }
        Object value = metadata.get("interactionId");
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    public RuntimeGraphSpecExecutionResult withContextSnapshot(Map<String, Object> context) {
        return new RuntimeGraphSpecExecutionResult(
                success, code, answer, nodeId, nodeType, steps, metadata, context);
    }

    public RuntimeGraphSpecExecutionResult withSteps(List<Map<String, Object>> nextSteps) {
        return new RuntimeGraphSpecExecutionResult(
                success, code, answer, nodeId, nodeType, nextSteps, metadata, contextSnapshot);
    }

    public RuntimeGraphSpecExecutionResult withMetadata(Map<String, Object> nextMetadata) {
        return new RuntimeGraphSpecExecutionResult(
                success, code, answer, nodeId, nodeType, steps, nextMetadata, contextSnapshot);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> copyContext(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                copy.put(entry.getKey(), copyContext((Map<String, Object>) nested));
            } else if (value instanceof List<?> list) {
                // List.copyOf rejects null elements; workflow context (LOOP results etc.) may contain nulls.
                copy.put(entry.getKey(), new ArrayList<>(list));
            } else {
                copy.put(entry.getKey(), value);
            }
        }
        return Collections.unmodifiableMap(copy);
    }
}
