package com.enterprise.ai.runtime.execution;

import com.enterprise.ai.runtime.supervisor.SupervisorRuntimeAdapter;
import com.enterprise.ai.runtime.supervisor.RuntimeAgentExecutionService;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.execution.interaction.WorkflowInteractionCodes;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.runops.RuntimeRunLifecycleService;
import com.enterprise.ai.runtime.runops.RuntimeRunMapper;
import com.enterprise.ai.runtime.supervisor.SupervisorExecutionTraceService;
import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import com.enterprise.ai.runtime.trace.RuntimeTraceEvidenceWriter;
import com.enterprise.ai.runtime.trace.RuntimeTraceRootService;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanMapper;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanTerminationService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual baseline tables, MyBatis and Spring transactions; no external Workflow side effects. */
class RuntimeWorkflowResumeRecoveryPersistenceTest {
    private static final String ID = "wfi_recovery";
    private static final String CODE = "RUNTIME_INTERACTION_RESUME_TIMEOUT";
    private static final String GRAPH = """
            {"schemaVersion":2,"entryNodeId":"form","exitNodeIds":["form"],
             "nodes":[{"id":"form","type":"INTERACTION"}],"edges":[]}
            """;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private RuntimeQueryTestDatabase db;
    private RuntimeInteractionSessionMapper sessions;
    private RuntimeInteractionEventMapper events;
    private RuntimeRunMapper runs;
    private RuntimeTraceSpanMapper spans;
    private RuntimeWorkflowInteractionSessionService waits;
    private RuntimeInteractionExpiryProcessor expiry;
    private RuntimeGraphSpecExecutor executor;
    private RuntimeInteractionResumeService resume;

    @BeforeEach
    void setUp() throws Exception {
        db = new RuntimeQueryTestDatabase(List.of("runtime_interaction_session", "runtime_interaction_event",
                "runtime_run", "runtime_trace_span"), RuntimeInteractionSessionMapper.class,
                RuntimeInteractionEventMapper.class, RuntimeRunMapper.class, RuntimeTraceSpanMapper.class);
        sessions = spy(db.mapper(RuntimeInteractionSessionMapper.class));
        events = spy(db.mapper(RuntimeInteractionEventMapper.class));
        runs = spy(db.mapper(RuntimeRunMapper.class));
        spans = spy(db.mapper(RuntimeTraceSpanMapper.class));
        waits = transactional(new RuntimeWorkflowInteractionSessionService(sessions, events, json));
        var traces = new SupervisorExecutionTraceService(mock(RuntimeTraceEvidenceWriter.class),
                new RuntimeRunLifecycleService(runs, json), json, mock(RuntimeTraceRootService.class),
                transactional(new RuntimeTraceSpanTerminationService(spans)));
        expiry = transactional(new RuntimeInteractionExpiryProcessor(sessions, waits, traces,
                mock(RuntimeSupervisorApprovalService.class)));
        executor = mock(RuntimeGraphSpecExecutor.class);
        when(executor.executeFromCheckpoint(any(), any(), any(), anyInt(), any(), any())).thenReturn(done());
        resume = transactional(new RuntimeInteractionResumeService(waits,
                mock(RuntimeCapabilityCatalogClient.class), executor, json, expiry));
        waits.createWaitingSession(new RuntimeWorkflowInteractionSessionService.CreateRequest(ID, "WORKFLOW",
                "run-a", "trace-a", "wf-a", 23L, null, GRAPH, "form", "COLLECT_INPUT", Map.of(),
                Map.of("component", "form"), Map.of("agentId", "agent-a", "agentConfigVersionId", 11),
                "orders", "tenant-a", "chat-a", "user-a", 3600));
        db.jdbc().update("INSERT INTO runtime_run (trace_id, run_type, entry_type, status, suspension_reason) VALUES (?, 'AGENT', 'EMBED', 'SUSPENDED', 'USER_INPUT')", "trace-a");
        db.jdbc().update("INSERT INTO runtime_trace_span (trace_id, span_id, span_type, status) VALUES (?, 'root', 'SUPERVISOR', 'WAITING_USER')", "trace-a");
    }

