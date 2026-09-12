package com.enterprise.ai.runtime.mcp.application;

import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutor;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.runops.RuntimeRunLifecycleService;
import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import com.enterprise.ai.runtime.support.RuntimeSqlQueryRecorder;
import com.enterprise.ai.runtime.trace.RuntimeTraceEvidenceWriter;
import com.enterprise.ai.runtime.trace.RuntimeTraceRootService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionMapper;
import com.enterprise.ai.runtime.workflow.RuntimePublishedWorkflowSnapshotReader;
import com.enterprise.ai.runtime.workflow.RuntimePublishedWorkflowSnapshotQuery;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Workflow version persistence; execution, Trace and RunOps are substituted. */
class RuntimeMcpWorkflowVersionPersistenceTest {
    private static final String GRAPH = "{\"nodes\":[{\"id\":\"发布节点\",\"type\":\"START\"}],\"edges\":[]}";
    private RuntimeQueryTestDatabase database;
    private RuntimeSqlQueryRecorder queries;
    private RuntimeGraphSpecExecutor graph;
    private RuntimeRunLifecycleService runs;
    private RuntimeMcpToolExecutionService mcp;
    private ExecutorService executor;
    private ScheduledExecutorService scheduler;

    @BeforeEach
    void setUp() throws Exception {
        database = new RuntimeQueryTestDatabase(List.of("runtime_workflow", "runtime_workflow_version"),
                RuntimeWorkflowVersionMapper.class);
        queries = new RuntimeSqlQueryRecorder();
        database.addInterceptor(queries);
        graph = mock(RuntimeGraphSpecExecutor.class);
        when(graph.execute(anyString(), anyMap(), any(), any(), any(WorkflowExecutionIdentity.class)))
                .thenReturn(new RuntimeGraphSpecExecutionResult(true, "OK", "执行成功", null, null, List.of(), Map.of()));
        runs = mock(RuntimeRunLifecycleService.class);
        when(runs.beginMcp(any(), any(), any(), any(), any(), any(), anyMap(), anyMap(), any())).thenReturn(77L);
        var roots = mock(RuntimeTraceRootService.class);
        when(roots.startMcp(any())).thenReturn(new RuntimeTraceRootService.Handle(1L, "fixture-trace", "fixture-root", LocalDateTime.now()));
        executor = Executors.newSingleThreadExecutor();
        scheduler = Executors.newSingleThreadScheduledExecutor();
        var proxy = new org.springframework.aop.framework.ProxyFactory(
                new RuntimePublishedWorkflowSnapshotReader(database.mapper(RuntimeWorkflowVersionMapper.class)));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(database.jdbc().getDataSource()),
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        mcp = new RuntimeMcpToolExecutionService(mock(RuntimeCapabilityCatalogClient.class), graph,
                (RuntimePublishedWorkflowSnapshotQuery) proxy.getProxy(), roots, mock(RuntimeTraceEvidenceWriter.class),
                runs, new ObjectMapper(), executor, scheduler);
        database.jdbc().update("""
                INSERT INTO runtime_workflow (id, key_slug, name, project_id, project_code, status, default_model_instance_id)
                VALUES ('wf-orders', 'orders-flow', '当前草稿', 9, 'other-project', 'DRAFT', 'draft-model')
                """);
        insertVersion(44L, "1.0.0", "RETIRED", "published-model");
        insertVersion(45L, "2.0.0", "ACTIVE", "newest-model");
    }

