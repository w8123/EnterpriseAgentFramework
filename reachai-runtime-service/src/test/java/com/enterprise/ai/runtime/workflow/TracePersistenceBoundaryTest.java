package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import com.enterprise.ai.runtime.runops.RuntimeGuardDecisionLogMapper;
import com.enterprise.ai.runtime.runops.RuntimeRunEntity;
import com.enterprise.ai.runtime.runops.RuntimeRunLifecycleService;
import com.enterprise.ai.runtime.runops.RuntimeRunMapper;
import com.enterprise.ai.runtime.trace.RuntimeToolCallLogMapper;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanEntity;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TracePersistenceBoundaryTest {

    @Test
    void debugAndRunFinishPersistenceNeverRetainRawExecutionPayloads() {
        String message = marker();
        String answer = marker();
        String httpBody = marker();
        String header = marker();
        String query = marker();
        String hit = marker();
        String token = marker();
        String uiRequest = marker();
        String graphSecret = marker();

        RuntimeRunMapper runMapper = mock(RuntimeRunMapper.class);
        RuntimeTraceSpanMapper spanMapper = mock(RuntimeTraceSpanMapper.class);
        AtomicReference<RuntimeRunEntity> run = new AtomicReference<>();
        AtomicReference<RuntimeTraceSpanEntity> root = new AtomicReference<>();
        when(runMapper.selectOne(any())).thenAnswer(call -> run.get());
        when(runMapper.insert(any())).thenAnswer(call -> {
            RuntimeRunEntity entity = call.getArgument(0);
            run.set(entity);
            return 1;
        });
        when(spanMapper.insert(any())).thenAnswer(call -> {
            RuntimeTraceSpanEntity entity = call.getArgument(0);
            if (entity.getParentSpanId() == null) {
                entity.setId(1L);
                root.set(entity);
            }
            return 1;
        });
        when(spanMapper.selectById(1L)).thenAnswer(call -> root.get());

        ObjectMapper json = new ObjectMapper();
        RuntimeRunLifecycleService lifecycle = new RuntimeRunLifecycleService(runMapper,
                mock(RuntimeToolCallLogMapper.class), mock(RuntimeGuardDecisionLogMapper.class), json);
        com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutor executor =
                mock(com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutor.class);
        when(executor.execute(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.<Map<String, Object>>any(), any(), any())).thenReturn(
                new RuntimeGraphSpecExecutionResult(
                false, "RUNTIME_GRAPH_FAILED", answer, "http", "HTTP_REQUEST",
                List.of(Map.of("nodeId", "http", "nodeType", "HTTP_REQUEST", "status", "FAILED",
                        "traceSummary", Map.of("url", "https://example.test/a?q=" + query,
                                "body", httpBody, "headers", Map.of("authorization", token)))),
                Map.of("answer", answer, "uiRequest", Map.of("card", uiRequest),
                        "headers", Map.of("x-secret", header), "hits", List.of(Map.of("content", hit)))));
        RuntimeWorkflowDebugService service = new RuntimeWorkflowDebugService(
                mock(RuntimeWorkflowDefinitionService.class), executor, lifecycle, spanMapper, json);

        String graph = "{\"nodes\":[{\"id\":\"http\",\"type\":\"HTTP_REQUEST\","
                + "\"config\":{\"body\":\"" + graphSecret + "\"}}]}";
        service.debugRun(new RuntimeWorkflowDebugService.DebugRunRequest(
                "wf-1", "wf", "Workflow", "CHAT", "demo", "LANGGRAPH4J", null,
                graph, null, message,
                Map.of("headers", Map.of("authorization", token), "query", query), Map.of()));

        ArgumentCaptor<RuntimeTraceSpanEntity> spanInserts = ArgumentCaptor.forClass(RuntimeTraceSpanEntity.class);
        ArgumentCaptor<RuntimeTraceSpanEntity> spanUpdates = ArgumentCaptor.forClass(RuntimeTraceSpanEntity.class);
        ArgumentCaptor<RuntimeRunEntity> runInserts = ArgumentCaptor.forClass(RuntimeRunEntity.class);
        ArgumentCaptor<RuntimeRunEntity> runUpdates = ArgumentCaptor.forClass(RuntimeRunEntity.class);
        verify(spanMapper, org.mockito.Mockito.atLeast(2)).insert(spanInserts.capture());
        verify(spanMapper).updateById(spanUpdates.capture());
        verify(runMapper).insert(runInserts.capture());
        verify(runMapper).updateById(runUpdates.capture());

        String persisted = strings(spanInserts.getAllValues()) + strings(spanUpdates.getAllValues())
                + strings(runInserts.getAllValues()) + strings(runUpdates.getAllValues());
        for (String sensitive : List.of(message, answer, httpBody, header, query, hit, token, uiRequest, graphSecret)) {
            assertFalse(persisted.contains(sensitive), () -> "raw value persisted: " + sensitive);
        }
        assertTrue(persisted.contains("HTTP_REQUEST"));
        assertTrue(persisted.contains("graphSpecDigest"));
        assertTrue(persisted.contains("\"nodeCount\":1"));
    }

    @Test
    void finishAgentAndWorkflowSanitizeRawMapsAtTheirOwnPersistenceBoundary() {
        String answer = marker();
        String metadataSecret = marker();
        RuntimeRunMapper mapper = mock(RuntimeRunMapper.class);
        AtomicReference<RuntimeRunEntity> saved = new AtomicReference<>();
        when(mapper.selectOne(any())).thenAnswer(call -> saved.get());
        when(mapper.insert(any())).thenAnswer(call -> {
            saved.set(call.getArgument(0));
            return 1;
        });
        RuntimeRunLifecycleService lifecycle = new RuntimeRunLifecycleService(mapper,
                mock(RuntimeToolCallLogMapper.class), mock(RuntimeGuardDecisionLogMapper.class), new ObjectMapper());
        RuntimeAgentConfigVersionEntity config = new RuntimeAgentConfigVersionEntity();
        config.setId(1L);
        config.setVersionNo(1);
        config.setRuntimeType("AGENTSCOPE");
        RuntimeAgentView agent = new RuntimeAgentView("agent", 1L, "demo", "agent", "Agent",
                null, "PROJECT", null, true, 1L, 1L, 1, "ACTIVE", "AGENTSCOPE", 1, null, null);

        lifecycle.beginAgent("agent-trace", "span", java.time.LocalDateTime.now(), agent, config, List.of(),
                Map.of("message", metadataSecret, "userId", metadataSecret));
        lifecycle.finishAgent("agent-trace", false, "FAILED", answer,
                Map.of("uiRequest", Map.of("defaults", metadataSecret), "planCount", 1), null);
        assertFalse(strings(List.of(saved.get())).contains(answer));
        assertFalse(strings(List.of(saved.get())).contains(metadataSecret));
        assertTrue(saved.get().getMetadataJson().contains("\"planCount\":1"));

        saved.set(null);
        lifecycle.beginWorkflow("workflow-trace", "span", "DEBUG", "wf", "wf", "Workflow", "demo",
                "LANGGRAPH4J", "{\"nodes\":[{\"type\":\"ANSWER\",\"config\":\"" + metadataSecret + "\"}]}",
                Map.of("message", metadataSecret));
        lifecycle.finishWorkflow("workflow-trace", false, "FAILED", answer, 1,
                Map.of("uiRequest", Map.of("card", metadataSecret), "nodeCount", 1));
        assertFalse(strings(List.of(saved.get())).contains(answer));
        assertFalse(strings(List.of(saved.get())).contains(metadataSecret));
        assertTrue(saved.get().getSnapshotJson().contains("graphSpecDigest"));
    }

    private static String strings(List<?> entities) {
        List<String> values = new ArrayList<>();
        for (Object entity : entities) {
            for (Field field : entity.getClass().getDeclaredFields()) {
                if (field.getType() == String.class) {
                    try {
                        field.setAccessible(true);
                        Object value = field.get(entity);
                        values.add(value == null ? "" : String.valueOf(value));
                    } catch (IllegalAccessException ex) {
                        throw new AssertionError(ex);
                    }
                }
            }
        }
        return String.join("", values);
    }

    private static String marker() {
        return UUID.randomUUID().toString();
    }
}
