package com.enterprise.ai.runtime.execution.trace;

import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionEventSink;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import com.enterprise.ai.runtime.execution.event.RuntimeExecutionEvent;
import com.enterprise.ai.runtime.execution.event.RuntimeExecutionEventType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministically projects RuntimeExecutionEvent V1 into the canonical Workflow trace shape.
 * The projector stores only the event contract's allowlisted attributes.
 */
public final class RuntimeExecutionTraceProjector implements RuntimeGraphSpecExecutionEventSink {

    public static final int PROJECTION_SCHEMA_VERSION = 1;

    private final RuntimeGraphSpecExecutionEventSink delegate;
    private final List<ProjectedNode> nodes = new ArrayList<>();
    private final Map<String, ProjectedNode> activeNodes = new LinkedHashMap<>();
    private long lastSequence;
    private long eventCount;
    private Instant runStartedAt;
    private Instant runEndedAt;
    private String runStatus;
    private String runCode;

    public RuntimeExecutionTraceProjector(RuntimeGraphSpecExecutionEventSink delegate) {
        this.delegate = delegate == null ? RuntimeGraphSpecExecutionEventSink.NOOP : delegate;
    }

    @Override
    public synchronized void onExecutionEvent(RuntimeExecutionEvent event) {
        if (event == null) {
            return;
        }
        if (event.sequence() <= lastSequence) {
            throw new IllegalStateException("Runtime execution event sequence is not strictly increasing");
        }
        lastSequence = event.sequence();
        eventCount++;
        project(event);
        delegate.onExecutionEvent(event);
    }

    @Override
    public void onNodeStarted(String nodeId, String nodeType, String nodeName, Map<String, Object> safePayload) {
        delegate.onNodeStarted(nodeId, nodeType, nodeName, safePayload);
    }

    @Override
    public void onNodeDelta(String nodeId, String nodeType, String text, Map<String, Object> safePayload) {
        delegate.onNodeDelta(nodeId, nodeType, text, safePayload);
    }

    @Override
    public void onNodeCompleted(String nodeId, String nodeType, String nodeName, Map<String, Object> safePayload) {
        delegate.onNodeCompleted(nodeId, nodeType, nodeName, safePayload);
    }

    @Override
    public void onNodeWaiting(String nodeId, String nodeType, String nodeName, Map<String, Object> safePayload) {
        delegate.onNodeWaiting(nodeId, nodeType, nodeName, safePayload);
    }

    @Override
    public void onNodeFailed(String nodeId, String nodeType, String nodeName, Map<String, Object> safePayload) {
        delegate.onNodeFailed(nodeId, nodeType, nodeName, safePayload);
    }

    @Override
    public void onExecutionCancelled(Map<String, Object> safePayload) {
        delegate.onExecutionCancelled(safePayload);
    }

    public synchronized RuntimeGraphSpecExecutionResult project(RuntimeGraphSpecExecutionResult result) {
        if (result == null) {
            return null;
        }
        Map<String, Object> metadata = result.metadata() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(result.metadata());
        List<Map<String, Object>> nodeTraces = nodes.stream()
                .map(ProjectedNode::toTraceMap)
                .toList();
        metadata.put("workflowNodeTraces", nodeTraces);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("schemaVersion", PROJECTION_SCHEMA_VERSION);
        summary.put("eventSchemaVersion", RuntimeExecutionEvent.SCHEMA_VERSION);
        summary.put("eventCount", eventCount);
        summary.put("nodeCount", nodeTraces.size());
        summary.put("status", firstText(runStatus, result.executionStatus().name()));
        if (runCode != null) summary.put("code", runCode);
        if (runStartedAt != null) summary.put("startedAt", runStartedAt.toEpochMilli());
        if (runEndedAt != null) summary.put("endedAt", runEndedAt.toEpochMilli());
        metadata.put("runtimeTraceProjection", Map.copyOf(summary));
        return new RuntimeGraphSpecExecutionResult(
                result.success(), result.code(), result.answer(), result.nodeId(), result.nodeType(),
                result.steps(), metadata, result.resumeCheckpoint());
    }

    private void project(RuntimeExecutionEvent event) {
        switch (event.eventType()) {
            case RUN_STARTED -> {
                runStartedAt = event.occurredAt();
                runStatus = "RUNNING";
            }
            case RUN_SUSPENDED, RUN_COMPLETED, RUN_FAILED, RUN_CANCELLED -> {
                runEndedAt = event.occurredAt();
                runStatus = normalizeStatus(event.status(), event.eventType());
                runCode = event.code();
            }
            case NODE_STARTED -> startNode(event);
            case NODE_COMPLETED, NODE_SUSPENDED, NODE_FAILED -> finishNode(event);
            case NODE_DELTA -> {
                // Deltas contribute eventCount only. Their text is intentionally absent from V1.
            }
        }
    }

    private void startNode(RuntimeExecutionEvent event) {
        ProjectedNode node = new ProjectedNode(
                event.nodeId(), event.nodeType(), event.nodeName(), event.occurredAt());
        nodes.add(node);
        if (event.nodeId() != null) {
            activeNodes.put(event.nodeId(), node);
        }
    }

    private void finishNode(RuntimeExecutionEvent event) {
        ProjectedNode node = event.nodeId() == null ? null : activeNodes.remove(event.nodeId());
        if (node == null) {
            node = new ProjectedNode(event.nodeId(), event.nodeType(), event.nodeName(), event.occurredAt());
            nodes.add(node);
        }
        node.finish(event);
    }

