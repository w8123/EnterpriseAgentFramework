package com.enterprise.ai.runtime.trace;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigSnapshot;
import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutor;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.runops.RuntimeRunLifecycleService;
import com.enterprise.ai.runtime.supervisor.SupervisorExecutionTraceService;
import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDebugService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDocumentCanonicalizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RuntimeTraceEvidencePersistenceTest {
    private static final LocalDateTime START = LocalDateTime.of(2026, 9, 6, 12, 0);
    private static final String GRAPH = """
            {"schemaVersion":2,"entryNodeId":"ask","exitNodeIds":["ask"],
             "nodes":[{"id":"ask","type":"USER_INPUT"}],"edges":[]}
            """;
    private RuntimeQueryTestDatabase database;
    private RuntimeTraceSpanMapper spans;
    private RuntimeToolCallLogMapper tools;
    private RuntimeTraceRootService roots;
    private RuntimeTraceEvidenceWriter writer;
    private RuntimeGraphSpecExecutor executor;
    private SupervisorExecutionTraceService supervisor;
    private RuntimeWorkflowDebugService debug;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        database = new RuntimeQueryTestDatabase(List.of("runtime_trace_span", "runtime_tool_call_log"),
                RuntimeTraceSpanMapper.class, RuntimeToolCallLogMapper.class);
        spans = spy(database.mapper(RuntimeTraceSpanMapper.class));
        tools = spy(database.mapper(RuntimeToolCallLogMapper.class));
        writer = new RuntimeTraceEvidenceWriter(spans, tools);
        roots = new RuntimeTraceRootService(spans, json);
        var runs = mock(RuntimeRunLifecycleService.class);
        supervisor = new SupervisorExecutionTraceService(writer,
                runs, json, roots, new RuntimeTraceSpanTerminationService(spans));
        executor = mock(RuntimeGraphSpecExecutor.class);
        var definitions = mock(RuntimeWorkflowDefinitionService.class);
        var workflow = new com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionEntity();
        workflow.setId("wf-1");
        workflow.setKeySlug("workflow-one");
        workflow.setName("测试流程");
        workflow.setProjectCode("orders");
        workflow.setExecutionEngine("GRAPH_SPEC");
        when(definitions.findById("wf-1")).thenReturn(java.util.Optional.of(workflow));
        debug = new RuntimeWorkflowDebugService(definitions, executor, runs,
                writer, json, new RuntimeWorkflowDocumentCanonicalizer(json), roots, new RuntimeTraceSpanTerminationService(spans));
    }

    @AfterEach
    void close() { if (database != null) database.close(); }

    @ParameterizedTest
    @CsvSource({"true,OK,SUCCESS", "false,TOOL_FAILED,FAILED",
            "false,RUNTIME_GRAPH_INTERACTION_WAITING,WAITING_USER", "false,BUSINESS_STOP,BUSINESS_TERMINAL"})
    void workflowToolAndNodeEvidencePreserveStatusHierarchyAndTrustedIdentity(
            boolean success, String code, String expected) throws Exception {
        var node = Map.<String, Object>of("nodeId", "节点一", "nodeType", "HTTP_REQUEST", "status", expected,
                "qualifiedName", "orders:get", "attempt", 2, "latencyMs", 12,
                "traceSummary", Map.of("statusCode", 200, "body", "private-http-body"));
        var result = Map.<String, Object>of("workflowNodeTraces", List.of(node),
                "outcomeClass", "BUSINESS_TERMINAL".equals(expected) ? expected : "NORMAL");
        supervisor.workflow(new SupervisorExecutionTraceService.TraceHandle("trace-1", "root-1", null, START),
                agent(), config(), Map.of("message", "private-question", "userId", "body-attacker",
                        "tenantId", "body-tenant", "projectCode", "body-project"),
                "wf_tool", "wf-1", 11L, "3", Map.of("password", "private-password", "message", "private-argument"),
                success, code, "private-answer", 42, result,
                WorkflowExecutionIdentity.fromAgent("tenant-a", 7L, "项目一", "trusted-user"));

        var parent = spans.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.<RuntimeTraceSpanEntity>lambdaQuery()
                .eq(RuntimeTraceSpanEntity::getSpanType, "WORKFLOW_TOOL"));
        var child = spans.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.<RuntimeTraceSpanEntity>lambdaQuery()
                .eq(RuntimeTraceSpanEntity::getSpanType, "WORKFLOW_NODE"));
        assertEquals("root-1", parent.getParentSpanId());
        assertEquals(parent.getSpanId(), child.getParentSpanId());
        assertEquals(expected, parent.getStatus());
        assertEquals(expected, child.getStatus());
        assertEquals("LANGGRAPH4J", child.getRuntimeType());
        assertEquals("节点一", child.getNodeId());
        assertEquals("项目一", child.getProjectCode());
        assertEquals("tenant-a", child.getTenantId());
        assertEquals("tenant-a", parent.getTenantId());
        assertEquals("测试 Agent", child.getAgentName());
        assertEquals(2, json.readTree(child.getMetadataJson()).path("attempt").asInt());
        assertEquals("WAITING_USER".equals(expected), child.getEndedAt() == null);
        assertEquals("WAITING_USER".equals(expected), parent.getEndedAt() == null);
        assertNull(child.getExternalUserId());
        var tool = tools.selectOne(null);
        assertEquals("tenant-a", tool.getTenantId());
        assertEquals("项目一", tool.getProjectCode());
        assertEquals("trusted-user", tool.getUserId());
        assertEquals("trusted-user", tool.getExternalUserId());
        assertEquals("trusted-user", tool.getGlobalUserId());
        assertEquals(42, tool.getElapsedMs());
        assertFalse(storedStrings().contains("private-"));
        assertFalse(storedStrings().contains("body-attacker"));
    }

    @Test
    void debugWaitingNodesRetainInteractionEvidenceAndOmitRawBodies() {
        var execution = new RuntimeGraphSpecExecutionResult(false, "RUNTIME_GRAPH_INTERACTION_WAITING",
                "private-answer", "ask", "USER_INPUT", List.of(),
                Map.of("interactionId", "interaction-1", "workflowNodeTraces", List.of(Map.of(
                        "nodeId", "ask", "nodeType", "USER_INPUT", "status", "WAITING_USER",
                        "traceSummary", Map.of("body", "private-http-body")))));
        when(executor.execute(anyString(), anyMap(), any(), any(), any(), any())).thenReturn(execution);

        debug.debugRun(request(Map.of("traceId", "debug-trace")));

        var child = spans.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.<RuntimeTraceSpanEntity>lambdaQuery()
                .eq(RuntimeTraceSpanEntity::getSpanType, "WORKFLOW_NODE"));
        var root = spans.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.<RuntimeTraceSpanEntity>lambdaQuery()
                .isNull(RuntimeTraceSpanEntity::getParentSpanId));
        assertEquals(root.getSpanId(), child.getParentSpanId());
        assertEquals("WAITING_USER", root.getStatus());
        assertEquals("WAITING_USER", child.getStatus());
        assertNull(child.getEndedAt());
        assertTrue(child.getMetadataJson().contains("interaction-1"));
        assertTrue(child.getMetadataJson().contains("workflowKeySlug"));
        assertFalse(storedStrings().contains("private-"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCESS", "FAILED", "TIMEOUT"})
    void waitingNodeCompletionOnlyChangesItsOwnOpenInterval(String terminalStatus) {
        for (int index = 0; index < 7; index++) {
            writer.appendChild(RuntimeTraceEvidenceWriter.ChildSpan.builder()
                    .traceId(index == 1 ? "other-trace" : "trace-1").spanId("span-" + index)
                    .parentSpanId(index == 2 ? "other-parent" : "parent-1")
                    .nodeId(index == 3 ? "other-node" : "ask")
                    .spanType(index == 4 ? "PLAN" : "WORKFLOW_NODE")
                    .status(index == 5 ? "FAILED" : "WAITING_USER")
                    .startedAt(index == 6 ? START.plusMinutes(10) : START)
                    .endedAt(index == 5 ? START.plusSeconds(1) : null)
                    .metadataJson("{\"interactionId\":\"original-interaction\"}").createdAt(START).build());
        }
        var before = database.jdbc().queryForList("SELECT * FROM runtime_trace_span ORDER BY id");
        var termination = new RuntimeTraceSpanTerminationService(spans);
        assertThrows(IllegalArgumentException.class, () -> termination.finishWaitingWorkflowNode(
                "trace-1", "parent-1", "ask", "WAITING_USER", null, START.plusSeconds(5)));
        assertEquals(before, database.jdbc().queryForList("SELECT * FROM runtime_trace_span ORDER BY id"));
        assertEquals(1, termination.finishWaitingWorkflowNode(
                "trace-1", "parent-1", "ask", terminalStatus, "SUCCESS".equals(terminalStatus) ? null : "NODE_FAILED", START.plusSeconds(5)));
        var after = database.jdbc().queryForList("SELECT * FROM runtime_trace_span ORDER BY id");
        assertEquals(terminalStatus, after.get(0).get("status"));
        assertNotNull(after.get(0).get("ended_at"));
        assertEquals(before.get(0).get("metadata_json"), after.get(0).get("metadata_json"));
        assertEquals(before.get(0).get("started_at"), after.get(0).get("started_at"));
        assertEquals(before.subList(1, 7), after.subList(1, 7));
        assertEquals(0, termination.finishWaitingWorkflowNode(
                "trace-1", "parent-1", "ask", "ERROR", "LATE_ERROR", START.plusSeconds(6)));
        assertEquals(after, database.jdbc().queryForList("SELECT * FROM runtime_trace_span ORDER BY id"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCESS", "FAILED", "ERROR", "CANCELLED", "TIMEOUT"})
    void debugContinuationCannotReopenATerminalRootBeforeExecuting(String status) {
        seedWorkflow(status);
        assertThrows(IllegalStateException.class,
                () -> debug.resumeSessionDebug(debug.captureDefinition(request(Map.of())),
                        new RuntimeWorkflowDebugService.DebugInput("hello", Map.of(), Map.of()),
                        new RuntimeWorkflowDebugService.DebugContinuation(
                                new RuntimeWorkflowDebugService.DebugRunReference("run-1", "trace-1"), "ask"),
                        null, null));
        assertEquals(status, spans.selectById(1L).getStatus());
        verifyNoInteractions(executor);
    }

    @ParameterizedTest
    @ValueSource(strings = {"WAITING_USER", "WAITING_APPROVAL"})
    void workflowResumeClearsTheStoredWaitAndErrorFields(String status) {
        seedWorkflow(status);
        assertNotNull(roots.resumeWorkflow("trace-1"));
        var root = spans.selectById(1L);
        assertEquals("RUNNING", root.getStatus());
        assertNull(root.getEndedAt());
        assertNull(root.getLatencyMs());
        assertNull(root.getErrorCode());
        assertNull(root.getErrorMessage());
    }

    @Test
    void workflowResumeDoesNotOverrideExpiryCommittedAfterItsRead() {
        seedWorkflow("WAITING_USER");
        doAnswer(call -> {
            Object before = call.callRealMethod();
            database.jdbc().update("UPDATE runtime_trace_span SET status = 'TIMEOUT' WHERE id = 1");
            return before;
        }).when(spans).selectOne(any());
        assertThrows(IllegalStateException.class, () -> roots.resumeWorkflow("trace-1"));
        assertEquals("TIMEOUT", spans.selectById(1L).getStatus());
    }

    @ParameterizedTest
    @CsvSource({"null,WORKFLOW_NODE", "child,WORKFLOW_NODE", "parent,SUPERVISOR"})
    void childWriterRejectsRootOrSelfParentingEvidence(String parent, String type) {
        var fact = RuntimeTraceEvidenceWriter.ChildSpan.builder().traceId("trace-1").spanId("child")
                .parentSpanId("null".equals(parent) ? null : parent).spanType(type).status("SUCCESS").build();
        assertThrows(IllegalArgumentException.class, () -> writer.appendChild(fact));
        assertEquals(0, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_trace_span", Integer.class));
    }

    @Test
    void childPersistenceFailureKeepsTheExistingBestEffortToolAuditBehavior() {
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("injected span failure"))
                .when(spans).insert(any(RuntimeTraceSpanEntity.class));
        assertDoesNotThrow(() -> supervisor.workflow(
                new SupervisorExecutionTraceService.TraceHandle("trace-1", "root-1", null, START),
                agent(), config(), Map.of("userId", "body-attacker"), "wf_tool", "wf-1", 11L, "3",
                Map.of(), true, "OK", "private-answer", 3, Map.of(), null));
        assertEquals(1, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_tool_call_log", Integer.class));
        assertNull(tools.selectOne(null).getUserId());
    }

    private RuntimeWorkflowDebugService.DebugRunRequest request(Map<String, Object> options) {
        return new RuntimeWorkflowDebugService.DebugRunRequest("wf-1", "workflow-one", "测试流程", "GENERAL",
                "项目一", "GRAPH_SPEC", null, GRAPH, null, "private-question", Map.of(), options);
    }

    private void seedWorkflow(String status) {
        database.jdbc().update("""
                INSERT INTO runtime_trace_span (id, trace_id, span_id, span_type, status, started_at,
                  ended_at, latency_ms, error_code, error_message)
                VALUES (1, 'trace-1', 'root-1', 'WORKFLOW', ?, ?, ?, 60000, 'OLD_ERROR', 'old evidence')
                """, status, START, START.plusMinutes(1));
    }

    private RuntimeAgentView agent() {
        return new RuntimeAgentView("agent-1", 7L, "项目一", "test-agent", "测试 Agent", "description",
                "PROJECT", null, true, 41L, 41L, 2, "ACTIVE", "AGENTSCOPE", 0, null, null);
    }

    private RuntimeAgentConfigSnapshot config() {
        RuntimeAgentConfigSnapshot config = RuntimeAgentConfigSnapshot.builder()
                .id(41L)
                .agentId("agent-1")
                .modelInstanceId("model-1")
                .build();
        return config;
    }

    private String storedStrings() {
        return database.jdbc().queryForList("SELECT * FROM runtime_trace_span").toString()
                + database.jdbc().queryForList("SELECT * FROM runtime_tool_call_log");
    }
}
