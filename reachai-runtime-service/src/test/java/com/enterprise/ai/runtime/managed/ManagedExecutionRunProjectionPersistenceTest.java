package com.enterprise.ai.runtime.managed;

import com.enterprise.ai.runtime.managed.ManagedExecutionViews.WorkerEventBatchRequest;
import com.enterprise.ai.runtime.managed.ManagedExecutionViews.WorkerEventV1;
import com.enterprise.ai.runtime.managed.ManagedExecutionViews.ApprovalDecisionRequest;
import com.enterprise.ai.runtime.execution.RuntimeManagedApprovalInteractionStore;
import com.enterprise.ai.runtime.execution.RuntimeInteractionSessionMapper;
import com.enterprise.ai.runtime.execution.RuntimeInteractionEventMapper;
import com.enterprise.ai.runtime.runops.RuntimeRunEntity;
import com.enterprise.ai.runtime.runops.RuntimeRunMapper;
import com.enterprise.ai.runtime.runops.RuntimeManagedRunProjectionWriter;
import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real aggregate, event, outbox and Run SQL in the same Spring transaction. */
class ManagedExecutionRunProjectionPersistenceTest {
    private static final String ID = "mex_projection_1";
    private final ObjectMapper json = new ObjectMapper();
    private final ManagedWorkerTokenService tokens = new ManagedWorkerTokenService();
    private RuntimeQueryTestDatabase database;
    private AnnotationConfigApplicationContext context;
    private ManagedExecutionMapper executions;
    private ManagedExecutionOutboxMapper outbox;
    private RuntimeRunMapper runs;
    private ManagedExecutionRunProjector projector;
    private ManagedExecutionService service;
    private TransactionTemplate transaction;
    private String workerToken;

    @BeforeEach
    void setUp() throws Exception {
        database = new RuntimeQueryTestDatabase(List.of("runtime_managed_execution",
                "runtime_managed_execution_event", "runtime_managed_execution_outbox", "runtime_run",
                "runtime_interaction_session", "runtime_interaction_event"),
                ManagedExecutionMapper.class, ManagedExecutionEventMapper.class,
                ManagedExecutionOutboxMapper.class, RuntimeRunMapper.class,
                RuntimeInteractionSessionMapper.class, RuntimeInteractionEventMapper.class);
        executions = spy(database.mapper(ManagedExecutionMapper.class));
        outbox = spy(database.mapper(ManagedExecutionOutboxMapper.class));
        runs = spy(database.mapper(RuntimeRunMapper.class));
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean(DataSourceTransactionManager.class,
                () -> new DataSourceTransactionManager(database.jdbc().getDataSource()));
        context.registerBean(RuntimeManagedRunProjectionWriter.class,
                () -> new RuntimeManagedRunProjectionWriter(runs, json));
        context.registerBean(RuntimeManagedApprovalInteractionStore.class,
                () -> new RuntimeManagedApprovalInteractionStore(database.mapper(RuntimeInteractionSessionMapper.class),
                        database.mapper(RuntimeInteractionEventMapper.class), json));
        context.registerBean(ManagedExecutionApprovalService.class,
                () -> new ManagedExecutionApprovalService(context.getBean(RuntimeManagedApprovalInteractionStore.class), executions, json));
        context.registerBean(ManagedExecutionRunProjector.class,
                () -> new ManagedExecutionRunProjector(executions,
                        context.getBean(RuntimeManagedRunProjectionWriter.class), json));
        var properties = ManagedExecutorPropertiesTest.properties(true, "PROJECT_A", "ANALYZE_READONLY", 2);
        context.registerBean(ManagedExecutionService.class, () -> new ManagedExecutionService(
                executions, database.mapper(ManagedExecutionEventMapper.class), mock(ManagedArtifactMapper.class),
                outbox, properties,
                tokens, new ManagedExecutionPayloadSanitizer(json, properties), context.getBean(ManagedExecutionApprovalService.class),
                context.getBean(ManagedExecutionRunProjector.class), mock(ManagedArtifactVerifier.class),
                mock(ManagedSandboxProvisioner.class), mock(ManagedArtifactStore.class), json));
        context.refresh();
        projector = context.getBean(ManagedExecutionRunProjector.class);
        service = context.getBean(ManagedExecutionService.class);
        transaction = new TransactionTemplate(context.getBean(DataSourceTransactionManager.class));
        workerToken = tokens.generate();
        seed();
    }