    private static String normalizeStatus(String raw, RuntimeExecutionEventType eventType) {
        String value = firstText(raw);
        if (eventType == RuntimeExecutionEventType.NODE_SUSPENDED
                || eventType == RuntimeExecutionEventType.RUN_SUSPENDED) {
            return "WAITING_USER";
        }
        if (eventType == RuntimeExecutionEventType.RUN_COMPLETED && "COMPLETED".equalsIgnoreCase(value)) {
            return "SUCCESS";
        }
        return value == null ? eventType.name() : value.toUpperCase(java.util.Locale.ROOT);
    }

    private static String firstText(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return null;
    }

    private static final class ProjectedNode {
        private final String nodeId;
        private final String nodeType;
        private final String nodeName;
        private final Instant startedAt;
        private Instant endedAt;
        private String status = "RUNNING";
        private String failureCode;
        private Long latencyMs;
        private Integer attempt;
        private Integer maxAttempts;
        private String errorPolicy;
        private String fallbackNodeId;
        private String nextNodeId;
        private String outcomeClass;
        private String businessOutcome;
        private String qualifiedName;
        private String failureCategory;
        private Boolean retryableFailure;
        private String sideEffect;
        private String dispatchStage;
        private Boolean nonRecoverableWrite;
        private Integer httpStatus;
        private String inputField;
        private String interactionId;
        private String interactionType;

        private ProjectedNode(String nodeId, String nodeType, String nodeName, Instant startedAt) {
            this.nodeId = nodeId;
            this.nodeType = nodeType;
            this.nodeName = nodeName;
            this.startedAt = startedAt == null ? Instant.now() : startedAt;
        }

        private void finish(RuntimeExecutionEvent event) {
            status = normalizeStatus(event.status(), event.eventType());
            if (!"WAITING_USER".equals(status)) {
                endedAt = event.occurredAt();
            }
            failureCode = event.eventType() == RuntimeExecutionEventType.NODE_FAILED ? event.code() : null;
            latencyMs = event.durationMs();
            Map<String, Object> attributes = event.safeAttributes();
            attempt = integer(attributes.get("attempt"));
            maxAttempts = integer(attributes.get("maxAttempts"));
            errorPolicy = text(attributes.get("errorPolicyDecision"));
            fallbackNodeId = text(attributes.get("fallbackNodeId"));
            nextNodeId = text(attributes.get("nextNodeId"));
            outcomeClass = text(attributes.get("outcomeClass"));
            businessOutcome = text(attributes.get("businessOutcome"));
            qualifiedName = text(attributes.get("qualifiedName"));
            failureCategory = text(attributes.get("failureCategory"));
            retryableFailure = bool(attributes.get("retryableFailure"));
            sideEffect = text(attributes.get("sideEffect"));
            dispatchStage = text(attributes.get("dispatchStage"));
            nonRecoverableWrite = bool(attributes.get("nonRecoverableWrite"));
            httpStatus = integer(attributes.get("httpStatus"));
            inputField = text(attributes.get("inputField"));
            interactionId = text(attributes.get("interactionId"));
            interactionType = text(attributes.get("interactionType"));
        }

        private Map<String, Object> toTraceMap() {
            Map<String, Object> trace = new LinkedHashMap<>();
            if (nodeId != null) trace.put("nodeId", nodeId);
            if (nodeType != null) trace.put("nodeType", nodeType);
            if (nodeName != null) trace.put("nodeName", nodeName);
            trace.put("status", status);
            trace.put("startedAt", startedAt.toEpochMilli());
            if (endedAt != null) trace.put("endedAt", endedAt.toEpochMilli());
            trace.put("latencyMs", latencyMs == null
                    ? Math.max(0L, (endedAt == null ? startedAt : endedAt).toEpochMilli() - startedAt.toEpochMilli())
                    : Math.max(0L, latencyMs));
            if (attempt != null) trace.put("attempt", attempt);
            if (maxAttempts != null) trace.put("maxAttempts", maxAttempts);
            if (errorPolicy != null) trace.put("errorPolicy", errorPolicy);
            if (fallbackNodeId != null) trace.put("fallbackNodeId", fallbackNodeId);
            if (nextNodeId != null) trace.put("nextNodeId", nextNodeId);
            if (outcomeClass != null) trace.put("outcomeClass", outcomeClass);
            if (businessOutcome != null) trace.put("businessOutcome", businessOutcome);
            if (qualifiedName != null) trace.put("qualifiedName", qualifiedName);
            if (failureCategory != null) trace.put("failureCategory", failureCategory);
            if (retryableFailure != null) trace.put("retryableFailure", retryableFailure);
            if (sideEffect != null) trace.put("sideEffect", sideEffect);
            if (dispatchStage != null) trace.put("dispatchStage", dispatchStage);
            if (nonRecoverableWrite != null) trace.put("nonRecoverableWrite", nonRecoverableWrite);
            if (httpStatus != null) trace.put("httpStatus", httpStatus);
            if (inputField != null) trace.put("inputField", inputField);
            if (interactionId != null) trace.put("interactionId", interactionId);
            if (interactionType != null) trace.put("interactionType", interactionType);
            if (failureCode != null) trace.put("failureCode", failureCode);
            return Map.copyOf(trace);
        }

        private static Integer integer(Object value) {
            if (value instanceof Number number) return number.intValue();
            try {
                String parsed = text(value);
                return parsed == null ? null : Integer.parseInt(parsed);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        private static Boolean bool(Object value) {
            if (value instanceof Boolean bool) return bool;
            if (value == null) return null;
            if ("true".equalsIgnoreCase(String.valueOf(value))) return true;
            if ("false".equalsIgnoreCase(String.valueOf(value))) return false;
            return null;
        }

        private static String text(Object value) {
            if (value == null) return null;
            String parsed = String.valueOf(value).trim();
            return parsed.isEmpty() ? null : parsed;
        }
    }
}
