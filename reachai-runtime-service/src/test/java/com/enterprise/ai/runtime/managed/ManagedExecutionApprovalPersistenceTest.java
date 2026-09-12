package com.enterprise.ai.runtime.managed;

import com.enterprise.ai.runtime.execution.RuntimeInteractionEventEntity;
import com.enterprise.ai.runtime.execution.RuntimeInteractionEventMapper;
import com.enterprise.ai.runtime.execution.RuntimeInteractionSessionEntity;
import com.enterprise.ai.runtime.execution.RuntimeInteractionSessionMapper;
import com.enterprise.ai.runtime.execution.RuntimeManagedApprovalInteractionStore;
import com.enterprise.ai.runtime.managed.ManagedExecutionPayloadSanitizer.SanitizedEvent;
import com.enterprise.ai.runtime.managed.ManagedExecutionViews.ApprovalDecisionRequest;
import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ManagedExecutionApprovalPersistenceTest {
    private static final String ID = "mex_approval_1";
    private RuntimeQueryTestDatabase database;
    private AnnotationConfigApplicationContext context;
    private ManagedExecutionMapper executions;
    private RuntimeInteractionSessionMapper sessions;
    private RuntimeInteractionEventMapper events;
    private ManagedExecutionApprovalService approvals;
    private String interactionId;

    @BeforeEach
    void setUp() throws Exception {
        database = new RuntimeQueryTestDatabase(java.util.List.of("runtime_managed_execution",
                "runtime_interaction_session", "runtime_interaction_event"), ManagedExecutionMapper.class,
                RuntimeInteractionSessionMapper.class, RuntimeInteractionEventMapper.class);
        executions = spy(database.mapper(ManagedExecutionMapper.class));
        sessions = spy(database.mapper(RuntimeInteractionSessionMapper.class));
        events = spy(database.mapper(RuntimeInteractionEventMapper.class));
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean(DataSourceTransactionManager.class,
                () -> new DataSourceTransactionManager(database.jdbc().getDataSource()));
        context.registerBean(RuntimeManagedApprovalInteractionStore.class,
                () -> new RuntimeManagedApprovalInteractionStore(sessions, events, new ObjectMapper()));
        context.registerBean(ManagedExecutionApprovalService.class,
                () -> new ManagedExecutionApprovalService(context.getBean(RuntimeManagedApprovalInteractionStore.class),
                        executions, new ObjectMapper()));
        context.refresh();
        approvals = context.getBean(ManagedExecutionApprovalService.class);
        database.jdbc().update("""
                INSERT INTO runtime_managed_execution (id, execution_id, tenant_id, project_code,
                    requested_by_user_id, source_type, source_ref, sandbox_profile,
                    objective_text, objective_sha256, status, max_wall_time_seconds, approval_timeout_seconds, command_sequence)
                VALUES (1, ?, 'tenant-a', 'PROJECT_A', 'user-a', 'AI_CODING_TASK', 'task-1',
                    'ANALYZE_READONLY', 'private objective', ?, 'WAITING_APPROVAL', 600, 600, 4)
                """, ID, "a".repeat(64));
    }

    @AfterEach
    void close() {
        if (context != null) context.close();
        if (database != null) database.close();
    }

    @Test
    void workerCannotAcceptWithoutARecordedRuntimeApproval() {
        open();
        assertCode("MANAGED_APPROVAL_DECISION_MISSING", () -> approvals.onResolved(current(), resolved("accept")));
        assertEquals("WAITING_USER", interaction().getStatus());
        assertEquals(interactionId, current().getPendingInteractionId());
        assertEquals(0, eventCount("COMPLETED"));
    }

    @Test
    void failedCompletionCasCannotPublishACompletedEventOrClearThePendingRequest() {
        open();
        approve();
        // Current reads now hold the Session lock. Inject a lost CAS result to check atomic rollback.
        doReturn(0).when(sessions).update(org.mockito.ArgumentMatchers.isNull(), any());
        assertCode("MANAGED_APPROVAL_CONFLICT", () -> approvals.onResolved(current(), resolved("accept")));
        assertEquals("RESUMING", database.jdbc().queryForObject(
                "SELECT status FROM runtime_interaction_session WHERE id = ?", String.class, interactionId));
        assertEquals(interactionId, current().getPendingInteractionId());
        assertEquals(0, eventCount("COMPLETED"));
    }

    @ParameterizedTest
    @CsvSource({"run_id,another-run", "trace_id,another-trace", "tenant_id,another-tenant",
            "user_id,another-user", "interaction_type,FORM", "node_id,another-node", "source_type,WORKFLOW"})
    void workerCompletionRejectsAForeignInteraction(String column, String value) {
        open();
        approve();
        alterInteraction(column, value);
        assertCode("MANAGED_APPROVAL_NOT_FOUND", () -> approvals.onResolved(current(), resolved("accept")));
        assertEquals("RESUMING", interaction().getStatus());
        assertEquals(interactionId, current().getPendingInteractionId());
        assertEquals(0, eventCount("COMPLETED"));
    }

    @ParameterizedTest
    @CsvSource({"run_id,another-run", "trace_id,another-trace", "tenant_id,another-tenant",
            "user_id,another-user", "interaction_type,FORM", "node_id,another-node"})
    void terminalCleanupDetachesItsPointerWithoutCancellingAForeignInteraction(String column, String value) {
        open();
        alterInteraction(column, value);
        database.jdbc().update("UPDATE runtime_managed_execution SET status = 'CANCELLED' WHERE id = 1");
        approvals.onExecutionTerminal(current());
        assertEquals("WAITING_USER", interaction().getStatus());
        assertEquals(0, interaction().getRevision());
        assertNull(current().getPendingInteractionId());
        assertEquals(0, eventCount("CANCELLED"));
    }

    @Test
    void repeatedRequestKeepsTheCounterAndAlreadyRecordedDecision() {
        open();
        approve();
        ManagedExecutionEntity replay = current();
        approvals.onRequested(replay, requested("git"));
        assertEquals(1, replay.getApprovalCount());
        assertEquals("accept", replay.getApprovalDecision());
        assertEquals(5L, replay.getCommandSequence());
        assertEquals(1, eventCount("CREATED"));
    }

    @Test
    void reusedRequestIdCannotChangeTheReviewedCommand() {
        open();
        assertCode("MANAGED_APPROVAL_REPLAY_MISMATCH", () -> approvals.onRequested(current(), requested("different-command")));
        assertEquals(1, current().getApprovalCount());
        assertEquals(1, eventCount("CREATED"));
    }

    @Test
    void completedRequestIdCannotBeReopened() {
        open();
        approve();
        approvals.onResolved(current(), resolved("accept"));
        assertCode("MANAGED_APPROVAL_REPLAY_MISMATCH", () -> approvals.onRequested(current(), requested("git")));
        assertNull(current().getPendingInteractionId());
        assertEquals("COMPLETED", interaction().getStatus());
        assertEquals(1, current().getApprovalCount());
    }

    @Test
    void acceptedDecisionReplayRetainsItsOriginalOutcomeAfterTheDeadline() {
        open();
        var first = approve();
        expire();
        var replay = approve();
        assertTrue(replay.idempotentReplay());
        assertEquals(first.commandSequence(), replay.commandSequence());
        assertEquals("accept", replay.decision());
        assertFalse(replay.expired());
        assertEquals(1, eventCount("SUBMITTED"));
    }

    @Test
    void expiredDecisionsStillDistinguishDifferentRequestsUsingTheSameKey() {
        open();
        expire();
        assertEquals("decline", approve().decision());
        assertCode("MANAGED_APPROVAL_IDEMPOTENCY_CONFLICT", () -> approvals.resolve(current(), "user-a", interactionId,
                new ApprovalDecisionRequest("REJECT", "decision-1")));
        assertEquals(5L, current().getCommandSequence());
        assertEquals(1, eventCount("EXPIRED"));
    }

    @Test
    void createdEventDuplicateIsNotMistakenForAnIdempotentSessionInsert() {
        doThrow(new DuplicateKeyException("injected event constraint")).when(events).insert(any(RuntimeInteractionEventEntity.class));
        assertThrows(DuplicateKeyException.class, this::open);
        assertEquals(0, sessions.selectCount(null));
        assertNull(current().getPendingInteractionId());
        assertEquals(0, current().getApprovalCount());
    }

    @Test
    void approvalDecisionAndWorkerAcknowledgementRemainOneShot() {
        open();
        assertFalse(approve().idempotentReplay());
        assertTrue(approve().idempotentReplay());
        approvals.onResolved(current(), resolved("accept"));
        assertNull(approvals.onResolved(current(), resolved("accept")));
        assertEquals("COMPLETED", interaction().getStatus());
        assertEquals(2, interaction().getRevision());
        assertNull(current().getPendingInteractionId());
        assertEquals(5L, current().getCommandSequence());
        assertEquals(1, eventCount("CREATED"));
        assertEquals(1, eventCount("SUBMITTED"));
        assertEquals(1, eventCount("COMPLETED"));
    }

    @Test
    void onlyTheRequestingUserCanDecide() {
        open();
        assertCode("MANAGED_APPROVAL_ACTOR_FORBIDDEN", () -> approvals.resolve(current(), "user-b", interactionId,
                new ApprovalDecisionRequest("APPROVE", "decision-1")));
        assertEquals("WAITING_USER", interaction().getStatus());
        assertEquals(4L, current().getCommandSequence());
    }

    @Test
    void permissionExpansionNeverCreatesAnApproval() {
        var permission = requested("git");
        permission = new SanitizedEvent(permission.sequence(), permission.eventId(), permission.occurredAt(), permission.type(),
                permission.phase(), permission.visibility(), permission.message(),
                "{\"approvalKind\":\"PERMISSIONS\"}", permission.payloadSha256());
        assertNull(approvals.onRequested(current(), permission));
        assertEquals(0, sessions.selectCount(null));
        assertEquals(0, events.selectCount(null));
    }

    @Test
    void staleTerminalCallbackDoesNotCancelAnActuallyActiveApproval() {
        open();
        var stale = current();
        stale.setStatus("CANCELLED");
        approvals.onExecutionTerminal(stale);
        assertEquals("WAITING_USER", interaction().getStatus());
        assertEquals(interactionId, current().getPendingInteractionId());
        assertEquals(0, eventCount("CANCELLED"));
    }

    @Test
    void executionFenceFailureRollsBackTheSessionAndSubmittedEvent() {
        open();
        doReturn(0).when(executions).resolveApproval(any(), any(), any(), any(), any());
        assertCode("MANAGED_APPROVAL_CONFLICT", this::approve);
        assertEquals("WAITING_USER", interaction().getStatus());
        assertEquals(0, interaction().getRevision());
        assertNull(interaction().getSubmittedPayloadJson());
        assertNull(current().getApprovalDecision());
        assertEquals(4L, current().getCommandSequence());
        assertEquals(0, eventCount("SUBMITTED"));
    }

    @Test
    void submittedEventFailureRollsBackBothOwners() {
        open();
        doThrow(new IllegalStateException("injected decision event failure")).when(events).insert(any(RuntimeInteractionEventEntity.class));
        assertThrows(IllegalStateException.class, this::approve);
        assertEquals("WAITING_USER", interaction().getStatus());
        assertEquals(0, interaction().getRevision());
        assertNull(interaction().getIdempotencyKey());
        assertNull(current().getApprovalDecision());
        assertEquals(4L, current().getCommandSequence());
        assertEquals(0, eventCount("SUBMITTED"));
    }

    @Test
    void expiredDeclineAcknowledgementPreservesExpiryEvidence() {
        open();
        expire();
        assertTrue(approve().expired());
        approvals.onResolved(current(), resolved("decline"));
        assertEquals("EXPIRED", interaction().getStatus());
        assertNull(current().getPendingInteractionId());
        assertEquals(1, eventCount("EXPIRED"));
        assertEquals(0, eventCount("COMPLETED"));
    }

    @Test
    void terminalCancellationIncrementsTheLiveRevisionAfterAConcurrentSessionUpdate() {
        open();
        database.jdbc().update("UPDATE runtime_managed_execution SET status = 'CANCELLED' WHERE id = 1");
        doAnswer(call -> {
            try (var connection = database.jdbc().getDataSource().getConnection();
                 var update = connection.prepareStatement("UPDATE runtime_interaction_session SET revision = 9, status = 'RESUMING' WHERE id = ?")) {
                assertTrue(connection.getAutoCommit());
                update.setString(1, interactionId);
                update.executeUpdate();
            }
            return call.callRealMethod();
        }).when(sessions).update(org.mockito.ArgumentMatchers.isNull(), any());
        approvals.onExecutionTerminal(current());
        var result = database.jdbc().queryForMap("SELECT status, revision FROM runtime_interaction_session WHERE id = ?", interactionId);
        assertEquals("CANCELLED", result.get("status"));
        assertEquals(10, result.get("revision"));
        assertEquals(1, eventCount("CANCELLED"));
        assertNull(current().getPendingInteractionId());
    }

    @Test
    void concurrentIdenticalDecisionsSerializeOnTheExecutionAndProduceOneCommand() throws Exception {
        open();
        var submitted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        doAnswer(call -> {
            Object inserted = call.callRealMethod();
            submitted.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return inserted;
        }).when(events).insert(any(RuntimeInteractionEventEntity.class));
        try {
            var first = pool.submit(this::approve);
            assertTrue(submitted.await(10, TimeUnit.SECONDS));
            var second = pool.submit(this::approve);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean blocked = false;
            while (System.nanoTime() < deadline) {
                if (database.jdbc().queryForObject("SELECT COUNT(*) FROM information_schema.sessions WHERE blocker_id IS NOT NULL", Integer.class) > 0) {
                    blocked = true;
                    break;
                }
                Thread.sleep(10);
            }
            assertTrue(blocked, "The second decision must wait for the owning aggregate transaction");
            release.countDown();
            var decided = first.get(10, TimeUnit.SECONDS);
            var replayed = second.get(10, TimeUnit.SECONDS);
            assertFalse(decided.idempotentReplay());
            assertTrue(replayed.idempotentReplay());
            assertEquals(decided.commandSequence(), replayed.commandSequence());
            assertEquals(5L, current().getCommandSequence());
            assertEquals(1, eventCount("SUBMITTED"));
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private void open() {
        interactionId = approvals.onRequested(current(), requested("git")).interactionId();
    }

    private ManagedExecutionViews.ApprovalDecisionView approve() {
        return approvals.resolve(current(), "user-a", interactionId, new ApprovalDecisionRequest("APPROVE", "decision-1"));
    }

    private void expire() {
        database.jdbc().update("UPDATE runtime_interaction_session SET expires_at = ? WHERE id = ?", LocalDateTime.now().minusMinutes(1), interactionId);
    }

    private void alterInteraction(String column, String value) {
        database.jdbc().update("UPDATE runtime_interaction_session SET " + column + " = ? WHERE id = ?", value, interactionId);
    }

    private ManagedExecutionEntity current() { return executions.selectById(1L); }
    private RuntimeInteractionSessionEntity interaction() { return sessions.selectById(interactionId); }
    private int eventCount(String type) {
        return database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_interaction_event WHERE session_id = ? AND event_type = ?", Integer.class, interactionId, type);
    }

    private void assertCode(String code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(ManagedExecutionException.class, action).code());
    }

    private SanitizedEvent requested(String command) {
        return new SanitizedEvent(3, ID + ":3", Instant.now(), "APPROVAL_REQUESTED", "WAITING_APPROVAL", "OPERATOR", "Command approval required",
                "{\"approvalRequestId\":\"approval-1\",\"approvalKind\":\"COMMAND\",\"command\":[\"" + command + "\",\"status\"]}", "a".repeat(64));
    }

    private SanitizedEvent resolved(String decision) {
        return new SanitizedEvent(4, ID + ":4", Instant.now(), "APPROVAL_RESOLVED", "RUNNING", "OPERATOR", "Approval resolved",
                "{\"approvalRequestId\":\"approval-1\",\"approvalKind\":\"COMMAND\",\"decision\":\"" + decision + "\"}", "b".repeat(64));
    }

    @Configuration
    @EnableTransactionManagement
    static class Transactions { }
}