    @AfterEach
    void close() {
        if (context != null) context.close();
        if (database != null) database.close();
    }

    @Test
    void workerStateChangeProjectsTheCommittedSequenceAndTime() throws Exception {
        WorkerEventV1 event = event();
        service.appendEvents(ID, workerToken, "worker-1", new WorkerEventBatchRequest(List.of(event)));
        var current = executions.selectById(1L);
        var root = root();
        assertEquals("RUNNING", current.getStatus());
        assertEquals("RUNNING", root.getStatus());
        assertEquals("MANAGED_EXECUTION", root.getRunType());
        assertEquals("CODEX_HARNESS", root.getRuntimeType());
        assertEquals("user-a", root.getUserId());
        assertEquals(current.getLastEventSequence(), json.readTree(root.getMetadataJson()).path("lastEventSequence").asInt());
        assertEquals(current.getUpdatedAt(), root.getUpdatedAt());
        assertFalse(root.getInputSummary().contains("private objective"));
        assertFalse(root.getSnapshotJson().contains(workerToken));
        service.appendEvents(ID, workerToken, "worker-1", new WorkerEventBatchRequest(List.of(event)));
        assertEquals(1, count("runtime_run"));
        assertEquals(1, count("runtime_managed_execution_event"));
        assertEquals(1, count("runtime_managed_execution_outbox"));
    }

    @Test
    void staleCallerSnapshotCannotReopenAnAuthoritativelyCancelledExecution() {
        var stale = executions.selectById(1L);
        transaction.executeWithoutResult(ignored -> projector.sync(stale.getExecutionId()));
        database.jdbc().update("""
                UPDATE runtime_managed_execution SET status = 'CANCELLED', completed_at = ?,
                    updated_at = ?, version = version + 1 WHERE execution_id = ?
                """, LocalDateTime.now(), LocalDateTime.now(), ID);
        transaction.executeWithoutResult(ignored -> projector.sync(stale.getExecutionId()));
        assertEquals("CANCELLED", root().getStatus());
        assertNotNull(root().getEndedAt());
    }

    @ParameterizedTest
    @CsvSource({"run_type,AGENT", "tenant_id,another-tenant", "project_code,ANOTHER_PROJECT",
            "entry_type,OPERATOR", "user_id,another-user", "snapshot_json,{}"})
    void collidingRootIdentityRejectsAndRollsBackTheWorkerEvent(String field, String value) {
        transaction.executeWithoutResult(ignored -> projector.sync(ID));
        database.jdbc().update("UPDATE runtime_run SET " + field + " = ? WHERE trace_id = ?", value, ID);
        assertThrows(IllegalStateException.class, this::appendStart);
        assertEquals("PROVISIONING", executions.selectById(1L).getStatus());
        assertEquals(0, executions.selectById(1L).getLastEventSequence());
        assertEquals(0, count("runtime_managed_execution_event"));
        assertEquals(0, count("runtime_managed_execution_outbox"));
        assertEquals(value, database.jdbc().queryForObject(
                "SELECT " + field + " FROM runtime_run WHERE trace_id = ?", String.class, ID));
    }

