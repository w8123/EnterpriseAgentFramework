package com.enterprise.ai.runtime.mcp.application;

import com.enterprise.ai.common.capability.CapabilityInvocationFailureCategory;
import com.enterprise.ai.common.capability.CapabilityInvocationRequest;
import com.enterprise.ai.common.capability.CapabilityInvocationResponse;
import com.enterprise.ai.common.capability.CapabilityInvocationStatus;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionCancellation;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionEventSink;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutor;
import com.enterprise.ai.runtime.execution.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.runops.RuntimeRunLifecycleService;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanEntity;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanMapper;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeMcpToolExecutionServiceTest {

    private RuntimeCapabilityCatalogClient capabilityClient;
    private RuntimeGraphSpecExecutor graphSpecExecutor;
    private RuntimeWorkflowVersionMapper versionMapper;
    private RuntimeWorkflowDefinitionService workflowService;
    private RuntimeTraceSpanMapper traceSpanMapper;
    private RuntimeRunLifecycleService runLifecycleService;
    private ExecutorService executor;
    private ScheduledExecutorService scheduler;
    private RuntimeMcpToolExecutionService service;

    @BeforeEach
    void setUp() {
        capabilityClient = mock(RuntimeCapabilityCatalogClient.class);
        graphSpecExecutor = mock(RuntimeGraphSpecExecutor.class);
        versionMapper = mock(RuntimeWorkflowVersionMapper.class);
        workflowService = mock(RuntimeWorkflowDefinitionService.class);
        traceSpanMapper = mock(RuntimeTraceSpanMapper.class);
        runLifecycleService = mock(RuntimeRunLifecycleService.class);
        executor = Executors.newFixedThreadPool(2);
        scheduler = Executors.newSingleThreadScheduledExecutor();
        doAnswer(invocation -> {
            RuntimeTraceSpanEntity span = invocation.getArgument(0);
            if (span.getId() == null) span.setId(100L);
            return 1;
        }).when(traceSpanMapper).insert(any(RuntimeTraceSpanEntity.class));
        when(runLifecycleService.beginMcp(any(), any(), any(), any(), any(), any(), anyMap(), anyMap(), any()))
                .thenReturn(77L);
        service = new RuntimeMcpToolExecutionService(
                capabilityClient, graphSpecExecutor, versionMapper, workflowService,
                traceSpanMapper, runLifecycleService, new ObjectMapper(), executor, scheduler);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        scheduler.shutdownNow();
    }

    @Test
    void capabilityCreatesAndCompletesOneDurableRootWithStableIds() {
        when(capabilityClient.invokeTool(eq("orders.lookup"), anyMap()))
                .thenReturn(capabilitySuccess("orders.lookup", Map.of("orderId", 42)));

        var outcome = service.execute(request("CAPABILITY", "orders.lookup", null), identity(8L, "orders"));

        assertTrue(outcome.success());
        assertEquals("77", outcome.runId());
        assertNotNull(outcome.traceId());
        verify(runLifecycleService).finishMcp(eq(outcome.traceId()), eq(true), eq("OK"), eq(true), anyMap());
        ArgumentCaptor<RuntimeTraceSpanEntity> trace = ArgumentCaptor.forClass(RuntimeTraceSpanEntity.class);
        verify(traceSpanMapper).insert(trace.capture());
        assertEquals("MCP_TOOLS_CALL", trace.getValue().getSpanType());
        assertEquals("orders", trace.getValue().getProjectCode());
    }

    @Test
    void workflowRejectsAProjectMismatchBeforeGraphExecutionAndStillClosesTheRoot() {
        RuntimeWorkflowVersionEntity version = new RuntimeWorkflowVersionEntity();
        version.setId(44L);
        version.setWorkflowId("wf-orders");
        version.setGraphSpecSnapshotJson("{\"nodes\":[],\"edges\":[]}");
        version.setSnapshotJson("{\"projectId\":9,\"projectCode\":\"billing\"}");
        when(versionMapper.selectById(44L)).thenReturn(version);
        when(workflowService.findById("wf-orders")).thenReturn(Optional.empty());

        var outcome = service.execute(request("WORKFLOW", "wf-orders", 44L), identity(8L, "orders"));

        assertFalse(outcome.success());
        assertEquals("MCP_WORKFLOW_PROJECT_SCOPE_DENIED", outcome.code());
        assertEquals("77", outcome.runId());
        verify(graphSpecExecutor, never()).execute(any(), anyMap(),
                any(RuntimeGraphSpecExecutionEventSink.class),
                any(RuntimeGraphSpecExecutionCancellation.class),
                any(WorkflowExecutionIdentity.class));
        verify(runLifecycleService).finishMcp(eq(outcome.traceId()), eq(false),
                eq("MCP_WORKFLOW_PROJECT_SCOPE_DENIED"), eq(false), anyMap());
    }

    @Test
    void workflowExecutesThePinnedSnapshotAndPersistsNodeTraceChildren() {
        RuntimeWorkflowVersionEntity version = new RuntimeWorkflowVersionEntity();
        version.setId(44L);
        version.setWorkflowId("wf-orders");
        version.setGraphSpecSnapshotJson("{\"nodes\":[],\"edges\":[]}");
        version.setSnapshotJson("{\"projectId\":8,\"projectCode\":\"orders\"}");
        when(versionMapper.selectById(44L)).thenReturn(version);
        RuntimeWorkflowDefinitionEntity definition = new RuntimeWorkflowDefinitionEntity();
        definition.setId("wf-orders");
        when(workflowService.findById("wf-orders")).thenReturn(Optional.of(definition));
        when(graphSpecExecutor.execute(eq(version.getGraphSpecSnapshotJson()), anyMap(),
                any(RuntimeGraphSpecExecutionEventSink.class),
                any(RuntimeGraphSpecExecutionCancellation.class),
                any(WorkflowExecutionIdentity.class)))
                .thenReturn(new RuntimeGraphSpecExecutionResult(
                        true, "OK", "done", null, null, List.of(Map.of("nodeId", "start")),
                        Map.of("workflowNodeTraces", List.of(Map.of(
                                "nodeId", "start", "nodeType", "START", "status", "SUCCESS",
                                "startedAt", 1_000L, "endedAt", 1_010L, "latencyMs", 10)))));

        var outcome = service.execute(request("WORKFLOW", "wf-orders", 44L), identity(8L, "orders"));

        assertTrue(outcome.success());
        assertEquals("done", outcome.output().get("answer"));
        assertFalse(outcome.output().containsKey("steps"));
        verify(graphSpecExecutor).execute(eq(version.getGraphSpecSnapshotJson()), anyMap(),
                any(RuntimeGraphSpecExecutionEventSink.class),
                any(RuntimeGraphSpecExecutionCancellation.class), any(WorkflowExecutionIdentity.class));
        verify(traceSpanMapper, org.mockito.Mockito.atLeast(2)).insert(any(RuntimeTraceSpanEntity.class));
    }

    private RuntimeMcpToolExecutionService.ExecutionRequest request(
            String kind, String sourceRef, Long versionId) {
        return new RuntimeMcpToolExecutionService.ExecutionRequest(
                kind, sourceRef, versionId, "orders_tool", Map.of("id", 42),
                Map.of("publicationId", 3, "revisionNo", 2, "environment", "test"), 5_000L);
    }

    private WorkflowExecutionIdentity identity(Long projectId, String projectCode) {
        return WorkflowExecutionIdentity.fromMcpRemoteClient(
                "tenant-a", projectId, projectCode, "mcp-client-17");
    }

    private CapabilityInvocationResponse capabilitySuccess(String qualifiedName, Object data) {
        return new CapabilityInvocationResponse(
                CapabilityInvocationRequest.CONTRACT_VERSION,
                "inv-success", qualifiedName, null, null,
                CapabilityInvocationStatus.SUCCEEDED, true, data, null, null,
                CapabilityInvocationFailureCategory.NONE, false,
                1L, 1, null, Map.of());
    }
}
