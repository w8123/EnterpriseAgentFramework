package com.enterprise.ai.runtime.execution;

import com.enterprise.ai.runtime.supervisor.SupervisorRuntimeAdapter;
import com.enterprise.ai.runtime.supervisor.RuntimeAgentExecutionService;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.execution.interaction.WorkflowInteractionCodes;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Baseline schema, actual MyBatis and transactional owner; only GraphSpec execution is substituted. */
class RuntimeWorkflowResumePersistenceTest {
    private static final String ID = "wfi_resume";
    private static final String GRAPH = """
            {"schemaVersion":2,"entryNodeId":"form","exitNodeIds":["next"],"nodes":[
              {"id":"form","type":"INTERACTION"},{"id":"next","type":"INTERACTION"}],
              "edges":[{"from":"form","to":"next"}]}
            """;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private RuntimeQueryTestDatabase db;
    private RuntimeInteractionSessionMapper sessions;
    private RuntimeInteractionEventMapper events;
    private RuntimeWorkflowInteractionSessionService waits;
    private RuntimeGraphSpecExecutor executor;
    private RuntimeInteractionResumeService resume;

    @BeforeEach
    void setUp() throws Exception {
        db = new RuntimeQueryTestDatabase(List.of("runtime_interaction_session", "runtime_interaction_event"),
                RuntimeInteractionSessionMapper.class, RuntimeInteractionEventMapper.class);
        sessions = spy(db.mapper(RuntimeInteractionSessionMapper.class));
        events = spy(db.mapper(RuntimeInteractionEventMapper.class));
        waits = transactional(new RuntimeWorkflowInteractionSessionService(sessions, events, json));
        var expiry = transactional(new RuntimeInteractionExpiryProcessor(sessions, waits,
                mock(RuntimeInteractionExpiryTracePort.class), mock(RuntimeSupervisorApprovalService.class)));
        executor = mock(RuntimeGraphSpecExecutor.class);
        when(executor.executeFromCheckpoint(any(), any(), any(), anyInt(), any(), any())).thenReturn(done());
        resume = transactional(new RuntimeInteractionResumeService(waits, executor, json, expiry));
        waits.createWaitingSession(new RuntimeWorkflowInteractionSessionService.CreateRequest(ID, "WORKFLOW",
                "run-a", "trace-a", "wf-a", 23L, GRAPH, "form", "COLLECT_INPUT",
                Map.of("lastOutput", "已读取的业务数据"), Map.of("component", "form"), Map.of(),
                "orders", "tenant-a", "chat-a", "user-a", 120));
    }

    @AfterEach
    void close() { if (db != null) db.close(); }

    @Test
    void submittedEventFailureRollsBackClaimBeforeAnyExternalExecution() {
        failEvent("SUBMITTED");
        assertThrows(DataAccessResourceFailureException.class, this::submit);
        assertEquals("WAITING_USER", row().getStatus());
        assertEquals(0, row().getRevision());
        assertNull(row().getIdempotencyKey());
        assertEquals(2, eventCount());
        verifyNoInteractions(executor);
    }

    @Test
    void completedEventFailureCannotCommitTheTerminalStateOrEarlierResumedEvent() {
        failEvent("COMPLETED");
        assertThrows(DataAccessResourceFailureException.class, this::submit);
        assertEquals("RESUMING", row().getStatus());
        assertEquals(1, row().getRevision());
        assertNull(row().getResultJson());
        assertEquals(3, eventCount());
    }

    @Test
    void thrownExecutorFailureProducesADurableFailedOutcomeWithoutLeakingItsMessage() {
        when(executor.executeFromCheckpoint(any(), any(), any(), anyInt(), any(), any()))
                .thenThrow(new IllegalStateException("private-executor-credential"));
        var response = assertDoesNotThrow(this::submit);
        assertEquals(false, response.get("success"));
        assertEquals("FAILED", row().getStatus());
        assertEquals(4, eventCount());
        assertFalse(response.toString().contains("private-executor-credential"));
    }

