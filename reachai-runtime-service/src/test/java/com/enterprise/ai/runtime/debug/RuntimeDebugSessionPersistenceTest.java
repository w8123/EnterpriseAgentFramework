package com.enterprise.ai.runtime.debug;

import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDebugService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real session persistence and transaction boundaries; the execution port records observable invocations. */
class RuntimeDebugSessionPersistenceTest {
    private static final com.enterprise.ai.runtime.debug.RuntimeDebugSessionOwner OWNER =
            new com.enterprise.ai.runtime.debug.RuntimeDebugSessionOwner("default", "42");
    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();
    private RuntimeQueryTestDatabase db;
    private RuntimeExecutableDebugSessionMapper mapper;
    private RuntimeWorkflowDebugService workflow;
    private RuntimeDebugSessionStore store;
    private RuntimeExecutableDebugSessionService service;
    private final AtomicInteger executions = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        db = new RuntimeQueryTestDatabase(List.of("runtime_executable_debug_session", "runtime_run", "runtime_trace_span"),
                RuntimeExecutableDebugSessionMapper.class);
        mapper = spy(db.mapper(RuntimeExecutableDebugSessionMapper.class));
        workflow = mock(RuntimeWorkflowDebugService.class);
        when(workflow.captureDefinition(any())).thenReturn(new RuntimeWorkflowDebugService.DebugDefinition(
                null, "draft", "Draft", "GENERAL", null, null, "GRAPH_SPEC", null, "{}", null));
        when(workflow.startSessionDebug(any(), any(), any(), any(), any())).thenAnswer(call -> {
            executions.incrementAndGet();
            RuntimeWorkflowDebugService.DebugRunReference run = call.getArgument(2);
            return completed(run.runId(), run.traceId());
        });
        when(workflow.resumeSessionDebug(any(), any(), any(), any(), any())).thenAnswer(call -> {
            executions.incrementAndGet();
            return completed("run-1", "trace-1");
        });
        store = transactional(new RuntimeDebugSessionStore(mapper, json, mock(RuntimeDebugExecutionLifecycle.class)));
        service = transactional(new RuntimeExecutableDebugSessionService(store, workflow, json, null, 8000));
    }

    @AfterEach
    void close() { if (db != null) db.close(); }

    @Test
    void rejectedInitialInsertPreventsExecution() {
        doThrow(new DataAccessResourceFailureException("injected insert failure")).when(mapper).insert(any());
        assertThrows(RuntimeException.class, () -> service.create(OWNER, createRequest()));
        assertEquals(0, executions.get());
    }

    @Test
    void retryAfterLostCreationReplyReturnsOriginalSessionWithoutExecutingAgain() {
        var request = keyedCreate("creation-attempt", "hello");
        var admitted = service.create(OWNER, request); // Response was committed but lost before reaching the client.
        var recovered = service.create(OWNER, request);
        assertEquals(admitted.sessionId(), recovered.sessionId());
        assertEquals(1, executions.get());
        assertEquals(1, mapper.selectCount(null));
    }

    @Test
    void creationIdentityCannotBeReusedForDifferentInput() {
        service.create(OWNER, keyedCreate("creation-attempt", "hello"));
        assertThrows(IllegalArgumentException.class,
                () -> service.create(OWNER, keyedCreate("creation-attempt", "changed")));
        assertEquals(1, executions.get());
        assertEquals(1, mapper.selectCount(null));
    }

    @Test
    void creationLookupOnlyReadsTheOwnerSessionAndNeverDispatches() {
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.getByCreationKey(OWNER,"missing"));
        assertEquals(0,executions.get());
        var created=service.create(OWNER,keyedCreate("lookup","hello"));
        assertEquals(created.sessionId(),service.getByCreationKey(OWNER,"lookup").sessionId());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                ()->service.getByCreationKey(new RuntimeDebugSessionOwner("default","43"),"lookup"));
        assertEquals(1,executions.get());
    }

    private RuntimeExecutableDebugSessionService.CreateRequest keyedCreate(String key, String message) {
        return new RuntimeExecutableDebugSessionService.CreateRequest("WORKFLOW_WORKING_COPY",
                Map.of("graphSpecJson", "{}"), message, Map.of(), Map.of(), key);
    }

    @Test
    void keyedCreationPersistsRunAndTraceWithinTheirSchemaAndReplaysTheSameIdentity() {
        doAnswer(call -> {
            RuntimeWorkflowDebugService.DebugRunReference run = call.getArgument(2);
            db.jdbc().update("INSERT INTO runtime_run(trace_id,run_type,entry_type) VALUES(?, 'WORKFLOW', 'WORKFLOW_STUDIO')", run.traceId());
            db.jdbc().update("INSERT INTO runtime_trace_span(trace_id,span_id,span_type) VALUES(?, ?, 'WORKFLOW')", run.traceId(), "span-1");
            executions.incrementAndGet();
            return completed(run.runId(), run.traceId());
        }).when(workflow).startSessionDebug(any(), any(), any(), any(), any());
        var request = keyedCreate("persistent-creation", "hello");
        var created = service.create(OWNER, request);
        var restarted = transactional(new RuntimeExecutableDebugSessionService(
                transactional(new RuntimeDebugSessionStore(mapper, json, mock(RuntimeDebugExecutionLifecycle.class))), workflow, json, null, 8000));
        var replay = restarted.create(OWNER, request);
        assertEquals(created.sessionId(), replay.sessionId());
        assertEquals(created.runId(), replay.runId());
        assertEquals(created.traceId(), replay.traceId());
        assertEquals(created.traceId(), db.jdbc().queryForObject("SELECT trace_id FROM runtime_run", String.class));
        assertEquals(created.traceId(), db.jdbc().queryForObject("SELECT trace_id FROM runtime_trace_span", String.class));
        assertEquals(1, executions.get());
    }

    @Test
    void concurrentFirstCreationsAdmitOneExecution() throws Exception {
        var captured=new CountDownLatch(2);
        var definition=workflow.captureDefinition(null);
        when(workflow.captureDefinition(any())).thenAnswer(call->{
            captured.countDown();assertTrue(captured.await(5,TimeUnit.SECONDS));return definition;
        });
        var request=keyedCreate("concurrent-creation","hello");
        var first=CompletableFuture.supplyAsync(()->service.create(OWNER,request));
        var second=CompletableFuture.supplyAsync(()->service.create(OWNER,request));
        assertEquals(first.get(10,TimeUnit.SECONDS).sessionId(),second.get(10,TimeUnit.SECONDS).sessionId());
        assertEquals(1,executions.get());assertEquals(1,mapper.selectCount(null));
    }

    @Test
    void sameCreationKeyIsIsolatedByUserAndTenant() {
        var request=keyedCreate("shared-key","hello");
        var first=service.create(OWNER,request);
        var otherUser=service.create(new RuntimeDebugSessionOwner("default","43"),request);
        var otherTenant=service.create(new RuntimeDebugSessionOwner("other","42"),request);
        assertNotEquals(first.sessionId(),otherUser.sessionId());
        assertNotEquals(first.sessionId(),otherTenant.sessionId());
        assertEquals(3,executions.get());assertEquals(3,mapper.selectCount(null));
    }

    @Test
    void mapKeyOrderDoesNotChangeCreationFingerprint() {
        var first=service.create(OWNER,new RuntimeExecutableDebugSessionService.CreateRequest("WORKFLOW_WORKING_COPY",
                Map.of("graphSpecJson","{}"),"hello",new java.util.LinkedHashMap<>(Map.of("a",1,"b",2)),Map.of(),"ordered"));
        var reordered=new java.util.LinkedHashMap<String,Object>();reordered.put("b",2);reordered.put("a",1);
        var second=service.create(OWNER,new RuntimeExecutableDebugSessionService.CreateRequest("WORKFLOW_WORKING_COPY",
                Map.of("graphSpecJson","{}"),"hello",reordered,Map.of(),"ordered"));
        assertEquals(first.sessionId(),second.sessionId());assertEquals(1,executions.get());
    }

    @Test
    void invalidCreationKeysFailBeforeReservationAndLegacyRequestsRemainIndependent() {
        assertThrows(IllegalArgumentException.class,()->service.create(OWNER,keyedCreate(" ","hello")));
        assertThrows(IllegalArgumentException.class,()->service.create(OWNER,keyedCreate("x".repeat(129),"hello")));
        assertEquals(0,executions.get());assertEquals(0,mapper.selectCount(null));
        assertNotEquals(service.create(OWNER,createRequest()).sessionId(),service.create(OWNER,createRequest()).sessionId());
        assertEquals(2,executions.get());
    }

    @Test
    void zeroRowsOnInitialInsertCannotReportSuccessOrExecute() {
        doReturn(0).when(mapper).insert(any());
        assertThrows(RuntimeException.class, () -> service.create(OWNER, createRequest()));
        assertEquals(0, executions.get());
    }

    @Test
    void initialReservationIsCommittedBeforeCallingTheExecutor() {
        doAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            var stored = mapper.selectOne(null);
            assertNotNull(stored, "a running session must exist before execution");
            assertEquals("RUNNING", stored.getStatus());
            assertEquals(0, stored.getRevision());
            assertTrue(stored.getWorkingCopyDefinitionJson().contains("graphSpecJson"));
            RuntimeWorkflowDebugService.DebugRunReference run = call.getArgument(2);
            assertEquals(run.runId(), stored.getRunId());
            assertEquals(run.traceId(), stored.getTraceId());
            return completed(run.runId(), run.traceId());
        }).when(workflow).startSessionDebug(any(), any(), any(), any(), any());
        var view = service.create(OWNER, createRequest());
        assertEquals("COMPLETED", view.status());
        assertEquals("COMPLETED", mapper.selectById(view.sessionId()).getStatus());
    }

    @Test
    void callerRollbackDoesNotEraseAnExecutedSession() {
        var outer = new TransactionTemplate(new DataSourceTransactionManager(db.jdbc().getDataSource()));
        String id = outer.execute(status -> {
            var view = service.create(OWNER, createRequest());
            status.setRollbackOnly();
            return view.sessionId();
        });
        assertNotNull(mapper.selectById(id));
        assertEquals("COMPLETED", mapper.selectById(id).getStatus());
        assertEquals(1, executions.get());
    }

    @Test
    void executionExceptionCannotReopenTheSameWaitingCheckpoint() {
        seedWaiting();
        doAnswer(call -> {
            executions.incrementAndGet();
            throw new IllegalStateException("failure after an external effect");
        }).when(workflow).resumeSessionDebug(any(), any(), any(), any(), any());
        assertThrows(RuntimeException.class, () -> service.submit(OWNER, "session-1", submitRequest()));
        assertNotEquals("SUSPENDED", row().getStatus());
        try { service.submit(OWNER, "session-1", submitRequest()); } catch (RuntimeException expected) { }
        assertEquals(1, executions.get());
    }

    @Test
    void projectionFailureCanBeRecoveredByReadingWithoutExecutingAgain() {
        seedWaiting();
        AtomicBoolean failProjection = new AtomicBoolean(true);
        doAnswer(call -> {
            com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<?> update = call.getArgument(1);
            if (update.getSqlSet().contains("state_snapshot_json") && failProjection.get()) {
                throw new DataAccessResourceFailureException("injected projection failure");
            }
            return call.callRealMethod();
        }).when(mapper).update(any(), any());
        var pending = assertThrows(RuntimeException.class, () -> service.submit(OWNER, "session-1", submitRequest()));
        assertTrue(pending.getMessage().contains("DEBUG_SESSION_COMPLETION_PENDING: sessionId=session-1"));
        assertNotNull(row().getResultJson(), "the execution result must be durable before projection");
        failProjection.set(false);
        service = transactional(new RuntimeExecutableDebugSessionService(
                transactional(new RuntimeDebugSessionStore(mapper, json, mock(RuntimeDebugExecutionLifecycle.class))), workflow, json, null, 8000));
        var recovered = service.get(OWNER, "session-1");
        assertEquals("COMPLETED", recovered.status());
        assertEquals("done", recovered.answer());
        assertEquals("COMPLETED", service.submit(OWNER, "session-1", submitRequest()).status());
        assertEquals(1, executions.get());
    }

    @Test
    void initialExecutionResultSurvivesProjectionFailure() {
        AtomicBoolean failProjection = new AtomicBoolean(true);
        doAnswer(call -> {
            com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<?> update = call.getArgument(1);
            if (update.getSqlSet().contains("state_snapshot_json") && failProjection.get()) {
                throw new DataAccessResourceFailureException("injected projection failure");
            }
            return call.callRealMethod();
        }).when(mapper).update(any(), any());
        assertThrows(RuntimeException.class, () -> service.create(OWNER, createRequest()));
        var stored = mapper.selectOne(null);
        assertNotNull(stored);
        assertNotNull(stored.getResultJson());
        failProjection.set(false);
        assertEquals("COMPLETED", service.get(OWNER, stored.getId()).status());
        assertEquals(1, executions.get());
    }

    @Test
    void anAdvancedCheckpointCannotBeClaimedUsingTheOldSnapshot() {
        seedWaiting();
        AtomicBoolean firstRead = new AtomicBoolean(true);
        doAnswer(call -> {
            Object stored = call.callRealMethod();
            if (firstRead.getAndSet(false)) {
                db.jdbc().update("UPDATE runtime_executable_debug_session SET revision = 8, current_node_id = 'next' WHERE id = 'session-1'");
            }
            return stored;
        }).when(mapper).selectByIdForUpdate("session-1");
        assertThrows(RuntimeException.class, () -> service.submit(OWNER, "session-1", submitRequest()));
        assertEquals(0, executions.get());
        assertEquals(8, row().getRevision());
        assertEquals("next", row().getCurrentNodeId());
    }

    @Test
    void cancellingACompletedSessionPreservesItsCommittedResult() {
        var completed = service.create(OWNER, createRequest());
        var cancelled = service.cancel(OWNER, completed.sessionId());
        assertEquals("COMPLETED", cancelled.status());
        assertEquals("done", cancelled.answer());
        assertEquals("COMPLETED", mapper.selectById(completed.sessionId()).getStatus());
    }

    @Test
    void lostAcknowledgementAfterReceiptCommitStillRecoversWithoutExecution() {
        seedWaiting();
        var lostReply = mock(RuntimeDebugSessionStore.class, org.mockito.AdditionalAnswers.delegatesTo(store));
        doAnswer(call -> {
            store.stageCompletion(OWNER, call.getArgument(1), call.getArgument(2), call.getArgument(3));
            throw new DataAccessResourceFailureException("receipt committed, response lost");
        }).when(lostReply).stageCompletion(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
        var sender = transactional(new RuntimeExecutableDebugSessionService(lostReply, workflow, json, null, 8000));
        assertThrows(RuntimeException.class, () -> sender.submit(OWNER, "session-1", submitRequest()));
        assertEquals("RESUMING", row().getStatus());
        assertNotNull(row().getResultJson());
        assertEquals("COMPLETED", service.get(OWNER, "session-1").status());
        assertEquals(1, executions.get());
    }

    @Test
    void simultaneousReadersProjectTheSameReceiptOnce() throws Exception {
        preparePendingCompletion();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        doAnswer(call -> {
            int index = reads.incrementAndGet();
            if (index == 2) secondEntered.countDown();
            Object result = call.callRealMethod();
            if (index == 1) {
                locked.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
            }
            return result;
        }).when(mapper).selectByIdForUpdate("session-1");
        var first = CompletableFuture.supplyAsync(() -> service.get(OWNER, "session-1"));
        try {
            assertTrue(locked.await(5, TimeUnit.SECONDS));
            var second = CompletableFuture.supplyAsync(() -> service.get(OWNER, "session-1"));
            assertTrue(secondEntered.await(5, TimeUnit.SECONDS));
            release.countDown();
            assertEquals("COMPLETED", first.get(5, TimeUnit.SECONDS).status());
            assertEquals("COMPLETED", second.get(5, TimeUnit.SECONDS).status());
        } finally {
            release.countDown();
        }
        assertEquals(2, row().getRevision());
        assertEquals(1, executions.get());
    }

    @Test
    void committedReceiptWinsOverLaterCancellation() {
        preparePendingCompletion();
        var result = service.cancel(OWNER, "session-1");
        assertEquals("COMPLETED", result.status());
        assertEquals("done", result.answer());
        assertEquals(1, executions.get());
    }

    @Test
    void unreadableReceiptFailsClosedAndCannotRestartTheCheckpoint() {
        preparePendingCompletion();
        db.jdbc().update("UPDATE runtime_executable_debug_session SET result_json = ? WHERE id = 'session-1'",
                "{\"completionSchema\":99}");
        assertThrows(IllegalStateException.class, () -> service.get(OWNER, "session-1"));
        assertThrows(IllegalStateException.class, () -> service.submit(OWNER, "session-1", submitRequest()));
        assertEquals("RESUMING", row().getStatus());
        assertEquals(1, executions.get());
    }

    @Test
    void missingReceiptAfterStorageFailureDoesNotInventAResultOrRetryExecution() {
        seedWaiting();
        doAnswer(call -> {
            com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<?> update = call.getArgument(1);
            if (update.getSqlSet().contains("result_json") && !update.getSqlSet().contains("status")) {
                throw new DataAccessResourceFailureException("receipt store unavailable");
            }
            return call.callRealMethod();
        }).when(mapper).update(any(), any());
        assertThrows(RuntimeException.class, () -> service.submit(OWNER, "session-1", submitRequest()));
        assertNull(row().getResultJson());
        assertEquals("RESUMING", service.get(OWNER, "session-1").status());
        assertThrows(RuntimeException.class, () -> service.submit(OWNER, "session-1", submitRequest()));
        assertEquals(1, executions.get());
    }

    @Test
    void streamAdmissionIsCommittedAndPublishedBeforeExecution() throws Exception {
        var admitted = new java.util.concurrent.atomic.AtomicReference<RuntimeExecutableDebugSessionService.SessionView>();
        var names = new java.util.ArrayList<String>();
        doAnswer(call -> {
            assertNotNull(admitted.get(), "the client must know the session before any execution");
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            var visible = mapper.selectById(admitted.get().sessionId());
            assertEquals("RUNNING", visible.getStatus());
            assertEquals(0, visible.getRevision());
            executions.incrementAndGet();
            return completed(visible.getRunId(), visible.getTraceId());
        }).when(workflow).startSessionDebug(any(), any(), any(), any(), any());
        service.runStreamCreate(OWNER, (name, data) -> {
            names.add(name);
            if ("session.created".equals(name)) {
                admitted.set((RuntimeExecutableDebugSessionService.SessionView) data);
                assertEquals(0, executions.get());
            }
        }, createRequest(), com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionCancellation.none());
        assertEquals("RUNNING", admitted.get().status());
        assertEquals("COMPLETED", service.get(OWNER, admitted.get().sessionId()).status());
        assertTrue(names.indexOf("session.created") < names.indexOf("turn.completed"));
        assertEquals(1, executions.get());
    }

    @Test
    void rejectedStreamReservationNeverPublishesAdmissionOrExecutes() {
        doReturn(0).when(mapper).insert(any());
        var names = new java.util.ArrayList<String>();
        assertThrows(Exception.class, () -> service.runStreamCreate(OWNER, (name, data) -> names.add(name),
                createRequest(), com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionCancellation.none()));
        assertFalse(names.contains("session.created"));
        assertEquals(0, executions.get());
    }

    @Test
    void failedAdmissionDeliveryDoesNotExecuteAndClosesTheReservedSession() {
        var cancellation = com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionCancellation.none();
        assertThrows(Exception.class, () -> service.runStreamCreate(OWNER, (name, data) -> {
            if ("session.created".equals(name)) throw new java.io.IOException("client disconnected during admission");
        }, createRequest(), cancellation));
        assertEquals(0, executions.get());
        assertEquals("CANCELLED", mapper.selectOne(null).getStatus());
    }

    @Test
    void executionFailureStillLeavesTheClientWithAQueryableSession() {
        var admitted = new java.util.concurrent.atomic.AtomicReference<RuntimeExecutableDebugSessionService.SessionView>();
        doAnswer(call -> {
            executions.incrementAndGet();
            throw new IllegalStateException("execution outcome unknown");
        }).when(workflow).startSessionDebug(any(), any(), any(), any(), any());
        assertThrows(Exception.class, () -> service.runStreamCreate(OWNER, (name, data) -> {
            if ("session.created".equals(name)) admitted.set((RuntimeExecutableDebugSessionService.SessionView) data);
        }, createRequest(), com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionCancellation.none()));
        assertNotNull(admitted.get());
        assertEquals("FAILED", service.get(OWNER, admitted.get().sessionId()).status());
        assertEquals(1, executions.get());
    }

    @Test
    void projectionFailureAfterAdmissionCanBeRecoveredUsingThePublishedId() {
        var admitted = new java.util.concurrent.atomic.AtomicReference<RuntimeExecutableDebugSessionService.SessionView>();
        AtomicBoolean fail = new AtomicBoolean(true);
        doAnswer(call -> {
            com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<?> update = call.getArgument(1);
            if (fail.get() && update.getSqlSet().contains("state_snapshot_json")) {
                throw new DataAccessResourceFailureException("projection unavailable");
            }
            return call.callRealMethod();
        }).when(mapper).update(any(), any());
        assertThrows(Exception.class, () -> service.runStreamCreate(OWNER, (name, data) -> {
            if ("session.created".equals(name)) admitted.set((RuntimeExecutableDebugSessionService.SessionView) data);
        }, createRequest(), com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionCancellation.none()));
        assertNotNull(admitted.get());
        fail.set(false);
        assertEquals("done", service.get(OWNER, admitted.get().sessionId()).answer());
        assertEquals(1, executions.get());
    }

    private void preparePendingCompletion() {
        seedWaiting();
        AtomicBoolean fail = new AtomicBoolean(true);
        doAnswer(call -> {
            com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<?> update = call.getArgument(1);
            if (fail.get() && update.getSqlSet().contains("state_snapshot_json")) {
                throw new DataAccessResourceFailureException("injected projection failure");
            }
            return call.callRealMethod();
        }).when(mapper).update(any(), any());
        assertThrows(RuntimeException.class, () -> service.submit(OWNER, "session-1", submitRequest()));
        fail.set(false);
        assertEquals("RESUMING", row().getStatus());
        assertTrue(row().getResultJson().contains("completionSchema"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullSource
    @org.junit.jupiter.params.provider.ValueSource(strings = {"answer"})
    void completedSubmissionReplaysAfterReconstructionWhenExecutionMovesPastWaitingNode(String finalNode) {
        seedWaiting();
        doAnswer(call -> {
            executions.incrementAndGet();
            return new RuntimeWorkflowDebugService.DebugRunResult("run-1", "trace-1", null, "WORKFLOW", true,
                    "COMPLETED", "done", finalNode, List.of(), null, List.of(), Map.of("lastOutput", "done"), null, null);
        }).when(workflow).resumeSessionDebug(any(), any(), any(), any(), any());
        assertEquals("COMPLETED", service.submit(OWNER, "session-1", submitRequest()).status());
        assertEquals(finalNode, row().getCurrentNodeId());
        var storedPayload = row().getSubmittedPayloadJson();
        var restarted = transactional(new RuntimeExecutableDebugSessionService(
                transactional(new RuntimeDebugSessionStore(mapper, json, mock(RuntimeDebugExecutionLifecycle.class))), workflow, json, null, 8000));

        assertEquals("done", restarted.submit(OWNER, "session-1", submitRequest()).answer());
        assertEquals(storedPayload, row().getSubmittedPayloadJson());
        assertEquals(2, row().getRevision());
        assertEquals(1, executions.get());
    }

    @Test
    void previousSubmissionReplaysAtNextWaitingNodeWithoutConsumingItsInteraction() {
        seedWaiting();
        doAnswer(call -> {
            executions.incrementAndGet();
            return new RuntimeWorkflowDebugService.DebugRunResult("run-1", "trace-1", null, "WORKFLOW", true,
                    "SUSPENDED", "next question", "second", List.of(), Map.of("interactionId", "interaction-2"),
                    List.of(), Map.of(), null, null);
        }).when(workflow).resumeSessionDebug(any(), any(), any(), any(), any());
        assertEquals("SUSPENDED", service.submit(OWNER, "session-1", submitRequest()).status());
        var replay = service.submit(OWNER, "session-1", submitRequest());
        assertEquals("second", replay.currentNodeId());
        assertEquals("interaction-2", ((Map<?, ?>) replay.uiRequest()).get("interactionId"));
        assertEquals(2, row().getRevision());
        assertEquals(1, executions.get());

        assertThrows(IllegalArgumentException.class, () -> service.submit(OWNER, "session-1",
                new RuntimeExecutableDebugSessionService.SubmitRequest("submit", Map.of("q", "value"),
                        null, "interaction-1", "attempt-2")));
        assertEquals(1, executions.get());
        service.submit(OWNER, "session-1", new RuntimeExecutableDebugSessionService.SubmitRequest(
                "submit", Map.of("q", "next"), null, "interaction-2", "attempt-2"));
        assertEquals(2, executions.get());
        assertEquals(4, row().getRevision());
    }

    @Test
    void completedSubmissionStillRejectsChangedInputActionInteractionAndOwner() {
        seedWaiting();
        service.submit(OWNER, "session-1", submitRequest());
        for (var changed : List.of(
                new RuntimeExecutableDebugSessionService.SubmitRequest("submit", Map.of("q", "different"), null, "interaction-1", "attempt-1"),
                new RuntimeExecutableDebugSessionService.SubmitRequest("cancel", Map.of("q", "value"), null, "interaction-1", "attempt-1"),
                new RuntimeExecutableDebugSessionService.SubmitRequest("submit", Map.of("q", "value"), null, "interaction-2", "attempt-1"))) {
            assertThrows(IllegalStateException.class, () -> service.submit(OWNER, "session-1", changed));
        }
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> service.submit(
                new RuntimeDebugSessionOwner("default", "43"), "session-1", submitRequest()));
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> service.submit(
                new RuntimeDebugSessionOwner("other", "42"), "session-1", submitRequest()));
        assertEquals(2, row().getRevision());
        assertEquals(1, executions.get());
    }

    private void seedWaiting() {
        var row = new RuntimeExecutableDebugSessionEntity();
        row.setId("session-1");
        row.setRunId("run-1");
        row.setTraceId("trace-1");
        row.setTargetType("WORKFLOW_WORKING_COPY");
        row.setStatus("SUSPENDED");
        row.setRevision(0);
        row.setCurrentNodeId("confirm");
        row.setWorkingCopyDefinitionJson("{\"graphSpecJson\":\"{}\",\"executionEngine\":\"GRAPH_SPEC\"}");
        row.setDebugOptionsJson("{}");
        row.setStateSnapshotJson("{}");
        row.setMessagesJson("[]");
        row.setStepsJson("[]");
        row.setUiRequestJson("{\"interactionId\":\"interaction-1\"}");
        row.setCreateTime(LocalDateTime.now());
        row.setUpdateTime(LocalDateTime.now());
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

    private RuntimeWorkflowDebugService.DebugRunResult completed(String runId, String traceId) {
        return new RuntimeWorkflowDebugService.DebugRunResult(runId, traceId, null, "WORKFLOW", true,
                "COMPLETED", "done", "confirm", List.of(), null, List.of(), Map.of("lastOutput", "done"), null, null);
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
