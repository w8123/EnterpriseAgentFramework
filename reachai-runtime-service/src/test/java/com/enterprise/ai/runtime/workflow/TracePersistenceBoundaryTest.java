package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigSnapshot;
import com.enterprise.ai.runtime.execution.RuntimeAgentRunLifecyclePort.AgentTarget;
import com.enterprise.ai.runtime.runops.RuntimeRunEntity;
import com.enterprise.ai.runtime.runops.RuntimeRunLifecycleService;
import com.enterprise.ai.runtime.runops.RuntimeRunSnapshots;
import com.enterprise.ai.runtime.runops.RuntimeRunMapper;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanEntity;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TracePersistenceBoundaryTest {

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                RuntimeRunEntity.class);
    }

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
            entity.setId(1L);
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
        when(spanMapper.update(org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(1);

        ObjectMapper json = new ObjectMapper();
        RuntimeRunLifecycleService lifecycle = new RuntimeRunLifecycleService(runMapper, json);
        com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutor executor =
                mock(com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutor.class);
        when(executor.execute(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.<Map<String, Object>>any(), any(), any(), any(), any())).thenReturn(
                new RuntimeGraphSpecExecutionResult(
                false, "RUNTIME_GRAPH_FAILED", answer, "http", "HTTP_REQUEST",
                List.of(Map.of("nodeId", "http", "nodeType", "HTTP_REQUEST", "status", "FAILED",
                        "traceSummary", Map.of("url", "https://example.test/a?q=" + query,
                                "body", httpBody, "headers", Map.of("authorization", token)))),
                Map.of("answer", answer, "uiRequest", Map.of("card", uiRequest),
                        "headers", Map.of("x-secret", header), "hits", List.of(Map.of("content", hit)))));
        var definitions = mock(RuntimeWorkflowDefinitionService.class);
        var workflow = new RuntimeWorkflowDefinitionEntity();
        workflow.setId("wf-1");
        workflow.setKeySlug("wf");
        workflow.setName("Workflow");
        workflow.setProjectCode("demo");
        workflow.setExecutionEngine("GRAPH_SPEC");
        when(definitions.findById("wf-1")).thenReturn(java.util.Optional.of(workflow));
        RuntimeWorkflowDebugService service = new RuntimeWorkflowDebugService(
                definitions,
                executor,
                lifecycle,
                new com.enterprise.ai.runtime.trace.RuntimeTraceEvidenceWriter(spanMapper, org.mockito.Mockito.mock(com.enterprise.ai.runtime.trace.RuntimeToolCallLogMapper.class)),
                json,
                new RuntimeWorkflowDocumentCanonicalizer(json),
                new com.enterprise.ai.runtime.trace.RuntimeTraceRootService(spanMapper, json),
                new com.enterprise.ai.runtime.trace.RuntimeTraceSpanTerminationService(spanMapper));

        String graph = "{\"schemaVersion\":2,\"entryNodeId\":\"http\",\"exitNodeIds\":[\"http\"],"
                + "\"nodes\":[{\"id\":\"http\",\"type\":\"HTTP_REQUEST\","
                + "\"config\":{\"body\":\"" + graphSecret + "\"}}],\"edges\":[]}";
        service.debugRun(new RuntimeWorkflowDebugService.DebugRunRequest(
                "wf-1", "wf", "Workflow", "GENERAL", "demo", "GRAPH_SPEC", null,
                graph, null, message,
                Map.of("headers", Map.of("authorization", token), "query", query), Map.of()));

        ArgumentCaptor<RuntimeTraceSpanEntity> spanInserts = ArgumentCaptor.forClass(RuntimeTraceSpanEntity.class);
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<RuntimeTraceSpanEntity>> spanUpdates =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        ArgumentCaptor<RuntimeRunEntity> runInserts = ArgumentCaptor.forClass(RuntimeRunEntity.class);
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<RuntimeRunEntity>> runUpdates =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(spanMapper, org.mockito.Mockito.atLeast(2)).insert(spanInserts.capture());
        verify(spanMapper, org.mockito.Mockito.atLeastOnce()).update(org.mockito.ArgumentMatchers.isNull(), spanUpdates.capture());
        verify(runMapper).insert(runInserts.capture());
        verify(runMapper).update(org.mockito.ArgumentMatchers.isNull(), runUpdates.capture());

        String persisted = strings(spanInserts.getAllValues()) + strings(spanUpdates.getAllValues().stream().map(update -> {
            update.getSqlSet();
            update.getSqlSegment();
            return update.getParamNameValuePairs();
        }).toList())
                + strings(runInserts.getAllValues()) + strings(runUpdates.getAllValues().stream().map(update -> {
                    update.getSqlSet();
                    update.getSqlSegment();
                    return update.getParamNameValuePairs();
                }).toList());
        for (String sensitive : List.of(message, answer, httpBody, header, query, hit, token, uiRequest, graphSecret)) {
            assertFalse(persisted.contains(sensitive), () -> "raw value persisted: " + sensitive);
        }
        assertTrue(persisted.contains("HTTP_REQUEST"));
        assertTrue(persisted.contains("graphSpecDigest"));
        assertTrue(persisted.contains("\"nodeCount\":1"));
    }

    @Test
    void debugPersistenceUsesCanonicalProjectedNodeTrace() {
        RuntimeTraceSpanMapper spanMapper = mock(RuntimeTraceSpanMapper.class);
        when(spanMapper.update(org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(1);
        AtomicReference<RuntimeTraceSpanEntity> root = new AtomicReference<>();
        when(spanMapper.insert(any())).thenAnswer(call -> {
            RuntimeTraceSpanEntity entity = call.getArgument(0);
            if (entity.getParentSpanId() == null) {
                entity.setId(1L);
                root.set(entity);
            }
            return 1;
        });
        when(spanMapper.selectById(1L)).thenAnswer(call -> root.get());

        com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutor executor =
                mock(com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutor.class);
        Map<String, Object> projected = Map.ofEntries(
                Map.entry("nodeId", "tool"),
                Map.entry("nodeType", "TOOL"),
                Map.entry("nodeName", "Echo"),
                Map.entry("status", "SUCCESS"),
                Map.entry("startedAt", 1_700_000_000_000L),
                Map.entry("endedAt", 1_700_000_000_012L),
                Map.entry("latencyMs", 12L),
                Map.entry("attempt", 2),
                Map.entry("maxAttempts", 3),
                Map.entry("qualifiedName", "system.echo"),
                Map.entry("failureCategory", "NONE"),
                Map.entry("retryableFailure", false));
        when(executor.execute(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.<Map<String, Object>>any(), any(), any(), any(), any())).thenReturn(
                new RuntimeGraphSpecExecutionResult(
                        true, "RUNTIME_GRAPH_EXECUTED", "ok", "tool", "TOOL",
                        List.of(Map.of("detail", "legacy-node")),
                        Map.of("workflowNodeTraces", List.of(projected))));

        ObjectMapper json = new ObjectMapper();
        RuntimeWorkflowDebugService service = new RuntimeWorkflowDebugService(
                mock(RuntimeWorkflowDefinitionService.class),
                executor,
                mock(RuntimeRunLifecycleService.class),
                new com.enterprise.ai.runtime.trace.RuntimeTraceEvidenceWriter(spanMapper, org.mockito.Mockito.mock(com.enterprise.ai.runtime.trace.RuntimeToolCallLogMapper.class)),
                json,
                new RuntimeWorkflowDocumentCanonicalizer(json),
                new com.enterprise.ai.runtime.trace.RuntimeTraceRootService(spanMapper, json),
                new com.enterprise.ai.runtime.trace.RuntimeTraceSpanTerminationService(spanMapper));
        String graph = "{\"schemaVersion\":2,\"entryNodeId\":\"tool\",\"exitNodeIds\":[\"tool\"],"
                + "\"nodes\":[{\"id\":\"tool\",\"type\":\"TOOL\",\"ref\":{\"qualifiedName\":\"system.echo\"}}],\"edges\":[]}";

        service.debugRun(new RuntimeWorkflowDebugService.DebugRunRequest(
                null, "wf", "Workflow", "GENERAL", "demo", "GRAPH_SPEC", null,
                graph, null, "hello", Map.of(), Map.of()));

        ArgumentCaptor<RuntimeTraceSpanEntity> inserted = ArgumentCaptor.forClass(RuntimeTraceSpanEntity.class);
        verify(spanMapper, org.mockito.Mockito.atLeast(2)).insert(inserted.capture());
        RuntimeTraceSpanEntity child = inserted.getAllValues().stream()
                .filter(entity -> entity.getParentSpanId() != null)
                .findFirst()
                .orElseThrow();
        assertEquals("tool", child.getNodeId());
        assertEquals("system.echo", child.getToolName());
        assertEquals(12, child.getLatencyMs());
        assertTrue(child.getMetadataJson().contains("\"attempt\":2"));
        assertTrue(child.getMetadataJson().contains("\"maxAttempts\":3"));
        assertTrue(child.getMetadataJson().contains("\"failureCategory\":\"NONE\""));
        assertFalse(child.getMetadataJson().contains("legacy-node"));
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
        RuntimeRunLifecycleService lifecycle = new RuntimeRunLifecycleService(mapper, new ObjectMapper());
        RuntimeAgentConfigSnapshot config = RuntimeAgentConfigSnapshot.builder()
                .id(1L)
                .versionNo(1)
                .runtimeType("AGENTSCOPE")
                .build();
        var agent = new AgentTarget("agent", "agent", "Agent", 1L, "demo");

        lifecycle.beginAgent("agent-trace", "span", java.time.LocalDateTime.now(), agent,
                new RuntimeRunSnapshots.AgentConfiguration(config.getId(), config.getVersionNo(), config.getRuntimeType(), 0, List.of()),
                Map.of("message", metadataSecret, "userId", metadataSecret));
        lifecycle.finishAgent("agent-trace", false, "FAILED", answer,
                Map.of("uiRequest", Map.of("defaults", metadataSecret), "planCount", 1), null);
        assertFalse(strings(List.of(saved.get())).contains(answer));
        assertFalse(strings(List.of(saved.get())).contains(metadataSecret));
        verify(mapper).update(any(), any());

        saved.set(null);
        lifecycle.beginWorkflow("workflow-trace", "span", "DEBUG",
                new RuntimeRunSnapshots.Workflow("wf", "wf", "Workflow", null, "demo",
                "GRAPH_SPEC", "{\"nodes\":[{\"type\":\"ANSWER\",\"config\":\"" + metadataSecret + "\"}]}"),
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
            if (entity instanceof Map<?, ?> || entity instanceof Iterable<?>) {
                values.add(String.valueOf(entity));
                continue;
            }
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
