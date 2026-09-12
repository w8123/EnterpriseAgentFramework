package com.enterprise.ai.runtime.mcp.application;

import com.enterprise.ai.common.capability.CapabilityInvocationFailureCategory;
import com.enterprise.ai.common.capability.CapabilityInvocationRequest;
import com.enterprise.ai.common.capability.CapabilityInvocationResponse;
import com.enterprise.ai.common.capability.CapabilityInvocationStatus;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutor;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.runops.RuntimeRunLifecycleService;
import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanEntity;
import com.enterprise.ai.runtime.trace.RuntimeTraceRootService;
import com.enterprise.ai.runtime.trace.RuntimeTraceEvidenceWriter;
import com.enterprise.ai.runtime.trace.RuntimeToolCallLogMapper;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanMapper;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RuntimeMcpTracePersistenceTest {
    private RuntimeQueryTestDatabase database;
    private RuntimeTraceSpanMapper spans;
    private RuntimeTraceRootService roots;
    private RuntimeCapabilityCatalogClient capabilities;
    private RuntimeGraphSpecExecutor graph;
    private RuntimeWorkflowVersionMapper versions;
    private RuntimeRunLifecycleService runs;
    private ExecutorService executor;
    private ScheduledExecutorService scheduler;
    private RuntimeMcpToolExecutionService service;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() throws Exception {
        database = new RuntimeQueryTestDatabase(List.of("runtime_trace_span"), RuntimeTraceSpanMapper.class);
        spans = spy(database.mapper(RuntimeTraceSpanMapper.class));
        capabilities = mock(RuntimeCapabilityCatalogClient.class);
        graph = mock(RuntimeGraphSpecExecutor.class);
        versions = mock(RuntimeWorkflowVersionMapper.class);
        runs = mock(RuntimeRunLifecycleService.class);
        executor = Executors.newSingleThreadExecutor();
        scheduler = Executors.newSingleThreadScheduledExecutor();
        when(runs.beginMcp(any(), any(), any(), any(), any(), any(), anyMap(), anyMap(), any())).thenReturn(77L);
        when(capabilities.invokeTool(any(), anyMap())).thenReturn(capabilityResult(null));
        var proxy = new org.springframework.aop.framework.ProxyFactory(new RuntimeTraceRootService(spans, json));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(database.jdbc().getDataSource()),
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        roots = (RuntimeTraceRootService) proxy.getProxy();
        service = new RuntimeMcpToolExecutionService(capabilities, graph,
                new com.enterprise.ai.runtime.workflow.RuntimePublishedWorkflowSnapshotReader(versions), roots,
                new RuntimeTraceEvidenceWriter(spans, mock(RuntimeToolCallLogMapper.class)), runs, json, executor, scheduler);
    }

    @AfterEach
    void close() throws Exception {
        executor.shutdownNow();
        scheduler.shutdownNow();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        assertTrue(scheduler.awaitTermination(5, TimeUnit.SECONDS));
        database.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANCELLED", "TIMED_OUT", "ERROR", "SUCCESS"})
    void aLateCapabilityResultCannotOverwriteAnAlreadyCommittedTerminalRoot(String status) {
        duringCapability(() -> database.jdbc().update("""
                UPDATE runtime_trace_span SET status = ?, error_code = 'ORIGINAL', error_message = '终态证据',
                  ended_at = '2026-09-06 10:01:00', latency_ms = 19, output_summary = '原始结果'
                WHERE parent_span_id IS NULL
                """, status));
        call("CAPABILITY");
        var root = root();
        assertEquals(status, root.getStatus());
        assertEquals("ORIGINAL", root.getErrorCode());
        assertEquals("终态证据", root.getErrorMessage());
        assertEquals("原始结果", root.getOutputSummary());
        assertEquals(19, root.getLatencyMs());
        assertEquals(java.time.LocalDateTime.of(2026, 9, 6, 10, 1), root.getEndedAt());
    }

    @Test
    void aMcpCompletionCannotRewriteAnIdentityThatNoLongerBelongsToMcp() {
        duringCapability(() -> database.jdbc().update("""
                UPDATE runtime_trace_span SET span_type = 'SUPERVISOR', status = 'RUNNING', node_id = 'other-owner'
                WHERE parent_span_id IS NULL
                """));
        call("CAPABILITY");
        assertEquals("SUPERVISOR", root().getSpanType());
        assertEquals("RUNNING", root().getStatus());
        assertEquals("other-owner", root().getNodeId());
    }

    @Test
    void completionRetainsMetadataCommittedWhileTheCapabilityIsRunning() throws Exception {
        duringCapability(() -> database.jdbc().update("""
                UPDATE runtime_trace_span SET metadata_json = '{"publicationId":3,"concurrentEvidence":"并发证据"}'
                WHERE parent_span_id IS NULL
                """));
        call("CAPABILITY");
        assertEquals("并发证据", json.readTree(root().getMetadataJson()).path("concurrentEvidence").asText());
    }

    @Test
    void successfulCompletionExplicitlyClearsErrorFields() {
        duringCapability(() -> database.jdbc().update("""
                UPDATE runtime_trace_span SET error_code = 'EARLIER', error_message = 'earlier error'
                WHERE parent_span_id IS NULL
                """));
        call("CAPABILITY");
        assertEquals("SUCCESS", root().getStatus());
        assertNull(root().getErrorCode());
        assertNull(root().getErrorMessage());
    }

    @Test
    void aMissingRootInsertCannotProceedToExternalExecution() {
        doReturn(0).when(spans).insert(any(RuntimeTraceSpanEntity.class));
        assertThrows(IllegalStateException.class, () -> call("CAPABILITY"));
        verify(capabilities, never()).invokeTool(any(), anyMap());
        verifyNoInteractions(runs);
    }

    @Test
    void capabilityRootRetainsTrustedScopePublicationAndOnlySanitizedPayloads() throws Exception {
        var outcome = call("CAPABILITY");
        assertTrue(outcome.success());
        assertEquals("77", outcome.runId());
        var root = root();
        assertEquals(outcome.traceId(), root.getTraceId());
        assertEquals("MCP_TOOLS_CALL", root.getSpanType());
        assertEquals("orders.lookup", root.getNodeId());
        assertEquals("订单查询", root.getToolName());
        assertEquals("tenant-a", root.getTenantId());
        assertEquals("orders", root.getProjectCode());
        assertEquals("[omitted]", root.getOutputSummary());
        assertEquals(3, json.readTree(root.getMetadataJson()).path("publicationId").asInt());
        assertNotNull(root.getEndedAt());
        assertTrue(root.getLatencyMs() >= 0);
        assertFalse(json.writeValueAsString(root).contains("private-"));
    }

    @ParameterizedTest
    @CsvSource({"MCP_CAPABILITY_TECHNICAL_FAILED,ERROR", "MCP_CAPABILITY_EXECUTION_TIMED_OUT,TIMED_OUT", "ACL_DENIED,ERROR"})
    void capabilityFailuresKeepTheExistingMcpStatusAndRejectionSummary(String code, String status) {
        when(capabilities.invokeTool(any(), anyMap())).thenReturn(capabilityResult(code));
        assertFalse(call("CAPABILITY").success());
        assertEquals(status, root().getStatus());
        assertEquals(code, root().getErrorCode());
        assertEquals("[rejected:" + code + "]", root().getErrorMessage());
        assertEquals("", root().getOutputSummary());
    }

    @Test
    void rootInsertFailureRemainsStrictAndStopsBeforeRunOrExternalCall() {
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("injected root failure"))
                .when(spans).insert(any(RuntimeTraceSpanEntity.class));
        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class, () -> call("CAPABILITY"));
        verifyNoInteractions(runs);
        verify(capabilities, never()).invokeTool(any(), anyMap());
    }

    @Test
    void aMissingRunClosesThePersistedRootWithoutInvokingTheCapability() {
        when(runs.beginMcp(any(), any(), any(), any(), any(), any(), anyMap(), anyMap(), any())).thenReturn(null);
        assertFalse(call("CAPABILITY").success());
        assertEquals("ERROR", root().getStatus());
        assertEquals("MCP_RUN_PERSISTENCE_FAILED", root().getErrorCode());
        verify(capabilities, never()).invokeTool(any(), anyMap());
    }

    @ParameterizedTest
    @CsvSource({"SUCCESS,SUCCESS", "WAITING_USER,WAITING_USER", "CANCELLED,CANCELLED", "FAILED,ERROR"})
    void workflowChildEvidenceKeepsItsParentStatusScopeAndPrivateContentOmission(String status, String storedStatus) throws Exception {
        workflow(List.of(Map.of("nodeId", "节点一", "nodeType", "LLM", "status", status,
                "startedAt", 1_000L, "endedAt", 1_010L, "latencyMs", 10,
                "input", "private-node-input", "output", "private-node-output")));
        var outcome = call("WORKFLOW");
        assertTrue(outcome.success());
        var children = spans.selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<RuntimeTraceSpanEntity>()
                .isNotNull("parent_span_id"));
        assertEquals(1, children.size());
        var child = children.get(0);
        assertEquals(root().getSpanId(), child.getParentSpanId());
        assertEquals(outcome.traceId(), child.getTraceId());
        assertEquals("wf-orders", child.getAgentId());
        assertEquals("tenant-a", child.getTenantId());
        assertEquals("orders", child.getProjectCode());
        assertEquals("节点一", child.getNodeId());
        assertEquals(storedStatus, child.getStatus());
        assertFalse(json.writeValueAsString(child).contains("private-"));
    }

    @Test
    void ordinaryWorkflowAndSupervisorCompletionCannotFinishAMcpRoot() {
        var handle = roots.startMcp(start("MCP_TOOLS_CALL"));
        assertFalse(roots.finish(handle, completion()));
        assertEquals("RUNNING", root().getStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUPERVISOR", "WORKFLOW"})
    void mcpCompletionCannotFinishOtherRootTypes(String type) {
        var handle = roots.start(start(type));
        assertFalse(roots.finishMcp(handle, completion()));
        assertEquals("RUNNING", root().getStatus());
    }

    @Test
    void mcpCompletionRollsBackAnActualSqlUpdateIfTheTransactionFails() {
        var handle = roots.startMcp(start("MCP_TOOLS_CALL"));
        var wrote = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call -> {
            assertEquals(1, ((Number) call.callRealMethod()).intValue());
            wrote.set(true);
            throw new IllegalStateException("failure after update");
        }).when(spans).update(isNull(), any());
        assertThrows(IllegalStateException.class, () -> roots.finishMcp(handle, completion()));
        assertTrue(wrote.get());
        assertEquals("RUNNING", root().getStatus());
        assertNull(root().getEndedAt());
        assertNull(root().getLatencyMs());
    }

    @Test
    void workflowNodePersistenceRetainsTheFiveHundredRowBound() {
        var traces = new java.util.ArrayList<Object>();
        traces.add("not a node trace");
        for (int i = 0; i < 503; i++) traces.add(Map.of("nodeId", "node-" + i, "nodeType", "START", "status", "SUCCESS"));
        workflow(traces);
        assertTrue(call("WORKFLOW").success());
        assertEquals(500, database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM runtime_trace_span WHERE parent_span_id IS NOT NULL", Integer.class));
    }

    @Test
    void aNodePersistenceFailureStillClosesTheRootAsFailed() {
        workflow(List.of(Map.of("nodeId", "first", "nodeType", "START", "status", "SUCCESS")));
        doAnswer(call -> {
            RuntimeTraceSpanEntity span = call.getArgument(0);
            if ("WORKFLOW_NODE".equals(span.getSpanType())) {
                throw new org.springframework.dao.DataAccessResourceFailureException("injected child failure");
            }
            return call.callRealMethod();
        }).when(spans).insert(any(RuntimeTraceSpanEntity.class));
        assertFalse(call("WORKFLOW").success());
        assertEquals("ERROR", root().getStatus());
        assertEquals("MCP_WORKFLOW_EXECUTION_FAILED", root().getErrorCode());
    }

    private RuntimeTraceRootService.Start start(String type) {
        return RuntimeTraceRootService.Start.builder().traceId("mcp-owner-test").spanId("root")
                .spanType(type).runtimeType("CAPABILITY").nodeId("orders.lookup").toolName("订单查询")
                .tenantId("tenant-a").projectCode("orders").startedAt(java.time.LocalDateTime.now()).build();
    }

    private RuntimeTraceRootService.Completion completion() {
        return new RuntimeTraceRootService.Completion("SUCCESS", "OK", "answer", java.time.LocalDateTime.now(), null);
    }

    private void duringCapability(Runnable update) {
        when(capabilities.invokeTool(any(), anyMap())).thenAnswer(call -> { update.run(); return capabilityResult(null); });
    }

    private RuntimeMcpToolExecutionService.ExecutionOutcome call(String kind) {
        var request = new RuntimeMcpToolExecutionService.ExecutionRequest(kind,
                "WORKFLOW".equals(kind) ? "wf-orders" : "orders.lookup", "WORKFLOW".equals(kind) ? 44L : null,
                "订单查询", Map.of("message", "private-message", "token", "private-token"),
                Map.of("publicationId", 3, "revisionNo", 2, "environment", "test", "secret", "private-metadata"),
                5_000L, "a".repeat(64));
        return service.execute(request, WorkflowExecutionIdentity.fromMcpRemoteClient("tenant-a", 8L, "orders", "mcp-client-17"));
    }

    private void workflow(List<?> traces) {
        var version = new RuntimeWorkflowVersionEntity();
        version.setId(44L);
        version.setWorkflowId("wf-orders");
        version.setStatus("ACTIVE");
        version.setGraphSpecSnapshotJson("{\"nodes\":[],\"edges\":[]}");
        version.setSnapshotJson("{\"projectId\":8,\"projectCode\":\"orders\",\"defaultModelInstanceId\":null}");
        when(versions.selectById(44L)).thenReturn(version);
        when(graph.execute(any(), anyMap(), any(), any(), any())).thenReturn(new RuntimeGraphSpecExecutionResult(
                true, "OK", "private-answer", null, null, List.of(Map.of("nodeId", "start")), Map.of("workflowNodeTraces", traces)));
    }

    private RuntimeTraceSpanEntity root() {
        return spans.selectOne(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<RuntimeTraceSpanEntity>()
                .isNull("parent_span_id"));
    }

    private CapabilityInvocationResponse capabilityResult(String errorCode) {
        return new CapabilityInvocationResponse(CapabilityInvocationRequest.CONTRACT_VERSION, "invoke-1", "orders.lookup", null, null,
                errorCode == null ? CapabilityInvocationStatus.SUCCEEDED : CapabilityInvocationStatus.TECHNICAL_FAILED,
                errorCode == null, errorCode == null ? Map.of("answer", "private-output") : null, errorCode, null,
                CapabilityInvocationFailureCategory.NONE, false, 1L, 1, null, Map.of());
    }
}
