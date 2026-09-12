package com.enterprise.ai.runtime.execution;

import com.enterprise.ai.runtime.supervisor.SupervisorRuntimeAdapter;
import com.enterprise.ai.runtime.supervisor.RuntimeAgentExecutionService;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigSnapshot;
import com.enterprise.ai.runtime.agent.RuntimeAgentExecutionContext;
import com.enterprise.ai.runtime.agent.RuntimeAgentExecutionContextResolver;
import com.enterprise.ai.runtime.agent.RuntimeAgentExecutionView;
import com.enterprise.ai.runtime.agent.RuntimeAgentView;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.interaction.RuntimeHumanApprovalService;
import com.enterprise.ai.runtime.runops.RuntimeRunLifecycleService;
import com.enterprise.ai.runtime.runops.RuntimeRunMapper;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanMapper;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanTerminationService;
import com.enterprise.ai.runtime.trace.RuntimeTraceRootService;
import com.enterprise.ai.runtime.trace.RuntimeTraceEvidenceWriter;
import com.enterprise.ai.runtime.supervisor.SupervisorApprovalInteractionService;
import com.enterprise.ai.runtime.supervisor.SupervisorExecutionTraceService;
import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RuntimeSupervisorApprovalPersistenceTest {
    private RuntimeQueryTestDatabase database;
    private RuntimeInteractionSessionMapper sessions;
    private RuntimeInteractionEventMapper events;
    private RuntimeSupervisorApprovalService approvals;
    private RuntimeInteractionExpiryTracePort expiryTrace;
    private SupervisorApprovalInteractionService factory;
    private RuntimeHumanApprovalService human;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private String id;

    @BeforeEach
    void setUp() throws Exception {
        database = new RuntimeQueryTestDatabase(List.of("runtime_interaction_session", "runtime_interaction_event",
                "runtime_run", "runtime_trace_span"), RuntimeInteractionSessionMapper.class,
                RuntimeInteractionEventMapper.class, RuntimeRunMapper.class, RuntimeTraceSpanMapper.class);
        sessions = spy(database.mapper(RuntimeInteractionSessionMapper.class));
        events = spy(database.mapper(RuntimeInteractionEventMapper.class));
        expiryTrace = spy(new SupervisorExecutionTraceService(mock(RuntimeTraceEvidenceWriter.class),
                new RuntimeRunLifecycleService(database.mapper(RuntimeRunMapper.class), json), json,
                mock(RuntimeTraceRootService.class),
                new RuntimeTraceSpanTerminationService(database.mapper(RuntimeTraceSpanMapper.class))));
        var proxy = new org.springframework.aop.framework.ProxyFactory(
                new RuntimeSupervisorApprovalService(sessions, events, json, expiryTrace, 900));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(database.jdbc().getDataSource()),
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        approvals = (RuntimeSupervisorApprovalService) proxy.getProxy();
        factory = new SupervisorApprovalInteractionService(approvals, json);
        human = new RuntimeHumanApprovalService(approvals, json);
    }

    @AfterEach
    void close() { if (database != null) database.close(); }

    @Test
    void confirmationClaimPersistsAResumeDeadline() {
        create(identity("tenant-a", "user-a"));
        prepare("confirm", true, identity("tenant-a", "user-a"));
        assertNotNull(row().getResumeDeadlineAt(), "A process crash must not leave an unbounded RESUMING claim");
        assertTrue(row().getResumeDeadlineAt().isAfter(LocalDateTime.now()));
    }

    @Test
    void abandonedConfirmationReturnsUnknownWithoutGrantAndRejectsLateSuccess() {
        create(identity("tenant-a", "user-a"));
        var first = prepare("confirm", true, identity("tenant-a", "user-a"));
        database.jdbc().update("UPDATE runtime_interaction_session SET resume_deadline_at=? WHERE id=?",
                LocalDateTime.now().minusSeconds(1), id);
        var retry = prepare("confirm", true, identity("tenant-a", "user-a"));
        assertFalse(retry.approved());
        assertNull(retry.grant());
        var metadata = (Map<?, ?>) retry.replayResult().get("metadata");
        assertEquals("SUPERVISOR_APPROVAL_RESUME_TIMEOUT", metadata.get("code"));
        assertEquals("UNKNOWN", metadata.get("outcome"));
        assertEquals(false, metadata.get("retryable"));
        assertEquals(true, metadata.get("reconciliationRequired"));
        assertEquals("EXPIRED", row().getStatus());
        var late = approvals.completeResume(id, first.idempotencyKey(), first.submittedPayload(), Map.of("success", true));
        assertEquals(false, late.get("success"));
        assertEquals(1, countEvents("EXPIRED"));
        assertEquals(0, countEvents("COMPLETED"));
    }

    @Test
    void sweeperFindsAndExpiresAnAbandonedSupervisorResume() {
        create(identity("tenant-a", "user-a"));
        prepare("confirm", true, identity("tenant-a", "user-a"));
        LocalDateTime now = LocalDateTime.now();
        database.jdbc().update("UPDATE runtime_interaction_session SET resume_deadline_at=? WHERE id=?", now.minusSeconds(1), id);
        var processor = new RuntimeInteractionExpiryProcessor(sessions,
                new RuntimeWorkflowInteractionSessionService(sessions, events, json), expiryTrace, approvals);
        var candidates = processor.findExpiredCandidates(now, 20);
        assertTrue(candidates.stream().anyMatch(candidate -> id.equals(candidate.getId())));
        assertTrue(processor.expireOne(row(), now));
        assertEquals("EXPIRED", row().getStatus());
        assertEquals(1, countEvents("EXPIRED"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"RUNNING", "SUSPENDED"})
    void expiryClosesOpenRunAndSpansAndPreservesTerminalEvidence(String runStatus) {
        create(identity("tenant-a", "user-a"));
        prepare("confirm", true, identity("tenant-a", "user-a"));
        deadlinePassed();
        database.jdbc().update("INSERT INTO runtime_run (trace_id, run_type, entry_type, status) VALUES ('trace-original', 'AGENT', 'EMBED', ?)", runStatus);
        for (String status : List.of("RUNNING", "WAITING_USER", "WAITING_APPROVAL", "SUCCESS")) {
            database.jdbc().update("INSERT INTO runtime_trace_span (trace_id, span_id, span_type, status, ended_at) VALUES ('trace-original', ?, 'SUPERVISOR', ?, ?)",
                    status, status, "SUCCESS".equals(status) ? LocalDateTime.now().minusMinutes(1) : null);
        }
        assertTrue(approvals.expireResume(row(), LocalDateTime.now()));
        assertEquals("TIMED_OUT", database.jdbc().queryForObject("SELECT status FROM runtime_run WHERE trace_id='trace-original'", String.class));
        assertEquals(RuntimeSupervisorApprovalService.RESUME_TIMEOUT,
                database.jdbc().queryForObject("SELECT error_code FROM runtime_run WHERE trace_id='trace-original'", String.class));
        assertEquals(3, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_trace_span WHERE status='TIMEOUT' AND ended_at IS NOT NULL", Integer.class));
        assertEquals(1, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_trace_span WHERE status='SUCCESS'", Integer.class));
    }

    @Test
    void expiredClaimRejectsWrongIdentitySessionAndAttemptBeforeChangingAnything() {
        create(identity("tenant-a", "user-a"));
        prepare("confirm", true, identity("tenant-a", "user-a"));
        deadlinePassed();
        for (var caller : List.of(identity("tenant-b", "user-a"), identity("tenant-a", "user-b"))) {
            assertThrows(IllegalArgumentException.class, () -> prepare("confirm", true, caller));
        }
        for (var change : Map.of("sessionId", "other-session", "idempotencyKey", "other-attempt").entrySet()) {
            var request = new java.util.LinkedHashMap<>(submission("confirm", true));
            request.put(change.getKey(), change.getValue());
            assertThrows(IllegalArgumentException.class, () -> approvals.prepareResume(id, request, identity("tenant-a", "user-a")));
        }
        assertThrows(IllegalArgumentException.class, () -> prepare("reject", false, identity("tenant-a", "user-a")));
        assertEquals("RESUMING", row().getStatus());
        assertEquals(0, countEvents("EXPIRED"));
        verifyNoInteractions(expiryTrace);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"event", "trace"})
    void expiryFailureRollsBackClaimReceiptAndEvent(String failure) {
        create(identity("tenant-a", "user-a"));
        prepare("confirm", true, identity("tenant-a", "user-a"));
        deadlinePassed();
        var before = row();
        database.jdbc().update("INSERT INTO runtime_run (trace_id, run_type, entry_type, status) VALUES ('trace-original', 'AGENT', 'EMBED', 'RUNNING')");
        database.jdbc().update("INSERT INTO runtime_trace_span (trace_id, span_id, span_type, status) VALUES ('trace-original', 'root', 'SUPERVISOR', 'RUNNING')");
        if ("event".equals(failure)) failEvent("EXPIRED");
        else doAnswer(call -> {
            call.callRealMethod();
            throw new org.springframework.dao.DataAccessResourceFailureException("injected after trace and run updates");
        })
                .when(expiryTrace).expireSupervisorApprovalResume(anyString(), anyString(), any());
        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,
                () -> approvals.expireResume(row(), LocalDateTime.now()));
        assertEquals("RESUMING", row().getStatus());
        assertEquals(before.getRevision(), row().getRevision());
        assertEquals(before.getResultJson(), row().getResultJson());
        assertEquals(0, countEvents("EXPIRED"));
        assertEquals("RUNNING", database.jdbc().queryForObject("SELECT status FROM runtime_run WHERE trace_id='trace-original'", String.class));
        assertEquals("RUNNING", database.jdbc().queryForObject("SELECT status FROM runtime_trace_span WHERE trace_id='trace-original'", String.class));
    }

    @Test
    void lateCompletionWithoutAPollStoresUnknownAndRepeatedReplaysAreImmutable() {
        create(identity("tenant-a", "user-a"));
        var claim = prepare("confirm", true, identity("tenant-a", "user-a"));
        deadlinePassed();
        var result = approvals.completeResume(id, claim.idempotencyKey(), claim.submittedPayload(), Map.of("success", true));
        assertEquals(false, result.get("success"));
        String receipt = row().getResultJson();
        int revision = row().getRevision();
        for (int i = 0; i < 3; i++) {
            var replay = prepare("confirm", true, identity("tenant-a", "user-a"));
            assertEquals(result, replay.replayResult());
            assertFalse(replay.approved());
            assertNull(replay.grant());
        }
        assertEquals(receipt, row().getResultJson());
        assertEquals(revision, row().getRevision());
        assertEquals(1, countEvents("EXPIRED"));
        assertEquals(0, countEvents("COMPLETED"));
        verify(expiryTrace, times(1)).expireSupervisorApprovalResume(eq("trace-original"), eq(id), any());
    }

    @Test
    void staleExpiryCandidateCannotReplaceCompletedReceipt() {
        create(identity("tenant-a", "user-a"));
        var claim = prepare("confirm", true, identity("tenant-a", "user-a"));
        var candidate = row();
        approvals.completeResume(id, claim.idempotencyKey(), claim.submittedPayload(), Map.of("success", true));
        String receipt = row().getResultJson();
        assertFalse(approvals.expireResume(candidate, candidate.getResumeDeadlineAt().plusSeconds(1)));
        assertEquals("COMPLETED", row().getStatus());
        assertEquals(receipt, row().getResultJson());
        assertEquals(0, countEvents("EXPIRED"));
        verifyNoInteractions(expiryTrace);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, -1})
    void resumeDeadlineConfigurationMustBePositive(int seconds) {
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeSupervisorApprovalService(sessions, events, json, expiryTrace, seconds));
    }

    private void deadlinePassed() {
        database.jdbc().update("UPDATE runtime_interaction_session SET resume_deadline_at=? WHERE id=?",
                LocalDateTime.now().minusSeconds(1), id);
    }

    @Test
    void expiryCommitsItsStateAndEventEvenThoughTheCallerReceivesAnExpiredError() {
        create(identity("tenant-a", "user-a"));
        expire();
        assertThrows(IllegalArgumentException.class, () -> prepare("confirm", true, identity("tenant-a", "user-a")));
        assertEquals("EXPIRED", row().getStatus());
        assertEquals(1, countEvents("EXPIRED"));
        assertEquals(1, row().getRevision());
    }

    @Test
    void pendingListDoesNotReturnExpiredWaitingRows() {
        create(identity("tenant-a", "user-a"));
        expire();
        assertTrue(human.listPendingHumanApprovals("agent-1", "user-a", 50).isEmpty());
    }

    @Test
    void pendingListDoesNotExposePrivateCheckpointArgumentsOrOriginalInput() throws Exception {
        create(identity("tenant-a", "user-a"));
        var views = human.listPendingHumanApprovals("agent-1", "user-a", 50);
        assertEquals(1, views.size());
        String visible = json.writeValueAsString(views);
        assertFalse(visible.contains("private-credential"));
        assertFalse(visible.contains("private-original-message"));
        assertFalse(views.get(0).state().containsKey("args"));
        assertFalse(views.get(0).state().containsKey("input"));
        assertTrue(visible.contains("update_order"));
    }

    @Test
    void creationBindsTheTrustedTenant() {
        create(identity("tenant-a", "user-a"));
        assertEquals("tenant-a", row().getTenantId());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void authenticatedGatewayPrincipalCanDecideItsStoredApprovalBeforeProjectBinding(boolean confirm) {
        create(identity("tenant-a", "user-a"));
        // This is the identity produced by the signed Control -> Runtime entry point.
        var caller = WorkflowExecutionIdentity.fromAgent("tenant-a", null, null, "user-a");
        var request = new java.util.LinkedHashMap<>(submission(confirm ? "confirm" : "reject", confirm));
        request.put("projectId", 999L);
        request.put("projectCode", "body-attacker");
        request.put("userId", "body-attacker");
        var decision = approvals.prepareResume(id, request, caller);
        assertEquals(confirm, decision.approved());
        assertEquals(!confirm, decision.rejected());
        assertEquals("agent-1", decision.agentId());
        assertEquals(12L, decision.agentConfigVersionId());
        assertEquals("RESUMING", row().getStatus());
        var response = Map.<String, Object>of("success", true, "answer", "stored-result");
        approvals.completeResume(id, decision.idempotencyKey(), decision.submittedPayload(), response);
        var replay = approvals.prepareResume(id, request, caller).replayResult();
        assertEquals(response.get("success"), replay.get("success"));
        assertEquals(response.get("answer"), replay.get("answer"));
        assertEquals(true, ((Map<?, ?>) replay.get("metadata")).get("idempotentReplay"));
        assertEquals(1, countEvents("SUBMITTED"));
        assertEquals(1, countEvents("COMPLETED"));
    }

    @Test
    void unboundGatewayPrincipalStillRequiresTheExactStoredUserTenantAndSession() {
        create(identity("tenant-a", "user-a"));
        for (var caller : List.of(
                WorkflowExecutionIdentity.fromAgent("tenant-a", null, null, "other-user"),
                WorkflowExecutionIdentity.fromAgent("other-tenant", null, null, "user-a"))) {
            assertThrows(IllegalArgumentException.class, () -> prepare("confirm", true, caller));
        }
        var wrongSession = new java.util.LinkedHashMap<>(submission("confirm", true));
        wrongSession.put("sessionId", "other-session");
        assertThrows(IllegalArgumentException.class, () -> approvals.prepareResume(id, wrongSession,
                WorkflowExecutionIdentity.fromAgent("tenant-a", null, null, "user-a")));
        assertEquals("WAITING_USER", row().getStatus());
        assertEquals(0, countEvents("SUBMITTED"));
    }

    @Test
    void anExplicitlyBoundDifferentProjectCannotClaimTheStoredApproval() {
        create(identity("tenant-a", "user-a"));
        for (var caller : List.of(
                WorkflowExecutionIdentity.fromAgent("tenant-a", 99L, "orders", "user-a"),
                WorkflowExecutionIdentity.fromAgent("tenant-a", 7L, "another-project", "user-a"),
                WorkflowExecutionIdentity.fromAgent("tenant-a", 99L, null, "user-a"),
                WorkflowExecutionIdentity.fromAgent("tenant-a", null, "another-project", "user-a"))) {
            assertThrows(IllegalArgumentException.class, () -> prepare("confirm", true, caller));
        }
        assertEquals("WAITING_USER", row().getStatus());
        assertEquals(0, countEvents("SUBMITTED"));
    }

    @Test
    void aTrustedGatewayUserCannotAdoptAnApprovalWithoutAStoredUserOwner() {
        create(WorkflowExecutionIdentity.fromAutomation("default", 7L, "orders", "automation-fixture"));
        assertNull(row().getUserId());
        assertEquals("default", row().getTenantId());
        assertThrows(IllegalArgumentException.class, () -> prepare("confirm", true,
                WorkflowExecutionIdentity.fromAgent("default", null, null, "user-a")));
        assertEquals("WAITING_USER", row().getStatus());
        assertEquals(0, countEvents("SUBMITTED"));
    }

    @Test
    void sameUserAndSessionFromAnotherTenantCannotApprove() {
        create(identity("tenant-a", "user-a"));
        assertThrows(IllegalArgumentException.class, () -> prepare("confirm", true, identity("tenant-b", "user-a")));
        assertEquals("WAITING_USER", row().getStatus());
        assertEquals(0, countEvents("SUBMITTED"));
    }

    @Test
    void anUntrustedCallerCannotReceiveAnApprovalGrant() {
        create(null);
        assertThrows(IllegalArgumentException.class, () -> prepare("confirm", true, null));
        assertEquals("WAITING_USER", row().getStatus());
    }

    @Test
    void missingPinnedConfigCannotProduceAnApprovalGrant() throws Exception {
        create(identity("tenant-a", "user-a"));
        ObjectNode checkpoint = (ObjectNode) json.readTree(row().getResumeCheckpointJson());
        checkpoint.remove("agentConfigVersionId");
        database.jdbc().update("UPDATE runtime_interaction_session SET resume_checkpoint_json = ? WHERE id = ?",
                checkpoint.toString(), id);
        assertThrows(IllegalArgumentException.class, () -> prepare("confirm", true, identity("tenant-a", "user-a")));
        assertEquals("WAITING_USER", row().getStatus());
    }

    @Test
    void equivalentExplicitConfirmationValuesShareOneIdempotentAttempt() {
        create(identity("tenant-a", "user-a"));
        assertTrue(prepare("confirm", true, identity("tenant-a", "user-a")).approved());
        var replay = prepare("approve", "TRUE", identity("tenant-a", "user-a"));
        assertFalse(replay.approved());
        assertNotNull(replay.replayResult());
        assertEquals("SUPERVISOR_APPROVAL_RESUMING", ((Map<?, ?>) replay.replayResult().get("metadata")).get("code"));
        assertEquals(1, countEvents("SUBMITTED"));
    }

    @Test
    void originalToolAndArgsRemainPinnedWhenSubmissionTriesToReplaceThem() {
        create(identity("tenant-a", "user-a"));
        var request = new java.util.LinkedHashMap<>(submission("confirm", true));
        request.put("toolName", "delete_all");
        request.put("args", Map.of("orderId", "another-order"));
        var prepared = approvals.prepareResume(id, request, identity("tenant-a", "user-a"));
        assertTrue(prepared.approved());
        assertEquals("update_order", prepared.grant().toolName());
        assertEquals("订单一", prepared.grant().approvedArgs().get("orderId"));
        assertEquals("private-credential", prepared.grant().approvedArgs().get("token"));
        assertEquals("trace-original", prepared.originalInput().get("traceId"));
        assertEquals("user-a", prepared.grant().approvedBy());
    }

    @Test
    void creationEventFailureRollsBackSessionAndEarlierEvent() {
        failEvent("REQUESTED");
        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,
                () -> create(identity("tenant-a", "user-a")));
        assertEquals(0, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_interaction_session", Integer.class));
        assertEquals(0, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_interaction_event", Integer.class));
    }

    @Test
    void submittedEventFailureRollsBackTheClaim() {
        create(identity("tenant-a", "user-a"));
        failEvent("SUBMITTED");
        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,
                () -> prepare("confirm", true, identity("tenant-a", "user-a")));
        assertEquals("WAITING_USER", row().getStatus());
        assertEquals(0, row().getRevision());
        assertEquals(0, countEvents("SUBMITTED"));
    }

    @Test
    void expiryEventFailureRollsBackExpiryInsteadOfCommittingPartialEvidence() {
        create(identity("tenant-a", "user-a"));
        expire();
        failEvent("EXPIRED");
        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,
                () -> prepare("confirm", true, identity("tenant-a", "user-a")));
        assertEquals("WAITING_USER", row().getStatus());
        assertEquals(0, countEvents("EXPIRED"));
    }

    @Test
    void completedResultReplaysWithoutASecondGrantOrEvent() {
        create(identity("tenant-a", "user-a"));
        var prepared = prepare("confirm", true, identity("tenant-a", "user-a"));
        approvals.completeResume(id, prepared.idempotencyKey(), prepared.submittedPayload(),
                Map.of("success", true, "answer", "执行完成", "metadata", Map.of("code", "OK")));
        var replay = prepare("confirm", true, identity("tenant-a", "user-a"));
        assertFalse(replay.approved());
        assertNull(replay.grant());
        assertEquals("执行完成", replay.replayResult().get("answer"));
        assertEquals(1, countEvents("COMPLETED"));
    }

    @Test
    void agentEntryResumesTheOriginallyApprovedConfigAfterTheActiveVersionChanges() {
        create(identity("tenant-a", "user-a"));
        var resolver = mock(RuntimeAgentExecutionContextResolver.class);
        var runtime = mock(SupervisorRuntimeAdapter.class);
        when(resolver.resolve("agent-1")).thenReturn(Optional.of(context(99L)));
        when(resolver.resolvePublished("agent-1", 12L)).thenReturn(Optional.of(context(12L)));
        when(runtime.execute(any())).thenReturn(new SupervisorRuntimeAdapter.SupervisorResult(
                true, "OK", "执行完成", "trace-original", List.of(), Map.of(), null));
        var execution = new RuntimeAgentExecutionService(resolver, runtime, approvals,
                mock(RuntimeInteractionResumeService.class), mock(RuntimeSessionClearPort.class),
                mock(RuntimeRunLifecycleService.class));
        Map<String, Object> response = execution.execute(submission("confirm", true), true,
                RuntimeAgentExecutionEventSink.NOOP, RuntimeAgentExecutionCancellation.NOOP,
                identity("tenant-a", "user-a"));
        assertEquals(true, response.get("success"));
        var request = ArgumentCaptor.forClass(SupervisorRuntimeAdapter.SupervisorRequest.class);
        verify(runtime).execute(request.capture());
        assertEquals(12L, request.getValue().config().getId());
        verify(resolver).resolvePublished("agent-1", 12L);
        verify(resolver, never()).resolve(any());
        assertEquals("COMPLETED", row().getStatus());
    }

    @Test
    void missingApprovedConfigFailsWithoutFallingBackToTheActiveVersion() {
        create(identity("tenant-a", "user-a"));
        var resolver = mock(RuntimeAgentExecutionContextResolver.class);
        var runtime = mock(SupervisorRuntimeAdapter.class);
        when(resolver.resolvePublished("agent-1", 12L)).thenReturn(Optional.empty());
        when(resolver.resolve("agent-1")).thenReturn(Optional.of(context(99L)));
        Map<String, Object> response = execute(resolver, runtime);
        assertEquals(false, response.get("success"));
        verify(runtime, never()).execute(any());
        verify(resolver, never()).resolve(any());
        assertEquals("COMPLETED", row().getStatus());
        assertNotNull(prepare("confirm", true, identity("tenant-a", "user-a")).replayResult());
    }

    @Test
    void anApprovedConfigDoesNotBypassTheAgentsCurrentDisabledState() {
        create(identity("tenant-a", "user-a"));
        var resolver = mock(RuntimeAgentExecutionContextResolver.class);
        var runtime = mock(SupervisorRuntimeAdapter.class);
        var disabled = new RuntimeAgentExecutionContext(new RuntimeAgentExecutionView("agent-1", 7L, "orders", "order-agent",
                "订单助手", null, "PROJECT", null, false, 99L, null, null), config(12L), List.of(), List.of(), null);
        when(resolver.resolvePublished("agent-1", 12L)).thenReturn(Optional.of(disabled));
        when(runtime.execute(any())).thenReturn(new SupervisorRuntimeAdapter.SupervisorResult(
                true, "OK", "不应执行", "trace-original", List.of(), Map.of(), null));
        Map<String, Object> response = execute(resolver, runtime);
        assertEquals(false, response.get("success"));
        assertEquals("RUNTIME_AGENT_DISABLED", ((Map<?, ?>) response.get("metadata")).get("code"));
        verify(runtime, never()).execute(any());
        assertEquals("COMPLETED", row().getStatus());
    }

    @Test
    void concurrentConfirmationsIssueOnlyOneGrantAndOneSubmissionEvent() throws Exception {
        create(identity("tenant-a", "user-a"));
        var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<RuntimeSupervisorApprovalPort.ResumeDecision> task = () -> {
            ready.countDown();
            assertTrue(start.await(5, java.util.concurrent.TimeUnit.SECONDS));
            return prepare("confirm", true, identity("tenant-a", "user-a"));
        };
        try {
            var first = workers.submit(task);
            var second = workers.submit(task);
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            var results = List.of(first.get(5, java.util.concurrent.TimeUnit.SECONDS),
                    second.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(RuntimeSupervisorApprovalPort.ResumeDecision::approved).count());
            assertEquals(1, results.stream().filter(value -> value.grant() != null).count());
            assertEquals(1, results.stream().filter(value -> value.replayResult() != null).count());
            assertEquals(1, countEvents("SUBMITTED"));
            assertEquals(1, row().getRevision());
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test
    void completionEventFailureRollsBackTheResultAndAllowsCompletionRetry() {
        create(identity("tenant-a", "user-a"));
        var prepared = prepare("confirm", true, identity("tenant-a", "user-a"));
        failEvent("COMPLETED");
        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class, () ->
                approvals.completeResume(id, prepared.idempotencyKey(), prepared.submittedPayload(), Map.of("success", true)));
        assertEquals("RESUMING", row().getStatus());
        assertEquals(1, row().getRevision());
        assertNull(row().getResultJson());
        assertEquals(0, countEvents("COMPLETED"));
        doCallRealMethod().when(events).insert(any(RuntimeInteractionEventEntity.class));
        approvals.completeResume(id, prepared.idempotencyKey(), prepared.submittedPayload(), Map.of("success", true));
        assertEquals("COMPLETED", row().getStatus());
        assertEquals(1, countEvents("COMPLETED"));
    }

    private Map<String, Object> execute(RuntimeAgentExecutionContextResolver resolver, SupervisorRuntimeAdapter runtime) {
        var execution = new RuntimeAgentExecutionService(resolver, runtime, approvals,
                mock(RuntimeInteractionResumeService.class), mock(RuntimeSessionClearPort.class),
                mock(RuntimeRunLifecycleService.class));
        return execution.execute(submission("confirm", true), true,
                RuntimeAgentExecutionEventSink.NOOP, RuntimeAgentExecutionCancellation.NOOP,
                identity("tenant-a", "user-a"));
    }

    private void create(WorkflowExecutionIdentity identity) {
        var config = config(12L);
        var agent = new RuntimeAgentView("agent-1", 7L, "orders", "order-agent", "订单助手", null,
                "PROJECT", null, true, 12L, 12L, 1, "ACTIVE", "AGENTSCOPE", 1, null, null);
        id = factory.create(new SupervisorExecutionTraceService.TraceHandle("trace-original", "root", 1L, LocalDateTime.now()),
                agent, config, "WORKFLOW", "update_order", "orders:write", "WRITE",
                Map.of("message", "private-original-message", "sessionId", "session-1", "userId", "body-attacker"),
                Map.of("orderId", "订单一", "token", "private-credential"), "需要确认", identity).interactionId();
    }

    private Map<String, Object> submission(String action, Object confirm) {
        return Map.of("interactionId", id, "idempotencyKey", "attempt-1", "sessionId", "session-1",
                "uiSubmit", Map.of("action", action, "values", Map.of("confirm", confirm)));
    }

    private RuntimeSupervisorApprovalPort.ResumeDecision prepare(String action, Object confirm, WorkflowExecutionIdentity identity) {
        return approvals.prepareResume(id, submission(action, confirm), identity);
    }

    private WorkflowExecutionIdentity identity(String tenant, String user) {
        return WorkflowExecutionIdentity.fromAgent(tenant, 7L, "orders", user);
    }

    private RuntimeAgentConfigSnapshot config(Long id) {
        RuntimeAgentConfigSnapshot config = RuntimeAgentConfigSnapshot.builder()
                .id(id)
                .agentId("agent-1")
                .versionNo(id.intValue())
                .status("ACTIVE")
                .runtimeType("AGENTSCOPE")
                .build();
        return config;
    }

    private RuntimeAgentExecutionContext context(Long version) {
        return new RuntimeAgentExecutionContext(new RuntimeAgentExecutionView("agent-1", 7L, "orders", "order-agent",
                "订单助手", null, "PROJECT", null, true, 99L, null, null), config(version), List.of(), List.of(), null);
    }

    private RuntimeInteractionSessionEntity row() { return sessions.selectById(id); }

    private void expire() {
        database.jdbc().update("UPDATE runtime_interaction_session SET expires_at = ? WHERE id = ?",
                LocalDateTime.now().minusSeconds(1), id);
    }

    private int countEvents(String type) {
        return database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_interaction_event WHERE session_id = ? AND event_type = ?",
                Integer.class, id, type);
    }

    private void failEvent(String type) {
        doAnswer(call -> {
            RuntimeInteractionEventEntity event = call.getArgument(0);
            if (type.equals(event.getEventType())) throw new org.springframework.dao.DataAccessResourceFailureException("injected event failure");
            return call.callRealMethod();
        }).when(events).insert(any(RuntimeInteractionEventEntity.class));
    }
}
