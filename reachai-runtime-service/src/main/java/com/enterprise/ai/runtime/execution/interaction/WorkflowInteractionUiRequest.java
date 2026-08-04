package com.enterprise.ai.runtime.execution.interaction;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical uiRequest generated solely by Runtime for Workflow INTERACTION nodes.
 * Debug / Agent / Embed must consume this shape via normalizeUiRequest.
 */
public record WorkflowInteractionUiRequest(
        String protocolVersion,
        String interactionId,
        String type,
        String component,
        String workflowId,
        String workflowVersionId,
        String nodeId,
        String runId,
        String traceId,
        String title,
        String message,
        Integer ttlSeconds,
        String expiresAt,
        List<Map<String, Object>> fields,
        List<Map<String, Object>> options,
        Map<String, Object> prefilled,
        Object data,
        Map<String, Object> summary,
        Map<String, Object> schema,
        List<Map<String, Object>> actions,
        Map<String, Object> presentation,
        Map<String, Object> behavior,
        Map<String, Object> extension
) {
    public Map<String, Object> toMap() {
        Map<String, Object> body = new LinkedHashMap<>();
        put(body, "protocolVersion", protocolVersion);
        put(body, "schemaVersion", protocolVersion);
        put(body, "interactionId", interactionId);
        put(body, "type", type);
        put(body, "component", component);
        put(body, "workflowId", workflowId);
        put(body, "workflowVersionId", workflowVersionId);
        put(body, "nodeId", nodeId);
        put(body, "runId", runId);
        put(body, "traceId", traceId);
        put(body, "title", title);
        put(body, "message", message);
        if (ttlSeconds != null) {
            body.put("ttlSeconds", ttlSeconds);
        }
        put(body, "expiresAt", expiresAt);
        if (fields != null && !fields.isEmpty()) {
            body.put("fields", fields);
        }
        if (options != null && !options.isEmpty()) {
            body.put("options", options);
        }
        if (prefilled != null && !prefilled.isEmpty()) {
            body.put("prefilled", prefilled);
        }
        if (data != null) {
            body.put("data", data);
        }
        if (summary != null && !summary.isEmpty()) {
            body.put("summary", summary);
        }
        if (schema != null && !schema.isEmpty()) {
            body.put("schema", schema);
        }
        if (actions != null && !actions.isEmpty()) {
            body.put("actions", actions);
        }
        if (presentation != null && !presentation.isEmpty()) {
            body.put("presentation", presentation);
        }
        if (behavior != null && !behavior.isEmpty()) {
            body.put("behavior", behavior);
        }
        if (extension != null && !extension.isEmpty()) {
            body.put("extension", extension);
        }
        return body;
    }

    private static void put(Map<String, Object> body, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String text && text.isBlank()) {
            return;
        }
        body.put(key, value);
    }
}
