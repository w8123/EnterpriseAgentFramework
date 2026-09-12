package com.enterprise.ai.runtime.trace;

import com.enterprise.ai.runtime.runops.RuntimeRunLifecycleService;
import com.enterprise.ai.runtime.supervisor.SupervisorExecutionTraceService;
import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuntimeTraceRootLifecyclePersistenceTest {
    private static final LocalDateTime START = LocalDateTime.of(2026, 9, 6, 10, 0);
    private RuntimeQueryTestDatabase database;
    private RuntimeTraceSpanMapper spans;
    private RuntimeRunLifecycleService runs;
    private SupervisorExecutionTraceService supervisor;
    private RuntimeTraceRootService roots;

    @BeforeEach
    void setUp() throws Exception {
        database = new RuntimeQueryTestDatabase(List.of("runtime_trace_span"), RuntimeTraceSpanMapper.class);
        spans = spy(database.mapper(RuntimeTraceSpanMapper.class));
        runs = mock(RuntimeRunLifecycleService.class);
        roots = new RuntimeTraceRootService(spans, new ObjectMapper());
        supervisor = new SupervisorExecutionTraceService(
                new com.enterprise.ai.runtime.trace.RuntimeTraceEvidenceWriter(spans, mock(RuntimeToolCallLogMapper.class)),
                runs,
                new ObjectMapper(),
                roots,
                new RuntimeTraceSpanTerminationService(spans));
    }

    @AfterEach
    void close() {
        if (database != null) database.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"WAITING_USER", "WAITING_APPROVAL"})
    void resumingWaitingRootActuallyClearsStoredTerminalFields(String status) {
        seed(status);
        var handle = supervisor.resume("trace-1");
        assertNotNull(handle);
        var stored = spans.selectById(1L);
        assertEquals("RUNNING", stored.getStatus());
        assertNull(stored.getEndedAt());
        assertNull(stored.getErrorCode());
        assertNull(stored.getErrorMessage());
        assertNull(stored.getLatencyMs());
        verify(runs).resumeAgent("trace-1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCESS", "FAILED", "ERROR", "CANCELLED", "TIMEOUT"})
    void terminalRootCannotBeReopenedByResume(String status) {
        seed(status);
        assertThrows(IllegalStateException.class, () -> supervisor.resume("trace-1"));
        assertEquals(status, spans.selectById(1L).getStatus());
        verifyNoInteractions(runs);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCESS", "FAILED", "ERROR", "CANCELLED", "TIMEOUT"})
    void lateCompletionPreservesTheExistingTerminalEvidence(String status) {
        seed(status);
        supervisor.finish(new SupervisorExecutionTraceService.TraceHandle("trace-1", "root-1", 1L, START),
                true, "OK", "late response", Map.of());
        var stored = spans.selectById(1L);
        assertEquals(status, stored.getStatus());
        assertEquals("original terminal evidence", stored.getErrorMessage());
        assertEquals(START.plusMinutes(1), stored.getEndedAt());
        verifyNoInteractions(runs);
    }

    @Test
    void completionCannotWriteAnotherTraceThroughAValidDatabaseId() {
        seed("RUNNING");
        supervisor.finish(new SupervisorExecutionTraceService.TraceHandle("other-trace", "other-root", 1L, START),
                true, "OK", "foreign response", Map.of());
        assertEquals("RUNNING", spans.selectById(1L).getStatus());
        verifyNoInteractions(runs);
    }

    @Test
    void finishStoresOnlySanitizedInputAndErrorSummaries() {
        var handle = roots.start(RuntimeTraceRootService.Start.builder()
                .traceId("trace-private").spanId("root-private").spanType("WORKFLOW").runtimeType("GRAPH_SPEC")
                .agentId("workflow-1").input(Map.of("message", "private request", "token", "private credential"))
                .startedAt(START).build());

        assertTrue(roots.finish(handle, new RuntimeTraceRootService.Completion(
                "ERROR", "WORKFLOW_FAILED", "private response", START.plusSeconds(3), null)));

        var stored = spans.selectById(handle.id());
        assertEquals("ERROR", stored.getStatus());
        assertEquals("[omitted]", stored.getErrorMessage());
        assertEquals("[omitted]", stored.getOutputSummary());
        assertFalse(stored.getInputSummary().contains("private request"));
        assertFalse(stored.getInputSummary().contains("private credential"));
        assertEquals(3000, stored.getLatencyMs());
    }

    @Test
    void enteringWaitClearsOldEndTimeAndErrorsInTheDatabase() {
        seed("RUNNING");
        assertTrue(roots.finish(new RuntimeTraceRootService.Handle(1L, "trace-1", "root-1", START),
                new RuntimeTraceRootService.Completion("WAITING_APPROVAL", "CONFIRM", "approval request", START.plusSeconds(3), null)));
        var stored = spans.selectById(1L);
        assertEquals("WAITING_APPROVAL", stored.getStatus());
        assertNull(stored.getEndedAt());
        assertNull(stored.getErrorCode());
        assertNull(stored.getErrorMessage());
        assertNull(stored.getLatencyMs());
    }

    @Test
    void concurrentExpiryAfterTheReadCannotBeReopenedByResume() {
        seed("WAITING_USER");
        doAnswer(call -> {
            Object previous = call.callRealMethod();
            database.jdbc().update("UPDATE runtime_trace_span SET status = 'TIMEOUT' WHERE id = 1");
            return previous;
        }).when(spans).selectOne(org.mockito.ArgumentMatchers.any());

        assertThrows(IllegalStateException.class, () -> supervisor.resume("trace-1"));

        assertEquals("TIMEOUT", spans.selectById(1L).getStatus());
        assertEquals(START.plusMinutes(1), spans.selectById(1L).getEndedAt());
        verifyNoInteractions(runs);
    }

    @Test
    void rootCompletionCannotTargetAChildSpan() {
        seed("RUNNING");
        database.jdbc().update("UPDATE runtime_trace_span SET parent_span_id = 'parent' WHERE id = 1");
        assertFalse(roots.finish(new RuntimeTraceRootService.Handle(1L, "trace-1", "root-1", START),
                new RuntimeTraceRootService.Completion("SUCCESS", "OK", "child answer", START.plusSeconds(3), null)));
        assertEquals("RUNNING", spans.selectById(1L).getStatus());
    }

    private void seed(String status) {
        database.jdbc().update("""
                INSERT INTO runtime_trace_span
                  (id, trace_id, span_id, span_type, status, started_at, ended_at, error_code, error_message, latency_ms)
                VALUES (1, 'trace-1', 'root-1', 'SUPERVISOR', ?, ?, ?, 'ORIGINAL', 'original terminal evidence', 60000)
                """, status, START, START.plusMinutes(1));
    }
}