    @Test
    void replayOfAFailedExecutionMustRemainFailed() {
        when(executor.executeFromCheckpoint(any(), any(), any(), anyInt(), any(), any())).thenReturn(
                new RuntimeGraphSpecExecutionResult(false, "RUNTIME_GRAPH_FAILED", "节点失败", "next", "INTERACTION",
                        List.of(), Map.of()));
        var first = submit();
        assertEquals(false, first.get("success"));
        var replay = submit();
        assertEquals(false, replay.get("success"));
        assertEquals("RUNTIME_GRAPH_FAILED", replay.get("code"));
        assertEquals(true, replay.get("idempotentReplay"));
        verify(executor, times(1)).executeFromCheckpoint(any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void replayOfAChainedWaitReturnsTheSavedNextInteractionAndUi() {
        when(executor.executeFromCheckpoint(any(), any(), any(), anyInt(), any(), any())).thenReturn(waiting("wfi_next", "next"));
        var first = submit();
        assertEquals("wfi_next", first.get("interactionId"));
        var replay = submit();
        assertEquals("wfi_next", replay.get("interactionId"));
        assertEquals(first.get("uiRequest"), replay.get("uiRequest"));
        assertEquals("WAITING_USER", replay.get("status"));
        assertEquals(true, replay.get("waiting"));
        assertEquals(2, db.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_interaction_session", Integer.class));
        verify(executor, times(1)).executeFromCheckpoint(any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void successfulReplayKeepsTheOriginalStepsMetadataAndPresentation() {
        var first = submit();
        var replay = submit();
        assertEquals(first.get("steps"), replay.get("steps"));
        assertEquals(first.get("metadata"), replay.get("metadata"));
        assertEquals(first.get("uiRequest"), replay.get("uiRequest"));
        assertEquals("COMPLETED", replay.get("status"));
    }

    @Test
    void aCompletedAttemptRemainsReplayableAfterItsFormerWaitingDeadline() {
        submit();
        db.jdbc().update("UPDATE runtime_interaction_session SET expires_at = DATEADD('SECOND', -1, CURRENT_TIMESTAMP) WHERE id = ?", ID);
        var replay = submit();
        assertEquals(true, replay.get("idempotentReplay"));
        assertEquals("COMPLETED", row().getStatus());
        verify(executor, times(1)).executeFromCheckpoint(any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void aLateWorkerCannotOverwriteANewerTerminalRevision() {
        when(executor.executeFromCheckpoint(any(), any(), any(), anyInt(), any(), any())).thenAnswer(call -> {
            db.jdbc().update("UPDATE runtime_interaction_session SET status = 'CANCELLED', revision = revision + 1 WHERE id = ?", ID);
            return done();
        });
        var response = submit();
        assertEquals("RUNTIME_INTERACTION_CONFLICT", response.get("code"));
        assertEquals("CANCELLED", row().getStatus());
        assertEquals(2, row().getRevision());
        assertEquals(3, eventCount());
    }

    @Test
    void theSameIdempotencyKeyCannotChangeFromSubmitToCancel() {
        submit();
        var changedAction = resume.resume(ID, Map.of("action", "cancel", "values", Map.of("选择", "同意"),
                "idempotencyKey", "attempt-a"), owner());
        assertEquals("RUNTIME_INTERACTION_CONFLICT", changedAction.get("code"));
        assertEquals("COMPLETED", row().getStatus());
        verify(executor, times(1)).executeFromCheckpoint(any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void validationEventFailureCannotCommitAFreshWaitingState() {
        String originalCheckpoint = row().getResumeCheckpointJson();
        failEvent("REQUESTED");
        when(executor.executeFromCheckpoint(any(), any(), any(), anyInt(), any(), any())).thenReturn(waiting(ID, "form"));
        assertThrows(DataAccessResourceFailureException.class, this::submit);
        assertEquals("RESUMING", row().getStatus());
        assertEquals(1, row().getRevision());
        assertEquals(originalCheckpoint, row().getResumeCheckpointJson());
        assertEquals("attempt-a", row().getIdempotencyKey());
    }

    @Test
    void anExpiredWaitingSessionCannotExecute() {
        db.jdbc().update("UPDATE runtime_interaction_session SET expires_at = DATEADD('SECOND', -1, CURRENT_TIMESTAMP) WHERE id = ?", ID);
        var response = submit();
        assertEquals("RUNTIME_INTERACTION_EXPIRED", response.get("code"));
        assertEquals("EXPIRED", row().getStatus());
        verifyNoInteractions(executor);
    }

    @Test
    void twoRequestsReadingTheSameWaitingRevisionExecuteOnlyOnce() throws Exception {
        CyclicBarrier bothRead = new CyclicBarrier(2);
        AtomicInteger reads = new AtomicInteger();
        doAnswer(call -> {
            Object loaded = call.callRealMethod();
            if (reads.getAndIncrement() < 2) bothRead.await(10, TimeUnit.SECONDS);
            return loaded;
        }).when(sessions).selectById(ID);
        CountDownLatch executing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(executor.executeFromCheckpoint(any(), any(), any(), anyInt(), any(), any())).thenAnswer(call -> {
            executing.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return done();
        });
        var pool = Executors.newFixedThreadPool(2);
        var completions = new ExecutorCompletionService<Map<String, Object>>(pool);
        try {
            completions.submit(this::submit);
            completions.submit(this::submit);
            assertTrue(executing.await(10, TimeUnit.SECONDS));
            var loser = completions.poll(10, TimeUnit.SECONDS);
            assertNotNull(loser);
            var inProgress = loser.get();
            assertEquals(false, inProgress.get("success"));
            assertEquals("RUNTIME_INTERACTION_CONFLICT", inProgress.get("code"));
            assertEquals(true, inProgress.get("retryable"));
            release.countDown();
            var winner = completions.poll(10, TimeUnit.SECONDS);
            assertNotNull(winner);
            assertEquals(true, winner.get().get("success"));
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals("COMPLETED", row().getStatus());
        assertEquals(5, eventCount());
        verify(executor, times(1)).executeFromCheckpoint(any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void workflowRunsOutsideTheCallersTransactionAndItsOutcomeSurvivesCallerRollback() {
        when(executor.executeFromCheckpoint(any(), any(), any(), anyInt(), any(), any())).thenAnswer(call -> {
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            return done();
        });
        var outer = new org.springframework.transaction.support.TransactionTemplate(
                new DataSourceTransactionManager(db.jdbc().getDataSource()));
        outer.executeWithoutResult(status -> {
            assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(true, submit().get("success"));
            assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            db.jdbc().update("INSERT INTO runtime_interaction_event(session_id,event_type) VALUES (?, 'OUTER_ONLY')", ID);
            status.setRollbackOnly();
        });
        assertEquals("COMPLETED", row().getStatus());
        assertEquals(5, eventCount());
    }

    @Test
    void validationReleasesTheAttemptAndAllowsCorrectedValuesWithTheSameKey() {
        when(executor.executeFromCheckpoint(any(), any(), any(), anyInt(), any(), any()))
                .thenReturn(waiting(ID, "form"), done());
        var validation = submit();
        assertEquals(true, validation.get("validationFailed"));
        assertEquals("WAITING_USER", row().getStatus());
        assertNull(row().getIdempotencyKey());
        assertNull(row().getSubmittedPayloadJson());
        assertNull(row().getResultJson());
        assertEquals(2, row().getRevision());
        var corrected = resume.resume(ID, Map.of("values", Map.of("选择", "已修改"), "idempotencyKey", "attempt-a"), owner());
        assertEquals(true, corrected.get("success"));
        assertEquals("COMPLETED", row().getStatus());
        assertEquals(4, row().getRevision());
        verify(executor, times(2)).executeFromCheckpoint(any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void uiSubmitAndExplicitValuesHaveTheSameNormalizedSubmissionIdentity() {
        submit();
        var replay = resume.resume(ID, Map.of("uiSubmit", Map.of("action", "SUBMIT", "values", Map.of("选择", "同意")),
                "idempotencyKey", "attempt-a"), owner());
        assertEquals(true, replay.get("idempotentReplay"));
        verify(executor, times(1)).executeFromCheckpoint(any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void explicitEmptyValuesDoNotCaptureNewTransportMetadataOnEveryRetry() {
        resume.resume(ID, Map.of("values", Map.of(), "idempotencyKey", "empty", "traceId", "request-one"), owner());
        var replay = resume.resume(ID, Map.of("values", Map.of(), "idempotencyKey", "empty", "traceId", "request-two",
                "metadata", Map.of("transport", "changed")), owner());
        assertEquals(true, replay.get("idempotentReplay"));
        verify(executor, times(1)).executeFromCheckpoint(any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void cancellationCanReplayAfterTheFormerWaitingDeadlineWithoutExecuting() {
        var body = Map.<String, Object>of("action", "cancel", "values", Map.of(), "idempotencyKey", "cancel-a");
        var first = resume.resume(ID, body, owner());
        db.jdbc().update("UPDATE runtime_interaction_session SET expires_at = DATEADD('SECOND', -1, CURRENT_TIMESTAMP) WHERE id = ?", ID);
        var replay = resume.resume(ID, body, owner());
        assertEquals(first.get("code"), replay.get("code"));
        assertEquals(true, replay.get("success"));
        assertEquals(true, replay.get("idempotentReplay"));
        assertEquals("CANCELLED", replay.get("status"));
        assertEquals(4, eventCount());
        verifyNoInteractions(executor);
    }

    @Test
    void corruptCheckpointFailsWithDurableRejectionAndNeverExecutes() {
        db.jdbc().update("UPDATE runtime_interaction_session SET checkpoint_digest = ? WHERE id = ?", "0".repeat(64), ID);
        var first = submit();
        assertEquals(false, first.get("success"));
        assertTrue(first.get("code").toString().contains("CHECKPOINT"));
        assertEquals("trace-a", first.get("traceId"));
        assertEquals("FAILED", row().getStatus());
        assertEquals(5, eventCount());
        var replay = submit();
        assertEquals(first.get("code"), replay.get("code"));
        assertEquals(false, replay.get("success"));
        assertEquals(5, eventCount());
        verifyNoInteractions(executor);
    }

    @Test
    void aStaleValidationWorkerCannotRestoreWaitingOverANewerTerminalState() {
        String checkpoint = row().getResumeCheckpointJson();
        when(executor.executeFromCheckpoint(any(), any(), any(), anyInt(), any(), any())).thenAnswer(call -> {
            cancelNewRevision();
            return waiting(ID, "form");
        });
        assertEquals("RUNTIME_INTERACTION_CONFLICT", submit().get("code"));
        assertEquals("CANCELLED", row().getStatus());
        assertEquals(checkpoint, row().getResumeCheckpointJson());
        assertEquals(3, eventCount());
    }

    @Test
    void aStaleChainedWorkerCannotCreateAnOrphanNextWait() {
        when(executor.executeFromCheckpoint(any(), any(), any(), anyInt(), any(), any())).thenAnswer(call -> {
            cancelNewRevision();
            return waiting("wfi_next", "next");
        });
        assertEquals("RUNTIME_INTERACTION_CONFLICT", submit().get("code"));
        assertEquals("CANCELLED", row().getStatus());
        assertNull(sessions.selectById("wfi_next"));
        assertEquals(3, eventCount());
    }

    @Test
    void chainedCreationFailurePreservesTheCommittedClaimAndRollsBackItsOutcome() {
        failEvent("REQUESTED");
        when(executor.executeFromCheckpoint(any(), any(), any(), anyInt(), any(), any())).thenReturn(waiting("wfi_next", "next"));
        assertThrows(DataAccessResourceFailureException.class, this::submit);
        assertEquals("RESUMING", row().getStatus());
        assertNull(row().getResultJson());
        assertNull(sessions.selectById("wfi_next"));
        assertEquals(3, eventCount());
    }

    @Test
    void failedEventCannotCommitOnlyTheFailureState() {
        failEvent("FAILED");
        when(executor.executeFromCheckpoint(any(), any(), any(), anyInt(), any(), any())).thenReturn(
                new RuntimeGraphSpecExecutionResult(false, "RUNTIME_GRAPH_FAILED", "节点失败", "form", "INTERACTION", List.of(), Map.of()));
        assertThrows(DataAccessResourceFailureException.class, this::submit);
        assertEquals("RESUMING", row().getStatus());
        assertNull(row().getResultJson());
        assertEquals(3, eventCount());
    }

    @Test
    void replayDoesNotRedispatchTheSupervisorContinuation() {
        db.jdbc().update("UPDATE runtime_interaction_session SET continuation_json = ? WHERE id = ?",
                "{\"agentId\":\"agent-a\",\"agentConfigVersionId\":11,\"originalInput\":{\"message\":\"private-original\"}}", ID);
        assertTrue(submit().containsKey("continuation"));
        var replay = submit();
        assertEquals(true, replay.get("idempotentReplay"));
        assertFalse(replay.containsKey("continuation"));
        assertFalse(replay.toString().contains("private-original"));
        verify(executor, times(1)).executeFromCheckpoint(any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void replayingTheWorkflowReceiptCannotFinishAnAgentWhoseContinuationMayStillBeRunning() {
        when(executor.executeFromCheckpoint(any(), any(), any(), anyInt(), any(), any())).thenReturn(
                done().withMetadata(Map.of("idempotentReplay", false)));
        db.jdbc().update("UPDATE runtime_interaction_session SET continuation_json = ? WHERE id = ?",
                "{\"agentId\":\"agent-a\",\"agentConfigVersionId\":11}", ID);
        assertTrue(submit().containsKey("continuation"));
        var lifecycle = mock(com.enterprise.ai.runtime.runops.RuntimeRunLifecycleService.class);
        var supervisor = mock(SupervisorRuntimeAdapter.class);
        var service = new RuntimeAgentExecutionService(
                mock(com.enterprise.ai.runtime.agent.RuntimeAgentExecutionContextResolver.class), supervisor,
                mock(RuntimeSupervisorApprovalPort.class), resume, mock(RuntimeSessionClearPort.class), lifecycle);
        var response = service.execute(Map.of("interactionId", ID, "appId", "orders", "sessionId", "chat-a",
                "values", Map.of("选择", "同意"), "idempotencyKey", "attempt-a"), false, null, null, owner().trustedIdentity());
        assertEquals(true, response.get("success"));
        verifyNoInteractions(lifecycle, supervisor);
        assertEquals(true, ((Map<?, ?>) response.get("metadata")).get("idempotentReplay"));
    }

    @Test
    void aDifferentAttemptCannotTakeOverAnAlreadyRunningSubmission() {
        db.jdbc().update("UPDATE runtime_interaction_session SET status = 'RESUMING', revision = 1, idempotency_key = 'another' WHERE id = ?", ID);
        assertEquals("RUNTIME_INTERACTION_CONFLICT", submit().get("code"));
        assertEquals("RESUMING", row().getStatus());
        verifyNoInteractions(executor);
    }

    private void cancelNewRevision() {
        db.jdbc().update("UPDATE runtime_interaction_session SET status = 'CANCELLED', revision = revision + 1 WHERE id = ?", ID);
    }

    private RuntimeGraphSpecExecutionResult done() {
        return new RuntimeGraphSpecExecutionResult(true, "RUNTIME_GRAPH_EXECUTED", "处理完成", "next", "INTERACTION",
                List.of(Map.of("nodeId", "next", "status", "COMPLETED")),
                Map.of("summary", "一项完成", "uiRequest", Map.of("component", "summary", "text", "处理完成")));
    }

    private RuntimeGraphSpecExecutionResult waiting(String id, String node) {
        return new RuntimeGraphSpecExecutionResult(false, WorkflowInteractionCodes.WAITING, "请继续填写", node,
                "INTERACTION", List.of(), Map.of("interactionId", id, "uiRequest",
                        Map.of("component", "form", "interactionId", id, "title", "下一步")), Map.of("lastOutput", "新断点"));
    }

    private Map<String, Object> submit() {
        return resume.resume(ID, Map.of("values", Map.of("选择", "同意"), "idempotencyKey", "attempt-a"), owner());
    }

    private RuntimeInteractionResumeService.Ownership owner() {
        return new RuntimeInteractionResumeService.Ownership("orders", "chat-a",
                WorkflowExecutionIdentity.fromEmbedSession("tenant-a", null, null, "user-a"));
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

    private RuntimeInteractionSessionEntity row() { return sessions.selectById(ID); }
    private int eventCount() { return db.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_interaction_event", Integer.class); }
}