    @AfterEach
    void close() throws Exception {
        if (executor != null) { executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); }
        if (scheduler != null) { scheduler.shutdownNow(); assertTrue(scheduler.awaitTermination(5, TimeUnit.SECONDS)); }
        if (database != null) database.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "RETIRED"})
    void executesTheExactPublishedVersionWithoutReadingTheCurrentWorkflow(String status) {
        database.jdbc().update("UPDATE runtime_workflow_version SET status = ? WHERE id = 44", status);
        var result = execute(44L);
        assertTrue(result.success());
        assertEquals("执行成功", result.output().get("answer"));
        var arguments = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(graph).execute(eq(GRAPH), arguments.capture(), any(), any(), any(WorkflowExecutionIdentity.class));
        assertEquals("published-model", arguments.getValue().get("workflowDefaultModelInstanceId"));
        assertEquals(42, arguments.getValue().get("businessValue"));
        assertEquals(1, queries.queries().size());
        assertTrue(queries.queries().get(0).sql().contains("runtime_workflow_version"));
        verify(runs).finishMcp(eq(result.traceId()), eq(true), eq("OK"), eq(true), anyMap());
    }

    @Test
    void anExistingPublishedSnapshotDoesNotRequireACurrentDefinitionRow() {
        database.jdbc().update("DELETE FROM runtime_workflow WHERE id = 'wf-orders'");
        assertTrue(execute(44L).success());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0L, -1L})
    void rejectsMissingOrInvalidVersionIdentifiersWithoutAQuery(Long id) {
        failure(id, "MCP_WORKFLOW_VERSION_REQUIRED");
        assertTrue(queries.queries().isEmpty());
    }

    @Test
    void aMissingVersionDoesNotFallBackToTheLatestVersion() {
        failure(99L, "MCP_WORKFLOW_VERSION_NOT_FOUND");
    }

    @Test
    void rejectsAVersionOwnedByAnotherWorkflow() {
        database.jdbc().update("UPDATE runtime_workflow_version SET workflow_id = 'other-workflow' WHERE id = 44");
        failure(44L, "MCP_WORKFLOW_VERSION_NOT_FOUND");
    }

    @Test
    void workflowIdentityComparisonRemainsCaseSensitive() {
        database.jdbc().update("UPDATE runtime_workflow_version SET workflow_id = 'WF-ORDERS' WHERE id = 44");
        failure(44L, "MCP_WORKFLOW_VERSION_NOT_FOUND");
    }

    @Test
    void rejectsAConflictingIdentityInsideThePublishedSnapshot() {
        snapshot("{\"id\":\"wrong-workflow\",\"projectId\":8,\"projectCode\":\"orders\",\"defaultModelInstanceId\":null}");
        failure(44L, "MCP_WORKFLOW_SNAPSHOT_INVALID");
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "active"})
    void preservesPublishedStatusValidationAndItsErrorCode(String status) {
        database.jdbc().update("UPDATE runtime_workflow_version SET status = ? WHERE id = 44", status);
        failure(44L, "MCP_WORKFLOW_SNAPSHOT_INVALID");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "[]", "{", "{\"defaultModelInstanceId\":32}",
            "{\"defaultModelInstanceId\":null,\"projectId\":-1}"})
    void refusesIncompleteOrMalformedExecutionSnapshots(String json) {
        snapshot(json);
        failure(44L, "MCP_WORKFLOW_SNAPSHOT_INVALID");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "{"})
    void refusesMissingOrMalformedPublishedGraphs(String json) {
        database.jdbc().update("UPDATE runtime_workflow_version SET graph_spec_snapshot_json = ? WHERE id = 44", json);
        failure(44L, "MCP_WORKFLOW_SNAPSHOT_INVALID");
    }

    @Test
    void authorizesThePublishedProjectAndIgnoresForgedArgumentScope() {
        snapshot("{\"projectId\":9,\"projectCode\":\"billing\",\"defaultModelInstanceId\":null}");
        failure(44L, "MCP_WORKFLOW_PROJECT_SCOPE_DENIED");
    }

    @Test
    void refusesASnapshotWithoutProjectScope() {
        snapshot("{\"defaultModelInstanceId\":null}");
        failure(44L, "MCP_WORKFLOW_PROJECT_SCOPE_DENIED");
    }

    @Test
    void explicitNullPublishedModelStillOverridesCallerDefaults() {
        snapshot("{\"projectId\":8,\"projectCode\":\"orders\",\"defaultModelInstanceId\":null}");
        assertTrue(execute(44L).success());
        var arguments = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(graph).execute(anyString(), arguments.capture(), any(), any(), any(WorkflowExecutionIdentity.class));
        assertTrue(arguments.getValue().containsKey("workflowDefaultModelInstanceId"));
        assertNull(arguments.getValue().get("workflowDefaultModelInstanceId"));
    }

    @Test
    void aDatabaseReadFailureRemainsATechnicalFailureInsteadOfNotFound() {
        database.jdbc().execute("DROP TABLE runtime_workflow_version");
        failure(44L, "MCP_WORKFLOW_EXECUTION_FAILED");
    }

    private void failure(Long id, String expected) {
        var result = execute(id);
        assertFalse(result.success());
        assertEquals(expected, result.code());
        assertEquals("77", result.runId());
        assertTrue(result.output().isEmpty());
        verifyNoInteractions(graph);
        verify(runs).finishMcp(eq(result.traceId()), eq(false), eq(expected), eq(false), anyMap());
    }

    private RuntimeMcpToolExecutionService.ExecutionOutcome execute(Long id) {
        return mcp.execute(new RuntimeMcpToolExecutionService.ExecutionRequest("WORKFLOW", "wf-orders", id,
                "orders_tool", Map.of("workflowDefaultModelInstanceId", "caller-model", "businessValue", 42,
                        "projectId", 9, "projectCode", "billing"), Map.of(), 5_000L),
                WorkflowExecutionIdentity.fromMcpRemoteClient("tenant-a", 8L, "orders", "client-1"));
    }

    private void snapshot(String json) {
        database.jdbc().update("UPDATE runtime_workflow_version SET snapshot_json = ? WHERE id = 44", json);
    }

    private void insertVersion(long id, String version, String status, String model) {
        database.jdbc().update("""
                INSERT INTO runtime_workflow_version (id, workflow_id, version, status, snapshot_json, graph_spec_snapshot_json)
                VALUES (?, 'wf-orders', ?, ?, ?, ?)
                """, id, version, status, "{\"id\":\"wf-orders\",\"projectId\":8,\"projectCode\":\"orders\",\"defaultModelInstanceId\":\"" + model + "\"}", GRAPH);
    }
}