    @Test
    void outboxFailureRollsBackAggregateEventAndRunThenRetrySucceeds() {
        doThrow(new IllegalStateException("injected outbox failure")).when(outbox).insert(any(ManagedExecutionOutboxEntity.class));
        assertThrows(IllegalStateException.class, this::appendStart);
        assertEmptyAfterRollback();
        doCallRealMethod().when(outbox).insert(any(ManagedExecutionOutboxEntity.class));
        appendStart();
        assertEquals(1, count("runtime_run"));
        assertEquals(1, count("runtime_managed_execution_outbox"));
    }

    @Test
    void runWriteFailureRollsBackAggregateAndEvent() {
        doThrow(new IllegalStateException("injected Run failure")).when(runs).insert(any(RuntimeRunEntity.class));
        assertThrows(IllegalStateException.class, this::appendStart);
        assertEmptyAfterRollback();
    }

    @Test
    void projectionRequiresTheOwningTransaction() {
        assertThrows(IllegalTransactionStateException.class, () -> projector.sync(ID));
        assertThrows(IllegalTransactionStateException.class,
                () -> context.getBean(RuntimeManagedRunProjectionWriter.class).sync(null));
        assertEquals(0, count("runtime_run"));
    }

    @Test
    void approvalResumeCompletionAndCleanupKeepOneFrozenRoot() throws Exception {
        transaction.executeWithoutResult(ignored -> projector.sync(ID));
        String frozen = root().getSnapshotJson();
        database.jdbc().update("UPDATE runtime_managed_execution SET status = 'WAITING_APPROVAL', approval_count = 1 WHERE execution_id = ?", ID);
        transaction.executeWithoutResult(ignored -> projector.sync(ID));
        assertEquals("SUSPENDED", root().getStatus());
        assertEquals("APPROVAL", root().getSuspensionReason());
        assertNull(root().getEndedAt());
        database.jdbc().update("UPDATE runtime_managed_execution SET status = 'RUNNING' WHERE execution_id = ?", ID);
        transaction.executeWithoutResult(ignored -> projector.sync(ID));
        assertEquals("RUNNING", root().getStatus());
        assertNull(root().getSuspensionReason());
        LocalDateTime ended = LocalDateTime.now().withNano(0);
        database.jdbc().update("UPDATE runtime_managed_execution SET status = 'SUCCEEDED', completed_at = ? WHERE execution_id = ?", ended, ID);
        transaction.executeWithoutResult(ignored -> projector.sync(ID));
        assertEquals("COMPLETED", root().getStatus());
        assertEquals(ended, root().getEndedAt());
        assertNull(root().getErrorCode());
        database.jdbc().update("UPDATE runtime_managed_execution SET cleanup_status = 'COMPLETED' WHERE execution_id = ?", ID);
        transaction.executeWithoutResult(ignored -> projector.sync(ID));
        assertEquals("COMPLETED", json.readTree(root().getMetadataJson()).path("cleanupStatus").asText());
        assertEquals(ended, root().getEndedAt());
        assertEquals(frozen, root().getSnapshotJson());
        assertEquals(1, count("runtime_run"));
    }

    @Test
    void snapshotFormattingDoesNotChangeFrozenIdentity() throws Exception {
        database.jdbc().update("UPDATE runtime_managed_execution SET source_ref = ? WHERE execution_id = ?", "任务-1", ID);
        transaction.executeWithoutResult(ignored -> projector.sync(ID));
        String pretty = json.writerWithDefaultPrettyPrinter().writeValueAsString(json.readTree(root().getSnapshotJson()));
        String prettyInput = json.writerWithDefaultPrettyPrinter().writeValueAsString(json.readTree(root().getInputSummary()));
        database.jdbc().update("UPDATE runtime_run SET snapshot_json = ?, input_summary = ? WHERE trace_id = ?", pretty, prettyInput, ID);
        appendStart();
        assertEquals("RUNNING", root().getStatus());
        assertEquals(pretty, root().getSnapshotJson());
        assertEquals("任务-1", json.readTree(root().getSnapshotJson()).path("sourceRef").asText());
    }

