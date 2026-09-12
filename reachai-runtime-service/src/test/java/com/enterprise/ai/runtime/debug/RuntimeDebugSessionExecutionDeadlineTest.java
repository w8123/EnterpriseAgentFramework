package com.enterprise.ai.runtime.debug;

import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDebugService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Uses the baseline SQL, actual MyBatis writes and Spring transactions; no elapsed-time sleeps. */
class RuntimeDebugSessionExecutionDeadlineTest {
    private static final com.enterprise.ai.runtime.debug.RuntimeDebugSessionOwner OWNER =
            new com.enterprise.ai.runtime.debug.RuntimeDebugSessionOwner("default", "42");
    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();
    private RuntimeQueryTestDatabase db;
    private RuntimeExecutableDebugSessionMapper mapper;
    private RuntimeDebugSessionStore store;
    private RuntimeDebugExecutionLifecycle executionLifecycle;
    private com.enterprise.ai.runtime.runops.RuntimeRunMapper runMapper;
    private com.enterprise.ai.runtime.trace.RuntimeTraceSpanMapper spanMapper;
    private RuntimeWorkflowDebugService workflow;
    private RuntimeExecutableDebugSessionService service;
    private final AtomicInteger executions = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        db = new RuntimeQueryTestDatabase(List.of("runtime_executable_debug_session", "runtime_run", "runtime_trace_span"),
                RuntimeExecutableDebugSessionMapper.class,
                com.enterprise.ai.runtime.runops.RuntimeRunMapper.class,
                com.enterprise.ai.runtime.trace.RuntimeTraceSpanMapper.class);
        mapper = spy(db.mapper(RuntimeExecutableDebugSessionMapper.class));
        runMapper = spy(db.mapper(com.enterprise.ai.runtime.runops.RuntimeRunMapper.class));
        spanMapper = spy(db.mapper(com.enterprise.ai.runtime.trace.RuntimeTraceSpanMapper.class));
        executionLifecycle = transactional(new RuntimeDebugExecutionLifecycle(
                new com.enterprise.ai.runtime.runops.RuntimeRunLifecycleService(runMapper, json),
                new com.enterprise.ai.runtime.trace.RuntimeTraceSpanTerminationService(spanMapper)));
        store = transactional(new RuntimeDebugSessionStore(mapper, json, executionLifecycle));
        workflow = mock(RuntimeWorkflowDebugService.class);
        when(workflow.captureDefinition(any())).thenReturn(new RuntimeWorkflowDebugService.DebugDefinition(
                null, "draft", "Draft", "GENERAL", null, null, "GRAPH_SPEC", null, "{}", null));
        when(workflow.startSessionDebug(any(), any(), any(), any(), any())).thenAnswer(call -> {
            executions.incrementAndGet();
            return completed("COMPLETED");
        });
        when(workflow.resumeSessionDebug(any(), any(), any(), any(), any())).thenAnswer(call -> {
            executions.incrementAndGet();
            return completed("COMPLETED");
        });
        service = transactional(new RuntimeExecutableDebugSessionService(store, workflow, json, null, 8000));
    }

    @AfterEach
    void close() { if (db != null) db.close(); }

    @Test
    void initialReservationHasAnIndependentDeadlineBeforeExecution() {
        doAnswer(call -> {
            var stored = mapper.selectOne(null);
            assertNotNull(stored.getExecutionDeadlineAt());
            assertTrue(stored.getExecutionDeadlineAt().isAfter(LocalDateTime.now().plusMinutes(14)));
            assertTrue(stored.getExecutionDeadlineAt().isBefore(stored.getExpiresAt().minusHours(23)));
            return completed("COMPLETED");
        }).when(workflow).startSessionDebug(any(), any(), any(), any(), any());
        assertEquals("COMPLETED", service.create(OWNER, createRequest()).status());
    }

    @Test
    void eachResumeReservesAFreshDeadlineIndependentOfTheWaitingLifetime() {
        seedWaiting();
        db.jdbc().update("UPDATE runtime_executable_debug_session SET expires_at = ? WHERE id = 'session-1'",
                LocalDateTime.now().plusSeconds(5));
        doAnswer(call -> {
            var stored = row();
            assertEquals("RESUMING", stored.getStatus());
            assertNotNull(stored.getExecutionDeadlineAt());
            assertTrue(stored.getExecutionDeadlineAt().isAfter(stored.getExpiresAt().plusMinutes(14)));
            return completed("SUSPENDED");
        }).when(workflow).resumeSessionDebug(any(), any(), any(), any(), any());
        assertEquals("SUSPENDED", service.submit(OWNER, "session-1", submitRequest()).status());
        assertNull(row().getExecutionDeadlineAt(), "a waiting checkpoint has no active execution attempt");
    }

    @Test
    void readingAnExpiredInitialAttemptReturnsAnUnknownTerminalResult() throws Exception {
        seedActive("RUNNING");
        pastDeadline();
        var view = service.get(OWNER, "session-1");
        assertEquals("EXPIRED", view.status());
        assertFalse(view.success());
        assertTrue(view.answer().contains("无法确认"));
        assertNull(view.uiRequest());
        assertUnknownResult();
        assertEquals(0, executions.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"RUNNING", "RESUMING"})
    void expiringExecutionClosesItsRunAndOpenSpansWhilePreservingTerminalEvidence(String status) {
        seedActive(status);
        pastDeadline();
        seedExecutionAudit("RESUMING".equals(status) ? "SUSPENDED" : "RUNNING");
        var terminalBefore = db.jdbc().queryForMap("SELECT * FROM runtime_trace_span WHERE span_id='finished-child'");
        var otherBefore = db.jdbc().queryForMap("SELECT * FROM runtime_run WHERE trace_id='other-trace'");
        assertEquals("EXPIRED", service.get(OWNER, "session-1").status());
        assertEquals("TIMED_OUT", db.jdbc().queryForObject("SELECT status FROM runtime_run WHERE trace_id='trace-1'", String.class));
        assertEquals(0, db.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_trace_span WHERE trace_id='trace-1' AND ended_at IS NULL", Integer.class));
        assertEquals(terminalBefore, db.jdbc().queryForMap("SELECT * FROM runtime_trace_span WHERE span_id='finished-child'"));
        assertEquals(otherBefore, db.jdbc().queryForMap("SELECT * FROM runtime_run WHERE trace_id='other-trace'"));
        var audit = db.jdbc().queryForList("SELECT * FROM runtime_trace_span ORDER BY id");
        var run = db.jdbc().queryForMap("SELECT * FROM runtime_run WHERE trace_id='trace-1'");
        assertEquals("EXPIRED", service.get(OWNER, "session-1").status());
        assertEquals(audit, db.jdbc().queryForList("SELECT * FROM runtime_trace_span ORDER BY id"));
        assertEquals(run, db.jdbc().queryForMap("SELECT * FROM runtime_run WHERE trace_id='trace-1'"));
        assertEquals(0, executions.get());
    }

    private void seedExecutionAudit(String status) {
        db.jdbc().update("INSERT INTO runtime_run(trace_id,run_type,entry_type,status) VALUES('trace-1','WORKFLOW','WORKFLOW_STUDIO',?)", status);
        db.jdbc().update("INSERT INTO runtime_run(trace_id,run_type,entry_type,status) VALUES('other-trace','WORKFLOW','WORKFLOW_STUDIO','RUNNING')");
        db.jdbc().update("INSERT INTO runtime_trace_span(trace_id,span_id,span_type,status,started_at) VALUES('trace-1','root','WORKFLOW','RUNNING',?)", LocalDateTime.now().minusMinutes(2));
        db.jdbc().update("INSERT INTO runtime_trace_span(trace_id,span_id,parent_span_id,span_type,status,started_at) VALUES('trace-1','waiting-child','root','WORKFLOW_NODE','WAITING_USER',?)", LocalDateTime.now().minusMinutes(2));
        db.jdbc().update("INSERT INTO runtime_trace_span(trace_id,span_id,parent_span_id,span_type,status,started_at,ended_at) VALUES('trace-1','finished-child','root','WORKFLOW_NODE','SUCCESS',?,?)", LocalDateTime.now().minusMinutes(2), LocalDateTime.now().minusMinutes(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"run", "trace"})
    void failureAfterAuditWriteRollsBackSessionRunAndSpansTogether(String failedOwner) {
        seedActive("RUNNING"); pastDeadline(); seedExecutionAudit("RUNNING");
        var beforeSession = db.jdbc().queryForMap("SELECT * FROM runtime_executable_debug_session WHERE id='session-1'");
        var beforeRuns = db.jdbc().queryForList("SELECT * FROM runtime_run ORDER BY id");
        var beforeSpans = db.jdbc().queryForList("SELECT * FROM runtime_trace_span ORDER BY id");
        AtomicBoolean fail = new AtomicBoolean(true);
        org.mockito.stubbing.Answer<Object> failure = call -> {
            Object changed = call.callRealMethod();
            assertEquals(1, ((Number) changed).intValue(), "run".equals(failedOwner) ? "Expected a real Run write" : "unused");
            if (fail.getAndSet(false)) throw new DataAccessResourceFailureException("after actual " + failedOwner + " update");
            return changed;
        };
        if ("run".equals(failedOwner)) {
            doAnswer(failure).when(runMapper).update(any(), any());
        } else {
            doAnswer(call -> {
                Object changed = call.callRealMethod();
                assertEquals(2, ((Number) changed).intValue(), "Both open spans must have been updated");
                if (fail.getAndSet(false)) throw new DataAccessResourceFailureException("after actual trace update");
                return changed;
            }).when(spanMapper).update(any(), any());
        }
        assertThrows(DataAccessResourceFailureException.class, () -> service.get(OWNER, "session-1"));
        assertEquals(beforeSession, db.jdbc().queryForMap("SELECT * FROM runtime_executable_debug_session WHERE id='session-1'"));
        assertEquals(beforeRuns, db.jdbc().queryForList("SELECT * FROM runtime_run ORDER BY id"));
        assertEquals(beforeSpans, db.jdbc().queryForList("SELECT * FROM runtime_trace_span ORDER BY id"));
        assertEquals("EXPIRED", service.get(OWNER, "session-1").status());
        assertEquals("TIMED_OUT", db.jdbc().queryForObject("SELECT status FROM runtime_run WHERE trace_id='trace-1'", String.class));
        assertEquals(0, executions.get());
    }

    @Test
    void wrongOwnerCannotExpireTheSessionOrItsExecutionEvidence() {
        seedActive("RESUMING"); pastDeadline(); seedExecutionAudit("SUSPENDED");
        var beforeRuns = db.jdbc().queryForList("SELECT * FROM runtime_run ORDER BY id");
        var beforeSpans = db.jdbc().queryForList("SELECT * FROM runtime_trace_span ORDER BY id");
        for (var owner : List.of(new RuntimeDebugSessionOwner("other", "42"), new RuntimeDebugSessionOwner("default", "43"))) {
            assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> service.get(owner, "session-1"));
        }
        assertEquals("RESUMING", row().getStatus()); assertNull(row().getResultJson());
        assertEquals(beforeRuns, db.jdbc().queryForList("SELECT * FROM runtime_run ORDER BY id"));
        assertEquals(beforeSpans, db.jdbc().queryForList("SELECT * FROM runtime_trace_span ORDER BY id"));
    }

    @Test
    void lifecycleCannotWriteWithoutTheDebugOwnersTransaction() {
        seedActive("RUNNING"); pastDeadline(); seedExecutionAudit("RUNNING");
        assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
                () -> executionLifecycle.expire("trace-1", "TIMEOUT", "expired", LocalDateTime.now()));
        assertEquals("RUNNING", row().getStatus());
        assertEquals("RUNNING", db.jdbc().queryForObject("SELECT status FROM runtime_run WHERE trace_id='trace-1'", String.class));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"WORKFLOW,AUTOMATION,RUNNING", "AGENT,WORKFLOW_STUDIO,RUNNING", "WORKFLOW,WORKFLOW_STUDIO,COMPLETED"})
    void debugTimeoutDoesNotChangeOtherRunTypesOrExistingTerminalRuns(String runType, String entryType, String status) {
        db.jdbc().update("INSERT INTO runtime_run(trace_id,run_type,entry_type,status) VALUES('trace-1',?,?,?)", runType, entryType, status);
        var before = db.jdbc().queryForMap("SELECT * FROM runtime_run WHERE trace_id='trace-1'");
        var runs = new com.enterprise.ai.runtime.runops.RuntimeRunLifecycleService(runMapper, json);
        assertEquals(0, runs.timeoutDebugExecution("trace-1", "DEBUG_TIMEOUT", "expired", LocalDateTime.now()));
        assertEquals(before, db.jdbc().queryForMap("SELECT * FROM runtime_run WHERE trace_id='trace-1'"));
    }

    @Test
    void expiredResumeKeepsItsCanonicalPayloadAndDoesNotExecuteOnDuplicateSubmit() throws Exception {
        seedWaiting();
        var snapshot = row();
        String payload = "{\"action\":\"submit\",\"values\":{\"q\":\"value\"},\"interactionId\":\"interaction-1\",\"nodeId\":\"confirm\"}";
        assertTrue(store.claim(OWNER, snapshot, "attempt-1", payload));
        pastDeadline();
        assertEquals("EXPIRED", service.submit(OWNER, "session-1", submitRequest()).status());
        assertEquals("confirm", row().getCurrentNodeId());
        assertEquals("attempt-1", row().getIdempotencyKey());
        assertEquals("EXPIRED", service.submit(OWNER, "session-1", submitRequest()).status());
        assertEquals(2, row().getRevision());
        assertUnknownResult();
        assertEquals(0, executions.get());
    }

    @Test
    void lateSuccessCannotReplaceAnExpiredAttemptEvenBeforeTheSchedulerRuns() {
        seedWaiting();
        doAnswer(call -> {
            executions.incrementAndGet();
            pastDeadline();
            return completed("COMPLETED");
        }).when(workflow).resumeSessionDebug(any(), any(), any(), any(), any());
        assertEquals("EXPIRED", service.submit(OWNER, "session-1", submitRequest()).status());
        assertEquals("EXPIRED", row().getStatus());
        assertEquals(1, executions.get());
    }

    @Test
    void lateWaitingResultCannotReopenTheExpiredCheckpoint() {
        seedWaiting();
        doAnswer(call -> {
            executions.incrementAndGet();
            pastDeadline();
            return completed("SUSPENDED");
        }).when(workflow).resumeSessionDebug(any(), any(), any(), any(), any());
        assertEquals("EXPIRED", service.submit(OWNER, "session-1", submitRequest()).status());
        assertNull(service.get(OWNER, "session-1").uiRequest());
        assertEquals("EXPIRED", service.submit(OWNER, "session-1", submitRequest()).status());
        assertEquals(1, executions.get());
    }

    @Test
    void timelyCompletionReceiptStillWinsAfterItsProjectionWasUnavailablePastTheDeadline() {
        seedWaiting();
        AtomicBoolean failProjection = new AtomicBoolean(true);
        doAnswer(call -> {
            com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<?> update = call.getArgument(1);
            if (failProjection.get() && update.getSqlSet().contains("state_snapshot_json"))
                throw new DataAccessResourceFailureException("projection unavailable");
            return call.callRealMethod();
        }).when(mapper).update(any(), any());
        assertThrows(RuntimeException.class, () -> service.submit(OWNER, "session-1", submitRequest()));
        assertTrue(row().getResultJson().contains("completionSchema"));
        pastDeadline();
        failProjection.set(false);
        assertEquals("COMPLETED", service.get(OWNER, "session-1").status());
        assertEquals("done", service.get(OWNER, "session-1").answer());
        assertNull(row().getExecutionDeadlineAt());
        assertEquals(1, executions.get());
    }

    @Test
    void cancellationAfterTheDeadlineRetainsTheUnknownResult() {
        seedActive("RESUMING");
        pastDeadline();
        assertEquals("EXPIRED", service.cancel(OWNER, "session-1").status());
        assertEquals("confirm", row().getCurrentNodeId());
        assertEquals(1, row().getRevision());
    }

    @Test
    void expiryIsCommittedOnlyOnceAcrossRepeatedReads() {
        seedActive("RESUMING");
        pastDeadline();
        assertEquals("EXPIRED", service.get(OWNER, "session-1").status());
        String result = row().getResultJson();
        for (int i = 0; i < 3; i++) assertEquals("EXPIRED", service.get(OWNER, "session-1").status());
        assertEquals(1, row().getRevision());
        assertEquals(result, row().getResultJson());
    }

    @Test
    void aFailedExpiryWriteRollsBackAndCanBeRetriedWithoutExecution() {
        seedActive("RUNNING");
        pastDeadline();
        AtomicBoolean fail = new AtomicBoolean(true);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            if (fail.getAndSet(false)) throw new DataAccessResourceFailureException("after expiry write");
            return result;
        }).when(mapper).update(any(), any());
        assertThrows(RuntimeException.class, () -> service.get(OWNER, "session-1"));
        assertEquals("RUNNING", row().getStatus());
        assertNull(row().getResultJson());
        assertEquals("EXPIRED", service.get(OWNER, "session-1").status());
        assertEquals(0, executions.get());
    }

    @Test
    void activeAttemptWithoutADeadlineFailsClosedWithoutInferringSuccess() throws Exception {
        seedActive("RUNNING");
        db.jdbc().update("UPDATE runtime_executable_debug_session SET execution_deadline_at = NULL WHERE id = 'session-1'");
        assertEquals("EXPIRED", service.get(OWNER, "session-1").status());
        var result = json.readTree(row().getResultJson());
        assertEquals("DEBUG_SESSION_EXECUTION_DEADLINE_MISSING", result.path("code").asText());
        assertEquals("UNKNOWN", result.path("outcome").asText());
    }

    @Test
    void liveAttemptsAndWaitingCheckpointsAreNotExpiredByReading() {
        seedActive("RUNNING");
        assertEquals("RUNNING", service.get(OWNER, "session-1").status());
        db.jdbc().update("UPDATE runtime_executable_debug_session SET status = 'SUSPENDED' WHERE id = 'session-1'");
        pastDeadline();
        assertEquals("SUSPENDED", service.get(OWNER, "session-1").status());
        assertEquals(0, row().getRevision());
    }

    @Test
    void invalidStoredReceiptIsPreservedAsEvidence() {
        seedActive("RUNNING");
        pastDeadline();
        String corrupt = "{\"completionSchema\":999}";
        db.jdbc().update("UPDATE runtime_executable_debug_session SET result_json = ? WHERE id = 'session-1'", corrupt);
        assertThrows(RuntimeException.class, () -> service.get(OWNER, "session-1"));
        assertEquals(corrupt, row().getResultJson());
        assertEquals("RUNNING", row().getStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"schema-string", "schema-fraction", "revision-missing", "revision-null", "revision-string", "revision-fraction"})
    void malformedReceiptIdentityCannotBeCoercedIntoAnAcceptedCompletion(String corruption) throws Exception {
        seedActive("RUNNING");
        var completed = row();
        completed.setStatus("COMPLETED");
        completed.setResultJson("{}");
        assertTrue(store.stageCompletion(OWNER, completed, "RUNNING", 0));
        var receipt = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(row().getResultJson());
        switch (corruption) {
            case "schema-string" -> receipt.put("completionSchema", "1");
            case "schema-fraction" -> receipt.put("completionSchema", 1.5);
            case "revision-missing" -> receipt.remove("expectedRevision");
            case "revision-null" -> receipt.putNull("expectedRevision");
            case "revision-string" -> receipt.put("expectedRevision", "0");
            case "revision-fraction" -> receipt.put("expectedRevision", 0.5);
            default -> fail("Unexpected test case");
        }
        String corrupt = json.writeValueAsString(receipt);
        db.jdbc().update("UPDATE runtime_executable_debug_session SET result_json = ? WHERE id = 'session-1'", corrupt);
        pastDeadline();
        var error = assertThrows(IllegalStateException.class, () -> service.get(OWNER, "session-1"));
        assertTrue(error.getMessage().startsWith("DEBUG_SESSION_COMPLETION_INVALID"));
        assertEquals(corrupt, row().getResultJson());
        assertEquals("RUNNING", row().getStatus());
        assertEquals(0, row().getRevision());
        assertEquals(0, executions.get());
    }

    @Test
    void schedulerUsesABoundedBatchAndOneUnavailableRowDoesNotBlockTheNext() {
        seedActive("RUNNING");
        copy("expired-0", "RUNNING", LocalDateTime.now().minusMinutes(3), null);
        copy("expired-1", "RESUMING", LocalDateTime.now().minusMinutes(2), null);
        copy("expired-2", "RUNNING", LocalDateTime.now().minusMinutes(1), null);
        copy("corrupt-receipt", "RUNNING", LocalDateTime.now().minusMinutes(10), "{\"completionSchema\":999}");
        copy("terminal", "EXPIRED", LocalDateTime.now().minusMinutes(10), null);
        assertEquals(List.of("expired-0", "expired-1"), store.findExpiredCandidateIds(2));
        AtomicBoolean unavailable = new AtomicBoolean(true);
        doAnswer(call -> {
            if ("expired-0".equals(call.getArgument(0)) && unavailable.get())
                throw new DataAccessResourceFailureException("unavailable row");
            return call.callRealMethod();
        }).when(mapper).selectByIdForUpdate(any());
        var scheduler = new RuntimeDebugSessionExpiryScheduler(store);
        scheduler.reconcileExpiredAttempts();
        assertEquals("RUNNING", mapper.selectById("expired-0").getStatus());
        assertEquals("EXPIRED", mapper.selectById("expired-1").getStatus());
        assertEquals("EXPIRED", mapper.selectById("expired-2").getStatus());
        assertEquals("RUNNING", mapper.selectById("corrupt-receipt").getStatus());
        assertEquals("RUNNING", row().getStatus());
        unavailable.set(false);
        scheduler.reconcileExpiredAttempts();
        assertEquals("EXPIRED", mapper.selectById("expired-0").getStatus());
        assertEquals(List.of(), store.findExpiredCandidateIds(100));
        assertEquals(0, executions.get());
    }

    @Test
    void anOldSchedulerCandidateCannotExpireANewerActiveAttempt() {
        seedActive("RUNNING");
        pastDeadline();
        var candidate = store.findExpiredCandidateIds(1).get(0);
        db.jdbc().update("UPDATE runtime_executable_debug_session SET status = 'RESUMING', revision = 3, execution_deadline_at = ? WHERE id = ?",
                LocalDateTime.now().plusMinutes(15), candidate);
        assertEquals("RESUMING", store.require(candidate).getStatus());
        assertEquals(3, row().getRevision());
        assertNull(row().getResultJson());
    }

    @Test
    void concurrentReadersConvergeOnOneCommittedExpiry() throws Exception {
        seedActive("RUNNING");
        seedExecutionAudit("RUNNING");
        pastDeadline();
        var locked = new CountDownLatch(1);
        var secondEntered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        doAnswer(call -> {
            int reader = reads.incrementAndGet();
            if (reader == 2) secondEntered.countDown();
            Object value = call.callRealMethod();
            if (reader == 1) {
                locked.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
            }
            return value;
        }).when(mapper).selectByIdForUpdate(any());
        var first = CompletableFuture.supplyAsync(() -> service.get(OWNER, "session-1"));
        try {
            assertTrue(locked.await(5, TimeUnit.SECONDS));
            var second = CompletableFuture.supplyAsync(() -> service.get(OWNER, "session-1"));
            assertTrue(secondEntered.await(5, TimeUnit.SECONDS));
            release.countDown();
            assertEquals("EXPIRED", first.get(5, TimeUnit.SECONDS).status());
            assertEquals("EXPIRED", second.get(5, TimeUnit.SECONDS).status());
            assertEquals(1, row().getRevision());
            assertEquals("TIMED_OUT", db.jdbc().queryForObject("SELECT status FROM runtime_run WHERE trace_id='trace-1'", String.class));
            assertEquals(0, db.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_trace_span WHERE trace_id='trace-1' AND ended_at IS NULL", Integer.class));
        } finally {
            release.countDown();
        }
    }

    @Test
    void completionUsesTheClockAfterAcquiringTheLockAndRejectsAtTheExactDeadline() {
        seedActive("RUNNING");
        var instant = new AtomicReference<>(Instant.parse("2030-01-01T00:00:00Z"));
        Clock clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return Clock.fixed(instant.get(), zone); }
            public Instant instant() { return instant.get(); }
        };
        db.jdbc().update("UPDATE runtime_executable_debug_session SET execution_deadline_at = ? WHERE id = 'session-1'",
                LocalDateTime.ofInstant(instant.get().plusSeconds(5), ZoneOffset.UTC));
        var completed = row();
        completed.setStatus("COMPLETED"); completed.setResultJson("{\"answer\":\"done\"}");
        doAnswer(call -> {
            Object value = call.callRealMethod();
            instant.set(Instant.parse("2030-01-01T00:00:05Z"));
            return value;
        }).when(mapper).selectByIdForUpdate(any());
        var timedStore = transactional(new RuntimeDebugSessionStore(mapper, json, executionLifecycle, 900, clock));
        assertFalse(timedStore.stageCompletion(OWNER, completed, "RUNNING", 0));
        assertEquals("EXPIRED", row().getStatus());
    }

    @Test
    void aRepeatedTimelyReceiptRemainsAcceptedAfterTheDeadline() {
        seedActive("RUNNING");
        var completed = row();
        completed.setStatus("COMPLETED"); completed.setResultJson("{\"answer\":\"done\"}");
        assertTrue(store.stageCompletion(OWNER, completed, "RUNNING", 0));
        pastDeadline();
        assertTrue(store.stageCompletion(OWNER, completed, "RUNNING", 0));
        assertEquals("COMPLETED", store.require("session-1").getStatus());
    }

    @Test
    void anOlderCompletionCannotCloseOrReplaceANewerAttempt() {
        seedActive("RESUMING");
        var completed = row();
        completed.setStatus("COMPLETED"); completed.setResultJson("{\"answer\":\"old result\"}");
        db.jdbc().update("UPDATE runtime_executable_debug_session SET revision = 3 WHERE id = 'session-1'");
        assertFalse(store.stageCompletion(OWNER, completed, "RESUMING", 0));
        assertEquals("RESUMING", row().getStatus());
        assertEquals(3, row().getRevision());
        assertNull(row().getResultJson());
    }

    @Test
    void activeResultsCannotMasqueradeAsCompletedReceiptsAndDisableExpiry() {
        seedActive("RUNNING");
        assertThrows(IllegalArgumentException.class, () -> store.stageCompletion(OWNER, row(), "RUNNING", 0));
        assertNull(row().getResultJson());
        pastDeadline();
        assertEquals("EXPIRED", store.require("session-1").getStatus());
    }

    @Test
    void configuredDeadlineIsOwnedByTheStoreAndCannotBeDisabledWithNonpositiveValues() {
        assertThrows(IllegalArgumentException.class, () -> new RuntimeDebugSessionStore(mapper, json, executionLifecycle, 0));
        assertThrows(IllegalArgumentException.class, () -> new RuntimeDebugSessionStore(mapper, json, executionLifecycle, -1));
        var clock = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);
        var configuredStore = transactional(new RuntimeDebugSessionStore(mapper, json, executionLifecycle, 42, clock));
        var reservation = new RuntimeExecutableDebugSessionEntity();
        reservation.setId("configured"); reservation.setStatus("RUNNING"); reservation.setTargetType("WORKFLOW_WORKING_COPY");
        reservation.setExecutionDeadlineAt(LocalDateTime.of(2099, 1, 1, 0, 0));
        configuredStore.create(OWNER, reservation);
        assertEquals(LocalDateTime.of(2030, 1, 1, 0, 0, 42), mapper.selectById("configured").getExecutionDeadlineAt());
    }

    private void copy(String id, String status, LocalDateTime deadline, String result) {
        var copy = row();
        copy.setId(id); copy.setStatus(status); copy.setExecutionDeadlineAt(deadline); copy.setResultJson(result);
        copy.setOwnerTenantId(OWNER.tenantId());
        copy.setOwnerUserId(OWNER.userId());
        mapper.insert(copy);
    }

    private void assertUnknownResult() throws Exception {
        var result = json.readTree(row().getResultJson());
        assertEquals("DEBUG_SESSION_EXECUTION_TIMEOUT", result.path("code").asText());
        assertEquals("UNKNOWN", result.path("outcome").asText());
        assertFalse(result.path("retryable").asBoolean(true));
        assertTrue(result.path("reconciliationRequired").asBoolean());
    }

    private void seedActive(String status) {
        seedWaiting();
        db.jdbc().update("UPDATE runtime_executable_debug_session SET status = ?, execution_deadline_at = ? WHERE id = 'session-1'",
                status, LocalDateTime.now().plusMinutes(15));
    }

    private void pastDeadline() {
        db.jdbc().update("UPDATE runtime_executable_debug_session SET execution_deadline_at = ? WHERE id = 'session-1'",
                LocalDateTime.now().minusMinutes(1));
    }

    private void seedWaiting() {
        var row = new RuntimeExecutableDebugSessionEntity();
        row.setId("session-1"); row.setRunId("run-1"); row.setTraceId("trace-1");
        row.setTargetType("WORKFLOW_WORKING_COPY"); row.setStatus("SUSPENDED"); row.setRevision(0);
        row.setCurrentNodeId("confirm"); row.setWorkingCopyDefinitionJson("{\"graphSpecJson\":\"{}\",\"executionEngine\":\"GRAPH_SPEC\"}");
        row.setDebugOptionsJson("{}"); row.setStateSnapshotJson("{}"); row.setStepsJson("[]");
        row.setMessagesJson("[{\"role\":\"assistant\",\"content\":\"Please confirm\"}]");
        row.setUiRequestJson("{\"interactionId\":\"interaction-1\"}");
        row.setCreateTime(LocalDateTime.now()); row.setUpdateTime(LocalDateTime.now());
        row.setExpiresAt(LocalDateTime.now().plusHours(24));
        row.setOwnerTenantId(OWNER.tenantId());
        row.setOwnerUserId(OWNER.userId());
        mapper.insert(row);
    }

    private RuntimeExecutableDebugSessionEntity row() { return mapper.selectById("session-1"); }

    private RuntimeExecutableDebugSessionService.CreateRequest createRequest() {
        return new RuntimeExecutableDebugSessionService.CreateRequest("WORKFLOW_WORKING_COPY",
                Map.of("graphSpecJson", "{}"), "hello", Map.of(), Map.of());
    }

    private RuntimeExecutableDebugSessionService.SubmitRequest submitRequest() {
        return new RuntimeExecutableDebugSessionService.SubmitRequest("submit", Map.of("q", "value"),
                null, "interaction-1", "attempt-1");
    }

    private RuntimeWorkflowDebugService.DebugRunResult completed(String status) {
        return new RuntimeWorkflowDebugService.DebugRunResult("run-1", "trace-1", null, "WORKFLOW", true,
                status, "done", "confirm", List.of(),
                "SUSPENDED".equals(status) ? Map.of("interactionId", "interaction-2") : null,
                List.of(), Map.of("lastOutput", "done"), null, null);
    }

    @SuppressWarnings("unchecked")
    private <T> T transactional(T target) {
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(db.jdbc().getDataSource()),
                new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }
}
