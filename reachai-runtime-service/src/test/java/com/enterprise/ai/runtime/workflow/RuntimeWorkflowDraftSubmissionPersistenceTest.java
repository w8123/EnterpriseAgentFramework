package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDeletionReferences;
import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import com.enterprise.ai.runtime.support.RuntimeMysqlTestDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Spring transactions and production MyBatis SQL; H2 by default, isolated development MySQL when opted in. */
class RuntimeWorkflowDraftSubmissionPersistenceTest {
    private static final RuntimeWorkflowDraftSubmissionService.Scope SCOPE =
            new RuntimeWorkflowDraftSubmissionService.Scope("PAGE_WORKBENCH", 7L, "orders", "ait-1", "orders.detail");
    private RuntimeQueryTestDatabase database;
    private RuntimeMysqlTestDatabase mysql;
    private AnnotationConfigApplicationContext context;
    private RuntimeWorkflowDraftSubmissionMapper receipts;
    private RuntimeWorkflowDefinitionMapper workflowMapper;
    private RuntimeWorkflowDefinitionService workflows;
    private RuntimeWorkflowDraftSubmissionService submissions;
    private final AtomicInteger writes = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        var tables = List.of("runtime_workflow", "runtime_workflow_draft_submission");
        if (Boolean.getBoolean("reachai.mysql.workflowDraftVerification")) {
            mysql = new RuntimeMysqlTestDatabase(tables);
            database = new RuntimeQueryTestDatabase(mysql, RuntimeWorkflowDefinitionMapper.class, RuntimeWorkflowDraftSubmissionMapper.class);
        } else {
            database = new RuntimeQueryTestDatabase(tables, RuntimeWorkflowDefinitionMapper.class, RuntimeWorkflowDraftSubmissionMapper.class);
        }
        receipts = spy(database.mapper(RuntimeWorkflowDraftSubmissionMapper.class));
        workflowMapper = spy(database.mapper(RuntimeWorkflowDefinitionMapper.class));
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean(DataSourceTransactionManager.class,
                () -> new DataSourceTransactionManager(database.jdbc().getDataSource()));
        context.registerBean(RuntimeWorkflowDefinitionService.class, () -> new RuntimeWorkflowDefinitionService(
                workflowMapper, mock(RuntimeWorkflowVersionMapper.class), mock(RuntimeWorkflowDeletionReferences.class),
                new RuntimeWorkflowDocumentCanonicalizer(new ObjectMapper()),
                mock(RuntimeWorkflowResourceBindingService.class), mock(RuntimeWorkflowReferenceIndex.class)));
        context.registerBean(RuntimeWorkflowDraftSubmissionService.class, () -> new RuntimeWorkflowDraftSubmissionService(
                receipts, context.getBean(RuntimeWorkflowDefinitionService.class), new ObjectMapper()));
        context.refresh();
        workflows = context.getBean(RuntimeWorkflowDefinitionService.class);
        submissions = context.getBean(RuntimeWorkflowDraftSubmissionService.class);
    }

    @AfterEach
    void close() {
        try {
            if (context != null) context.close();
            if (database != null) database.close();
        } finally {
            if (mysql != null) mysql.close();
        }
    }

    @Test
    void replayReadsTheCurrentHumanEditedDraftWithoutWritingOrAdvancingRevision() {
        var first = submit("original");
        var change = new RuntimeWorkflowDefinitionEntity();
        change.setName("Edited in Studio");
        var edited = workflows.update(first.getId(), change, first.getUpdatedAt().toString());

        var replay = submit("original");

        assertEquals("Edited in Studio", replay.getName());
        assertEquals(edited.getUpdatedAt(), replay.getUpdatedAt());
        assertEquals(1, writes.get());
        assertRows(1, 1);
        assertThrows(RuntimeWorkflowRevisionConflictException.class, () -> submit("correction"));
        assertRows(1, 1);
        assertEquals("Edited in Studio", current(first.getId()).getName());
    }

    @Test
    void correctionAndOlderReplayKeepOneWorkflowAndTheNewestAppliedContent() {
        var first = submit("original");
        var corrected = submit("correction");
        var replay = submit("original");

        assertEquals(first.getId(), corrected.getId());
        assertEquals("correction", replay.getName());
        assertEquals(corrected.getUpdatedAt(), replay.getUpdatedAt());
        assertTrue(corrected.getUpdatedAt().isAfter(first.getUpdatedAt()));
        assertEquals(2, writes.get());
        assertRows(1, 2);
    }

    @Test
    void objectFieldOrderIsCanonicalButArrayOrderChangesTheRequest() {
        var a = new LinkedHashMap<String, Object>();
        a.put("name", "draft");
        a.put("steps", List.of("read", "answer"));
        var b = new LinkedHashMap<String, Object>();
        b.put("steps", List.of("read", "answer"));
        b.put("name", "draft");
        apply(SCOPE, a, "same", "same");
        apply(SCOPE, b, "must-not-run", "same");
        assertEquals(1, writes.get());
        b.put("steps", List.of("answer", "read"));
        apply(SCOPE, b, "reordered", "same");
        assertEquals(2, writes.get());
        assertRows(1, 2);
    }

    @Test
    void changingRequestedKeySlugDoesNotCreateAnotherWorkflow() {
        var first = apply(SCOPE, Map.of("keySlug", "first"), "first", "first");
        var second = apply(SCOPE, Map.of("keySlug", "second"), "second", "second");
        assertEquals(first.getId(), second.getId());
        assertEquals("second", second.getKeySlug());
        assertRows(1, 2);
    }

    @Test
    void taskAndTargetSeparateIdentitiesWhileScopeCaseAndWhitespaceAreNormalized() {
        var first = submit("original");
        var equivalent = new RuntimeWorkflowDraftSubmissionService.Scope(
                " page_workbench ", 7L, " ORDERS ", " ait-1 ", " orders.detail ");
        assertEquals(first.getId(), apply(equivalent, "original", "must-not-run", "first").getId());
        var otherTask = new RuntimeWorkflowDraftSubmissionService.Scope(
                "PAGE_WORKBENCH", 7L, "orders", "ait-2", "orders.detail");
        var second = apply(otherTask, "original", "second", "second");
        assertNotEquals(first.getId(), second.getId());
        var otherTarget = new RuntimeWorkflowDraftSubmissionService.Scope(
                "PAGE_WORKBENCH", 7L, "orders", "ait-1", "orders.list");
        assertNotEquals(first.getId(), apply(otherTarget, "original", "third", "third").getId());
        assertRows(3, 3);
    }

    @Test
    void publishedDraftCanBeReadByReplayButCannotBeReplaced() {
        var first = submit("original");
        database.jdbc().update("UPDATE runtime_workflow SET status = 'ACTIVE', updated_at = ? WHERE id = ?",
                first.getUpdatedAt(), first.getId());
        assertEquals("ACTIVE", submit("original").getStatus());
        assertThrows(IllegalArgumentException.class, () -> submit("correction"));
        assertEquals(1, writes.get());
        assertRows(1, 1);
    }

    @Test
    void deletedDraftIsNeverRecreatedByReplayOrCorrection() {
        var first = submit("original");
        database.jdbc().update("DELETE FROM runtime_workflow WHERE id = ?", first.getId());
        assertThrows(IllegalArgumentException.class, () -> submit("original"));
        assertThrows(IllegalArgumentException.class, () -> submit("correction"));
        assertEquals(1, writes.get());
        assertRows(0, 1);
    }

    @Test
    void changedProjectCannotBeReadOrOverwrittenByAnOldSubmission() {
        var first = submit("original");
        database.jdbc().update("UPDATE runtime_workflow SET project_id = 99 WHERE id = ?", first.getId());
        assertThrows(IllegalArgumentException.class, () -> submit("original"));
        assertThrows(IllegalArgumentException.class, () -> submit("correction"));
        assertRows(1, 1);
    }

    @Test
    void existingUntrackedDraftIsNotAdoptedBySlug() {
        var existing = new RuntimeWorkflowDefinitionEntity();
        existing.setKeySlug("draft");
        existing.setName("Existing Studio draft");
        existing.setProjectId(7L);
        existing.setProjectCode("orders");
        workflows.create(existing);
        assertThrows(RuntimeWorkflowKeySlugConflictException.class, () -> submit("original"));
        assertEquals("Existing Studio draft", current(existing.getId()).getName());
        assertRows(1, 0);
    }

    @Test
    void failedApplicationRollsBackDraftAndSubmissionReservation() {
        assertThrows(IllegalStateException.class, () -> submissions.apply(SCOPE, "original", attempt -> {
            write(attempt, "original", "draft");
            throw new IllegalStateException("application failed after insert");
        }, this::current));
        assertRows(0, 0);
        submit("original");
        assertRows(1, 1);
    }

    @Test
    void receiptCompletionFailureRollsBackCorrectionAndAllowsRetry() {
        var first = submit("original");
        doThrow(new IllegalStateException("receipt completion failed")).when(receipts).complete(anyLong(), any());
        assertThrows(IllegalStateException.class, () -> submit("correction"));
        assertEquals("original", current(first.getId()).getName());
        assertEquals(first.getUpdatedAt(), current(first.getId()).getUpdatedAt());
        assertRows(1, 1);
        doCallRealMethod().when(receipts).complete(anyLong(), any());
        assertEquals("correction", submit("correction").getName());
        assertRows(1, 2);
    }

    @Test
    void inconsistentWriterRevisionRollsBackInsteadOfRecordingSuccess() {
        assertThrows(IllegalStateException.class, () -> submissions.apply(SCOPE, "original", attempt -> {
            var applied = write(attempt, "original", "draft");
            return new RuntimeWorkflowDraftSubmissionService.Applied<>(applied.workflowId(),
                    applied.revision().plusSeconds(1), applied.value());
        }, this::current));
        assertRows(0, 0);
    }

    @Test
    void duplicateConcurrentRequestsExecuteTheWriterOnlyOnce() throws Exception {
        var firstReady = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        doAnswer(call -> {
            firstReady.countDown();
            await(releaseFirst);
            return call.callRealMethod();
        }).when(receipts).complete(anyLong(), any());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> submit("original"));
            await(firstReady);
            doAnswer(call -> {
                secondStarted.countDown();
                return call.callRealMethod();
            }).when(receipts).insert(any(RuntimeWorkflowDraftSubmissionEntity.class));
            var second = pool.submit(() -> submit("original"));
            await(secondStarted);
            releaseFirst.countDown();
            assertEquals(first.get(10, TimeUnit.SECONDS).getId(), second.get(10, TimeUnit.SECONDS).getId());
            assertEquals(1, writes.get());
            assertRows(1, 1);
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void competingFirstCorrectionsCannotCreateTwoWorkflowsAndLoserCanRetry() throws Exception {
        var bothCreating = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        Function<String, RuntimeWorkflowDefinitionEntity> concurrentSubmit = name -> submissions.apply(
                SCOPE, name, attempt -> {
                    assertNull(attempt.current());
                    bothCreating.countDown();
                    await(release);
                    return write(attempt, name, name);
                }, this::current);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> outcome(() -> concurrentSubmit.apply("first")));
            var second = pool.submit(() -> outcome(() -> concurrentSubmit.apply("second")));
            await(bothCreating);
            release.countDown();
            Object a = first.get(10, TimeUnit.SECONDS);
            Object b = second.get(10, TimeUnit.SECONDS);
            assertTrue((a instanceof RuntimeWorkflowDefinitionEntity) ^ (b instanceof RuntimeWorkflowDefinitionEntity));
            assertInstanceOf(RuntimeWorkflowKeySlugConflictException.class,
                    a instanceof RuntimeException ? a : b);
            assertRows(1, 1);
            String retry = a instanceof RuntimeException ? "first" : "second";
            apply(SCOPE, retry, retry, retry);
            assertRows(1, 2);
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void waitingCorrectionReadsTheReceiptCommittedByThePreviousWriter() throws Exception {
        submit("original");
        var firstReady = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var secondAtLock = new CountDownLatch(1);
        doAnswer(call -> {
            firstReady.countDown();
            await(releaseFirst);
            return call.callRealMethod();
        }).when(receipts).complete(anyLong(), any());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> submit("first correction"));
            await(firstReady);
            doAnswer(call -> {
                secondAtLock.countDown();
                return call.callRealMethod();
            }).when(workflowMapper).selectForRelease(anyString());
            var second = pool.submit(() -> submit("second correction"));
            await(secondAtLock);
            releaseFirst.countDown();
            var a = first.get(10, TimeUnit.SECONDS);
            var b = second.get(10, TimeUnit.SECONDS);
            assertEquals(a.getId(), b.getId());
            assertEquals("second correction", current(a.getId()).getName());
            assertTrue(b.getUpdatedAt().isAfter(a.getUpdatedAt()));
            assertRows(1, 3);
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private RuntimeWorkflowDefinitionEntity submit(String name) {
        return apply(SCOPE, name, name, "draft");
    }

    private RuntimeWorkflowDefinitionEntity apply(RuntimeWorkflowDraftSubmissionService.Scope scope,
                                                   Object content, String name, String slug) {
        return submissions.apply(scope, content, attempt -> write(attempt, name, slug), this::current);
    }

    private RuntimeWorkflowDraftSubmissionService.Applied<RuntimeWorkflowDefinitionEntity> write(
            RuntimeWorkflowDraftSubmissionService.Attempt attempt, String name, String slug) {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals(Connection.TRANSACTION_READ_COMMITTED,
                TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
        writes.incrementAndGet();
        var change = new RuntimeWorkflowDefinitionEntity();
        change.setName(name);
        change.setKeySlug(slug);
        RuntimeWorkflowDefinitionEntity saved;
        if (attempt.current() == null) {
            change.setId(attempt.workflowId());
            change.setProjectId(7L);
            change.setProjectCode("orders");
            saved = workflows.create(change);
        } else {
            saved = workflows.update(attempt.workflowId(), change, attempt.baseRevision());
        }
        return new RuntimeWorkflowDraftSubmissionService.Applied<>(saved.getId(), saved.getUpdatedAt(), saved);
    }

    private RuntimeWorkflowDefinitionEntity current(String id) {
        return workflows.findById(id).orElseThrow();
    }

    private void assertRows(int workflowCount, int receiptCount) {
        assertEquals(workflowCount, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_workflow", Integer.class));
        assertEquals(receiptCount, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_workflow_draft_submission", Integer.class));
        assertEquals(0, database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM runtime_workflow_draft_submission WHERE workflow_revision IS NULL", Integer.class));
    }

    private static Object outcome(java.util.concurrent.Callable<?> operation) throws Exception {
        try {
            return operation.call();
        } catch (RuntimeException failure) {
            return failure;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "Timed out waiting for the database operation");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Transactions { }
}
