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
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.runops.RuntimeRunLifecycleService;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanEntity;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanMapper;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionMapper;
import com.enterprise.ai.runtime.workflow.RuntimePublishedWorkflowSnapshotReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
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
    private RuntimeTraceSpanMapper traceSpanMapper;
    private RuntimeRunLifecycleService runLifecycleService;
    private ExecutorService executor;
    private ScheduledExecutorService scheduler;
    private RuntimeMcpToolExecutionService service;

    @BeforeEach
    void setUp() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), "mcp-trace-test"), RuntimeTraceSpanEntity.class);
        capabilityClient = mock(RuntimeCapabilityCatalogClient.class);
        graphSpecExecutor = mock(RuntimeGraphSpecExecutor.class);
        versionMapper = mock(RuntimeWorkflowVersionMapper.class);
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
                capabilityClient, graphSpecExecutor, new RuntimePublishedWorkflowSnapshotReader(versionMapper),
                new com.enterprise.ai.runtime.trace.RuntimeTraceRootService(traceSpanMapper, new ObjectMapper()),
                new com.enterprise.ai.runtime.trace.RuntimeTraceEvidenceWriter(traceSpanMapper,
                        mock(com.enterprise.ai.runtime.trace.RuntimeToolCallLogMapper.class)),
                runLifecycleService, new ObjectMapper(), executor, scheduler);
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
        verify(capabilityClient).invokeTool(eq("orders.lookup"), org.mockito.ArgumentMatchers.argThat(
                invocation -> Map.of("expectedContractHash", "a".repeat(64)).equals(invocation.get("constraints"))));
        assertEquals("77", outcome.runId());
        assertNotNull(outcome.traceId());
        verify(runLifecycleService).finishMcp(eq(outcome.traceId()), eq(true), eq("OK"), eq(true), anyMap());
        ArgumentCaptor<RuntimeTraceSpanEntity> trace = ArgumentCaptor.forClass(RuntimeTraceSpanEntity.class);
        verify(traceSpanMapper).insert(trace.capture());
        assertEquals("MCP_TOOLS_CALL", trace.getValue().getSpanType());
        assertEquals("orders", trace.getValue().getProjectCode());
    }

    @Test
    void capabilityReceivesTheDurableRootTraceInsteadOfCallerSuppliedTraceFields() {
        when(capabilityClient.invokeTool(eq("orders.lookup"), anyMap()))
                .thenReturn(capabilitySuccess("orders.lookup", Map.of("orderId", 42)));
        Map<String, Object> arguments = Map.of(
                "id", 42,
                "traceId", "forged-argument-trace",
                "supervisorTraceId", "forged-argument-supervisor",
                "context", Map.of("supervisorTraceId", "forged-nested-trace"));
        var request = new RuntimeMcpToolExecutionService.ExecutionRequest(
                "CAPABILITY", "orders.lookup", null, "orders_tool", arguments,
                Map.of("traceId", "forged-metadata-trace", "supervisorTraceId", "forged-metadata-supervisor"),
                5_000L, "a".repeat(64));

        var outcome = service.execute(request, identity(8L, "orders"));

        assertTrue(outcome.success());
        assertTrue(outcome.traceId().matches("mcp_[0-9a-f]{32}"));
        verify(capabilityClient).invokeTool(eq("orders.lookup"), org.mockito.ArgumentMatchers.argThat(
                invocation -> arguments.equals(invocation.get("input"))
                        && Map.of("supervisorTraceId", outcome.traceId()).equals(invocation.get("context"))));
        ArgumentCaptor<RuntimeTraceSpanEntity> trace = ArgumentCaptor.forClass(RuntimeTraceSpanEntity.class);
        verify(traceSpanMapper).insert(trace.capture());
        assertEquals(outcome.traceId(), trace.getValue().getTraceId());
        verify(runLifecycleService).beginMcp(eq(outcome.traceId()), any(), eq("CAPABILITY"),
                eq("orders.lookup"), any(), eq("orders_tool"), eq(arguments), anyMap(), any());
        verify(runLifecycleService).finishMcp(eq(outcome.traceId()), eq(true), eq("OK"), eq(true), anyMap());
    }

    @Test
    void workflowRejectsAProjectMismatchBeforeGraphExecutionAndStillClosesTheRoot() {
        RuntimeWorkflowVersionEntity version = new RuntimeWorkflowVersionEntity();
        version.setId(44L);
        version.setWorkflowId("wf-orders");
        version.setStatus("ACTIVE");
        version.setGraphSpecSnapshotJson("{\"nodes\":[],\"edges\":[]}");
        version.setSnapshotJson("{\"projectId\":9,\"projectCode\":\"billing\",\"defaultModelInstanceId\":null}");
        when(versionMapper.selectById(44L)).thenReturn(version);

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
        version.setStatus("ACTIVE");
        version.setGraphSpecSnapshotJson("{\"nodes\":[],\"edges\":[]}");
        version.setSnapshotJson("{\"projectId\":8,\"projectCode\":\"orders\",\"defaultModelInstanceId\":null}");
        when(versionMapper.selectById(44L)).thenReturn(version);
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

    @Test void legacyPublicationWithoutCapabilityPinIsBlockedBeforeOutboundCall() {
        var request = new RuntimeMcpToolExecutionService.ExecutionRequest("CAPABILITY", "orders.lookup", null,
                "lookup", Map.of(), Map.of(), 5_000L);
        var outcome = service.execute(request, identity(8L, "orders"));
        assertEquals("CAPABILITY_PUBLISHED_CONTRACT_REQUIRED", outcome.code());
        verify(capabilityClient, never()).invokeTool(any(), anyMap());
    }

    @Test
    void realWorkflowEngineUsesPublishedDefaultAndIgnoresCallerModelOverrides() {
        RuntimeModelServiceClient model = useRealWorkflowEngine();
        publishModelWorkflow("\"published-model\"");
        var outcome = service.execute(new RuntimeMcpToolExecutionService.ExecutionRequest(
                "WORKFLOW", "wf-orders", 44L, "orders_tool",
                Map.of("modelInstanceId", "caller-model", "workflowDefaultModelInstanceId", "forged-default"),
                Map.of(), 5_000L), identity(8L, "orders"));

        assertTrue(outcome.success(), outcome.code());
        ArgumentCaptor<RuntimeModelServiceClient.ModelChatRequest> sent =
                ArgumentCaptor.forClass(RuntimeModelServiceClient.ModelChatRequest.class);
        verify(model).chat(sent.capture());
        assertEquals("published-model", sent.getValue().getModelInstanceId());
    }

    @Test
    void explicitNullPublishedModelCannotFallBackToCallerModel() {
        RuntimeModelServiceClient model = useRealWorkflowEngine();
        publishModelWorkflow("null");
        var outcome = service.execute(new RuntimeMcpToolExecutionService.ExecutionRequest(
                "WORKFLOW", "wf-orders", 44L, "orders_tool", Map.of("modelInstanceId", "caller-model"),
                Map.of(), 5_000L), identity(8L, "orders"));

        assertFalse(outcome.success());
        assertEquals("RUNTIME_GRAPH_MODEL_REQUIRED", outcome.code());
        verify(model, never()).chat(any());
    }

    @Test
    void missingExecutionSnapshotFailsBeforeGraphExecution() {
        RuntimeWorkflowVersionEntity version = publishModelWorkflow("null");
        version.setSnapshotJson("{\"projectId\":8,\"projectCode\":\"orders\"}");

        var outcome = service.execute(request("WORKFLOW", "wf-orders", 44L), identity(8L, "orders"));

        assertEquals("MCP_WORKFLOW_SNAPSHOT_INVALID", outcome.code());
        verify(graphSpecExecutor, never()).execute(any(), anyMap(), any(), any(), any(WorkflowExecutionIdentity.class));
    }

    private RuntimeWorkflowVersionEntity publishModelWorkflow(String modelJson) {
        RuntimeWorkflowVersionEntity version = new RuntimeWorkflowVersionEntity();
        version.setId(44L);
        version.setWorkflowId("wf-orders");
        version.setStatus("ACTIVE");
        version.setGraphSpecSnapshotJson("""
                {"entryNodeId":"llm","exitNodeIds":["llm"],
                 "nodes":[{"id":"llm","type":"LLM","config":{"userPrompt":"hello"}}]}
                """);
        version.setSnapshotJson("{\"projectId\":8,\"projectCode\":\"orders\",\"defaultModelInstanceId\":" + modelJson + "}");
        when(versionMapper.selectById(44L)).thenReturn(version);
        return version;
    }

    private RuntimeModelServiceClient useRealWorkflowEngine() {
        RuntimeModelServiceClient model = mock(RuntimeModelServiceClient.class);
        RuntimeModelServiceClient.ModelChatData data = new RuntimeModelServiceClient.ModelChatData();
        data.setContent("answer");
        RuntimeModelServiceClient.ModelChatResult response = new RuntimeModelServiceClient.ModelChatResult();
        response.setCode(200);
        response.setData(data);
        when(model.chat(any())).thenReturn(response);
        RuntimeGraphSpecExecutor realEngine = new RuntimeGraphSpecExecutor(
                new ObjectMapper(), model, capabilityClient, mock(RuntimeControlCatalogClient.class));
        service = new RuntimeMcpToolExecutionService(capabilityClient, realEngine, new RuntimePublishedWorkflowSnapshotReader(versionMapper),
                new com.enterprise.ai.runtime.trace.RuntimeTraceRootService(traceSpanMapper, new ObjectMapper()),
                new com.enterprise.ai.runtime.trace.RuntimeTraceEvidenceWriter(traceSpanMapper,
                        mock(com.enterprise.ai.runtime.trace.RuntimeToolCallLogMapper.class)),
                runLifecycleService, new ObjectMapper(), executor, scheduler);
        return model;
    }

    private RuntimeMcpToolExecutionService.ExecutionRequest request(
            String kind, String sourceRef, Long versionId) {
        return new RuntimeMcpToolExecutionService.ExecutionRequest(
                kind, sourceRef, versionId, "orders_tool", Map.of("id", 42),
                Map.of("publicationId", 3, "revisionNo", 2, "environment", "test"), 5_000L, "a".repeat(64));
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
