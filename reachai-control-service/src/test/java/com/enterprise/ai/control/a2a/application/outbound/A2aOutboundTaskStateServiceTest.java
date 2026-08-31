package com.enterprise.ai.control.a2a.application.outbound;

import com.enterprise.ai.control.a2a.application.port.A2aContentCipher;
import com.enterprise.ai.control.a2a.application.port.A2aCredentialRepository;
import com.enterprise.ai.control.a2a.application.port.A2aOutboundExecutionRepository;
import com.enterprise.ai.control.a2a.application.port.A2aOutboundProtocolCodec;
import com.enterprise.ai.control.a2a.application.port.A2aPrincipalRepository;
import com.enterprise.ai.control.a2a.application.port.A2aRemoteAgentRepository;
import com.enterprise.ai.control.a2a.application.port.A2aRemoteAuthenticationPlanner;
import com.enterprise.ai.control.a2a.application.port.A2aRemoteProtocolTransport;
import com.enterprise.ai.control.a2a.application.port.A2aTaskRepository;
import com.enterprise.ai.control.a2a.application.port.A2aTransportAuditRepository;
import com.enterprise.ai.control.a2a.application.port.A2aTrustProfileRepository;
import com.enterprise.ai.control.a2a.domain.A2aDirection;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.domain.A2aPrincipalType;
import com.enterprise.ai.control.a2a.domain.A2aTaskState;
import com.enterprise.ai.control.a2a.domain.identity.A2aPrincipal;
import com.enterprise.ai.control.a2a.domain.identity.A2aPrincipalStatus;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteAgent;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteAgentRevision;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteAgentStatus;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteInterface;
import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteRevisionReviewStatus;
import com.enterprise.ai.control.a2a.domain.trust.A2aTrustProfile;
import com.enterprise.ai.control.a2a.infrastructure.A2aHubProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class A2aOutboundTaskStateServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 24, 1, 0);

    private final A2aTaskRepository tasks = mock(A2aTaskRepository.class);
    private final A2aOutboundExecutionRepository executions =
            mock(A2aOutboundExecutionRepository.class);
    private final A2aPrincipalRepository principals = mock(A2aPrincipalRepository.class);
    private final A2aTrustProfileRepository trusts = mock(A2aTrustProfileRepository.class);
    private final A2aRemoteAgentRepository remoteAgents = mock(A2aRemoteAgentRepository.class);
    private final A2aCredentialRepository credentials = mock(A2aCredentialRepository.class);
    private final A2aRemoteAuthenticationPlanner authentication =
            mock(A2aRemoteAuthenticationPlanner.class);
    private final A2aOutboundProtocolCodec codec = mock(A2aOutboundProtocolCodec.class);
    private final A2aOutboundPolicyAuthorizer authorizer = mock(A2aOutboundPolicyAuthorizer.class);
    private final A2aContentCipher contentCipher = mock(A2aContentCipher.class);
    private final A2aTransportAuditRepository audit = mock(A2aTransportAuditRepository.class);
    private final A2aHubProperties properties = new A2aHubProperties();
    private final Clock clock = Clock.fixed(
            Instant.parse("2026-08-24T01:00:00Z"), ZoneOffset.UTC);
    private final A2aOutboundTaskStateService service = new A2aOutboundTaskStateService(
            tasks, executions, principals, trusts, remoteAgents, credentials,
            authentication, codec, authorizer, contentCipher, audit,
            new ObjectMapper(), properties, clock);

    @BeforeEach
    void retainNoResponseContentByDefault() {
        when(tasks.findMessages(A2aDirection.OUTBOUND, 21L, "tenant-a", 101L, 100))
                .thenReturn(List.of());
        when(tasks.findArtifacts(A2aDirection.OUTBOUND, 21L, "tenant-a", 101L))
                .thenReturn(List.of());
        when(tasks.saveTask(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void terminalPollPersistsRemoteStateAndStopsThePollingProjection() {
        var prepared = prepared("tasks:get", task(NOW.plusMinutes(5)), execution("worker-1", 2));
        arrangeLocked(prepared);
        A2aOutboundProtocolCodec.DecodedResponse decoded = new A2aOutboundProtocolCodec.DecodedResponse(
                "{}", "remote-task-1", "remote-context-1",
                A2aTaskState.TASK_STATE_COMPLETED, NOW, "Remote Task completed",
                List.of(), List.of());

        var result = service.completeTaskOperation(
                prepared, transportResponse(), decoded, new byte[0]);

        assertEquals(A2aTaskState.TASK_STATE_COMPLETED.name(), result.state());
        ArgumentCaptor<A2aTaskRepository.TaskRecord> saved =
                ArgumentCaptor.forClass(A2aTaskRepository.TaskRecord.class);
        verify(tasks).saveTask(saved.capture());
        assertEquals(A2aTaskState.TASK_STATE_COMPLETED, saved.getValue().state());
        assertEquals(NOW, saved.getValue().completedAt());
        verify(executions).markTerminal(101L);
        verify(executions, never()).schedule(
                anyLong(), any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void uncertainGetSchedulesBoundedRetryWithoutInventingAStateTransition() {
        var prepared = prepared("tasks:get", task(NOW.plusMinutes(5)), execution("worker-1", 2));
        arrangeLocked(prepared);

        var result = service.failTaskOperation(
                prepared, null, new byte[0],
                new A2aDomainException(
                        "A2A_OUTBOUND_DELIVERY_UNCERTAIN", "network outcome unknown"));

        assertEquals(A2aTaskState.TASK_STATE_WORKING.name(), result.state());
        ArgumentCaptor<A2aTaskRepository.TaskRecord> saved =
                ArgumentCaptor.forClass(A2aTaskRepository.TaskRecord.class);
        verify(tasks).saveTask(saved.capture());
        assertEquals(A2aTaskState.TASK_STATE_WORKING, saved.getValue().state());
        verify(executions).schedule(
                101L, "worker-1", NOW.plusSeconds(8), NOW, 3,
                "A2A_OUTBOUND_DELIVERY_UNCERTAIN",
                "Outbound tasks:get failed: A2A_OUTBOUND_DELIVERY_UNCERTAIN");
        verify(executions, never()).markTerminal(101L);
    }

    @Test
    void uncertainCancelIsNeverRetriedAndSafePollingResumes() {
        var prepared = prepared("tasks:cancel", task(NOW.plusMinutes(5)), execution("worker-1", 2));
        arrangeLocked(prepared);

        var result = service.failTaskOperation(
                prepared, null, "{}".getBytes(StandardCharsets.UTF_8),
                new A2aDomainException(
                        "A2A_OUTBOUND_DELIVERY_UNCERTAIN", "network outcome unknown"));

        assertEquals(A2aTaskState.TASK_STATE_WORKING.name(), result.state());
        ArgumentCaptor<A2aTaskRepository.TaskRecord> saved =
                ArgumentCaptor.forClass(A2aTaskRepository.TaskRecord.class);
        verify(tasks).saveTask(saved.capture());
        assertEquals("UNKNOWN", saved.getValue().cancelPhase());
        verify(executions).activate(101L, NOW.plusSeconds(2));
        verify(executions, never()).schedule(
                anyLong(), any(), any(), any(), anyInt(), any(), any());
    }

    @Test
    void overdueOutboundTaskConvergesToFailedAndTerminatesPolling() {
        A2aTaskRepository.TaskRecord overdue = task(NOW.minusSeconds(1));
        when(tasks.lockTaskByExecutionId("execution-1")).thenReturn(Optional.of(overdue));

        assertTrue(service.expireOutboundTask("execution-1"));

        ArgumentCaptor<A2aTaskRepository.TaskRecord> saved =
                ArgumentCaptor.forClass(A2aTaskRepository.TaskRecord.class);
        verify(tasks).saveTask(saved.capture());
        assertEquals(A2aTaskState.TASK_STATE_FAILED, saved.getValue().state());
        assertEquals("A2A_TASK_DEADLINE_EXCEEDED", saved.getValue().errorCode());
        verify(executions).markTerminal(101L);
    }

    @Test
    void claimedPollWithRevokedPolicyFailsOnceInsteadOfSpinningOnTheLease() {
        A2aTaskRepository.TaskRecord working = task(NOW.plusMinutes(5));
        when(tasks.lockTaskByRefId(101L)).thenReturn(Optional.of(working));
        when(executions.lockByTaskRefId(101L))
                .thenReturn(Optional.of(execution("worker-1", 2)));

        service.failClaimedPollPreparation(
                101L, "worker-1",
                new A2aDomainException(
                        "A2A_OUTBOUND_OPERATION_FORBIDDEN", "policy was revoked"));

        ArgumentCaptor<A2aTaskRepository.TaskRecord> saved =
                ArgumentCaptor.forClass(A2aTaskRepository.TaskRecord.class);
        verify(tasks).saveTask(saved.capture());
        assertEquals(A2aTaskState.TASK_STATE_FAILED, saved.getValue().state());
        assertEquals("A2A_OUTBOUND_OPERATION_FORBIDDEN", saved.getValue().errorCode());
        verify(executions).markTerminal(101L);
    }

    @Test
    void idempotentReplayUsesTheAcceptedBindingWithoutReopeningRemoteCredentials() {
        var request = new A2aOutboundDelegationContracts.SendRequest(
                401L, "agent-a", 501L, 21L, 31L, 32L,
                "runtime-session-1", "context-1", "task-1", "message-1",
                "Review this", "review", "INTERNAL", List.of("text/plain"),
                20, 60_000L, "trace-1");
        A2aPrincipal principal = mock(A2aPrincipal.class);
        when(principal.id()).thenReturn(21L);
        when(principal.status()).thenReturn(A2aPrincipalStatus.ACTIVE);
        when(principal.principalType()).thenReturn(A2aPrincipalType.LOCAL_AGENT);
        when(principal.trustProfileId()).thenReturn(601L);
        when(principal.tenantScope()).thenReturn("tenant-a");
        when(principal.credentialId()).thenReturn(null);
        when(principal.attributes()).thenReturn(Map.of("runtimeAgentId", "agent-a"));
        A2aTrustProfile principalTrust = mock(A2aTrustProfile.class);
        when(principalTrust.id()).thenReturn(601L);
        A2aRemoteAgent remoteAgent = mock(A2aRemoteAgent.class);
        when(remoteAgent.id()).thenReturn(31L);
        when(remoteAgent.status()).thenReturn(A2aRemoteAgentStatus.TRUSTED);
        A2aRemoteAgentRevision revision = mock(A2aRemoteAgentRevision.class);
        when(revision.id()).thenReturn(32L);
        when(revision.reviewStatus()).thenReturn(A2aRemoteRevisionReviewStatus.APPROVED);
        A2aRemoteInterface remoteInterface = new A2aRemoteInterface(
                "http-json", "https://agent.example/a2a", "HTTP+JSON", "1.0", "tenant-a");
        when(revision.requireInterface("http-json")).thenReturn(remoteInterface);
        A2aTaskRepository.TaskRecord task = task(NOW.plusMinutes(5));
        A2aTaskRepository.MessageRecord duplicate = new A2aTaskRepository.MessageRecord(
                701L, "message-1", A2aDirection.OUTBOUND, 21L, "tenant-a",
                201L, 101L, "ROLE_USER", "cipher", null, "key", "nonce",
                "message-sha", "idem-sha", 100L, "INTERNAL", "1 text Part", "{}",
                NOW.plusDays(7), null, NOW.minusMinutes(1));
        A2aOutboundProtocolCodec.EncodedRequest encoded =
                new A2aOutboundProtocolCodec.EncodedRequest(
                        "{}".getBytes(StandardCharsets.UTF_8),
                        "{}".getBytes(StandardCharsets.UTF_8),
                        "message-sha", "idem-sha", 2L, "1 text Part");
        when(principals.lockActiveById(21L)).thenReturn(Optional.of(principal));
        when(trusts.findActiveById(601L)).thenReturn(Optional.of(principalTrust));
        when(remoteAgents.findById(31L)).thenReturn(Optional.of(remoteAgent));
        when(remoteAgents.findRevision(31L, 32L)).thenReturn(Optional.of(revision));
        when(tasks.findMessage(A2aDirection.OUTBOUND, 21L, "tenant-a", "message-1"))
                .thenReturn(Optional.of(duplicate));
        when(tasks.findTaskByRefId(A2aDirection.OUTBOUND, 21L, "tenant-a", 101L))
                .thenReturn(Optional.of(task));
        when(tasks.findContext(A2aDirection.OUTBOUND, 21L, "tenant-a", "context-1"))
                .thenReturn(Optional.of(context()));
        when(executions.lockByTaskRefId(101L))
                .thenReturn(Optional.of(execution(null, 2)));
        when(codec.encodeTextMessage(
                "tenant-a", "message-1", "remote-context-1", "remote-task-1",
                "Review this", "review", "INTERNAL", List.of("text/plain"), 20))
                .thenReturn(encoded);

        var replay = service.prepare(request);

        assertTrue(replay.replay());
        assertTrue(replay.replayResponse().idempotentReplay());
        verify(trusts, never()).findActiveById(602L);
        verify(authentication, never()).requireBinding(any(), any(), any(), any(), any());
        verify(credentials, never()).findById(anyLong());
    }

    private void arrangeLocked(A2aOutboundTaskStateService.PreparedTaskOperation prepared) {
        when(tasks.lockTaskByExecutionId("execution-1"))
                .thenReturn(Optional.of(task(NOW.plusMinutes(5))));
        when(executions.lockByTaskRefId(101L))
                .thenReturn(Optional.of(prepared.outboundExecution()));
        when(tasks.findContext(
                A2aDirection.OUTBOUND, 21L, "tenant-a", "context-1"))
                .thenReturn(Optional.of(context()));
    }

    private A2aOutboundTaskStateService.PreparedTaskOperation prepared(
            String operation,
            A2aTaskRepository.TaskRecord task,
            A2aOutboundExecutionRepository.OutboundExecution execution) {
        A2aPrincipal principal = mock(A2aPrincipal.class);
        when(principal.id()).thenReturn(21L);
        A2aRemoteAgent remoteAgent = mock(A2aRemoteAgent.class);
        when(remoteAgent.id()).thenReturn(31L);
        when(remoteAgent.remoteAgentKey()).thenReturn("remote-reviewer");
        A2aRemoteInterface remoteInterface = new A2aRemoteInterface(
                "http-json", "https://agent.example/a2a", "HTTP+JSON", "1.0", "tenant-a");
        A2aOutboundPolicyAuthorizer.EffectivePolicy policy =
                new A2aOutboundPolicyAuthorizer.EffectivePolicy(
                        "tenant-a", "INTERNAL", List.of("text/plain"), "review",
                        60_000L, NOW.plusDays(7), 4_000L, 8_000, 8_000L, 10);
        return new A2aOutboundTaskStateService.PreparedTaskOperation(
                "request-1", 101L, "task-1", task.executionId(), 201L, "context-1",
                "remote-task-1", principal, remoteAgent, null, remoteInterface,
                A2aRemoteAuthenticationPlanner.Binding.anonymous(), null, execution,
                policy, operation, "worker-1", "PLATFORM_USER", "operator-1",
                "trace-1", null);
    }

    private A2aTaskRepository.TaskRecord task(LocalDateTime deadline) {
        return new A2aTaskRepository.TaskRecord(
                101L, "task-1", A2aDirection.OUTBOUND, 21L, "tenant-a",
                201L, "context-1", null, null, 31L, 32L, "remote-task-1",
                "message-1", "a".repeat(64), A2aTaskState.TASK_STATE_WORKING,
                1, 3L, "execution-1", null, "trace-1", null,
                "Remote Task is working", null, null, "NONE", null, 1,
                NOW.minusMinutes(1), NOW.minusMinutes(1), null, NOW.plusDays(7),
                deadline, NOW.minusMinutes(1), NOW.minusMinutes(1));
    }

    private A2aTaskRepository.ContextRecord context() {
        return new A2aTaskRepository.ContextRecord(
                201L, "context-1", A2aDirection.OUTBOUND, 21L, "tenant-a",
                null, 31L, 32L, "remote-context-1", "runtime-session-1", "ACTIVE",
                NOW, NOW.plusDays(7), NOW.minusMinutes(1), NOW);
    }

    private A2aOutboundExecutionRepository.OutboundExecution execution(
            String leaseOwner, int attemptCount) {
        return new A2aOutboundExecutionRepository.OutboundExecution(
                301L, 101L, 401L, 501L, 601L, 602L,
                "http-json", null, null, "review", "INTERNAL", "[\"text/plain\"]",
                20, 4_000L, 8_000, 8_000L, 60_000L, "ACTIVE", attemptCount,
                NOW, NOW.minusSeconds(2), null, null, leaseOwner,
                NOW.plusSeconds(30), NOW.minusMinutes(1), NOW);
    }

    private A2aRemoteProtocolTransport.Response transportResponse() {
        return new A2aRemoteProtocolTransport.Response(
                "{}".getBytes(StandardCharsets.UTF_8), "application/a2a+json",
                200, "f".repeat(64), 12L);
    }
}