    @AfterEach
    void close() { if (db != null) db.close(); }

    @Test
    void aCommittedClaimWhoseProcessDisappearedIsAnExpiryCandidate() {
        claim();
        assertEquals(List.of(ID), expiry.findExpiredCandidates(cutoff(), 100).stream()
                .map(RuntimeInteractionSessionEntity::getId).toList());
    }

    @Test
    void recoveryStoresUnknownOutcomeAndClosesWaitingEvidenceWithoutExecution() {
        claim();
        assertTrue(expiry.expireOne(row(), cutoff()));
        assertUnknown(submit("attempt-a"));
        assertEquals("EXPIRED", row().getStatus());
        assertEquals(2, row().getRevision());
        assertEquals(4, eventCount());
        assertEquals("TIMED_OUT", runStatus());
        assertEquals("TIMEOUT", spanStatus());
        assertEquals(CODE, db.jdbc().queryForObject("SELECT error_code FROM runtime_run WHERE trace_id = 'trace-a'", String.class));
        assertFalse(expiry.expireOne(row(), cutoff()));
        verifyNoInteractions(executor);
    }

    @Test
    void completedExecutionWithUncommittedReceiptCanBeReconciledAfterRestart() {
        failEvent("COMPLETED");
        assertThrows(DataAccessResourceFailureException.class, () -> submit("attempt-a"));
        assertEquals("RESUMING", row().getStatus());
        assertTrue(expiry.expireOne(row(), cutoff()));
        assertUnknown(submit("attempt-a"));
        verify(executor, times(1)).executeFromCheckpoint(any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void lateExecutorResultCannotReplaceTheReconciledUnknownReceipt() {
        when(executor.executeFromCheckpoint(any(), any(), any(), anyInt(), any(), any())).thenAnswer(call -> {
            expiry.expireOne(row(), cutoff());
            return done();
        });
        assertUnknown(submit("attempt-a"));
        assertEquals("EXPIRED", row().getStatus());
        assertEquals(4, eventCount());
    }

    @Test
    void changingTheIdempotencyKeyCannotHideTheUnknownOutcomeOrExecuteAgain() {
        claim();
        expiry.expireOne(row(), cutoff());
        assertUnknown(submit("new-key"));
        verifyNoInteractions(executor);
    }

    @Test
    void runPersistenceFailureRollsBackSessionEventAndTraceClosureTogether() {
        claim();
        doThrow(new DataAccessResourceFailureException("run unavailable"))
                .when(runs).update(isNull(), any(Wrapper.class));
        assertThrows(DataAccessResourceFailureException.class, () -> expiry.expireOne(row(), cutoff()));
        assertEquals("RESUMING", row().getStatus());
        assertNull(row().getResultJson());
        assertEquals(3, eventCount());
        assertEquals("SUSPENDED", runStatus());
        assertEquals("WAITING_USER", spanStatus());
    }

    @Test
    void originalWaitingTtlCannotExpireAnAttemptWithinItsSeparateResumeDeadline() {
        claim();
        db.jdbc().update("UPDATE runtime_interaction_session SET expires_at = DATEADD('SECOND', -1, CURRENT_TIMESTAMP) WHERE id = ?", ID);
        assertFalse(expiry.expireOne(row(), LocalDateTime.now()));
        assertTrue(expiry.findExpiredCandidates(LocalDateTime.now(), 100).isEmpty());
        var pending = submit("attempt-a");
        assertEquals("RUNTIME_INTERACTION_CONFLICT", pending.get("code"));
        assertEquals(true, pending.get("retryable"));
        assertEquals("RESUMING", row().getStatus());
        assertEquals("SUSPENDED", runStatus());
    }

    @Test
    void claimPersistsTheConfiguredDeadlineAlongsideItsSubmission() {
        waits = transactional(new RuntimeWorkflowInteractionSessionService(sessions, events, json, 262144, 1800));
        LocalDateTime before = LocalDateTime.now().plusSeconds(1799);
        claim();
        assertFalse(row().getResumeDeadlineAt().isBefore(before));
        assertFalse(row().getResumeDeadlineAt().isAfter(LocalDateTime.now().plusSeconds(1801)));
        assertEquals("attempt-a", row().getIdempotencyKey());
        assertTrue(row().getSubmittedPayloadJson().contains("同意"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void invalidTimeoutCannotDisableTheDeadlineSilently(int seconds) {
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeWorkflowInteractionSessionService(sessions, events, json, 262144, seconds));
    }

    @Test
    void submittedEventFailureLeavesNoDeadlineOrClaim() {
        failEvent("SUBMITTED");
        assertThrows(DataAccessResourceFailureException.class, this::claim);
        assertEquals("WAITING_USER", row().getStatus());
        assertNull(row().getResumeDeadlineAt());
        assertNull(row().getIdempotencyKey());
        assertEquals(2, eventCount());
    }

    @Test
    void aClientReadCanReconcileAnOverdueClaimWithoutWaitingForTheScheduler() {
        claim();
        elapseDeadline();
        assertUnknown(submit("attempt-a"));
        assertEquals("TIMED_OUT", runStatus());
        assertEquals(4, eventCount());
        verifyNoInteractions(executor);
    }

    @ParameterizedTest
    @ValueSource(strings = {"complete", "next_wait", "validation", "throw"})
    void elapsedDeadlineFencesEveryCompletionPathEvenBeforeReconciliation(String outcome) {
        when(executor.executeFromCheckpoint(any(), any(), any(), anyInt(), any(), any())).thenAnswer(call -> {
            elapseDeadline();
            return switch (outcome) {
                case "complete" -> done();
                case "next_wait" -> waiting("wfi_next");
                case "validation" -> waiting(ID);
                default -> throw new IllegalStateException("private-executor-context");
            };
        });
        var response = submit("attempt-a");
        assertUnknown(response);
        assertFalse(response.toString().contains("private-executor-context"));
        assertEquals("EXPIRED", row().getStatus());
        assertEquals(4, eventCount());
        assertEquals(1, db.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_interaction_session", Integer.class));
    }

    @Test
    void untrustedCallerCannotReadOrReconcileAnotherUsersExpiredClaim() {
        claim();
        elapseDeadline();
        var response = resume.resume(ID, Map.of("idempotencyKey", "attempt-a"),
                new RuntimeInteractionResumeService.Ownership("orders", "chat-a",
                        WorkflowExecutionIdentity.fromEmbedSession("tenant-b", null, null, "user-a")));
        assertEquals("RUNTIME_INTERACTION_FORBIDDEN", response.get("code"));
        assertEquals("RESUMING", row().getStatus());
        assertEquals(3, eventCount());
        verifyNoInteractions(executor);
    }

    @Test
    void aReconciledAttemptStillRejectsDifferentValuesUnderItsOriginalKey() {
        claim();
        expiry.expireOne(row(), cutoff());
        var response = resume.resume(ID, Map.of("values", Map.of("选择", "拒绝"), "idempotencyKey", "attempt-a"), owner());
        assertEquals("RUNTIME_INTERACTION_CONFLICT", response.get("code"));
        assertEquals("EXPIRED", row().getStatus());
        assertEquals(4, eventCount());
    }

    @Test
    void validationClearsTheOldDeadlineAndTheNextSubmissionGetsItsOwnDeadline() {
        when(executor.executeFromCheckpoint(any(), any(), any(), anyInt(), any(), any())).thenReturn(waiting(ID));
        submit("attempt-a");
        assertEquals("WAITING_USER", row().getStatus());
        assertNull(row().getResumeDeadlineAt());
        assertNull(row().getIdempotencyKey());
        claim();
        assertTrue(row().getResumeDeadlineAt().isAfter(LocalDateTime.now()));
        assertEquals(3, row().getRevision());
    }

    @Test
    void aChainedWaitStartsWithoutInheritingTheConsumedAttemptDeadline() {
        when(executor.executeFromCheckpoint(any(), any(), any(), anyInt(), any(), any())).thenReturn(waiting("wfi_next"));
        submit("attempt-a");
        assertEquals("COMPLETED", row().getStatus());
        assertNotNull(row().getResumeDeadlineAt());
        assertNull(sessions.selectById("wfi_next").getResumeDeadlineAt());
        assertFalse(expiry.expireOne(row(), cutoff()));
    }

    @Test
    void expiryEventFailureRollsBackBeforeTraceAndRunWrites() {
        claim();
        failEvent("EXPIRED");
        assertThrows(DataAccessResourceFailureException.class, () -> expiry.expireOne(row(), cutoff()));
        assertEquals("RESUMING", row().getStatus());
        assertNull(row().getResultJson());
        assertEquals(3, eventCount());
        assertEquals("SUSPENDED", runStatus());
        assertEquals("WAITING_USER", spanStatus());
    }

    @Test
    void traceFailureRollsBackTheUnknownReceiptAndItsEvent() {
        claim();
        doThrow(new DataAccessResourceFailureException("trace unavailable"))
                .when(spans).update(isNull(), any(Wrapper.class));
        assertThrows(DataAccessResourceFailureException.class, () -> expiry.expireOne(row(), cutoff()));
        assertEquals("RESUMING", row().getStatus());
        assertEquals(3, eventCount());
        assertEquals("SUSPENDED", runStatus());
    }

    @Test
    void aStaleExpiryCandidateCannotOverwriteACompletedAttempt() {
        claim();
        var candidate = row();
        waits.finishResume(row(), "COMPLETED", Map.of("success", true, "status", "COMPLETED"), "user-a", "COMPLETED");
        String result = row().getResultJson();
        assertFalse(expiry.expireOne(candidate, cutoff()));
        assertEquals(result, row().getResultJson());
        assertEquals("SUSPENDED", runStatus());
        assertEquals("WAITING_USER", spanStatus());
    }

    @Test
    void concurrentReconcilersCommitOnlyOneExpiryAndOneSetOfEvidence() throws Exception {
        claim();
        var first = row();
        var second = row();
        var gate = new CyclicBarrier(2);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var a = workers.submit(() -> { gate.await(10, TimeUnit.SECONDS); return expiry.expireOne(first, cutoff()); });
            var b = workers.submit(() -> { gate.await(10, TimeUnit.SECONDS); return expiry.expireOne(second, cutoff()); });
            assertNotEquals(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
            assertEquals(4, eventCount());
            assertEquals(2, row().getRevision());
            assertEquals("TIMED_OUT", runStatus());
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"RUNNING", "COMPLETED"})
    void recoveryPreservesResumedOrFinishedRootsAndForeignTraceEvidence(String status) {
        claim();
        String span = "RUNNING".equals(status) ? "RUNNING" : "SUCCESS";
        db.jdbc().update("UPDATE runtime_run SET status = ?, output_summary = 'earlier evidence' WHERE trace_id = 'trace-a'", status);
        db.jdbc().update("UPDATE runtime_trace_span SET status = ?, output_summary = 'earlier evidence' WHERE trace_id = 'trace-a'", span);
        db.jdbc().update("INSERT INTO runtime_trace_span (trace_id, span_id, span_type, status) VALUES ('foreign', 'root', 'SUPERVISOR', 'WAITING_USER')");
        assertTrue(expiry.expireOne(row(), cutoff()));
        assertEquals(status, runStatus());
        assertEquals(span, spanStatus());
        assertEquals("earlier evidence", db.jdbc().queryForObject("SELECT output_summary FROM runtime_run WHERE trace_id = 'trace-a'", String.class));
        assertEquals("WAITING_USER", db.jdbc().queryForObject("SELECT status FROM runtime_trace_span WHERE trace_id = 'foreign'", String.class));
    }

    @Test
    void agentResponsePreservesUnknownOutcomeWithoutFinishingOrContinuingTheRoot() {
        claim();
        expiry.expireOne(row(), cutoff());
        var lifecycle = mock(RuntimeAgentRunLifecyclePort.class);
        var supervisor = mock(SupervisorRuntimeAdapter.class);
        var service = new RuntimeAgentExecutionService(
                mock(com.enterprise.ai.runtime.agent.RuntimeAgentExecutionContextResolver.class), supervisor,
                mock(RuntimeSupervisorApprovalPort.class), resume, mock(RuntimeSessionClearPort.class), lifecycle);
        var response = service.execute(Map.of("interactionId", ID, "appId", "orders", "sessionId", "chat-a",
                "values", Map.of("选择", "同意"), "idempotencyKey", "attempt-a"), false, null, null, owner().trustedIdentity());
        assertEquals(false, response.get("success"));
        var metadata = (Map<?, ?>) response.get("metadata");
        assertEquals(CODE, metadata.get("code"));
        assertEquals("TIMED_OUT", metadata.get("status"));
        assertEquals("UNKNOWN", metadata.get("outcome"));
        assertEquals(false, metadata.get("retryable"));
        assertEquals(true, metadata.get("reconciliationRequired"));
        verifyNoInteractions(lifecycle, supervisor);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUPERVISOR_POLICY", "MANAGED_EXECUTOR", "OTHER"})
    void anotherProtocolCannotBeClaimedOrExpiredAsAWorkflow(String source) {
        db.jdbc().update("UPDATE runtime_interaction_session SET source_type = ? WHERE id = ?", source, ID);
        assertFalse(waits.claimResume(row(), "attempt-a", Map.of("action", "submit", "values", Map.of()), "user-a"));
        db.jdbc().update("UPDATE runtime_interaction_session SET status = 'RESUMING', resume_deadline_at = CURRENT_TIMESTAMP WHERE id = ?", ID);
        assertTrue(expiry.findExpiredCandidates(cutoff(), 100).isEmpty());
        assertFalse(expiry.expireOne(row(), cutoff()));
        assertEquals("RESUMING", row().getStatus());
        assertEquals(2, eventCount());
    }

    @Test
    void candidateBatchMergesByActualDeadlineAndClampsBothSmallAndLargeRequests() {
        claim();
        db.jdbc().update("UPDATE runtime_interaction_session SET resume_deadline_at = DATEADD('SECOND', -100, CURRENT_TIMESTAMP) WHERE id = ?", ID);
        for (int i = 0; i < 510; i++) {
            db.jdbc().update("INSERT INTO runtime_interaction_session (id, node_id, interaction_type, expires_at) VALUES (?, 'form', 'COLLECT_INPUT', DATEADD('SECOND', -50, CURRENT_TIMESTAMP))", "queued_" + i);
        }
        assertEquals(List.of(ID), expiry.findExpiredCandidates(LocalDateTime.now(), 0).stream()
                .map(RuntimeInteractionSessionEntity::getId).toList());
        assertEquals(500, expiry.findExpiredCandidates(LocalDateTime.now(), Integer.MAX_VALUE).size());
    }

    @Test
    void oneFailedReconciliationDoesNotPreventTheSchedulerFromClosingOtherCandidates() {
        claim();
        elapseDeadline();
        db.jdbc().update("INSERT INTO runtime_interaction_session (id, node_id, interaction_type, expires_at) VALUES ('other_wait', 'form', 'COLLECT_INPUT', DATEADD('SECOND', -1, CURRENT_TIMESTAMP))");
        doAnswer(call -> {
            RuntimeInteractionEventEntity event = call.getArgument(0);
            if (ID.equals(event.getSessionId()) && "EXPIRED".equals(event.getEventType())) {
                throw new DataAccessResourceFailureException("event unavailable");
            }
            return call.callRealMethod();
        }).when(events).insert(any(RuntimeInteractionEventEntity.class));
        var scheduler = new RuntimeInteractionExpiryScheduler(expiry);
        ReflectionTestUtils.setField(scheduler, "batchSize", 100);
        scheduler.reconcileExpiredInteractions();
        assertEquals("RESUMING", row().getStatus());
        assertEquals("EXPIRED", sessions.selectById("other_wait").getStatus());
        assertEquals("SUSPENDED", runStatus());
    }

    private void claim() {
        assertTrue(waits.claimResume(row(), "attempt-a",
                Map.of("submissionSchemaVersion", 1, "action", "submit", "values", Map.of("选择", "同意")), "user-a"));
    }

    private void assertUnknown(Map<String, Object> response) {
        assertEquals(CODE, response.get("code"));
        assertEquals(false, response.get("success"));
        assertEquals(false, response.get("retryable"));
        assertEquals("UNKNOWN", response.get("outcome"));
        assertEquals(true, response.get("reconciliationRequired"));
        assertEquals(true, response.get("idempotentReplay"));
        assertEquals("trace-a", response.get("traceId"));
        assertFalse(response.containsKey("continuation"));
        assertTrue(String.valueOf(response.get("answer")).contains("未确认"));
    }

    private Map<String, Object> submit(String key) {
        return resume.resume(ID, Map.of("values", Map.of("选择", "同意"), "idempotencyKey", key),
                owner());
    }

    private RuntimeInteractionResumeService.Ownership owner() {
        return new RuntimeInteractionResumeService.Ownership("orders", "chat-a",
                WorkflowExecutionIdentity.fromEmbedSession("tenant-a", null, null, "user-a"));
    }

    private void elapseDeadline() {
        db.jdbc().update("UPDATE runtime_interaction_session SET resume_deadline_at = DATEADD('SECOND', -1, CURRENT_TIMESTAMP) WHERE id = ?", ID);
    }

    private RuntimeGraphSpecExecutionResult waiting(String id) {
        return new RuntimeGraphSpecExecutionResult(false, WorkflowInteractionCodes.WAITING, "请继续填写", "form",
                "INTERACTION", List.of(), Map.of("interactionId", id, "uiRequest",
                        Map.of("component", "form", "interactionId", id)), Map.of("lastOutput", "已保存"));
    }

    private RuntimeGraphSpecExecutionResult done() {
        return new RuntimeGraphSpecExecutionResult(true, "RUNTIME_GRAPH_EXECUTED", "处理完成", "form",
                "INTERACTION", List.of(), Map.of());
    }

    private void failEvent(String type) {
        doAnswer(call -> {
            RuntimeInteractionEventEntity event = call.getArgument(0);
            if (type.equals(event.getEventType())) throw new DataAccessResourceFailureException("event unavailable");
            return call.callRealMethod();
        }).when(events).insert(any(RuntimeInteractionEventEntity.class));
    }

    @SuppressWarnings("unchecked")
    private <T> T transactional(T target) {
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(db.jdbc().getDataSource()),
                new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }

    private LocalDateTime cutoff() { return LocalDateTime.now().plusHours(2); }
    private RuntimeInteractionSessionEntity row() { return sessions.selectById(ID); }
    private int eventCount() { return db.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_interaction_event", Integer.class); }
    private String runStatus() { return db.jdbc().queryForObject("SELECT status FROM runtime_run WHERE trace_id = 'trace-a'", String.class); }
    private String spanStatus() { return db.jdbc().queryForObject("SELECT status FROM runtime_trace_span WHERE trace_id = 'trace-a'", String.class); }
}
