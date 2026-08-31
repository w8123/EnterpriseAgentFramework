package com.enterprise.ai.control.a2a.application.task;

import com.enterprise.ai.control.a2a.application.identity.A2aInboundCallContext;
import com.enterprise.ai.control.a2a.application.port.A2aContentCipher;
import com.enterprise.ai.control.a2a.application.port.A2aPrincipalRepository;
import com.enterprise.ai.control.a2a.application.port.A2aPublicationRepository.PublishedCard;
import com.enterprise.ai.control.a2a.application.port.A2aTaskRepository;
import com.enterprise.ai.control.a2a.application.port.A2aTaskRepository.ContextRecord;
import com.enterprise.ai.control.a2a.application.port.A2aTaskRepository.MessageRecord;
import com.enterprise.ai.control.a2a.application.port.A2aTaskRepository.OutboxRecord;
import com.enterprise.ai.control.a2a.application.port.A2aTaskRepository.TaskRecord;
import com.enterprise.ai.control.a2a.domain.A2aAuthenticationMethod;
import com.enterprise.ai.control.a2a.domain.A2aDirection;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.domain.A2aPrincipalContext;
import com.enterprise.ai.control.a2a.domain.A2aPrincipalType;
import com.enterprise.ai.control.a2a.domain.A2aTaskState;
import com.enterprise.ai.control.a2a.domain.A2aTrustLevel;
import com.enterprise.ai.control.a2a.domain.identity.A2aPrincipal;
import com.enterprise.ai.control.a2a.domain.identity.A2aPrincipalStatus;
import com.enterprise.ai.control.a2a.domain.trust.A2aAuthorizationPolicy;
import com.enterprise.ai.control.a2a.domain.trust.A2aDataPolicy;
import com.enterprise.ai.control.a2a.domain.trust.A2aDelegatedIdentityPolicy;
import com.enterprise.ai.control.a2a.domain.trust.A2aEnvironment;
import com.enterprise.ai.control.a2a.domain.trust.A2aPersonalMemoryPolicy;
import com.enterprise.ai.control.a2a.domain.trust.A2aTrustProfile;
import com.enterprise.ai.control.a2a.domain.trust.A2aTrustProfileStatus;
import com.enterprise.ai.control.a2a.infrastructure.A2aHubProperties;
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
import java.util.Set;

