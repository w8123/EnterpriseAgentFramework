package com.enterprise.ai.runtime.execution.event;

import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionCancellation;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionEventSink;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class RuntimeExecutionEventBridgeSinkTest {

    @Test
    void emitsOrderedVersionedEventsWithoutChangingLegacyCallbacks() {
        RuntimeGraphSpecExecutor executor = executor();
        List<RuntimeExecutionEvent> events = new ArrayList<>();
        List<String> legacy = new ArrayList<>();
        RuntimeGraphSpecExecutionEventSink sink = new RuntimeGraphSpecExecutionEventSink() {
            @Override
            public void onExecutionEvent(RuntimeExecutionEvent event) {
                events.add(event);
            }

            @Override
            public void onNodeStarted(String nodeId,
                                      String nodeType,
                                      String nodeName,
                                      Map<String, Object> safePayload) {
                legacy.add("started:" + nodeId);
            }

            @Override
            public void onNodeCompleted(String nodeId,
                                        String nodeType,
                                        String nodeName,
                                        Map<String, Object> safePayload) {
                legacy.add("completed:" + nodeId);
            }
        };

        RuntimeGraphSpecExecutionResult result = executor.execute(
                """
                {"schemaVersion":2,"entryNodeId":"n1","nodes":[
                  {"id":"n1","type":"ANSWER","config":{"template":"ok"}}
                ],"edges":[],"exitNodeIds":["n1"]}
                """,
                Map.of("runId", "run-1", "traceId", "trace-1", "workflowId", "wf-1", "workflowVersionId", 7),
                sink,
                RuntimeGraphSpecExecutionCancellation.none());

        assertTrue(result.success());
        assertEquals(List.of(
                RuntimeExecutionEventType.RUN_STARTED,
                RuntimeExecutionEventType.NODE_STARTED,
                RuntimeExecutionEventType.NODE_COMPLETED,
                RuntimeExecutionEventType.RUN_COMPLETED),
                events.stream().map(RuntimeExecutionEvent::eventType).toList());
        assertEquals(List.of(1L, 2L, 3L, 4L), events.stream().map(RuntimeExecutionEvent::sequence).toList());
        assertTrue(events.stream().allMatch(event -> event.schemaVersion() == 1));
        assertTrue(events.stream().allMatch(event -> "run-1".equals(event.runId())));
        assertTrue(events.stream().allMatch(event -> "trace-1".equals(event.traceId())));
        assertEquals(List.of("started:n1", "completed:n1"), legacy);
        assertFalse(events.stream().anyMatch(event -> event.safeAttributes().containsKey("summary")));
    }

    @Test
    void emitsRunFailedForCompileFailure() {
        List<RuntimeExecutionEvent> events = new ArrayList<>();
        RuntimeGraphSpecExecutionResult result = executor().execute(
                "not-json",
                Map.of("traceId", "trace-invalid"),
                new RuntimeGraphSpecExecutionEventSink() {
                    @Override
                    public void onExecutionEvent(RuntimeExecutionEvent event) {
                        events.add(event);
                    }
                },
                RuntimeGraphSpecExecutionCancellation.none());

        assertFalse(result.success());
        assertEquals(List.of(RuntimeExecutionEventType.RUN_STARTED, RuntimeExecutionEventType.RUN_FAILED),
                events.stream().map(RuntimeExecutionEvent::eventType).toList());
        assertEquals("RUNTIME_WORKFLOW_GRAPH_INVALID", events.get(1).code());
    }

    private RuntimeGraphSpecExecutor executor() {
        return new RuntimeGraphSpecExecutor(
                new ObjectMapper(),
                mock(RuntimeModelServiceClient.class),
                mock(RuntimeCapabilityCatalogClient.class),
                mock(RuntimeControlCatalogClient.class));
    }
}