    @Test
    void duplicateInsertChecksTheCommittedWinnerAndRollsBackOnlyItsOwnEvent() {
        doAnswer(call -> {
            Object absent = call.callRealMethod();
            assertNull(absent);
            // JdbcTemplate would join the outer transaction; use an independent auto-commit connection.
            try (var connection = database.jdbc().getDataSource().getConnection();
                 var insert = connection.prepareStatement("INSERT INTO runtime_run (trace_id, run_type, entry_type, status) VALUES (?, 'AGENT', 'API', 'RUNNING')")) {
                assertTrue(connection.getAutoCommit());
                insert.setString(1, ID);
                assertEquals(1, insert.executeUpdate());
            }
            return null;
        }).when(runs).selectOne(any());
        assertThrows(IllegalStateException.class, this::appendStart);
        assertEquals("AGENT", root().getRunType());
        assertEquals("PROVISIONING", executions.selectById(1L).getStatus());
        assertEquals(0, count("runtime_managed_execution_event"));
        assertEquals(0, count("runtime_managed_execution_outbox"));
    }

    @Test
    void aggregateLockOrdersConcurrentProjectionAndCancellation() throws Exception {
        assertEquals(0, database.jdbc().queryForObject("SELECT COUNT(*) FROM information_schema.sessions WHERE blocker_id IS NOT NULL", Integer.class));
        var inserted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        doAnswer(call -> {
            Object written = call.callRealMethod();
            inserted.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return written;
        }).when(runs).insert(any(RuntimeRunEntity.class));
        try {
            var first = pool.submit(() -> transaction.executeWithoutResult(ignored -> projector.sync(ID)));
            assertTrue(inserted.await(10, TimeUnit.SECONDS));
            var second = pool.submit(() -> transaction.executeWithoutResult(ignored -> {
                database.jdbc().update("UPDATE runtime_managed_execution SET status = 'CANCELLED', completed_at = ? WHERE execution_id = ?", LocalDateTime.now(), ID);
                projector.sync(ID);
            }));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean blocked = false;
            while (System.nanoTime() < deadline) {
                if (database.jdbc().queryForObject("SELECT COUNT(*) FROM information_schema.sessions WHERE blocker_id IS NOT NULL", Integer.class) > 0) {
                    blocked = true;
                    break;
                }
                Thread.sleep(10);
            }
            assertTrue(blocked, "H2 must report the second connection waiting on the aggregate row lock");
            release.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            assertEquals("CANCELLED", root().getStatus());
            assertNotNull(root().getEndedAt());
            assertEquals(1, count("runtime_run"));
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void workerEventEntryRequiresHumanApprovalAndReplaysWithoutDuplicateOutboxEvents() {
        appendStart();
        WorkerEventV1 request = new WorkerEventV1("reachai.managed-execution.event.v1", ID, 2, ID + ":2", Instant.now(),
                "APPROVAL_REQUESTED", "WAITING_APPROVAL", "OPERATOR", "DURABLE", "Review command",
                Map.of("approvalRequestId", "approval-live", "approvalKind", "COMMAND", "command", List.of("git", "status")));
        service.appendEvents(ID, workerToken, "worker-1", new WorkerEventBatchRequest(List.of(request)));
        String interaction = executions.selectById(1L).getPendingInteractionId();
        assertNotNull(interaction);
        assertEquals("SUSPENDED", root().getStatus());
        WorkerEventV1 result = new WorkerEventV1("reachai.managed-execution.event.v1", ID, 3, ID + ":3", Instant.now(),
                "APPROVAL_RESOLVED", "RUNNING", "OPERATOR", "DURABLE", "Approval resolved",
                Map.of("approvalRequestId", "approval-live", "approvalKind", "COMMAND", "decision", "accept"));
        assertEquals("MANAGED_APPROVAL_DECISION_MISSING", assertThrows(ManagedExecutionException.class,
                () -> service.appendEvents(ID, workerToken, "worker-1", new WorkerEventBatchRequest(List.of(result)))).code());
        assertEquals(2, executions.selectById(1L).getLastEventSequence());
        assertEquals("WAITING_APPROVAL", executions.selectById(1L).getStatus());
        assertEquals(2, count("runtime_managed_execution_event"));
        var command = service.resolveApproval(ID, "tenant-a", "user-a", interaction, new ApprovalDecisionRequest("APPROVE", "live-key"));
        int outboxCount = count("runtime_managed_execution_outbox");
        var replay = service.resolveApproval(ID, "tenant-a", "user-a", interaction, new ApprovalDecisionRequest("APPROVE", "live-key"));
        assertTrue(replay.idempotentReplay());
        assertEquals(command.commandSequence(), replay.commandSequence());
        assertEquals(outboxCount, count("runtime_managed_execution_outbox"));
        service.appendEvents(ID, workerToken, "worker-1", new WorkerEventBatchRequest(List.of(result)));
        service.appendEvents(ID, workerToken, "worker-1", new WorkerEventBatchRequest(List.of(result)));
        assertEquals("RUNNING", root().getStatus());
        assertNull(executions.selectById(1L).getPendingInteractionId());
        assertEquals(1, executions.selectById(1L).getApprovalCount());
        assertEquals(1, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_interaction_event WHERE event_type = 'SUBMITTED'", Integer.class));
        assertEquals(1, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_interaction_event WHERE event_type = 'COMPLETED'", Integer.class));
    }

    private void assertEmptyAfterRollback() {
        assertEquals("PROVISIONING", executions.selectById(1L).getStatus());
        assertEquals(0, executions.selectById(1L).getLastEventSequence());
        assertEquals(0, count("runtime_managed_execution_event"));
        assertEquals(0, count("runtime_managed_execution_outbox"));
        assertEquals(0, count("runtime_run"));
    }

    private void appendStart() {
        service.appendEvents(ID, workerToken, "worker-1", new WorkerEventBatchRequest(List.of(event())));
    }

    private WorkerEventV1 event() {
        return new WorkerEventV1("reachai.managed-execution.event.v1", ID, 1, ID + ":1", Instant.now(),
                "TURN_STARTED", "RUNNING", "OPERATOR", "DURABLE", "TURN_STARTED", Map.of());
    }

    private RuntimeRunEntity root() {
        return runs.selectList(null).get(0);
    }

    private int count(String table) {
        return database.jdbc().queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void seed() {
        var row = new ManagedExecutionEntity();
        row.setId(1L);
        row.setExecutionId(ID);
        row.setTenantId("tenant-a");
        row.setProjectCode("PROJECT_A");
        row.setRequestedByUserId("user-a");
        row.setSourceType("AI_CODING_TASK");
        row.setSourceRef("task-1");
        row.setExecutorProvider("CODEX");
        row.setSandboxProfile("ANALYZE_READONLY");
        row.setAcceptanceProfile("PROJECT_DEFAULT");
        row.setObjectiveText("private objective");
        row.setObjectiveSha256("a".repeat(64));
        row.setStatus("PROVISIONING");
        row.setCleanupStatus("PENDING");
        row.setMaxWallTimeSeconds(600);
        row.setApprovalTimeoutSeconds(120);
        row.setLastEventSequence(0);
        row.setWorkerTokenDigest(tokens.digest(workerToken));
        row.setWorkerTokenExpiresAt(LocalDateTime.now().plusMinutes(10));
        row.setLeaseOwner("worker-1");
        row.setLeaseExpiresAt(LocalDateTime.now().plusSeconds(90));
        row.setVersion(1L);
        row.setCreatedAt(LocalDateTime.now().minusMinutes(1).withNano(0));
        row.setStartedAt(row.getCreatedAt());
        row.setUpdatedAt(row.getCreatedAt());
        executions.insert(row);
    }

    @Configuration
    @EnableTransactionManagement
    static class Transactions { }
}
