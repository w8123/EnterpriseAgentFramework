package com.enterprise.ai.runtime.execution;

import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanEntity;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanMapper;
import com.enterprise.ai.runtime.trace.RuntimeWorkflowInteractionTraceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

/** Reproduces the live Agent Workflow receipt whose tool/node spans remain waiting after completion. */
class RuntimeWorkflowInteractionTraceRecoveryPersistenceTest {
    private static final String GRAPH = """
            {"schemaVersion":2,"entryNodeId":"first","exitNodeIds":["answer"],"nodes":[
              {"id":"first","type":"INTERACTION"},{"id":"second","type":"INTERACTION"},
              {"id":"answer","type":"ANSWER"}],"edges":[
              {"from":"first","to":"second"},{"from":"second","to":"answer"}]}
            """;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private RuntimeQueryTestDatabase db;
    private RuntimeInteractionSessionMapper sessions;
    private RuntimeTraceSpanMapper spans;
    private RuntimeWorkflowInteractionSessionService waits;

    @BeforeEach
    void setUp() throws Exception {
        db = new RuntimeQueryTestDatabase(List.of("runtime_interaction_session", "runtime_interaction_event", "runtime_trace_span"),
                RuntimeInteractionSessionMapper.class, RuntimeInteractionEventMapper.class, RuntimeTraceSpanMapper.class);
        sessions = db.mapper(RuntimeInteractionSessionMapper.class);
        spans = spy(db.mapper(RuntimeTraceSpanMapper.class));
        waits = transactional(new RuntimeWorkflowInteractionSessionService(sessions,
                db.mapper(RuntimeInteractionEventMapper.class), json,
                new RuntimeWorkflowInteractionTraceService(spans, json)));
        waits.createWaitingSession(request("wfi_first", "first"));
        span("tool-a", "root-a", "WORKFLOW_TOOL", "wf-a", null);
        span("node-a", "tool-a", "WORKFLOW_NODE", "first", "wfi_first");
        // A different call of the same Workflow in the same trace must never be closed by this receipt.
        span("other-tool", "root-a", "WORKFLOW_TOOL", "wf-a", null);
        span("other-node", "other-tool", "WORKFLOW_NODE", "first", "wfi_other");
    }

    @AfterEach
    void close() { if (db != null) db.close(); }

