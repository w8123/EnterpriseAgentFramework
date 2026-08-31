package com.enterprise.ai.runtime.execution.event;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Versioned, provider-neutral execution event emitted in real time by the GraphSpec Runtime.
 *
 * <p>{@link #safeAttributes()} is an allowlisted diagnostic projection. It must never contain
 * prompts, user messages, retrieved evidence, credentials, raw tool arguments, or HTTP bodies.</p>
 */
public record RuntimeExecutionEvent(
        int schemaVersion,
        String eventId,
        long sequence,
        RuntimeExecutionEventType eventType,
        String runId,
        String traceId,
        String workflowId,
        Long workflowVersionId,
        String nodeId,
        String nodeType,
        String nodeName,
        String status,
        Instant occurredAt,
        Long durationMs,
        Integer attempt,
        String code,
        Map<String, Object> safeAttributes
) {

    public static final int SCHEMA_VERSION = 1;

    public RuntimeExecutionEvent {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported Runtime execution event schema: " + schemaVersion);
        }
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        safeAttributes = safeAttributes == null
                ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(safeAttributes));
    }
}
