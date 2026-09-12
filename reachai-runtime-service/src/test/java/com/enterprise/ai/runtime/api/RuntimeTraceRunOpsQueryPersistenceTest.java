package com.enterprise.ai.runtime.api;

import com.enterprise.ai.runtime.runops.RuntimeGuardDecisionLogMapper;
import com.enterprise.ai.runtime.runops.RuntimeRunEntity;
import com.enterprise.ai.runtime.runops.RuntimeRunMapper;
import com.enterprise.ai.runtime.runops.RuntimeRunQuery;
import com.enterprise.ai.runtime.runops.RuntimeRunReader;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsQueryService;
import com.enterprise.ai.runtime.trace.RuntimeToolCallLogEntity;
import com.enterprise.ai.runtime.trace.RuntimeToolCallLogMapper;
import com.enterprise.ai.runtime.trace.RuntimeTraceNodeView;
import com.enterprise.ai.runtime.trace.RuntimeTraceQueryService;
import com.enterprise.ai.runtime.trace.RuntimeTraceRecordQuery;
import com.enterprise.ai.runtime.trace.RuntimeTraceRecords;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanEntity;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import com.enterprise.ai.runtime.support.RuntimeSqlQueryRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeTraceRunOpsQueryPersistenceTest {

    private AnnotationConfigApplicationContext context;
    private RuntimeQueryTestDatabase database;
    private RuntimeSqlQueryRecorder sql;
    private RuntimeRunMapper runs;
    private RuntimeTraceSpanMapper spans;
    private RuntimeToolCallLogMapper tools;
    private RuntimeTraceQueryService trace;
    private RuntimeRunOpsQueryService runOps;
    private final LocalDateTime now = LocalDateTime.now().withNano(0);

    @BeforeEach
    void databaseAndOwningQueries() throws Exception {
        database = new RuntimeQueryTestDatabase(
                List.of("runtime_run", "runtime_trace_span", "runtime_tool_call_log", "runtime_guard_decision_log"),
                RuntimeRunMapper.class, RuntimeTraceSpanMapper.class, RuntimeToolCallLogMapper.class, RuntimeGuardDecisionLogMapper.class);
        sql = new RuntimeSqlQueryRecorder();
        database.addInterceptor(sql);
        context = new AnnotationConfigApplicationContext();
        context.registerBean(RuntimeRunMapper.class, () -> database.mapper(RuntimeRunMapper.class));
        context.registerBean(RuntimeTraceSpanMapper.class, () -> database.mapper(RuntimeTraceSpanMapper.class));
        context.registerBean(RuntimeToolCallLogMapper.class, () -> database.mapper(RuntimeToolCallLogMapper.class));
        context.registerBean(RuntimeGuardDecisionLogMapper.class, () -> database.mapper(RuntimeGuardDecisionLogMapper.class));
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
        context.register(RuntimeTraceQueryService.class, RuntimeRunOpsQueryService.class, RuntimeRunReader.class);
        context.refresh();
        runs = context.getBean(RuntimeRunMapper.class);
        spans = context.getBean(RuntimeTraceSpanMapper.class);
        tools = context.getBean(RuntimeToolCallLogMapper.class);
        trace = context.getBean(RuntimeTraceQueryService.class);
        runOps = context.getBean(RuntimeRunOpsQueryService.class);
        assertSame(trace, context.getBean(RuntimeTraceRecordQuery.class));
    }

    @AfterEach
    void close() {
        if (context != null) context.close();
        if (database != null) database.close();
    }

    @Test
    void traceRecordsStayOrderedIsolatedAndDetachedAcrossBothViews() {
        runs.insert(run("trace-1", "user-1", now));
        var late = span(1L, "trace-1", now.plusSeconds(2));
        spans.insert(late);
        spans.insert(span(3L, "trace-1", now));
        spans.insert(span(2L, "trace-1", now));
        spans.insert(span(4L, "another-trace", now));
        tools.insert(tool(10L, "trace-1", now.plusSeconds(3)));
        tools.insert(tool(20L, "trace-1", now.plusSeconds(1)));
        tools.insert(tool(30L, "another-trace", now));

        RuntimeTraceRecords records = trace.findRecords(" trace-1 ");
        assertEquals(records.spans(), trace.findSpans(" trace-1 "));
        assertEquals(List.of(2L, 3L, 1L), records.spans().stream().map(RuntimeTraceRecords.Span::id).toList());
        assertEquals(List.of(20L, 10L), records.toolCalls().stream().map(RuntimeTraceRecords.ToolCall::id).toList());
        assertThrows(UnsupportedOperationException.class, () -> records.spans().clear());
        assertThrows(UnsupportedOperationException.class, () -> records.toolCalls().clear());
        late.setOutputSummary("later database value");
        spans.updateById(late);
        assertEquals("original output", records.spans().get(2).outputSummary());

        var legacy = trace.getTraceDetail(" trace-1 ").orElseThrow();
        assertEquals(List.of(2L, 3L, 20L, 1L, 10L), legacy.nodes().stream().map(RuntimeTraceNodeView::id).toList());
        assertEquals(List.of(Map.of("docId", "kb-1")), legacy.nodes().get(2).retrievalCandidates());
        var detail = runOps.detail(" trace-1 ");
        assertEquals(List.of(2L, 3L, 1L), detail.spans().stream().map(item -> item.id()).toList());
        assertEquals("WAITING_USER", detail.spans().get(1).status());
        assertEquals(Map.of("route", "wait"), detail.spans().get(1).metadata());
        assertEquals("later database value", detail.spans().get(2).outputSummary());
        assertEquals(List.of(20L, 10L), detail.toolCalls().stream().map(item -> item.id()).toList());
        assertEquals(0, detail.toolCalls().get(0).elapsedMs());
    }

    @Test
    void legacyNodeTimeFallbackAndEmptyTraceDoNotBroadenTheQuery() {
        var missingStart = span(1L, "trace-fallback", null);
        missingStart.setCreatedAt(now);
        spans.insert(missingStart);
        assertEquals(now, trace.getTraceDetail("trace-fallback").orElseThrow().nodes().get(0).createdAt());
        assertTrue(trace.findRecords(" ").spans().isEmpty());
        assertTrue(trace.findRecords(null).toolCalls().isEmpty());
        assertTrue(trace.getTraceDetail(" ").isEmpty());
        assertTrue(trace.getTraceDetail("absent").isEmpty());
    }

    @Test
    void legacyRecentListRetainsItsOwnLimitsWindowAndUserFilter() {
        for (int i = 1; i <= 205; i++) runs.insert(run("trace-" + i, "user-1", now.minusHours(1)));
        runs.insert(run("other-user", "user-2", now));
        runs.insert(run("history-2", "history-user", now.minusDays(2)));
        runs.insert(run("history-29", "history-user", now.minusDays(29)));
        runs.insert(run("history-31", "history-user", now.minusDays(31)));

        var recent = runOps.listRecentTraces(" user-1 ", 999, 999);
        assertEquals(200, recent.size());
        assertEquals("trace-205", recent.get(0).traceId());
        assertEquals("trace-6", recent.get(199).traceId());
        assertTrue(recent.stream().allMatch(item -> "user-1".equals(item.userId())));
        assertEquals("工作流快照", recent.get(0).agentName());
        assertEquals("session-user-1", recent.get(0).sessionId());
        assertEquals("API", recent.get(0).intentType());
        assertEquals(0, recent.get(0).callCount());
        assertEquals(1L, recent.get(0).successCount());
        assertEquals(1, runOps.listRecentTraces("user-1", 0, 0).size());
        assertEquals(2, runOps.listRecentTraces("history-user", 10, 999).size());
        assertTrue(runOps.listRecentTraces("history-user", 10, 0).isEmpty());
        assertEquals(100, runOps.recent(null, null, null, null, null, "user-1", 999, 999).size());
        assertEquals(3, runOps.recent(null, null, null, null, null, "history-user", 10, 999).size());
    }

    @Test
    void rootRunQueryKeepsTraceIdentityAndStatusInAnImmutableProjection() {
        RuntimeRunEntity target = run("target-trace", "user-1", now);
        target.setProjectCode("orders");
        target.setPageInstanceId("page-instance-1");
        target.setEntryType("EMBED");
        runs.insert(target);
        runs.insert(run("another-trace", "user-2", now));
        RuntimeRunQuery query = context.getBean(RuntimeRunQuery.class);

        RuntimeRunQuery.Run snapshot = query.findByTraceId(" target-trace ").orElseThrow();
        assertEquals(new RuntimeRunQuery.Run("target-trace", "orders",
                "session-user-1", "page-instance-1", "EMBED", "COMPLETED"), snapshot);
        target.setStatus("FAILED");
        runs.updateById(target);
        assertEquals("COMPLETED", snapshot.status());
        assertEquals("FAILED", query.findByTraceId("target-trace").orElseThrow().status());
        assertTrue(query.findByTraceId("missing").isEmpty());
        assertTrue(query.findByTraceId(" ").isEmpty());
        assertTrue(query.findByTraceId(null).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 100})
    void diagnosticsUsesFourQueriesForTheSelectedRuns(int count) {
        for (int index = 0; index < count; index++) {
            String traceId = "picked-" + index;
            RuntimeRunEntity root = run(traceId, "user-1", now.minusSeconds(index));
            root.setProjectCode("project-picked");
            root.setAgentId("agent-1");
            root.setAgentConfigVersionId(11L);
            runs.insert(root);
            spans.insert(span(index + 1L, traceId, now.minusSeconds(index)));
            var call = tool(index + 1L, traceId, now.minusSeconds(index));
            call.setSuccess(true);
            tools.insert(call);
        }
        RuntimeRunEntity foreign = run("picked-foreign", "user-2", now);
        foreign.setProjectCode("project-other");
        runs.insert(foreign);
        tools.insert(tool(count + 1L, foreign.getTraceId(), now));
        sql.clear();

        var diagnostics = runOps.diagnostics("project-picked", "completed", "workflow", "api",
                "agent-1", "user-1", "picked-", 100, 7);

        assertEquals(4, sql.queries().size(), "diagnostic SQL count for " + count + " selected roots");
        assertEquals(count, diagnostics.versionComparisons().get(0).runCount());
        assertTrue(diagnostics.failureClusters().isEmpty());
        assertTrue(diagnostics.versionComparisons().stream().allMatch(row -> row.toolErrorCount() == 0));
    }

    @Test
    void traceBatchMatchesSingleQueriesWithoutMixingOrderingOrSparseEvidence() {
        spans.insert(span(3L, "trace-1", now));
        spans.insert(span(2L, "trace-1", now));
        spans.insert(span(1L, "trace-1", now.plusSeconds(1)));
        spans.insert(span(4L, "unrequested", now));
        tools.insert(tool(2L, "trace-1", now));
        tools.insert(tool(1L, "trace-1", now));
        tools.insert(tool(3L, "trace-2", now));
        tools.insert(tool(4L, "unrequested", now));
        sql.clear();

        var batch = trace.findRecordsByTraceIds(List.of(" trace-1 ", "trace-2", "missing", "trace-1", " "));

        assertEquals(2, sql.queries().size());
        assertEquals(List.of("trace-1", "trace-2", "missing"), new ArrayList<>(batch.keySet()));
        assertEquals(List.of(2L, 3L, 1L), batch.get("trace-1").spans().stream().map(RuntimeTraceRecords.Span::id).toList());
        assertEquals(List.of(1L, 2L), batch.get("trace-1").toolCalls().stream().map(RuntimeTraceRecords.ToolCall::id).toList());
        assertEquals(trace.findRecords("trace-1"), batch.get("trace-1"));
        assertEquals(trace.findRecords("trace-2"), batch.get("trace-2"));
        assertTrue(batch.get("trace-2").spans().isEmpty());
        assertEquals(new RuntimeTraceRecords(List.of(), List.of()), batch.get("missing"));
        assertThrows(UnsupportedOperationException.class, batch::clear);
        assertThrows(UnsupportedOperationException.class, () -> batch.get("trace-1").spans().clear());
    }

    @Test
    void emptyAndOversizedTraceBatchesDoNotQueryPersistence() {
        assertTrue(trace.findRecordsByTraceIds(null).isEmpty());
        assertTrue(trace.findRecordsByTraceIds(Arrays.asList(null, " ", "\t")).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> trace.findRecordsByTraceIds(
                IntStream.range(0, 101).mapToObj(index -> "trace-" + index).toList()));
        assertTrue(sql.queries().isEmpty());
        assertEquals(1, trace.findRecordsByTraceIds(Collections.nCopies(101, "same-trace")).size());
        assertEquals(2, sql.queries().size());
    }

    @Test
    void emptyDiagnosticsDoesNotLoadOrphanChildEvidence() {
        spans.insert(span(1L, "orphan", now));
        tools.insert(tool(1L, "orphan", now));

        var diagnostics = runOps.diagnostics(null, null, null, null, null, null, 100, 7);

        assertTrue(diagnostics.failureClusters().isEmpty());
        assertTrue(diagnostics.versionComparisons().isEmpty());
        assertEquals(1, sql.queries().size());
    }

    @Test
    void diagnosticGuardBatchKeepsEachTraceLimitAndEarliestDecisionOrder() {
        runs.insert(run("trace-a", "user-1", now));
        runs.insert(run("trace-b", "user-1", now));
        List<Object[]> decisions = new ArrayList<>();
        for (int id = 1; id <= 1001; id++) {
            decisions.add(new Object[] {id, "trace-a", "ALLOW", "allowed", now});
        }
        decisions.add(new Object[] {2002L, "trace-b", "DENY", "later", now});
        decisions.add(new Object[] {2001L, "trace-b", "DENY", "first", now});
        database.jdbc().batchUpdate("""
                INSERT INTO runtime_guard_decision_log
                    (id, trace_id, decision, reason, created_at, decision_type, target_kind, target_name)
                VALUES (?, ?, ?, ?, ?, 'ACL', 'TOOL', 'orders.read')
                """, decisions);

        var diagnostics = runOps.diagnostics(null, null, null, null, null, "user-1", 100, 7);

        assertEquals(4, sql.queries().size());
        assertEquals(502, sql.queries().stream().filter(query -> query.sql().contains("runtime_guard_decision_log"))
                .findFirst().orElseThrow().rowCount());
        assertEquals(1, diagnostics.failureClusters().size());
        assertEquals("trace-b", diagnostics.failureClusters().get(0).sampleTraceId());
        assertEquals("first", diagnostics.failureClusters().get(0).errorMessage());
    }

    @Test
    void completedWorkflowResumeDoesNotTurnTheImmutablePauseAuditIntoAFailure() {
        runs.insert(run("resumed", "user-1", now));
        var parent = span(1L, "resumed", now);
        parent.setSpanType("WORKFLOW_TOOL");
        parent.setToolName("orders.read");
        parent.setNodeId("workflow-1");
        parent.setEndedAt(now.plusSeconds(1));
        parent.setMetadataJson("{\"workflowId\":\"workflow-1\",\"workflowVersionId\":7}");
        spans.insert(parent);
        var child = span(2L, "resumed", now);
        child.setSpanType("WORKFLOW_NODE");
        child.setParentSpanId(parent.getSpanId());
        child.setMetadataJson("{\"workflowId\":\"workflow-1\",\"workflowVersionId\":7,\"interactionId\":\"interaction-1\"}");
        spans.insert(child);
        var pause = tool(1L, "resumed", now);
        pause.setSuccess(false);
        pause.setRetrievalTraceJson("""
                {"workflowId":"workflow-1","workflowVersionId":7,"code":"RUNTIME_GRAPH_INTERACTION_WAITING",
                 "summary":{"workflowNodeTraces":[{"nodeId":"node-2","status":"WAITING_USER","interactionId":"interaction-1"}]}}
                """);
        tools.insert(pause);

        var detail = runOps.detail("resumed");
        assertEquals("SUCCESS", detail.toolCalls().get(0).status());
        assertEquals("span-1", detail.toolCalls().get(0).statusSourceSpanId());
        assertFalse(detail.toolCalls().get(0).success());
        assertTrue(detail.repairHints().isEmpty(), "the completed resume must not suggest repairing a failed tool");
        var diagnostics = runOps.diagnostics(null, null, null, null, null, "user-1", 100, 7);
        assertTrue(diagnostics.failureClusters().isEmpty());
        assertTrue(diagnostics.versionComparisons().stream().allMatch(row -> row.toolErrorCount() == 0));
        assertEquals(pause.getRetrievalTraceJson(), tools.selectById(1L).getRetrievalTraceJson());
        assertFalse(tools.selectById(1L).getSuccess(), "the original pause observation stays immutable");
    }

    private RuntimeRunEntity run(String traceId, String userId, LocalDateTime startedAt) {
        var run = new RuntimeRunEntity();
        run.setTraceId(traceId);
        run.setRunType("WORKFLOW");
        run.setEntryType("API");
        run.setStatus("COMPLETED");
        run.setAgentName(" ");
        run.setWorkflowName("工作流快照");
        run.setSessionId("session-" + userId);
        run.setUserId(userId);
        run.setStartedAt(startedAt);
        run.setEndedAt(startedAt.plusSeconds(1));
        return run;
    }

    private RuntimeTraceSpanEntity span(Long id, String traceId, LocalDateTime startedAt) {
        var span = new RuntimeTraceSpanEntity();
        span.setId(id);
        span.setTraceId(traceId);
        span.setSpanId("span-" + id);
        span.setSpanType("GRAPH_NODE");
        span.setNodeId("node-" + id);
        span.setStatus(id == 3L ? "WAITING_USER" : "SUCCESS");
        span.setMetadataJson("{\"route\":\"wait\"}");
        span.setOutputSummary("original output");
        span.setStartedAt(startedAt);
        return span;
    }

    private RuntimeToolCallLogEntity tool(Long id, String traceId, LocalDateTime createdAt) {
        var tool = new RuntimeToolCallLogEntity();
        tool.setId(id);
        tool.setTraceId(traceId);
        tool.setToolName("orders.read");
        tool.setRetrievalTraceJson("[{\"docId\":\"kb-1\"}]");
        tool.setCreateTime(createdAt);
        return tool;
    }
}
