package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDeletionReferences;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.debug.RuntimeExecutableDebugSessionMapper;
import com.enterprise.ai.runtime.debug.RuntimeExecutableDebugSessionService;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutor;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.runops.RuntimeRunEntity;
import com.enterprise.ai.runtime.runops.RuntimeRunLifecycleService;
import com.enterprise.ai.runtime.runops.RuntimeRunMapper;
import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import com.enterprise.ai.runtime.trace.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real definition lookup, GraphSpec, Studio session, RunOps and Trace against baseline tables. */
class RuntimeWorkflowDebugSnapshotPersistenceTest {
    private static final com.enterprise.ai.runtime.debug.RuntimeDebugSessionOwner OWNER =
            new com.enterprise.ai.runtime.debug.RuntimeDebugSessionOwner("default", "42");
    private static final String ANSWER = """
            {"schemaVersion":2,"entryNodeId":"answer","exitNodeIds":["answer"],"nodes":[{
              "id":"answer","type":"ANSWER","config":{"template":"candidate answer"}}],"edges":[]}
            """;
    private static final String WAIT = """
            {"schemaVersion":2,"entryNodeId":"ask","exitNodeIds":["answer"],"nodes":[
              {"id":"ask","type":"INTERACTION","config":{
                "interactionType":"COLLECT_INPUT","fields":[{"key":"q","required":true}]}},
              {"id":"answer","type":"ANSWER","config":{"template":"frozen answer"}}
            ],"edges":[{"from":"ask","to":"answer"}]}
            """;
    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();
    private RuntimeQueryTestDatabase database;
    private RuntimeWorkflowDefinitionMapper workflows;
    private RuntimeWorkflowDefinitionService definitions;
    private RuntimeGraphSpecExecutor executor;
    private RuntimeWorkflowDebugService debug;
    private RuntimeExecutableDebugSessionService sessions;

    @BeforeEach
    void setUp() throws Exception {
        database = new RuntimeQueryTestDatabase(List.of("runtime_workflow", "runtime_executable_debug_session",
                        "runtime_run", "runtime_trace_span", "runtime_tool_call_log"),
                RuntimeWorkflowDefinitionMapper.class, RuntimeExecutableDebugSessionMapper.class,
                RuntimeRunMapper.class, RuntimeTraceSpanMapper.class, RuntimeToolCallLogMapper.class);
        workflows = database.mapper(RuntimeWorkflowDefinitionMapper.class);
        var canonicalizer = new RuntimeWorkflowDocumentCanonicalizer(json);
        definitions = spy(new RuntimeWorkflowDefinitionService(workflows, mock(RuntimeWorkflowVersionMapper.class),
                mock(RuntimeWorkflowDeletionReferences.class), canonicalizer,
                mock(RuntimeWorkflowResourceBindingService.class), mock(RuntimeWorkflowReferenceIndex.class)));
        executor = spy(new RuntimeGraphSpecExecutor(json, mock(RuntimeModelServiceClient.class),
                mock(RuntimeCapabilityCatalogClient.class), mock(RuntimeControlCatalogClient.class)));
        var spans = database.mapper(RuntimeTraceSpanMapper.class);
        debug = new RuntimeWorkflowDebugService(definitions, executor,
                new RuntimeRunLifecycleService(database.mapper(RuntimeRunMapper.class), json),
                new RuntimeTraceEvidenceWriter(spans, database.mapper(RuntimeToolCallLogMapper.class)),
                json, canonicalizer, new RuntimeTraceRootService(spans, json), new RuntimeTraceSpanTerminationService(spans));
        sessions = new RuntimeExecutableDebugSessionService(
                database.mapper(RuntimeExecutableDebugSessionMapper.class), debug, json,
                new com.enterprise.ai.runtime.debug.RuntimeDebugExecutionLifecycle(
                        new RuntimeRunLifecycleService(database.mapper(RuntimeRunMapper.class), json),
                        new RuntimeTraceSpanTerminationService(spans)));
    }

    @AfterEach
    void close() { if (database != null) database.close(); }

    @Test
    void candidateGraphKeepsSavedAttributionAndRemainsUntrusted() throws Exception {
        saved(7L, "orders", ANSWER.replace("candidate answer", "saved answer"));
        var result = debug.debugRun(request("wf-1", ANSWER));
        assertTrue(result.success(), result.errorMessage());
        assertEquals("candidate answer", result.answer());
        assertAttribution(result.traceId(), 7L, "orders", "订单流程", 2);
        verify(definitions).findById("wf-1");
        verify(executor).execute(anyString(), anyMap(), any(), any(),
                argThat((WorkflowExecutionIdentity identity) -> !identity.projectTrusted() && !identity.userTrusted()), any());
        assertGraphSnapshot(result.traceId(), ANSWER);
    }

