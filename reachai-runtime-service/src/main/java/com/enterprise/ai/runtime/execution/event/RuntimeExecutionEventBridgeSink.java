package com.enterprise.ai.runtime.execution.event;

import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionEventSink;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import com.enterprise.ai.runtime.execution.interaction.WorkflowInteractionCodes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Emits RuntimeExecutionEvent V1 while preserving the existing node callback contract.
 */
public final class RuntimeExecutionEventBridgeSink implements RuntimeGraphSpecExecutionEventSink {

    private static final Set<String> SAFE_ATTRIBUTE_KEYS = Set.of(
            "currentNodeId",
            "nodeId",
            "nodeType",
            "nodeName",
            "qualifiedName",
            "elapsedMs",
            "status",
            "code",
            "nextNodeId",
            "attempt",
            "attemptCount",
            "maxAttempts",
            "errorPolicyDecision",
            "fallbackNodeId",
            "outcomeClass",
            "businessOutcome",
            "failureCategory",
            "retryableFailure",
            "sideEffect", "dispatchStage", "nonRecoverableWrite", "httpStatus", "inputField",
            "interactionId",
            "interactionType",
            "deltaChars");

    private final RuntimeGraphSpecExecutionEventSink delegate;
    private final AtomicLong sequence = new AtomicLong();
    private final String executionEventPrefix = UUID.randomUUID().toString();
    private volatile Map<String, Object> executionContext;

    public RuntimeExecutionEventBridgeSink(RuntimeGraphSpecExecutionEventSink delegate,
                                           Map<String, Object> initialContext) {
        this.delegate = delegate == null ? RuntimeGraphSpecExecutionEventSink.NOOP : delegate;
        this.executionContext = initialContext == null ? Map.of() : initialContext;
    }

    public void bindExecutionContext(Map<String, Object> context) {
        this.executionContext = context == null ? Map.of() : context;
    }

    public void onRunStarted() {
        emit(RuntimeExecutionEventType.RUN_STARTED, null, null, null,
                "RUNNING", null, Map.of());
    }

