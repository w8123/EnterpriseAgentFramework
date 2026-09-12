package com.enterprise.ai.runtime.runops;

import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Exercise the baseline table and real MyBatis SQL, including a second connection's commit. */
class RuntimeRunCompletionPersistenceTest {
    private static final LocalDateTime END = LocalDateTime.of(2026, 9, 6, 11, 0);
    private static final Map<String, Object> COUNTS = Map.of(
            "toolCallCount", 2, "guardDenyCount", 0, "approvalCount", 0, "tokenCost", 7);
    private RuntimeQueryTestDatabase database;
    private RuntimeRunMapper mapper;
    private RuntimeRunLifecycleService lifecycle;

    enum Path { AGENT, WORKFLOW, MCP }

    @BeforeEach
    void setUp() throws Exception {
        database = new RuntimeQueryTestDatabase(List.of("runtime_run", "runtime_tool_call_log", "runtime_guard_decision_log"), RuntimeRunMapper.class);
        mapper = spy(database.mapper(RuntimeRunMapper.class));
        lifecycle = new RuntimeRunLifecycleService(mapper, new ObjectMapper());
    }

    @AfterEach
    void close() {
        if (database != null) database.close();
    }

    @ParameterizedTest
    @CsvSource({"AGENT,COMPLETED", "AGENT,FAILED", "AGENT,CANCELLED", "AGENT,TIMED_OUT",
            "WORKFLOW,COMPLETED", "WORKFLOW,FAILED", "WORKFLOW,CANCELLED", "WORKFLOW,TIMED_OUT",
            "MCP,COMPLETED", "MCP,FAILED", "MCP,CANCELLED", "MCP,TIMED_OUT"})
    void lateCompletionRetainsEveryTerminalOutcome(Path path, String status) {
        seed(path.name(), status);
        finish(path, true, "OK");
        assertEvidence(status);
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void successfulCompletionExplicitlyClearsSuspensionAndErrors(Path path) {
        seed(path.name(), "SUSPENDED");
        finish(path, true, "OK");
        var current = mapper.selectById(1L);
        assertEquals("COMPLETED", current.getStatus());
        assertNull(current.getSuspensionReason());
        assertNull(current.getErrorCode());
        assertNull(current.getErrorMessage());
        assertNotNull(current.getEndedAt());
        assertEquals("[omitted]", current.getOutputSummary());
        assertEquals("{\"stable\":1}", current.getSnapshotJson());
    }

    @ParameterizedTest
    @EnumSource(value = Path.class, names = {"WORKFLOW", "MCP"})
    void completionCannotOverwriteTerminalCommitAfterItsRead(Path path) {
        seed(path.name(), "RUNNING");
        doAnswer(call -> {
            Object previous = call.callRealMethod();
            database.jdbc().update("UPDATE runtime_run SET status = 'TIMED_OUT' WHERE id = 1");
            return previous;
        }).when(mapper).selectOne(any());
        finish(path, true, "OK");
        assertEvidence("TIMED_OUT");
    }

    @ParameterizedTest
    @EnumSource(Path.class)
    void failureStillRecordsItsCodeWithoutRawPayloads(Path path) {
        seed(path.name(), "RUNNING");
        finish(path, false, "TECHNICAL_FAILURE");
        var current = mapper.selectById(1L);
        assertEquals("FAILED", current.getStatus());
        assertEquals("TECHNICAL_FAILURE", current.getErrorCode());
        assertNotNull(current.getEndedAt());
        assertNull(current.getSuspensionReason());
        assertEquals("[omitted]", current.getOutputSummary());
        assertNotNull(current.getErrorMessage());
        assertFalse(current.getErrorMessage().contains("private answer"));
        assertFalse(current.getMetadataJson().contains("privateData"));
    }

    @ParameterizedTest
    @EnumSource(value = Path.class, names = {"AGENT", "WORKFLOW"})
    void waitingClearsOldTerminalFieldsAndKeepsTheRootOpen(Path path) {
        seed(path.name(), "RUNNING");
        finish(path, false, "RUNTIME_GRAPH_INTERACTION_WAITING");
        var current = mapper.selectById(1L);
        assertEquals("SUSPENDED", current.getStatus());
        assertEquals("USER_INPUT", current.getSuspensionReason());
        assertNull(current.getEndedAt());
        assertNull(current.getLatencyMs());
        assertNull(current.getErrorCode());
        assertNull(current.getErrorMessage());
    }

    @ParameterizedTest
    @CsvSource({"AGENT,MCP", "WORKFLOW,AGENT", "MCP,WORKFLOW"})
    void completionCannotModifyAnUnrelatedRootType(Path path, String runType) {
        seed(runType, "RUNNING");
        finish(path, true, "OK");
        assertEvidence("RUNNING");
    }

    @Test
    void interactionResumeCanStillFinishTheStandaloneWorkflowThroughExecutionPort() {
        seed("WORKFLOW", "SUSPENDED");
        finish(Path.AGENT, true, "OK");
        assertEquals("COMPLETED", mapper.selectById(1L).getStatus());
        assertNull(mapper.selectById(1L).getSuspensionReason());
    }

    @Test
    void skillSnapshotWriteCannotRestoreStaleLifecycleFields() throws Exception {
        seed("AGENT", "RUNNING");
        doAnswer(call -> {
            Object previous = call.callRealMethod();
            database.jdbc().update("UPDATE runtime_run SET status = 'CANCELLED' WHERE id = 1");
            return previous;
        }).when(mapper).selectOne(any());
        lifecycle.recordSkillBindings("trace-1", List.of());
        assertEvidence("CANCELLED");
        var snapshot = new ObjectMapper().readTree(mapper.selectById(1L).getSnapshotJson());
        assertEquals(1, snapshot.path("stable").asInt());
        assertEquals(0, snapshot.path("skillBindingCount").asInt());
        assertTrue(snapshot.path("skillBindings").isArray());
    }

    private void finish(Path path, boolean success, String code) {
        switch (path) {
            case AGENT -> lifecycle.finishAgent("trace-1", success, code, "private answer", COUNTS,
                    END.plusSeconds(1), null, 9_000, END.minusSeconds(8));
            case WORKFLOW -> lifecycle.finishWorkflow("trace-1", success, code, "private answer", 2, COUNTS);
            case MCP -> lifecycle.finishMcp("trace-1", success, code, true,
                    Map.of("sourceKind", "CAPABILITY", "toolName", "orders", "privateData", "secret"));
        }
    }

    private void assertEvidence(String status) {
        var current = mapper.selectById(1L);
        assertEquals(status, current.getStatus());
        assertEquals(END, current.getEndedAt());
        assertEquals("ORIGINAL", current.getErrorCode());
        assertEquals("original evidence", current.getErrorMessage());
        assertEquals("original output", current.getOutputSummary());
        assertEquals(8000, current.getLatencyMs());
    }

    private void seed(String runType, String status) {
        database.jdbc().update("""
                INSERT INTO runtime_run (id, trace_id, run_type, entry_type, status, suspension_reason,
                    started_at, ended_at, latency_ms, error_code, error_message, output_summary, snapshot_json)
                VALUES (1, 'trace-1', ?, 'API', ?, 'USER_INPUT', ?, ?, 8000,
                    'ORIGINAL', 'original evidence', 'original output', '{"stable":1}')
                """, runType, status, END.minusSeconds(8), END);
    }
}
