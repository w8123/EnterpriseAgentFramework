package com.enterprise.ai.runtime.automation;

import com.enterprise.ai.common.capability.CapabilityInvocationRequest;
import com.enterprise.ai.common.capability.CapabilityInvocationResponse;
import com.enterprise.ai.common.capability.CapabilityInvocationStatus;
import com.enterprise.ai.runtime.agent.RuntimeAgentPublishedConfigQuery;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutor;
import com.enterprise.ai.runtime.runops.RuntimeRunLifecycleService;
import com.enterprise.ai.runtime.runops.RuntimeRunMapper;
import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import com.enterprise.ai.runtime.supervisor.RuntimeAgentExecutionService;
import com.enterprise.ai.runtime.trace.RuntimeToolCallLogEntity;
import com.enterprise.ai.runtime.trace.RuntimeToolCallLogMapper;
import com.enterprise.ai.runtime.trace.RuntimeTraceEvidenceWriter;
import com.enterprise.ai.runtime.trace.RuntimeTraceRootService;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanEntity;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanMapper;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowExecutionQuery;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowExecutionView;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowPublishedVersionView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RuntimeAutomationWorkflowTraceEvidenceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final RuntimeCapabilityCatalogClient capability = mock(RuntimeCapabilityCatalogClient.class);
    private final RuntimeTraceSpanMapper spans = mock(RuntimeTraceSpanMapper.class);
    private final RuntimeToolCallLogMapper tools = mock(RuntimeToolCallLogMapper.class);
    private final RuntimeTraceRootService roots = mock(RuntimeTraceRootService.class);
    private final RuntimeRunLifecycleService lifecycle = mock(RuntimeRunLifecycleService.class);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private RuntimeAutomationTargetExecutor executor;
    private Object businessOutput = 2;
    private RuntimeWorkflowExecutionQuery published;
    private RuntimeGraphSpecExecutor engine;

    @BeforeEach
    void preparePublishedGraphAndActualExecutor() {
        String hash = "a".repeat(64);
        String graph = """
                {"nodes":[{"id":"count","type":"TOOL","ref":{"kind":"TOOL","qualifiedName":"orders:count",
                "assetType":"BUSINESS_METHOD","assetId":2,"acceptedRevisionId":2,"projectCode":"orders",
                "contractHash":"%s","businessContractHash":"%s","bindingHash":"%s","executionRevision":"%s"},
                "config":{"inputMapping":{"parentId":"params.parentId"}}},
                {"id":"assign","type":"VARIABLE_ASSIGN","config":{"assignments":{"count":"nodeOutput.count"}}}],
                "edges":[{"from":"count","to":"assign"}],"entryNodeId":"count","exitNodeIds":["assign"]}
                """.formatted(hash, hash, hash, hash);
        var workflow = RuntimeWorkflowExecutionView.builder().id("wf-count").name("Read count")
                .projectId(36L).projectCode("orders").executionEngine("GRAPH_SPEC").status("ACTIVE").build();
        var version = RuntimeWorkflowPublishedVersionView.builder().id(31L).workflowId("wf-count")
                .version("v1.1.0").status("ACTIVE").graphSpecSnapshotJson(graph)
                .snapshotJson("{\"id\":\"wf-count\",\"projectId\":36,\"projectCode\":\"orders\",\"defaultModelInstanceId\":null}").build();
        published = mock(RuntimeWorkflowExecutionQuery.class);
        when(published.resolveOne("wf-count", 31L)).thenReturn(new RuntimeWorkflowExecutionQuery.Target(workflow, version));
        when(capability.invokeTool(eq("orders:count"), anyMap())).thenAnswer(call -> {
            CapabilityInvocationRequest request = CapabilityInvocationRequest.fromRuntime("orders:count", call.getArgument(1));
            return new CapabilityInvocationResponse(1, request.invocationId(), request.qualifiedName(),
                    "count", "Read count", CapabilityInvocationStatus.SUCCEEDED, true, businessOutput,
                    null, null, null, false, 1L, 1, null, Map.of());
        });
        when(roots.start(any())).thenAnswer(call -> {
            RuntimeTraceRootService.Start start = call.getArgument(0);
            return new RuntimeTraceRootService.Handle(1L, start.traceId(), start.spanId(), start.startedAt());
        });
        when(roots.finish(any(), any())).thenReturn(true);
        engine = new RuntimeGraphSpecExecutor(json, mock(RuntimeModelServiceClient.class), capability,
                mock(RuntimeControlCatalogClient.class));
        executor = new RuntimeAutomationTargetExecutor(mock(RuntimeAgentPublishedConfigQuery.class),
                mock(RuntimeAgentExecutionService.class), published, engine, lifecycle, roots,
                new RuntimeTraceEvidenceWriter(spans, tools), mock(RuntimeAutomationInteractionTerminationService.class),
                new RuntimeAutomationJsonSupport(json), scheduler);
    }

    @AfterEach
    void stopTimeoutThread() {
        scheduler.shutdownNow();
    }

    @Test
    void actualToolAndAssignmentProducePinnedSafeChildSpansAndOneToolAudit() throws Exception {
        var result = execute();
        assertTrue(result.success(), result.code());
        assertEquals(2, json.readTree(result.message()).path("assignments").path("var.count").asInt());
        ArgumentCaptor<RuntimeTraceSpanEntity> children = ArgumentCaptor.forClass(RuntimeTraceSpanEntity.class);
        verify(spans, times(2)).insert(children.capture());
        String rootSpan = rootHandle().spanId();
        assertEquals(List.of("count", "assign"), children.getAllValues().stream().map(RuntimeTraceSpanEntity::getNodeId).toList());
        for (var child : children.getAllValues()) {
            assertEquals("WORKFLOW_NODE", child.getSpanType());
            assertEquals("SUCCESS", child.getStatus());
            assertEquals("trace-automation", child.getTraceId());
            assertEquals(rootSpan, child.getParentSpanId());
            assertEquals("tenant-a", child.getTenantId());
            assertEquals("orders", child.getProjectCode());
            assertEquals("wf-count", json.readTree(child.getMetadataJson()).path("workflowId").asText());
            assertEquals(31L, json.readTree(child.getMetadataJson()).path("workflowVersionId").asLong());
            assertEquals("v1.1.0", json.readTree(child.getMetadataJson()).path("workflowVersion").asText());
            assertFalse(child.getMetadataJson().contains("private-parent-id"));
            assertFalse(child.getMetadataJson().contains("private-business-note"));
            assertNull(child.getInputSummary());
            assertNull(child.getOutputSummary());
            assertNotNull(child.getStartedAt());
            assertNotNull(child.getEndedAt());
        }
        ArgumentCaptor<RuntimeToolCallLogEntity> audit = ArgumentCaptor.forClass(RuntimeToolCallLogEntity.class);
        verify(tools).insert(audit.capture());
        assertEquals("orders:count", audit.getValue().getToolName());
        assertEquals("tenant-a", audit.getValue().getTenantId());
        assertEquals("{}", audit.getValue().getArgsJson());
        assertEquals("[omitted]", audit.getValue().getResultSummary());
        assertTrue(audit.getValue().getSuccess());
        assertNull(audit.getValue().getUserId());
        assertFalse(audit.getValue().getRetrievalTraceJson().contains("private-parent-id"));
        verify(capability, times(1)).invokeTool(eq("orders:count"), anyMap());
    }

    @Test
    void failedBusinessMethodRecordsFailedNodeWithoutInventingUnexecutedAssignment() {
        businessOutput = Map.of("code", 400, "message", "private-business-error");
        var result = execute();
        assertFalse(result.success());
        ArgumentCaptor<RuntimeTraceSpanEntity> child = ArgumentCaptor.forClass(RuntimeTraceSpanEntity.class);
        verify(spans).insert(child.capture());
        assertEquals("count", child.getValue().getNodeId());
        assertEquals("FAILED", child.getValue().getStatus());
        assertNotNull(child.getValue().getErrorCode());
        assertFalse(child.getValue().getMetadataJson().contains("private-business-error"));
        ArgumentCaptor<RuntimeToolCallLogEntity> audit = ArgumentCaptor.forClass(RuntimeToolCallLogEntity.class);
        verify(tools).insert(audit.capture());
        assertFalse(audit.getValue().getSuccess());
        assertFalse(audit.getValue().getRetrievalTraceJson().contains("private-business-error"));
    }

    @Test
    void evidenceWriteFailureDoesNotRetryACompletedBusinessCall() {
        doThrow(new IllegalStateException("private-database-diagnostic")).when(spans).insert(any(RuntimeTraceSpanEntity.class));
        var result = execute();
        assertTrue(result.success());
        assertFalse(result.retryable());
        verify(capability, times(1)).invokeTool(eq("orders:count"), anyMap());
        verify(lifecycle).finishWorkflow(eq("trace-automation"), eq(true), any(), any(), anyInt(), anyMap());
    }

    @Test
    void closedRootCannotAppendLateDuplicateNodeEvidence() {
        when(roots.finish(any(), any())).thenReturn(false);
        assertTrue(execute().success());
        verifyNoInteractions(spans, tools);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void realAutomationExecutionCompletesLifecycleWithThePersistedToolAuditCount(boolean success) throws Exception {
        if (!success) businessOutput = Map.of("code", 400, "message", "private-business-error");
        try (var database = new RuntimeQueryTestDatabase(
                List.of("runtime_run", "runtime_trace_span", "runtime_tool_call_log", "runtime_guard_decision_log"),
                RuntimeRunMapper.class, RuntimeTraceSpanMapper.class, RuntimeToolCallLogMapper.class)) {
            var runs = database.mapper(RuntimeRunMapper.class);
            executor = new RuntimeAutomationTargetExecutor(mock(RuntimeAgentPublishedConfigQuery.class),
                    mock(RuntimeAgentExecutionService.class), published, engine,
                    new RuntimeRunLifecycleService(runs, json), roots,
                    new RuntimeTraceEvidenceWriter(database.mapper(RuntimeTraceSpanMapper.class),
                            database.mapper(RuntimeToolCallLogMapper.class)),
                    mock(RuntimeAutomationInteractionTerminationService.class), new RuntimeAutomationJsonSupport(json), scheduler);
            var result = execute();
            assertEquals(success, result.success());
            var persisted = runs.selectList(null);
            assertEquals(1, persisted.size());
            var run = persisted.get(0);
            assertEquals(success ? "COMPLETED" : "FAILED", run.getStatus());
            assertEquals("AUTOMATION", run.getEntryType());
            assertEquals(31L, run.getWorkflowVersionId());
            assertEquals("tenant-a", run.getTenantId());
            assertEquals(1, run.getToolCallCount());
            assertEquals(0, run.getGuardDenyCount());
            assertEquals(0, run.getApprovalCount());
            assertEquals(1, database.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM runtime_tool_call_log WHERE trace_id = 'trace-automation'", Integer.class));
            assertEquals(success ? 2 : 1, database.jdbc().queryForObject(
                    "SELECT COUNT(*) FROM runtime_trace_span WHERE trace_id = 'trace-automation' AND span_type = 'WORKFLOW_NODE'", Integer.class));
            assertFalse(run.getMetadataJson().contains("private-parent-id"));
            assertFalse(run.getMetadataJson().contains("private-business-error"));
            assertEquals("[omitted]", run.getOutputSummary());
            verify(capability, times(1)).invokeTool(eq("orders:count"), anyMap());
        }
    }

    private RuntimeTraceRootService.Handle rootHandle() {
        ArgumentCaptor<RuntimeTraceRootService.Handle> handle = ArgumentCaptor.forClass(RuntimeTraceRootService.Handle.class);
        verify(roots).finish(handle.capture(), any());
        return handle.getValue();
    }

    private RuntimeAutomationTargetExecutor.ExecutionOutcome execute() {
        var automation = new RuntimeAutomationEntity();
        automation.setTenantId("tenant-a");
        automation.setProjectId(36L);
        automation.setProjectCode("orders");
        var version = new RuntimeAutomationVersionEntity();
        version.setTargetType("WORKFLOW");
        version.setTargetId("wf-count");
        version.setTargetVersionId(31L);
        version.setPrincipalId("automation-test");
        return executor.execute(automation, version, new RuntimeAutomationOccurrenceEntity(), "trace-automation",
                Map.of("parentId", "private-parent-id", "customerNote", "private-business-note"));
    }
}
