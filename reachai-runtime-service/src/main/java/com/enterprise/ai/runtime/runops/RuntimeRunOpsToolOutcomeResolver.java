package com.enterprise.ai.runtime.runops;

import com.enterprise.ai.runtime.trace.RuntimeTraceRecords;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Projects resumed Workflow outcomes without rewriting the original tool-call observation. */
final class RuntimeRunOpsToolOutcomeResolver {
    private static final Set<String> WAITING_CODES = Set.of(
            "RUNTIME_GRAPH_INTERACTION_WAITING", "WAITING_USER", "RUNTIME_INTERACTION_WAITING");
    private static final Set<String> TERMINAL = Set.of(
            "SUCCESS", "BUSINESS_TERMINAL", "FAILED", "ERROR", "CANCELLED", "TIMED_OUT", "TIMEOUT");
    private final ObjectMapper mapper;
    private final List<Evidence> evidence;

    RuntimeRunOpsToolOutcomeResolver(ObjectMapper mapper, List<RuntimeTraceRecords.Span> spans) {
        this.mapper = mapper;
        evidence = spans.stream().map(span -> new Evidence(span, read(span.metadataJson()))).toList();
    }

    Outcome resolve(RuntimeTraceRecords.ToolCall tool) {
        JsonNode observation = read(tool.retrievalTraceJson());
        if (!WAITING_CODES.contains(observation.path("code").asText())) {
            return new Outcome(Boolean.TRUE.equals(tool.success()) ? "SUCCESS" : "FAILED", null);
        }
        // An initial pause proves neither eventual failure nor successful completion.
        Outcome unresolved = new Outcome("UNKNOWN", null);
        String workflow = scalar(observation, "workflowId");
        String version = scalar(observation, "workflowVersionId");
        if (workflow == null || version == null) return unresolved;
        JsonNode nodes = observation.path("summary").path("workflowNodeTraces");
        if (!nodes.isArray()) return unresolved;
        JsonNode anchor = null;
        for (JsonNode node : nodes) {
            if (!"WAITING_USER".equals(node.path("status").asText())) continue;
            if (anchor != null) return unresolved;
            anchor = node;
        }
        if (anchor == null) return unresolved;
        String interaction = scalar(anchor, "interactionId"), nodeId = scalar(anchor, "nodeId");
        if (interaction == null || nodeId == null) return unresolved;
        var children = evidence.stream().filter(item ->
                "WORKFLOW_NODE".equals(item.span().spanType())
                && Objects.equals(tool.traceId(), item.span().traceId())
                && nodeId.equals(item.span().nodeId())
                && interaction.equals(scalar(item.metadata(), "interactionId"))
                && pinned(item.metadata(), workflow, version)).toList();
        if (children.size() != 1 || children.get(0).span().parentSpanId() == null) return unresolved;
        String parentId = children.get(0).span().parentSpanId();
        var parents = evidence.stream().filter(item ->
                "WORKFLOW_TOOL".equals(item.span().spanType())
                && Objects.equals(tool.traceId(), item.span().traceId())
                && parentId.equals(item.span().spanId())
                && Objects.equals(tool.toolName(), item.span().toolName())
                && workflow.equals(item.span().nodeId())
                && pinned(item.metadata(), workflow, version)).toList();
        if (parents.size() != 1) return unresolved;
        var parent = parents.get(0).span();
        String status = parent.status();
        if ("WAITING_USER".equals(status) && parent.endedAt() == null
                || TERMINAL.contains(status == null ? "" : status) && parent.endedAt() != null) {
            return new Outcome(status, parent.spanId());
        }
        return unresolved;
    }

    private boolean pinned(JsonNode metadata, String workflow, String version) {
        return workflow.equals(scalar(metadata, "workflowId")) && version.equals(scalar(metadata, "workflowVersionId"));
    }

    private JsonNode read(String json) {
        if (json != null) {
            try {
                JsonNode value = mapper.readTree(json);
                if (value != null && value.isObject()) return value;
            } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
                // Historical unstructured tool metadata is not evidence of a resumed outcome.
            }
        }
        return mapper.createObjectNode();
    }

    private static String scalar(JsonNode node, String key) {
        JsonNode value = node.path(key);
        if (!value.isTextual() && !value.isIntegralNumber()) return null;
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    record Outcome(String status, String sourceSpanId) { }
    private record Evidence(RuntimeTraceRecords.Span span, JsonNode metadata) { }
}
