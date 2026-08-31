package com.enterprise.ai.runtime.execution.trace;

import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionEventSink;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import com.enterprise.ai.runtime.execution.event.RuntimeExecutionEvent;
import com.enterprise.ai.runtime.execution.event.RuntimeExecutionEventType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeExecutionTraceProjectorTest {

    @Test
    void projectsTypedEventsIntoCanonicalSafeNodeTraceAndForwardsDelegate() {
        List<RuntimeExecutionEvent> forwarded = new ArrayList<>();
        RuntimeExecutionTraceProjector projector = new RuntimeExecutionTraceProjector(
                new RuntimeGraphSpecExecutionEventSink() {
                    @Override
                    public void onExecutionEvent(RuntimeExecutionEvent event) {
                        forwarded.add(event);
                    }
                });
        Instant start = Instant.parse("2026-08-24T00:00:00Z");
        projector.onExecutionEvent(event(1, RuntimeExecutionEventType.RUN_STARTED,
                null, null, "RUNNING", start, null, Map.of()));
        projector.onExecutionEvent(event(2, RuntimeExecutionEventType.NODE_STARTED,
                "n1", "TOOL", "RUNNING", start.plusMillis(10), null, Map.of()));
        projector.onExecutionEvent(event(3, RuntimeExecutionEventType.NODE_COMPLETED,
                "n1", "TOOL", "SUCCESS", start.plusMillis(35), null,
                Map.of("attempt", 1, "maxAttempts", 2, "nextNodeId", "n2")));
        projector.onExecutionEvent(event(4, RuntimeExecutionEventType.RUN_COMPLETED,
                "n1", "TOOL", "COMPLETED", start.plusMillis(40), "RUNTIME_GRAPH_EXECUTED", Map.of()));

        RuntimeGraphSpecExecutionResult projected = projector.project(new RuntimeGraphSpecExecutionResult(
                true, "RUNTIME_GRAPH_EXECUTED", "secret output is not a trace attribute",
                "n1", "TOOL", List.of(), Map.of("rawPrompt", "must-not-enter-projection")));

        assertEquals(4, forwarded.size());
        List<?> traces = (List<?>) projected.metadata().get("workflowNodeTraces");
        assertEquals(1, traces.size());
        Map<?, ?> node = (Map<?, ?>) traces.get(0);
        assertEquals("SUCCESS", node.get("status"));
        assertEquals(25L, node.get("latencyMs"));
        assertEquals("n2", node.get("nextNodeId"));
        assertFalse(node.containsKey("answer"));
        Map<?, ?> summary = (Map<?, ?>) projected.metadata().get("runtimeTraceProjection");
        assertEquals(4L, summary.get("eventCount"));
        assertEquals("SUCCESS", summary.get("status"));
    }

    @Test
    void rejectsDuplicateOrOutOfOrderSequence() {
        RuntimeExecutionTraceProjector projector = new RuntimeExecutionTraceProjector(
                RuntimeGraphSpecExecutionEventSink.NOOP);
        projector.onExecutionEvent(event(1, RuntimeExecutionEventType.RUN_STARTED,
                null, null, "RUNNING", Instant.now(), null, Map.of()));
        assertThrows(IllegalStateException.class, () -> projector.onExecutionEvent(event(
                1, RuntimeExecutionEventType.RUN_FAILED,
                null, null, "FAILED", Instant.now(), "BROKEN", Map.of())));
    }

    @Test
    void preservesTypedFailureClassificationAndRetryDecision() {
        RuntimeExecutionTraceProjector projector = new RuntimeExecutionTraceProjector(
                RuntimeGraphSpecExecutionEventSink.NOOP);
        Instant start = Instant.parse("2026-08-24T00:00:00Z");
        projector.onExecutionEvent(event(1, RuntimeExecutionEventType.RUN_STARTED,
                null, null, "RUNNING", start, null, Map.of()));
        projector.onExecutionEvent(event(2, RuntimeExecutionEventType.NODE_STARTED,
                "tool", "TOOL", "RUNNING", start.plusMillis(1), null, Map.of()));
        projector.onExecutionEvent(event(3, RuntimeExecutionEventType.NODE_FAILED,
                "tool", "TOOL", "FAILED", start.plusMillis(5), "CAPABILITY_CONNECTIVITY_FAILED",
                Map.of("failureCategory", "CONNECTIVITY", "retryableFailure", true,
                        "qualifiedName", "orders:query")));
        projector.onExecutionEvent(event(4, RuntimeExecutionEventType.RUN_FAILED,
                "tool", "TOOL", "FAILED", start.plusMillis(6), "CAPABILITY_CONNECTIVITY_FAILED",
                Map.of()));

        RuntimeGraphSpecExecutionResult result = projector.project(new RuntimeGraphSpecExecutionResult(
                false, "CAPABILITY_CONNECTIVITY_FAILED", "failed", "tool", "TOOL",
                List.of(), Map.of()));
        Map<?, ?> node = (Map<?, ?>) ((List<?>) result.metadata().get("workflowNodeTraces")).get(0);

        assertEquals("CONNECTIVITY", node.get("failureCategory"));
        assertEquals(true, node.get("retryableFailure"));
        assertEquals("orders:query", node.get("qualifiedName"));
    }

    private RuntimeExecutionEvent event(long sequence,
                                        RuntimeExecutionEventType type,
                                        String nodeId,
                                        String nodeType,
                                        String status,
                                        Instant at,
                                        String code,
                                        Map<String, Object> attributes) {
        Long duration = attributes.get("elapsedMs") instanceof Number number
                ? number.longValue()
                : (type == RuntimeExecutionEventType.NODE_COMPLETED ? 25L : null);
        return new RuntimeExecutionEvent(
                1, "event-" + sequence, sequence, type,
                "run-1", "trace-1", "workflow-1", 7L,
                nodeId, nodeType, nodeId == null ? null : "Node one", status,
                at, duration, null, code, attributes);
    }
}