    public void onRunTerminal(RuntimeGraphSpecExecutionResult result) {
        if (result == null) {
            emit(RuntimeExecutionEventType.RUN_FAILED, null, null, null,
                    "FAILED", "RUNTIME_GRAPH_RESULT_MISSING", Map.of());
            return;
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (result.nodeId() != null) {
            attributes.put("currentNodeId", result.nodeId());
        }
        if (result.code() != null) {
            attributes.put("code", result.code());
        }
        if ("RUNTIME_GRAPH_CANCELLED".equals(result.code())) {
            emit(RuntimeExecutionEventType.RUN_CANCELLED, result.nodeId(), result.nodeType(), null,
                    "CANCELLED", result.code(), attributes);
        } else if (result.isWaitingUser() || WorkflowInteractionCodes.WAITING.equals(result.code())) {
            emit(RuntimeExecutionEventType.RUN_SUSPENDED, result.nodeId(), result.nodeType(), null,
                    "SUSPENDED", result.code(), attributes);
        } else if (result.success()) {
            emit(RuntimeExecutionEventType.RUN_COMPLETED, result.nodeId(), result.nodeType(), null,
                    "COMPLETED", result.code(), attributes);
        } else {
            emit(RuntimeExecutionEventType.RUN_FAILED, result.nodeId(), result.nodeType(), null,
                    "FAILED", result.code(), attributes);
        }
    }

    public void onRunException(RuntimeException exception) {
        emit(RuntimeExecutionEventType.RUN_FAILED, null, null, null,
                "FAILED", exception == null ? "RUNTIME_GRAPH_UNEXPECTED" : exception.getClass().getSimpleName(),
                Map.of());
    }

    @Override
    public void onNodeStarted(String nodeId,
                              String nodeType,
                              String nodeName,
                              Map<String, Object> safePayload) {
        emit(RuntimeExecutionEventType.NODE_STARTED, nodeId, nodeType, nodeName,
                "RUNNING", null, safePayload);
        delegate.onNodeStarted(nodeId, nodeType, nodeName, safePayload);
    }

    @Override
    public void onNodeDelta(String nodeId,
                            String nodeType,
                            String text,
                            Map<String, Object> safePayload) {
        Map<String, Object> attributes = safeAttributes(safePayload);
        attributes.put("deltaChars", text == null ? 0 : text.length());
        emit(RuntimeExecutionEventType.NODE_DELTA, nodeId, nodeType, null,
                "RUNNING", null, attributes);
        delegate.onNodeDelta(nodeId, nodeType, text, safePayload);
    }

    @Override
    public void onNodeCompleted(String nodeId,
                                String nodeType,
                                String nodeName,
                                Map<String, Object> safePayload) {
        emit(RuntimeExecutionEventType.NODE_COMPLETED, nodeId, nodeType, nodeName,
                status(safePayload, "COMPLETED"), code(safePayload), safePayload);
        delegate.onNodeCompleted(nodeId, nodeType, nodeName, safePayload);
    }

    @Override
    public void onNodeWaiting(String nodeId,
                              String nodeType,
                              String nodeName,
                              Map<String, Object> safePayload) {
        emit(RuntimeExecutionEventType.NODE_SUSPENDED, nodeId, nodeType, nodeName,
                "SUSPENDED", code(safePayload), safePayload);
        delegate.onNodeWaiting(nodeId, nodeType, nodeName, safePayload);
    }

    @Override
    public void onNodeFailed(String nodeId,
                             String nodeType,
                             String nodeName,
                             Map<String, Object> safePayload) {
        emit(RuntimeExecutionEventType.NODE_FAILED, nodeId, nodeType, nodeName,
                "FAILED", code(safePayload), safePayload);
        delegate.onNodeFailed(nodeId, nodeType, nodeName, safePayload);
    }

    @Override
    public void onExecutionCancelled(Map<String, Object> safePayload) {
        // The outer execution boundary emits the single canonical RUN_CANCELLED terminal event.
        // Keep this callback only for the legacy live-stream contract.
        delegate.onExecutionCancelled(safePayload);
    }

    private void emit(RuntimeExecutionEventType type,
                      String nodeId,
                      String nodeType,
                      String nodeName,
                      String status,
                      String code,
                      Map<String, Object> attributes) {
        long nextSequence = sequence.incrementAndGet();
        Map<String, Object> context = executionContext;
        RuntimeExecutionEvent event = new RuntimeExecutionEvent(
                RuntimeExecutionEvent.SCHEMA_VERSION,
                executionEventPrefix + ":" + nextSequence,
                nextSequence,
                type,
                firstText(context, "runId", "runtimeRunId"),
                firstText(context, "traceId"),
                firstText(context, "workflowId"),
                longValue(context.get("workflowVersionId")),
                nodeId,
                nodeType,
                nodeName,
                status,
                Instant.now(),
                longValue(attributes == null ? null : attributes.get("elapsedMs")),
                intValue(attributes == null ? null : attributes.get("attempt")),
                code,
                safeAttributes(attributes));
        delegate.onExecutionEvent(event);
    }

    private static Map<String, Object> safeAttributes(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : SAFE_ATTRIBUTE_KEYS) {
            Object value = source.get(key);
            if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                result.put(key, value);
            }
        }
        return result;
    }

    private static String status(Map<String, Object> payload, String fallback) {
        String value = text(payload == null ? null : payload.get("status"));
        return value == null ? fallback : value;
    }

    private static String code(Map<String, Object> payload) {
        return text(payload == null ? null : payload.get("code"));
    }

    private static String firstText(Map<String, Object> source, String... keys) {
        if (source == null) {
            return null;
        }
        for (String key : keys) {
            String value = text(source.get(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String result = String.valueOf(value).trim();
        return result.isEmpty() ? null : result;
    }

    private static Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            String text = text(value);
            return text == null ? null : Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Integer intValue(Object value) {
        Long parsed = longValue(value);
        return parsed == null ? null : parsed.intValue();
    }
}