    @Test
    void terminalReceiptClosesOnlyItsWaitingToolAndNodeAndRecordsResumedNodes() {
        waits.finishResume(claim(), "COMPLETED", result(false), "user-a", "RESUMED", "COMPLETED");
        assertEquals("COMPLETED", sessions.selectById("wfi_first").getStatus());
        assertEquals("SUCCESS", spanStatus("node-a"), "The consumed node must leave WAITING_USER");
        assertEquals("SUCCESS", spanStatus("tool-a"), "The completed Workflow call must leave WAITING_USER");
        assertNotNull(db.jdbc().queryForObject("SELECT ended_at FROM runtime_trace_span WHERE span_id='tool-a'", LocalDateTime.class));
        assertEquals(1, db.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_trace_span WHERE parent_span_id='tool-a' AND node_id='answer' AND status='SUCCESS'", Integer.class));
        assertEquals("WAITING_USER", spanStatus("other-tool"));
        assertEquals("WAITING_USER", spanStatus("other-node"));
    }

    @Test
    void chainedReceiptClosesTheConsumedNodeAndPersistsTheNextWaitingNodeUnderTheOriginalCall() {
        waits.completeAndCreateNext(claim(), result(true), "user-a", request("wfi_second", "second"));
        assertEquals("COMPLETED", sessions.selectById("wfi_first").getStatus());
        assertEquals("WAITING_USER", sessions.selectById("wfi_second").getStatus());
        assertEquals("SUCCESS", spanStatus("node-a"));
        assertEquals("WAITING_USER", spanStatus("tool-a"));
        assertEquals(1, db.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_trace_span WHERE parent_span_id='tool-a' AND node_id='second' AND status='WAITING_USER' AND ended_at IS NULL", Integer.class));
        assertEquals("WAITING_USER", spanStatus("other-node"));
    }

    @Test
    void cancellationClosesTheOwnedWaitWithoutInventingDownstreamExecution() {
        waits.finishResume(claim(), "CANCELLED", Map.of("status", "CANCELLED", "code", "RUNTIME_GRAPH_CANCELLED"), "user-a", "CANCELLED");
        assertEquals("CANCELLED", spanStatus("node-a"));
        assertEquals("CANCELLED", spanStatus("tool-a"));
        assertEquals(4, db.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_trace_span", Integer.class));
        assertEquals("WAITING_USER", spanStatus("other-tool"));
    }

    @Test
    void traceInsertionFailureRollsBackReceiptEventsAndEarlierSpanCompletion() {
        var current = claim();
        doThrow(new DataAccessResourceFailureException("trace unavailable")).when(spans).insert(any(RuntimeTraceSpanEntity.class));
        assertThrows(DataAccessResourceFailureException.class,
                () -> waits.finishResume(current, "COMPLETED", result(false), "user-a", "RESUMED", "COMPLETED"));
        assertEquals("RESUMING", sessions.selectById("wfi_first").getStatus());
        assertNull(sessions.selectById("wfi_first").getResultJson());
        assertEquals("WAITING_USER", spanStatus("node-a"));
        assertEquals("WAITING_USER", spanStatus("tool-a"));
        assertEquals(3, db.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_interaction_event", Integer.class));
    }

    @Test
    void traceInsertionFailureCannotLeaveADurableNextInteraction() {
        var current = claim();
        doThrow(new DataAccessResourceFailureException("trace unavailable")).when(spans).insert(any(RuntimeTraceSpanEntity.class));
        assertThrows(DataAccessResourceFailureException.class,
                () -> waits.completeAndCreateNext(current, result(true), "user-a", request("wfi_second", "second")));
        assertNull(sessions.selectById("wfi_second"));
        assertEquals("RESUMING", sessions.selectById("wfi_first").getStatus());
        assertEquals("WAITING_USER", spanStatus("node-a"));
        assertEquals(3, db.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_interaction_event", Integer.class));
    }

    @Test
    void staleReceiptCannotAppendOrRewriteTraceEvidence() {
        var current = claim();
        waits.finishResume(current, "COMPLETED", result(false), "user-a", "RESUMED", "COMPLETED");
        var before = db.jdbc().queryForList("SELECT * FROM runtime_trace_span ORDER BY id");
        assertThrows(RuntimeWorkflowInteractionSessionService.ResumeConflictException.class,
                () -> waits.finishResume(current, "COMPLETED", result(false), "user-a", "RESUMED", "COMPLETED"));
        assertEquals(before, db.jdbc().queryForList("SELECT * FROM runtime_trace_span ORDER BY id"));
    }

    @Test
    void secondResumeUsesThePersistedOriginalToolParentAndSanitizesNodeDetails() {
        waits.completeAndCreateNext(claim(), result(true), "user-a", request("wfi_second", "second"));
        var second = sessions.selectById("wfi_second");
        assertTrue(waits.claimResume(second, "attempt-b", Map.of("action", "submit", "values", Map.of()), "user-a"));
        // Recreate the owner to prove this does not depend on an in-memory parent span handle.
        var restarted = transactional(new RuntimeWorkflowInteractionSessionService(sessions,
                db.mapper(RuntimeInteractionEventMapper.class), json, new RuntimeWorkflowInteractionTraceService(spans, json)));
        restarted.finishResume(sessions.selectById("wfi_second"), "COMPLETED", Map.of("status", "COMPLETED", "code", "RUNTIME_GRAPH_EXECUTED",
                "metadata", Map.of("workflowNodeTraces", List.of(
                        Map.of("nodeId", "second", "nodeType", "INTERACTION", "status", "SUCCESS", "password", "private-secret"),
                        Map.of("nodeId", "answer", "nodeType", "ANSWER", "status", "SUCCESS", "output", "private-secret")))), "user-a", "COMPLETED");
        assertEquals("SUCCESS", spanStatus("tool-a"));
        assertEquals(0, db.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_trace_span WHERE parent_span_id='tool-a' AND ended_at IS NULL", Integer.class));
        assertFalse(db.jdbc().queryForList("SELECT metadata_json FROM runtime_trace_span").toString().contains("private-secret"));
        assertEquals("WAITING_USER", spanStatus("other-tool"));
    }

    private RuntimeWorkflowInteractionSessionService.CreateRequest request(String id, String node) {
        return new RuntimeWorkflowInteractionSessionService.CreateRequest(id, "WORKFLOW", "run-a", "trace-a",
                "wf-a", 23L, GRAPH, node, "COLLECT_INPUT", Map.of(),
                Map.of("interactionId", id, "component", "form"), Map.of(), "orders", "tenant-a", "chat-a", "user-a", 120);
    }

    private RuntimeInteractionSessionEntity claim() {
        var current = sessions.selectById("wfi_first");
        assertTrue(waits.claimResume(current, "attempt-a", Map.of("action", "submit", "values", Map.of()), "user-a"));
        return sessions.selectById("wfi_first");
    }

    private Map<String, Object> result(boolean nextWait) {
        return Map.of("status", nextWait ? "WAITING_USER" : "COMPLETED", "success", true,
                "code", nextWait ? "RUNTIME_GRAPH_INTERACTION_WAITING" : "RUNTIME_GRAPH_EXECUTED",
                "metadata", Map.of("workflowNodeTraces", List.of(
                        Map.of("nodeId", "first", "nodeType", "INTERACTION", "status", "SUCCESS"),
                        nextWait ? Map.of("nodeId", "second", "nodeType", "INTERACTION", "status", "WAITING_USER", "interactionId", "wfi_second")
                                : Map.of("nodeId", "answer", "nodeType", "ANSWER", "status", "SUCCESS"))));
    }

    private void span(String id, String parent, String type, String node, String interaction) throws Exception {
        var row = new RuntimeTraceSpanEntity();
        row.setTraceId("trace-a"); row.setSpanId(id); row.setParentSpanId(parent); row.setSpanType(type);
        row.setNodeId(node); row.setStatus("WAITING_USER"); row.setStartedAt(LocalDateTime.now().minusMinutes(1));
        row.setCreatedAt(row.getStartedAt()); row.setTenantId("tenant-a"); row.setAppId("orders");
        row.setMetadataJson(json.writeValueAsString(interaction == null ? Map.of("workflowId", "wf-a", "workflowVersionId", 23)
                : Map.of("workflowId", "wf-a", "workflowVersionId", 23, "interactionId", interaction)));
        spans.insert(row);
    }

    private String spanStatus(String id) { return db.jdbc().queryForObject("SELECT status FROM runtime_trace_span WHERE span_id=?", String.class, id); }

    @SuppressWarnings("unchecked")
    private <T> T transactional(T target) {
        ProxyFactory proxy = new ProxyFactory(target); proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(db.jdbc().getDataSource()), new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }
}
