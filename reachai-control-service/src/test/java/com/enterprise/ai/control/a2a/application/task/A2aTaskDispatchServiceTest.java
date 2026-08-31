package com.enterprise.ai.control.a2a.application.task;

import com.enterprise.ai.control.a2a.application.port.A2aContentCipher;
import com.enterprise.ai.control.a2a.application.port.A2aPrincipalRepository;
import com.enterprise.ai.control.a2a.application.port.A2aPublicationRepository;
import com.enterprise.ai.control.a2a.application.port.A2aRuntimeExecutionGateway.CancelResult;
import com.enterprise.ai.control.a2a.application.port.A2aRuntimeExecutionGateway.ExecutionResult;
import com.enterprise.ai.control.a2a.application.port.A2aTaskRepository;
import com.enterprise.ai.control.a2a.application.port.A2aTaskRepository.MessageRecord;
import com.enterprise.ai.control.a2a.application.port.A2aTaskRepository.TaskRecord;
import com.enterprise.ai.control.a2a.application.port.A2aTrustProfileRepository;
import com.enterprise.ai.control.a2a.domain.A2aDirection;
import com.enterprise.ai.control.a2a.domain.A2aPrincipalType;
import com.enterprise.ai.control.a2a.domain.A2aTaskState;
import com.enterprise.ai.control.a2a.domain.A2aTrustLevel;
import com.enterprise.ai.control.a2a.domain.identity.A2aPrincipal;
import com.enterprise.ai.control.a2a.domain.identity.A2aPrincipalStatus;
import com.enterprise.ai.control.a2a.domain.publication.A2aPublication;
import com.enterprise.ai.control.a2a.domain.publication.A2aPublicationRevision;
import com.enterprise.ai.control.a2a.domain.publication.A2aPublicationStatus;
import com.enterprise.ai.control.a2a.domain.trust.A2aTrustProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class A2aTaskDispatchServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 24, 0, 0);

    private A2aTaskRepository tasks;
    private A2aPublicationRepository publications;
    private A2aPrincipalRepository principals;
    private A2aTrustProfileRepository trusts;
    private A2aContentCipher cipher;
    private A2aTaskDispatchService service;

    @BeforeEach
    void setUp() {
        tasks = mock(A2aTaskRepository.class);
        publications = mock(A2aPublicationRepository.class);
        principals = mock(A2aPrincipalRepository.class);
        trusts = mock(A2aTrustProfileRepository.class);
        cipher = mock(A2aContentCipher.class);
        Clock clock = Clock.fixed(Instant.parse("2026-08-24T00:00:00Z"), ZoneOffset.UTC);
        service = new A2aTaskDispatchService(
                tasks, publications, principals, trusts, cipher, new ObjectMapper(), clock);
        when(tasks.saveTask(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void preparesFrozenConfigAndAttestedRemotePrincipal() {
        byte[] canonical = "{\"messageId\":\"msg-1\"}".getBytes(StandardCharsets.UTF_8);
        TaskRecord task = task(A2aTaskState.TASK_STATE_SUBMITTED, "NONE", NOW.plusMinutes(2));
        when(tasks.lockTaskByExecutionId("exec-1")).thenReturn(Optional.of(task));
        A2aPublication publication = mock(A2aPublication.class);
        when(publication.id()).thenReturn(1L);
        when(publication.agentId()).thenReturn("agent-1");
        when(publication.trustProfileId()).thenReturn(10L);
        when(publication.status()).thenReturn(A2aPublicationStatus.PUBLISHED);
        when(publications.findById(1L)).thenReturn(Optional.of(publication));
        A2aPublicationRevision revision = mock(A2aPublicationRevision.class);
        when(revision.agentConfigVersionId()).thenReturn(42L);
        when(publications.findRevision(1L, 2L)).thenReturn(Optional.of(revision));
        A2aPrincipal principal = mock(A2aPrincipal.class);
        when(principal.status()).thenReturn(A2aPrincipalStatus.ACTIVE);
        when(principal.trustProfileId()).thenReturn(10L);
        when(principal.tenantScope()).thenReturn("tenant-a");
        when(principal.principalKey()).thenReturn("partner-agent");
        when(principal.principalType()).thenReturn(A2aPrincipalType.REMOTE_AGENT);
        when(principal.scopes()).thenReturn(Set.of("a2a:message:send"));
        when(principals.findById(7L)).thenReturn(Optional.of(principal));
        A2aTrustProfile trust = mock(A2aTrustProfile.class);
        when(trust.trustLevel()).thenReturn(A2aTrustLevel.TRUSTED);
        when(trust.taskTimeoutMs()).thenReturn(60_000L);
        when(trusts.findActiveById(10L)).thenReturn(Optional.of(trust));
        MessageRecord message = message(canonical);
        when(tasks.findLatestMessage(
                A2aDirection.INBOUND, 7L, "tenant-a", 12L, "ROLE_USER"))
                .thenReturn(Optional.of(message));
        when(cipher.decrypt(any(), any())).thenReturn(canonical);

        var preparation = service.prepareDispatch("exec-1");

        assertThat(preparation.executable()).isTrue();
        assertThat(preparation.command().agentId()).isEqualTo("agent-1");
        assertThat(preparation.command().agentConfigVersionId()).isEqualTo(42L);
        assertThat(preparation.command().principalKey()).isEqualTo("partner-agent");
        assertThat(preparation.command().trustLevel()).isEqualTo("TRUSTED");
        assertThat(preparation.command().canonicalMessage()).isEqualTo(canonical);
        ArgumentCaptor<TaskRecord> saved = ArgumentCaptor.forClass(TaskRecord.class);
        verify(tasks).saveTask(saved.capture());
        assertThat(saved.getValue().state()).isEqualTo(A2aTaskState.TASK_STATE_WORKING);
        assertThat(saved.getValue().attemptCount()).isEqualTo(1);
    }

    @Test
    void cancelAcceptanceDoesNotForgeCanceledTerminalState() {
        TaskRecord task = task(A2aTaskState.TASK_STATE_WORKING, "REQUESTED", NOW.plusMinutes(2));
        when(tasks.lockTaskByExecutionId("exec-1")).thenReturn(Optional.of(task));

        service.applyCancelResult("exec-1", new CancelResult(true, true, false));

        ArgumentCaptor<TaskRecord> saved = ArgumentCaptor.forClass(TaskRecord.class);
        verify(tasks).saveTask(saved.capture());
        assertThat(saved.getValue().state()).isEqualTo(A2aTaskState.TASK_STATE_WORKING);
        assertThat(saved.getValue().cancelPhase()).isEqualTo("ACCEPTED");
    }

    @Test
    void completedExecutionKeepsAbsentRuntimeErrorAbsent() {
        TaskRecord task = task(A2aTaskState.TASK_STATE_WORKING, "NONE", NOW.plusMinutes(2));
        when(tasks.lockTaskByExecutionId("exec-1")).thenReturn(Optional.of(task));
        A2aPrincipal principal = mock(A2aPrincipal.class);
        when(principal.trustProfileId()).thenReturn(10L);
        when(principals.findById(7L)).thenReturn(Optional.of(principal));
        when(trusts.findById(10L)).thenReturn(Optional.of(mock(A2aTrustProfile.class)));
        when(publications.findRevision(1L, 2L))
                .thenReturn(Optional.of(mock(A2aPublicationRevision.class)));

        service.applyExecutionResult("exec-1", new ExecutionResult(
                A2aTaskState.TASK_STATE_COMPLETED,
                "run-1",
                "trace-1",
                null,
                "Task completed",
                null,
                null,
                null,
                null));

        ArgumentCaptor<TaskRecord> saved = ArgumentCaptor.forClass(TaskRecord.class);
        verify(tasks).saveTask(saved.capture());
        assertThat(saved.getValue().state()).isEqualTo(A2aTaskState.TASK_STATE_COMPLETED);
        assertThat(saved.getValue().runtimeRunId()).isEqualTo("run-1");
        assertThat(saved.getValue().traceId()).isEqualTo("trace-1");
        assertThat(saved.getValue().errorCode()).isNull();
        assertThat(saved.getValue().errorSummary()).isNull();
    }

    @Test
    void deadlineProducesFailedTaskAndRuntimeCleanupOutbox() {
        TaskRecord task = task(A2aTaskState.TASK_STATE_WORKING, "NONE", NOW.minusSeconds(1));
        when(tasks.lockTaskByExecutionId("exec-1")).thenReturn(Optional.of(task));

        var preparation = service.prepareDispatch("exec-1");

        assertThat(preparation.executable()).isFalse();
        ArgumentCaptor<TaskRecord> saved = ArgumentCaptor.forClass(TaskRecord.class);
        verify(tasks).saveTask(saved.capture());
        assertThat(saved.getValue().state()).isEqualTo(A2aTaskState.TASK_STATE_FAILED);
        verify(tasks).saveOutbox(any());
    }

    private TaskRecord task(A2aTaskState state, String cancelPhase, LocalDateTime deadline) {
        return new TaskRecord(
                12L, "task-1", A2aDirection.INBOUND, 7L, "tenant-a",
                11L, "ctx-1", 1L, 2L, null, null, null,
                "msg-1", "a".repeat(64), state, 0, 2,
                "exec-1", null, null, null, "Task accepted", null, null,
                cancelPhase, null, 0, NOW, state == A2aTaskState.TASK_STATE_WORKING ? NOW : null,
                null, NOW.plusDays(1), deadline, NOW, NOW);
    }

    private MessageRecord message(byte[] canonical) {
        return new MessageRecord(
                21L, "msg-1", A2aDirection.INBOUND, 7L, "tenant-a",
                11L, 12L, "ROLE_USER", "ciphertext", null, "key-1", "nonce-1",
                sha256(canonical), sha256(canonical), canonical.length, "INTERNAL", "Message received",
                "{}", NOW.plusDays(1), null, NOW);
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