    @Test
    void idOnlyRunRecordsTheActualResolvedGraph() throws Exception {
        saved(7L, "orders", ANSWER);
        var result = debug.debugRun(request("wf-1", null));
        assertTrue(result.success(), result.errorMessage());
        assertGraphSnapshot(result.traceId(), ANSWER);
        assertAttribution(result.traceId(), 7L, "orders", "订单流程", 2);
    }

    @Test
    void candidateGraphCannotBypassMissingSavedTarget() {
        var result = debug.debugRun(request("missing-workflow", ANSWER));
        assertFalse(result.success());
        assertEquals("WORKFLOW_NOT_FOUND", result.errorCode());
        verifyNoInteractions(executor);
        assertEquals(0, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_run", Integer.class));
        assertEquals(0, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_trace_span", Integer.class));
    }

    @Test
    void nodeDebugUsesTheSameSavedAttributionAndCandidateGraph() throws Exception {
        saved(7L, "orders", ANSWER.replace("candidate answer", "saved answer"));
        var result = debug.debugNode(new RuntimeWorkflowDebugService.NodeDebugRequest(
                "wf-1", "body-slug", "body-name", "GENERAL", "body-project", "GRAPH_SPEC", null,
                ANSWER, null, "answer", "hello", forgedInput()));
        assertTrue(result.success(), result.errorMessage());
        assertEquals("candidate answer", result.nodeOutput());
        assertAttribution(result.traceId(), 7L, "orders", "订单流程", 2);
        assertGraphSnapshot(result.traceId(), ANSWER);
    }

    @Test
    void globalSavedTargetDoesNotAcquireTheRequestProject() {
        saved(null, null, ANSWER);
        var result = debug.debugRun(request("wf-1", ANSWER));
        assertTrue(result.success(), result.errorMessage());
        assertAttribution(result.traceId(), null, null, "订单流程", 2);
    }

    @Test
    void unsavedCandidateStillRunsWithoutInventingASavedTargetOrUser() {
        var result = debug.debugRun(request(null, ANSWER));
        assertTrue(result.success(), result.errorMessage());
        assertEquals("candidate answer", result.answer());
        assertNull(run(result.traceId()).getWorkflowId());
        assertNull(run(result.traceId()).getProjectId());
        assertNull(run(result.traceId()).getTenantId());
        assertNull(run(result.traceId()).getUserId());
        verifyNoInteractions(definitions);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void studioResumeUsesItsPersistedDefinitionAfterTheSavedWorkflowIsDeleted(boolean candidateGraph) throws Exception {
        saved(7L, "orders", WAIT);
        Map<String, Object> workingCopy = new LinkedHashMap<>();
        workingCopy.put("workflowId", "wf-1");
        workingCopy.put("workflowName", "body-name");
        workingCopy.put("projectCode", "body-project");
        workingCopy.put("projectId", 999L);
        if (candidateGraph) workingCopy.put("graphSpecJson", WAIT);
        var started = sessions.create(OWNER, new RuntimeExecutableDebugSessionService.CreateRequest(
                "WORKFLOW_WORKING_COPY", workingCopy, "hello", forgedInput(), Map.of()));
        assertEquals("SUSPENDED", started.status());
        var stored = database.mapper(RuntimeExecutableDebugSessionMapper.class).selectById(started.sessionId());
        var snapshot = json.readTree(stored.getWorkingCopyDefinitionJson());
        assertEquals("orders", snapshot.path("projectCode").asText());
        assertEquals(7L, snapshot.path("projectId").asLong());
        assertEquals("订单流程", snapshot.path("workflowName").asText());
        assertEquals(json.readTree(new RuntimeWorkflowDocumentCanonicalizer(json).canonicalizeGraphSpecJson(WAIT)),
                json.readTree(snapshot.path("graphSpecJson").asText()));
        workflows.deleteById("wf-1");
        workingCopy.put("graphSpecJson", ANSWER);
        var resumed = sessions.submit(OWNER, started.sessionId(), new RuntimeExecutableDebugSessionService.SubmitRequest(
                "submit", Map.of("q", "accepted", "projectCode", "resume-body-project", "tenantId", "body-tenant"),
                null));
        assertEquals("COMPLETED", resumed.status());
        assertEquals("frozen answer", resumed.answer());
        assertAttribution(started.traceId(), 7L, "orders", "订单流程", 4);
        assertGraphSnapshot(started.traceId(), WAIT);
        verify(definitions, times(1)).findById("wf-1");
        assertEquals(1, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_run", Integer.class));
    }

    @Test
    void unsupportedSessionTargetIsRejectedBeforeAnyExecutionOrAuditWrite() {
        assertThrows(IllegalArgumentException.class, () -> sessions.create(OWNER,
                new RuntimeExecutableDebugSessionService.CreateRequest("UNSUPPORTED",
                        Map.of("graphSpecJson", ANSWER), "hello", Map.of(), Map.of())));
        verifyNoInteractions(executor);
        assertEquals(0, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_run", Integer.class));
        assertEquals(0, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_executable_debug_session", Integer.class));
    }

    @Test
    void completingEachInputClosesItsPreviousWaitingSpanWithoutClosingTheNextInput() {
        String graph = """
                {"schemaVersion":2,"entryNodeId":"first","exitNodeIds":["answer"],"nodes":[
                  {"id":"first","type":"INTERACTION","config":{"interactionType":"COLLECT_INPUT","fields":[{"key":"x","required":true}]}},
                  {"id":"second","type":"INTERACTION","config":{"interactionType":"COLLECT_INPUT","fields":[{"key":"y","required":true}]}},
                  {"id":"answer","type":"ANSWER","config":{"template":"done"}}
                ],"edges":[{"from":"first","to":"second"},{"from":"second","to":"answer"}]}
                """;
        var started = sessions.create(OWNER, new RuntimeExecutableDebugSessionService.CreateRequest(
                "WORKFLOW_WORKING_COPY", Map.of("graphSpecJson", graph), "hello", Map.of(), Map.of(), "trace-waiting"));
        String traceId = started.traceId();
        var firstWait = database.jdbc().queryForMap("SELECT id,metadata_json,started_at FROM runtime_trace_span WHERE trace_id=? AND node_id='first'", traceId);
        var second = sessions.submit(OWNER, started.sessionId(), new RuntimeExecutableDebugSessionService.SubmitRequest(
                "submit", Map.of("x", "one"), null, ((Map<?, ?>) started.uiRequest()).get("interactionId").toString(), "input-1"));
        assertEquals("SUSPENDED", second.status());
        assertEquals("second", second.currentNodeId());
        var closedFirst = database.jdbc().queryForMap("SELECT status,ended_at,metadata_json,started_at FROM runtime_trace_span WHERE id=?", firstWait.get("id"));
        assertEquals("SUCCESS", closedFirst.get("status"));
        assertNotNull(closedFirst.get("ended_at"));
        assertEquals(firstWait.get("metadata_json"), closedFirst.get("metadata_json"));
        assertEquals(firstWait.get("started_at"), closedFirst.get("started_at"));
        assertEquals(1, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_trace_span WHERE trace_id=? AND node_id='second' AND status='WAITING_USER' AND ended_at IS NULL", Integer.class, traceId));
        var completed = sessions.submit(OWNER, started.sessionId(), new RuntimeExecutableDebugSessionService.SubmitRequest(
                "submit", Map.of("y", "two"), null, ((Map<?, ?>) second.uiRequest()).get("interactionId").toString(), "input-2"));
        assertEquals("COMPLETED", completed.status());
        assertEquals("COMPLETED", run(traceId).getStatus());
        assertEquals(0, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_trace_span WHERE trace_id=? AND ended_at IS NULL", Integer.class, traceId));
    }

    @Test
    void malformedCandidateKeepsTheStructuredDebugFailure() {
        var result = debug.debugRun(request(null, "{broken"));
        assertFalse(result.success());
        assertEquals("RUNTIME_WORKFLOW_GRAPH_INVALID", result.errorCode());
        verifyNoInteractions(executor);
    }

    @Test
    void savedEngineIsResolvedBeforeInterpretingCallerEngineLabels() {
        saved(7L, "orders", ANSWER);
        var result = debug.debugRun(new RuntimeWorkflowDebugService.DebugRunRequest(
                "wf-1", "body-slug", "body-name", "INVALID_KIND", "body-project", "INVALID_ENGINE", null,
                ANSWER, null, "hello", forgedInput(), Map.of()));
        assertTrue(result.success(), result.errorMessage());
        assertEquals("GRAPH_SPEC", run(result.traceId()).getRuntimeType());
        assertAttribution(result.traceId(), 7L, "orders", "订单流程", 2);
    }

    @Test
    void nodeCandidateCannotBypassMissingSavedTarget() {
        var result = debug.debugNode(new RuntimeWorkflowDebugService.NodeDebugRequest(
                "missing", "body-slug", "body-name", "GENERAL", "body-project", "GRAPH_SPEC", null,
                ANSWER, null, "answer", "hello", forgedInput()));
        assertFalse(result.success());
        assertEquals("WORKFLOW_NOT_FOUND", result.errorCode());
        verifyNoInteractions(executor);
        assertEquals(0, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_run", Integer.class));
    }

    @Test
    void malformedSessionCandidateStillReturnsAFailedSession() {
        var result = sessions.create(OWNER, new RuntimeExecutableDebugSessionService.CreateRequest(
                "WORKFLOW_WORKING_COPY", Map.of("graphSpecJson", "{broken"), "hello", Map.of(), Map.of()));
        assertEquals("FAILED", result.status());
        assertEquals("FAILED", run(result.traceId()).getStatus());
        verifyNoInteractions(executor);
        assertNotNull(database.mapper(RuntimeExecutableDebugSessionMapper.class).selectById(result.sessionId()));
    }

    @Test
    void savedTargetWithoutAnyGraphRetainsTheRequiredGraphError() {
        saved(7L, "orders", null);
        var result = debug.debugRun(request("wf-1", null));
        assertFalse(result.success());
        assertEquals("WORKFLOW_DEBUG_GRAPH_REQUIRED", result.errorCode());
        assertAttribution(result.traceId(), 7L, "orders", "订单流程", 1);
        verifyNoInteractions(executor);
    }

    private void saved(Long projectId, String projectCode, String graph) {
        saveDefinition("wf-1", projectId, projectCode, graph);
    }

    @ParameterizedTest
    @ValueSource(strings = {"WAITING_USER", "RUNNING", "SUCCESS"})
    void rawDebugRequestCannotAttachAnotherWorkflowToAnExistingTrace(String rootStatus) throws Exception {
        saved(7L, "orders", WAIT);
        saveDefinition("wf-other", 8L, "billing", ANSWER);
        var session = sessions.create(OWNER, new RuntimeExecutableDebugSessionService.CreateRequest(
                "WORKFLOW_WORKING_COPY", Map.of("workflowId", "wf-1"), "hello", Map.of(), Map.of()));
        assertEquals("SUSPENDED", session.status());
        database.jdbc().update("UPDATE runtime_trace_span SET status = ? WHERE trace_id = ? AND parent_span_id IS NULL",
                rootStatus, session.traceId());
        var beforeRun = json.writeValueAsString(run(session.traceId()));
        var beforeSpans = database.jdbc().queryForList(
                "SELECT * FROM runtime_trace_span WHERE trace_id = ? ORDER BY id", session.traceId());
        var result = debug.debugRun(new RuntimeWorkflowDebugService.DebugRunRequest(
                "wf-other", null, null, null, null, null, null, ANSWER, null, "hello", Map.of(),
                Map.of("traceId", session.traceId(), "runId", session.runId(), "sessionId", session.sessionId(),
                        "entryNodeId", "answer")));
        assertTrue(result.success(), result.errorMessage());
        assertNotEquals(session.traceId(), result.traceId());
        assertNotEquals(session.runId(), result.runId());
        assertEquals("wf-other", run(result.traceId()).getWorkflowId());
        assertEquals("billing", run(result.traceId()).getProjectCode());
        assertEquals(beforeRun, json.writeValueAsString(run(session.traceId())));
        assertEquals(beforeSpans, database.jdbc().queryForList(
                "SELECT * FROM runtime_trace_span WHERE trace_id = ? ORDER BY id", session.traceId()));
        assertEquals("SUSPENDED", sessions.get(OWNER, session.sessionId()).status());
    }

    @Test
    void repeatedRawRequestsReceiveIndependentServerRunIdentifiers() {
        var options = Map.<String, Object>of("traceId", "caller-trace", "runId", "caller-run");
        var request = new RuntimeWorkflowDebugService.DebugRunRequest(null, null, null, "GENERAL", null,
                "GRAPH_SPEC", null, ANSWER, null, "hello", Map.of(), options);
        var first = debug.debugRun(request);
        var second = debug.debugRun(request);
        assertTrue(first.success());
        assertTrue(second.success());
        assertNotEquals("caller-trace", first.traceId());
        assertNotEquals("caller-run", first.runId());
        assertNotEquals(first.traceId(), second.traceId());
        assertNotEquals(first.runId(), second.runId());
        assertEquals(2, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_run", Integer.class));
        assertEquals(2, database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM runtime_trace_span WHERE parent_span_id IS NULL", Integer.class));
    }

    @Test
    void newSessionCannotUseCallerContinuationOptionsToSkipItsGraphEntry() {
        saved(7L, "orders", WAIT);
        var result = sessions.create(OWNER, new RuntimeExecutableDebugSessionService.CreateRequest(
                "WORKFLOW_WORKING_COPY", Map.of("workflowId", "wf-1"), "hello", Map.of(),
                Map.of("traceId", "caller-trace", "runId", "caller-run", "entryNodeId", "answer")));
        assertEquals("SUSPENDED", result.status());
        assertEquals("ask", result.currentNodeId());
        assertNotEquals("caller-trace", result.traceId());
        var resumed = sessions.submit(OWNER, result.sessionId(), new RuntimeExecutableDebugSessionService.SubmitRequest(
                "submit", Map.of("q", "accepted"), null));
        assertEquals("COMPLETED", resumed.status());
        assertEquals(result.runId(), resumed.runId());
        assertEquals(result.traceId(), resumed.traceId());
        assertEquals("frozen answer", resumed.answer());
        assertEquals(1, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_run", Integer.class));
    }

    private void saveDefinition(String id, Long projectId, String projectCode, String graph) {
        var workflow = new RuntimeWorkflowDefinitionEntity();
        workflow.setId(id);
        workflow.setKeySlug("wf-1".equals(id) ? "orders-flow" : id);
        workflow.setName("订单流程");
        workflow.setProjectId(projectId);
        workflow.setProjectCode(projectCode);
        workflow.setWorkflowKind("GENERAL");
        workflow.setExecutionEngine("GRAPH_SPEC");
        workflow.setGraphSpecJson(graph);
        workflows.insert(workflow);
    }

    private RuntimeWorkflowDebugService.DebugRunRequest request(String id, String graph) {
        return new RuntimeWorkflowDebugService.DebugRunRequest(id, "body-slug", "body-name", "GENERAL",
                "body-project", "GRAPH_SPEC", null, graph, null, "hello", forgedInput(), Map.of());
    }

    private Map<String, Object> forgedInput() {
        return Map.of("projectId", 999L, "projectCode", "input-project", "tenantId", "body-tenant", "userId", "body-user");
    }

    private RuntimeRunEntity run(String traceId) {
        return database.mapper(RuntimeRunMapper.class).selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<RuntimeRunEntity>lambdaQuery()
                        .eq(RuntimeRunEntity::getTraceId, traceId));
    }

    private void assertAttribution(String traceId, Long projectId, String projectCode, String name, int spanCount) {
        var run = run(traceId);
        assertEquals(projectId, run.getProjectId());
        assertEquals(projectCode, run.getProjectCode());
        assertEquals(name, run.getWorkflowName());
        assertEquals("orders-flow", run.getWorkflowKeySlug());
        assertEquals("wf-1", run.getWorkflowId());
        assertNull(run.getTenantId());
        assertNull(run.getUserId());
        var spans = database.mapper(RuntimeTraceSpanMapper.class).selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<RuntimeTraceSpanEntity>lambdaQuery()
                        .eq(RuntimeTraceSpanEntity::getTraceId, traceId));
        assertEquals(spanCount, spans.size());
        for (var span : spans) {
            assertEquals(projectCode, span.getProjectCode());
            assertEquals(projectCode, span.getAppId());
            assertEquals("wf-1", span.getAgentId());
            assertEquals(name, span.getAgentName());
            assertNull(span.getTenantId());
            assertNull(span.getExternalUserId());
        }
    }

    private void assertGraphSnapshot(String traceId, String graph) throws Exception {
        String canonical = new RuntimeWorkflowDocumentCanonicalizer(json).canonicalizeGraphSpecJson(graph);
        var snapshot = json.readTree(run(traceId).getSnapshotJson());
        assertEquals(WorkflowTraceSanitizer.sanitizeWorkflowSnapshot(canonical).get("graphSpecDigest"),
                snapshot.path("graphSpecDigest").asText());
        assertTrue(snapshot.path("nodeCount").asInt() > 0);
    }
}