import static com.enterprise.ai.control.a2a.application.task.A2aTaskContracts.PartProfile;
import static com.enterprise.ai.control.a2a.application.task.A2aTaskContracts.SendCommand;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class A2aTaskApplicationServiceTest {

    private A2aTaskRepository tasks;
    private A2aPrincipalRepository principals;
    private A2aContentCipher cipher;
    private A2aTaskApplicationService service;
    private A2aInboundCallContext call;
    private byte[] canonical;

    @BeforeEach
    void setUp() {
        tasks = mock(A2aTaskRepository.class);
        principals = mock(A2aPrincipalRepository.class);
        cipher = mock(A2aContentCipher.class);
        A2aHubProperties properties = new A2aHubProperties();
        properties.setMaxHistoryMessages(50);
        properties.setMaxPageSize(100);
        service = new A2aTaskApplicationService(tasks, principals, cipher,
                new A2aTaskPolicyAuthorizer(), properties,
                Clock.fixed(Instant.parse("2026-08-23T12:00:00Z"), ZoneOffset.UTC));
        call = call();
        canonical = "{\"messageId\":\"msg-1\",\"parts\":[{\"text\":\"hello\"}],\"role\":\"ROLE_USER\"}"
                .getBytes(StandardCharsets.UTF_8);
        when(principals.lockActiveById(7L)).thenReturn(Optional.of(principal()));
        when(tasks.findMessage(any(), anyLong(), any(), any())).thenReturn(Optional.empty());
        when(tasks.countNonTerminalTasks(7L, 1L)).thenReturn(0L);
        when(tasks.saveContext(any())).thenAnswer(invocation -> withContextId(invocation.getArgument(0)));
        when(tasks.saveTask(any())).thenAnswer(invocation -> withTaskId(invocation.getArgument(0)));
        when(tasks.saveMessage(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(tasks.findMessages(any(), anyLong(), any(), anyLong(), anyInt())).thenReturn(List.of());
        when(tasks.findArtifacts(any(), anyLong(), any(), anyLong())).thenReturn(List.of());
        when(cipher.encrypt(any(), any())).thenReturn(
                new A2aContentCipher.EncryptedContent("key-1", "nonce", "ciphertext"));
    }

    @Test
    void persistsTaskMessageOrderedEventsAndSafeOutboxBeforeReturningSubmitted() {
        var result = service.send(call, command("f".repeat(64)));

        assertEquals(A2aTaskState.TASK_STATE_SUBMITTED, result.state());
        verify(tasks).findMessage(A2aDirection.INBOUND, 7L, "tenant-a", "msg-1");
        verify(tasks).saveMessage(any(MessageRecord.class));
        verify(tasks, org.mockito.Mockito.times(2)).saveEvent(any());
        ArgumentCaptor<OutboxRecord> outbox = ArgumentCaptor.forClass(OutboxRecord.class);
        verify(tasks).saveOutbox(outbox.capture());
        assertEquals("EXECUTION_DISPATCH_REQUESTED", outbox.getValue().eventType());
        assertFalse(outbox.getValue().resourceRefJson().contains("hello"));
        assertFalse(outbox.getValue().resourceRefJson().contains("msg-1"));
        verify(cipher).encrypt(eq(canonical),
                org.mockito.ArgumentMatchers.contains("|7|tenant-a|msg-1"));
    }

    @Test
    void returnsExistingTaskForSameMessageHashWithoutSecondExecution() {
        MessageRecord existingMessage = new MessageRecord(
                21L, "msg-1", A2aDirection.INBOUND, 7L, "tenant-a", 11L, 12L,
                "ROLE_USER", "ciphertext", null, "key-1", "nonce", "f".repeat(64),
                "f".repeat(64), canonical.length, "INTERNAL", "hello", "{}", null, null, null);
        TaskRecord existingTask = existingTask();
        when(tasks.findMessage(A2aDirection.INBOUND, 7L, "tenant-a", "msg-1"))
                .thenReturn(Optional.of(existingMessage));
        when(tasks.findTaskByRefId(A2aDirection.INBOUND, 7L, "tenant-a", 12L))
                .thenReturn(Optional.of(existingTask));

        var result = service.send(call, command("f".repeat(64)));

        assertEquals("task_existing", result.taskId());
        verify(tasks, never()).saveOutbox(any());
        verify(cipher, never()).encrypt(any(), any());
    }

    @Test
    void rejectsSameMessageIdWithDifferentCanonicalHash() {
        MessageRecord existingMessage = new MessageRecord(
                21L, "msg-1", A2aDirection.INBOUND, 7L, "tenant-a", 11L, 12L,
                "ROLE_USER", "ciphertext", null, "key-1", "nonce", "e".repeat(64),
                "e".repeat(64), canonical.length, "INTERNAL", "hello", "{}", null, null, null);
        when(tasks.findMessage(A2aDirection.INBOUND, 7L, "tenant-a", "msg-1"))
                .thenReturn(Optional.of(existingMessage));

        A2aDomainException error = assertThrows(A2aDomainException.class,
                () -> service.send(call, command("f".repeat(64))));

        assertEquals("A2A_MESSAGE_IDEMPOTENCY_CONFLICT", error.code());
        verify(tasks, never()).saveTask(any());
    }

    private SendCommand command(String hash) {
        return new SendCommand(null, "msg-1", null, null, canonical, hash, canonical.length,
                "hello", "{}", "INTERNAL", new PartProfile(true, false, false, false,
                Set.of("text/plain")), List.of("text/plain"), 0, true, false);
    }

    private A2aInboundCallContext call() {
        PublishedCard publication = new PublishedCard(
                1L, 2L, "support-agent", "agent.example.com", "agent-1", 3L, 10L,
                "tenant-a", "DEVELOPMENT", List.of("text/plain"), List.of("text/plain"),
                List.of(), "{}", "0".repeat(64), LocalDateTime.of(2026, 8, 23, 10, 0));
        A2aPrincipalContext principal = new A2aPrincipalContext(
                7L, "partner-principal", A2aPrincipalType.REMOTE_AGENT, "tenant-a",
                A2aAuthenticationMethod.API_KEY, A2aTrustLevel.KNOWN,
                Set.of("a2a:message:send"), null);
        return new A2aInboundCallContext(publication, principal, trust());
    }

    private A2aTrustProfile trust() {
        return new A2aTrustProfile(
                10L, "partner-default", "Partner default", null, A2aDirection.INBOUND,
                A2aEnvironment.DEVELOPMENT, A2aTrustLevel.KNOWN,
                Set.of(A2aAuthenticationMethod.API_KEY), Set.of("a2a:message:send"),
                new A2aAuthorizationPolicy(Set.of("support-agent"), Set.of("*"), Set.of("*"),
                        Set.of("*"), Set.of("tenant-a")),
                new A2aDataPolicy(Set.of("INTERNAL"), true, true, false, 30),
                A2aDelegatedIdentityPolicy.DENY, A2aPersonalMemoryPolicy.DISABLED,
                60, 10, 1_048_576, 10_485_760, 300_000, false,
                A2aTrustProfileStatus.ACTIVE, 0, "test", "test", null, null);
    }

    private A2aPrincipal principal() {
        return new A2aPrincipal(
                7L, "partner-principal", A2aPrincipalType.REMOTE_AGENT, "Partner Agent",
                "tenant-a", "sha256:" + "a".repeat(64), 10L, 20L,
                Set.of("a2a:message:send"), Map.of(), A2aPrincipalStatus.ACTIVE,
                null, 0, "test", "test", null, null);
    }

    private ContextRecord withContextId(ContextRecord value) {
        return new ContextRecord(11L, value.contextId(), value.direction(), value.principalId(),
                value.tenantScope(), value.publicationId(), value.remoteAgentId(),
                value.remoteRevisionId(), value.remoteContextId(), value.runtimeSessionId(), value.status(),
                value.lastActivityAt(), value.expiresAt(), value.createdAt(), value.updatedAt());
    }

    private TaskRecord withTaskId(TaskRecord value) {
        return new TaskRecord(12L, value.taskId(), value.direction(), value.principalId(),
                value.tenantScope(), value.contextRefId(), value.contextId(), value.publicationId(),
                value.publicationRevisionId(), value.remoteAgentId(), value.remoteRevisionId(),
                value.remoteTaskId(), value.originMessageId(), value.originPayloadSha256(),
                value.state(), value.stateVersion(), value.lastEventSequence(), value.executionId(),
                value.runtimeRunId(), value.traceId(), value.runtimeInteractionId(),
                value.statusMessageSummary(), value.errorCode(),
                value.errorSummary(), value.cancelPhase(), value.cancelRequestedAt(), value.attemptCount(),
                value.submittedAt(), value.startedAt(), value.completedAt(), value.retentionExpiresAt(),
                value.deadlineAt(),
                value.createdAt(), LocalDateTime.of(2026, 8, 23, 12, 0));
    }

    private TaskRecord existingTask() {
        return new TaskRecord(
                12L, "task_existing", A2aDirection.INBOUND, 7L, "tenant-a", 11L, "ctx-existing",
                1L, 2L, null, null, null, "msg-1", "f".repeat(64),
                A2aTaskState.TASK_STATE_SUBMITTED, 0, 2, "exec-existing", null, null,
                null, "hello", null, null, "NONE", null, 0,
                LocalDateTime.of(2026, 8, 23, 12, 0), null, null,
                LocalDateTime.of(2026, 9, 23, 12, 0),
                LocalDateTime.of(2026, 8, 23, 12, 1),
                LocalDateTime.of(2026, 8, 23, 12, 0),
                LocalDateTime.of(2026, 8, 23, 12, 0));
    }
}
