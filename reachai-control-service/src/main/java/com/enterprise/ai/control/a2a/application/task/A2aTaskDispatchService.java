package com.enterprise.ai.control.a2a.application.task;

import com.enterprise.ai.control.a2a.application.port.A2aContentCipher;
import com.enterprise.ai.control.a2a.application.port.A2aPrincipalRepository;
import com.enterprise.ai.control.a2a.application.port.A2aPublicationRepository;
import com.enterprise.ai.control.a2a.application.port.A2aRuntimeExecutionGateway.CancelCommand;
import com.enterprise.ai.control.a2a.application.port.A2aRuntimeExecutionGateway.CancelResult;
import com.enterprise.ai.control.a2a.application.port.A2aRuntimeExecutionGateway.DispatchCommand;
import com.enterprise.ai.control.a2a.application.port.A2aRuntimeExecutionGateway.ExecutionResult;
import com.enterprise.ai.control.a2a.application.port.A2aRuntimeExecutionGateway.OutputResource;
import com.enterprise.ai.control.a2a.application.port.A2aTaskRepository;
import com.enterprise.ai.control.a2a.application.port.A2aTaskRepository.ArtifactRecord;
import com.enterprise.ai.control.a2a.application.port.A2aTaskRepository.MessageRecord;
import com.enterprise.ai.control.a2a.application.port.A2aTaskRepository.OutboxRecord;
import com.enterprise.ai.control.a2a.application.port.A2aTaskRepository.TaskEventRecord;
import com.enterprise.ai.control.a2a.application.port.A2aTaskRepository.TaskRecord;
import com.enterprise.ai.control.a2a.application.port.A2aTrustProfileRepository;
import com.enterprise.ai.control.a2a.domain.A2aDirection;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.domain.A2aTaskLifecycle;
import com.enterprise.ai.control.a2a.domain.A2aTaskState;
import com.enterprise.ai.control.a2a.domain.identity.A2aPrincipal;
import com.enterprise.ai.control.a2a.domain.identity.A2aPrincipalStatus;
import com.enterprise.ai.control.a2a.domain.publication.A2aPublication;
import com.enterprise.ai.control.a2a.domain.publication.A2aPublicationRevision;
import com.enterprise.ai.control.a2a.domain.publication.A2aPublicationStatus;
import com.enterprise.ai.control.a2a.domain.trust.A2aTrustProfile;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Transactional Task projection around non-transactional Runtime calls. */
@Service
@RequiredArgsConstructor
public class A2aTaskDispatchService {

    private static final A2aDirection INBOUND = A2aDirection.INBOUND;

    private final A2aTaskRepository tasks;
    private final A2aPublicationRepository publications;
    private final A2aPrincipalRepository principals;
    private final A2aTrustProfileRepository trustProfiles;
    private final A2aContentCipher cipher;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Transactional
    public DispatchPreparation prepareDispatch(String executionId) {
        TaskRecord task = lock(executionId);
        if (task.state().terminal()) {
            return DispatchPreparation.skipped(task.taskId(), "TASK_ALREADY_TERMINAL");
        }
        if (due(task, now())) {
            expireLocked(task);
            return DispatchPreparation.skipped(task.taskId(), "TASK_DEADLINE_EXCEEDED");
        }
        if ("REQUESTED".equals(task.cancelPhase())
                && task.state() == A2aTaskState.TASK_STATE_SUBMITTED) {
            transitionTerminal(task, A2aTaskState.TASK_STATE_CANCELED,
                    "CANCELLED_BEFORE_DISPATCH", "Task cancelled before Runtime dispatch",
                    "SYSTEM", "CANCEL_CONFIRMED");
            return DispatchPreparation.skipped(task.taskId(), "CANCELLED_BEFORE_DISPATCH");
        }

        A2aPublication publication = publications.findById(required(task.publicationId(), "publicationId"))
                .orElse(null);
        if (publication == null || publication.status() != A2aPublicationStatus.PUBLISHED) {
            transitionTerminal(task, A2aTaskState.TASK_STATE_REJECTED,
                    "A2A_PUBLICATION_NOT_ACTIVE", "Publication is no longer active",
                    "POLICY", "POLICY_REJECTED");
            return DispatchPreparation.skipped(task.taskId(), "PUBLICATION_NOT_ACTIVE");
        }
        A2aPublicationRevision revision = publications.findRevision(
                        publication.id(), required(task.publicationRevisionId(), "publicationRevisionId"))
                .orElse(null);
        if (revision == null) {
            transitionTerminal(task, A2aTaskState.TASK_STATE_FAILED,
                    "A2A_PUBLICATION_REVISION_MISSING", "Frozen publication revision is unavailable",
                    "SYSTEM", "DISPATCH_FAILED");
            return DispatchPreparation.skipped(task.taskId(), "REVISION_MISSING");
        }
        A2aPrincipal principal = principals.findById(task.principalId()).orElse(null);
        if (principal == null || principal.status() != A2aPrincipalStatus.ACTIVE
                || principal.trustProfileId() != publication.trustProfileId()
                || !tenant(principal.tenantScope()).equals(tenant(task.tenantScope()))) {
            transitionTerminal(task, A2aTaskState.TASK_STATE_REJECTED,
                    "A2A_PRINCIPAL_NOT_ACTIVE", "Principal is no longer authorized",
                    "POLICY", "POLICY_REJECTED");
            return DispatchPreparation.skipped(task.taskId(), "PRINCIPAL_NOT_ACTIVE");
        }
        A2aTrustProfile trust = trustProfiles.findActiveById(principal.trustProfileId()).orElse(null);
        if (trust == null) {
            transitionTerminal(task, A2aTaskState.TASK_STATE_REJECTED,
                    "A2A_TRUST_PROFILE_NOT_ACTIVE", "Trust Profile is no longer active",
                    "POLICY", "POLICY_REJECTED");
            return DispatchPreparation.skipped(task.taskId(), "TRUST_NOT_ACTIVE");
        }
        MessageRecord message = tasks.findLatestMessage(
                        INBOUND, task.principalId(), task.tenantScope(), task.id(), "ROLE_USER")
                .orElse(null);
        if (message == null || message.contentDeletedAt() != null
                || message.payloadCiphertext() == null
                || expired(message.retentionExpiresAt())) {
            transitionTerminal(task, A2aTaskState.TASK_STATE_FAILED,
                    "A2A_MESSAGE_CONTENT_UNAVAILABLE", "Task input is no longer available",
                    "SYSTEM", "DISPATCH_FAILED");
            return DispatchPreparation.skipped(task.taskId(), "MESSAGE_UNAVAILABLE");
        }
        byte[] canonicalMessage = cipher.decrypt(new A2aContentCipher.EncryptedContent(
                        message.encryptionKeyId(), message.encryptionNonce(), message.payloadCiphertext()),
                A2aContentBinding.message(
                        INBOUND, task.principalId(), task.tenantScope(), message.messageId()));
        if (!sha256(canonicalMessage).equals(message.payloadSha256())) {
            transitionTerminal(task, A2aTaskState.TASK_STATE_FAILED,
                    "A2A_MESSAGE_INTEGRITY_FAILED", "Task input integrity verification failed",
                    "SYSTEM", "DISPATCH_FAILED");
            return DispatchPreparation.skipped(task.taskId(), "MESSAGE_INTEGRITY_FAILED");
        }

        LocalDateTime now = now();
        A2aTaskState next = task.state() == A2aTaskState.TASK_STATE_SUBMITTED
                || task.state().interrupted()
                ? A2aTaskState.TASK_STATE_WORKING : task.state();
        A2aTaskLifecycle.requireTransition(task.state(), next);
        long sequence = task.lastEventSequence() + 1;
        TaskRecord working = tasks.saveTask(copy(
                task, next, sequence, task.runtimeRunId(), task.traceId(),
                task.runtimeInteractionId(), "Runtime dispatch started", null, null,
                task.cancelPhase(), task.cancelRequestedAt(), task.attemptCount() + 1,
                task.startedAt() == null ? now : task.startedAt(), null));
        tasks.saveEvent(event(working, sequence, "EXECUTION_DISPATCH_STARTED",
                task.state(), next, "SYSTEM", null, "TASK", task.taskId(),
                "Runtime dispatch started", working.traceId(), now));

        DispatchCommand command = new DispatchCommand(
                working.executionId(), working.taskId(), working.contextId(), publication.agentId(),
                revision.agentConfigVersionId(), working.tenantScope(), principal.principalKey(),
                principal.principalType().name(), trust.trustLevel().name(),
                principal.scopes().stream().sorted().toList(), working.runtimeInteractionId(),
                trust.taskTimeoutMs(), canonicalMessage);
        return DispatchPreparation.ready(working.taskId(), command);
    }

    @Transactional
    public void applyExecutionResult(String executionId, ExecutionResult received) {
        TaskRecord task = lock(executionId);
        if (task.state().terminal()) {
            return;
        }
        if (due(task, now())) {
            expireLocked(task);
            return;
        }
        ExecutionResult result = normalizeResult(received);
        A2aPrincipal principal = principals.findById(task.principalId())
                .orElseThrow(() -> new A2aDomainException(
                        "A2A_PRINCIPAL_NOT_FOUND", "Task Principal is unavailable"));
        A2aTrustProfile trust = trustProfiles.findById(principal.trustProfileId())
                .orElseThrow(() -> new A2aDomainException(
                        "A2A_TRUST_PROFILE_NOT_FOUND", "Task Trust Profile is unavailable"));
        A2aPublicationRevision revision = publications.findRevision(
                        required(task.publicationId(), "publicationId"),
                        required(task.publicationRevisionId(), "publicationRevisionId"))
                .orElseThrow(() -> new A2aDomainException(
                        "A2A_PUBLICATION_REVISION_MISSING", "Frozen publication revision is unavailable"));
        validateOutput(result.agentMessage(), trust, revision);
        validateOutput(result.artifact(), trust, revision);

        MessageRecord input = tasks.findLatestMessage(
                        INBOUND, task.principalId(), task.tenantScope(), task.id(), "ROLE_USER")
                .orElse(null);
        String classification = input == null ? "INTERNAL" : input.contentClassification();
        LocalDateTime retention = task.retentionExpiresAt();
        LocalDateTime now = now();
        long sequence = task.lastEventSequence();

        if (result.agentMessage() != null) {
            OutputResource output = result.agentMessage();
            A2aContentCipher.EncryptedContent encrypted = cipher.encrypt(
                    output.canonicalJson(), A2aContentBinding.message(
                            INBOUND, task.principalId(), task.tenantScope(), output.resourceId()));
            tasks.saveMessage(new MessageRecord(
                    null, output.resourceId(), INBOUND, task.principalId(), task.tenantScope(),
                    task.contextRefId(), task.id(), "ROLE_AGENT", encrypted.ciphertext(), null,
                    encrypted.keyId(), encrypted.nonce(), output.sha256(),
                    output.sha256(), output.bytes(),
                    classification, safe(output.safeSummary()), "{}", retention, null, now));
            sequence++;
            tasks.saveEvent(event(task, sequence, "MESSAGE_ADDED", null, null,
                    "RUNTIME", null, "MESSAGE", output.resourceId(),
                    safe(output.safeSummary()), result.traceId(), now));
        }
        if (result.artifact() != null) {
            OutputResource output = result.artifact();
            A2aContentCipher.EncryptedContent encrypted = cipher.encrypt(
                    output.canonicalJson(), A2aContentBinding.artifact(
                            INBOUND, task.principalId(), task.tenantScope(),
                            task.taskId(), output.resourceId()));
            tasks.saveArtifact(new ArtifactRecord(
                    null, task.id(), output.resourceId(), "Agent result", null,
                    encrypted.ciphertext(), null, encrypted.keyId(), encrypted.nonce(),
                    output.sha256(), output.bytes(), mediaTypes(output.mediaTypes()),
                    classification, safe(output.safeSummary()), 0, true,
                    retention, null, now, now));
            sequence++;
            tasks.saveEvent(event(task, sequence, "ARTIFACT_UPDATED", null, null,
                    "RUNTIME", null, "ARTIFACT", output.resourceId(),
                    safe(output.safeSummary()), result.traceId(), now));
        }

        A2aTaskLifecycle.requireTransition(task.state(), result.state());
        sequence++;
        String cancelPhase = result.state() == A2aTaskState.TASK_STATE_CANCELED
                ? "ACCEPTED" : task.cancelPhase();
        String interactionId = result.state().interrupted() ? result.interactionId() : null;
        TaskRecord updated = tasks.saveTask(copy(
                task, result.state(), sequence, result.runtimeRunId(), result.traceId(),
                interactionId, safe(result.statusSummary()), safeOptionalCode(result.errorCode()),
                safe(result.errorSummary()), cancelPhase, task.cancelRequestedAt(),
                task.attemptCount(), task.startedAt() == null ? now : task.startedAt(),
                result.state().terminal() ? now : null));
        tasks.saveEvent(event(updated, sequence, "STATE_CHANGED", task.state(), result.state(),
                "RUNTIME", null, "TASK", task.taskId(), safe(result.statusSummary()),
                result.traceId(), now));
        tasks.touchContext(task.contextRefId(), now);
    }

    @Transactional
    public CancelPreparation prepareCancel(String executionId) {
        return prepareCancel(executionId, false);
    }

    @Transactional
    public CancelPreparation prepareCancel(String executionId, boolean timeoutCleanup) {
        TaskRecord task = lock(executionId);
        if (task.state().terminal() && !timeoutCleanup) {
            return CancelPreparation.skipped(task.taskId(), "TASK_ALREADY_TERMINAL");
        }
        A2aPrincipal principal = principals.findById(task.principalId()).orElse(null);
        if (principal == null) {
            markUnknownLocked(task, true, "A2A_CANCEL_PRINCIPAL_MISSING",
                    "Cancellation Principal is unavailable");
            return CancelPreparation.skipped(task.taskId(), "PRINCIPAL_MISSING");
        }
        return CancelPreparation.ready(task.taskId(), new CancelCommand(
                task.executionId(), task.tenantScope(), principal.principalKey()));
    }

    @Transactional
    public void applyCancelResult(String executionId, CancelResult result) {
        TaskRecord task = lock(executionId);
        if (task.state().terminal()) {
            return;
        }
        boolean accepted = result != null && result.accepted();
        LocalDateTime now = now();
        long sequence = task.lastEventSequence() + 1;
        TaskRecord updated = tasks.saveTask(copy(
                task, task.state(), sequence, task.runtimeRunId(), task.traceId(),
                task.runtimeInteractionId(),
                accepted ? "Runtime accepted cancellation" : "Runtime cancellation outcome is unknown",
                task.errorCode(), task.errorSummary(), accepted ? "ACCEPTED" : "UNKNOWN",
                task.cancelRequestedAt(), task.attemptCount(), task.startedAt(), task.completedAt()));
        tasks.saveEvent(event(updated, sequence,
                accepted ? "CANCEL_ACCEPTED" : "CANCEL_OUTCOME_UNKNOWN",
                task.state(), task.state(), "RUNTIME", null, "TASK", task.taskId(),
                updated.statusMessageSummary(), task.traceId(), now));
    }

    @Transactional
    public void requestCancellation(String executionId, String actorType, String actorId) {
        TaskRecord task = lock(executionId);
        if (task.direction() != A2aDirection.INBOUND) {
            throw new A2aDomainException("A2A_TASK_DIRECTION_INVALID",
                    "Runtime cancellation is only valid for inbound A2A Tasks");
        }
        if (task.state().terminal()) {
            throw new A2aDomainException("A2A_TASK_NOT_CANCELABLE",
                    "the task is already in a terminal state");
        }
        if ("REQUESTED".equals(task.cancelPhase()) || "ACCEPTED".equals(task.cancelPhase())) {
            return;
        }
        LocalDateTime now = now();
        long sequence = task.lastEventSequence() + 1;
        TaskRecord updated = tasks.saveTask(copy(
                task, task.state(), sequence, task.runtimeRunId(), task.traceId(),
                task.runtimeInteractionId(), "Cancellation requested", task.errorCode(),
                task.errorSummary(), "REQUESTED", now, task.attemptCount(),
                task.startedAt(), task.completedAt()));
        tasks.saveEvent(event(
                updated, sequence, "CANCEL_REQUESTED", task.state(), task.state(),
                safeActorType(actorType), safeActor(actorId), "TASK", task.taskId(),
                "Cancellation requested", task.traceId(), now));
        tasks.saveOutbox(outbox(updated, "EXECUTION_CANCEL_REQUESTED", now));
    }

    @Transactional
    public void markOutcomeUnknown(
            String executionId, boolean cancellation, String code, String summary) {
        TaskRecord task = lock(executionId);
        if (!task.state().terminal()) {
            markUnknownLocked(task, cancellation, code, summary);
        }
    }

    @Transactional
    public void failDispatch(String executionId, String code, String summary) {
        TaskRecord task = lock(executionId);
        if (!task.state().terminal()) {
            transitionTerminal(task, A2aTaskState.TASK_STATE_FAILED,
                    safeCode(code), safe(summary), "SYSTEM", "DISPATCH_FAILED");
        }
    }

    @Transactional
    public boolean expireTask(String executionId) {
        TaskRecord task = lock(executionId);
        if (task.direction() != A2aDirection.INBOUND) {
            throw new A2aDomainException("A2A_TASK_DIRECTION_INVALID",
                    "Runtime deadline handling is only valid for inbound A2A Tasks");
        }
        if (task.state().terminal() || !due(task, now())) {
            return false;
        }
        expireLocked(task);
        return true;
    }

    private void expireLocked(TaskRecord task) {
        LocalDateTime now = now();
        TaskRecord failed = transitionTerminal(
                task, A2aTaskState.TASK_STATE_FAILED,
                "A2A_TASK_DEADLINE_EXCEEDED", "Task execution deadline was exceeded",
                "SYSTEM", "TASK_TIMED_OUT");
        tasks.saveOutbox(outbox(failed, "EXECUTION_TIMEOUT_CANCEL_REQUESTED", now));
    }

    private void markUnknownLocked(
            TaskRecord task, boolean cancellation, String code, String summary) {
        LocalDateTime now = now();
        long sequence = task.lastEventSequence() + 1;
        TaskRecord updated = tasks.saveTask(copy(
                task, task.state(), sequence, task.runtimeRunId(), task.traceId(),
                task.runtimeInteractionId(), safe(summary), safeCode(code), safe(summary),
                cancellation ? "UNKNOWN" : task.cancelPhase(), task.cancelRequestedAt(),
                task.attemptCount(), task.startedAt(), task.completedAt()));
        tasks.saveEvent(event(updated, sequence,
                cancellation ? "CANCEL_OUTCOME_UNKNOWN" : "DISPATCH_OUTCOME_UNKNOWN",
                task.state(), task.state(), "SYSTEM", null, "TASK", task.taskId(),
                safe(summary), task.traceId(), now));
    }

    private TaskRecord transitionTerminal(
            TaskRecord task,
            A2aTaskState state,
            String code,
            String summary,
            String actorType,
            String eventType) {
        A2aTaskLifecycle.requireTransition(task.state(), state);
        LocalDateTime now = now();
        long sequence = task.lastEventSequence() + 1;
        TaskRecord updated = tasks.saveTask(copy(
                task, state, sequence, task.runtimeRunId(), task.traceId(), null,
                safe(summary), safeCode(code), safe(summary),
                state == A2aTaskState.TASK_STATE_CANCELED ? "ACCEPTED" : task.cancelPhase(),
                task.cancelRequestedAt(), task.attemptCount(), task.startedAt(), now));
        tasks.saveEvent(event(updated, sequence, eventType, task.state(), state,
                actorType, null, "TASK", task.taskId(), safe(summary), task.traceId(), now));
        return updated;
    }

    private ExecutionResult normalizeResult(ExecutionResult result) {
        if (result == null || result.state() == null || !result.state().persistable()
                || result.state() == A2aTaskState.TASK_STATE_SUBMITTED
                || result.state() == A2aTaskState.TASK_STATE_WORKING) {
            return new ExecutionResult(
                    A2aTaskState.TASK_STATE_FAILED, null, null, null,
                    "Runtime returned an invalid terminal/interrupted result",
                    "A2A_RUNTIME_RESULT_INVALID", "Runtime result is invalid", null, null);
        }
        if (result.state().interrupted()
                && (result.interactionId() == null || result.interactionId().isBlank())) {
            return new ExecutionResult(
                    A2aTaskState.TASK_STATE_FAILED, result.runtimeRunId(), result.traceId(), null,
                    "Runtime did not return a continuation identifier",
                    "A2A_RUNTIME_CONTINUATION_MISSING",
                    "Runtime interrupted without a continuation identifier",
                    result.agentMessage(), null);
        }
        return result;
    }

    private void validateOutput(
            OutputResource output, A2aTrustProfile trust, A2aPublicationRevision revision) {
        if (output == null) {
            return;
        }
        if (output.canonicalJson() == null || output.bytes() != output.canonicalJson().length
                || output.bytes() < 0 || output.bytes() > trust.maxArtifactBytes()
                || !sha256(output.canonicalJson()).equals(output.sha256())) {
            throw new A2aDomainException("A2A_RUNTIME_OUTPUT_INVALID",
                    "Runtime output failed size or integrity validation");
        }
        Set<String> allowed = revision.defaultOutputModes().stream()
                .map(value -> value.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
        if (output.mediaTypes().isEmpty() || output.mediaTypes().stream()
                .map(value -> value == null ? "" : value.toLowerCase(Locale.ROOT))
                .anyMatch(value -> !allowed.contains(value))) {
            throw new A2aDomainException("A2A_RUNTIME_OUTPUT_MODE_REJECTED",
                    "Runtime output mode is not declared by the frozen publication revision");
        }
    }

    private TaskRecord lock(String executionId) {
        if (executionId == null || executionId.isBlank() || executionId.length() > 128) {
            throw new A2aDomainException("A2A_EXECUTION_ID_INVALID", "executionId is invalid");
        }
        return tasks.lockTaskByExecutionId(executionId.trim())
                .orElseThrow(() -> new A2aDomainException(
                        "A2A_TASK_NOT_FOUND", "A2A Task execution was not found"));
    }

    private TaskRecord copy(
            TaskRecord task,
            A2aTaskState state,
            long lastSequence,
            String runtimeRunId,
            String traceId,
            String interactionId,
            String statusSummary,
            String errorCode,
            String errorSummary,
            String cancelPhase,
            LocalDateTime cancelRequestedAt,
            int attemptCount,
            LocalDateTime startedAt,
            LocalDateTime completedAt) {
        return new TaskRecord(
                task.id(), task.taskId(), task.direction(), task.principalId(), task.tenantScope(),
                task.contextRefId(), task.contextId(), task.publicationId(), task.publicationRevisionId(),
                task.remoteAgentId(), task.remoteRevisionId(), task.remoteTaskId(),
                task.originMessageId(), task.originPayloadSha256(), state, task.stateVersion(),
                lastSequence, task.executionId(), runtimeRunId, traceId, interactionId,
                statusSummary, errorCode, errorSummary, cancelPhase, cancelRequestedAt,
                attemptCount, task.submittedAt(), startedAt, completedAt,
                task.retentionExpiresAt(), task.deadlineAt(), task.createdAt(), task.updatedAt());
    }

    private TaskEventRecord event(
            TaskRecord task,
            long sequence,
            String eventType,
            A2aTaskState from,
            A2aTaskState to,
            String actorType,
            String actorId,
            String resourceType,
            String resourceId,
            String summary,
            String traceId,
            LocalDateTime now) {
        return new TaskEventRecord(
                null, task.id(), sequence, opaque("evt"), eventType, from, to,
                actorType, actorId, resourceType, resourceId, safe(summary), null,
                null, traceId, now);
    }

    private OutboxRecord outbox(TaskRecord task, String eventType, LocalDateTime now) {
        String eventId = opaque("outbox");
        String safeRef = "{\"executionId\":\"" + task.executionId()
                + "\",\"taskId\":\"" + task.taskId() + "\"}";
        return new OutboxRecord(
                null, eventId, "A2A_TASK", task.taskId(), eventType, safeRef,
                "PENDING", 0, now, null, null, null, null, now, null, null);
    }

    private String mediaTypes(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException exception) {
            throw new A2aDomainException("A2A_RUNTIME_OUTPUT_INVALID",
                    "Runtime output media types could not be persisted");
        }
    }

    private boolean expired(LocalDateTime value) {
        return value != null && value.isBefore(now());
    }

    private boolean due(TaskRecord task, LocalDateTime now) {
        return task.deadlineAt() != null && !task.deadlineAt().isAfter(now);
    }

    private long required(Long value, String field) {
        if (value == null || value <= 0) {
            throw new A2aDomainException("A2A_TASK_REFERENCE_INVALID", field + " is invalid");
        }
        return value;
    }

    private String tenant(String value) {
        return value == null ? "" : value.trim();
    }

    private String safe(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.length() <= 1000 ? normalized : normalized.substring(0, 1000);
    }

    private String safeCode(String value) {
        String normalized = value == null || value.isBlank()
                ? "A2A_RUNTIME_EXECUTION_FAILED" : value.trim();
        return normalized.length() <= 96 ? normalized : normalized.substring(0, 96);
    }

    private String safeOptionalCode(String value) {
        return value == null || value.isBlank() ? null : safeCode(value);
    }

    private String safeActorType(String value) {
        String normalized = value == null || value.isBlank() ? "SYSTEM" : value.trim().toUpperCase(Locale.ROOT);
        return normalized.length() <= 32 ? normalized : normalized.substring(0, 32);
    }

    private String safeActor(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.length() <= 128 ? normalized : normalized.substring(0, 128);
    }

    private String opaque(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    public record DispatchPreparation(
            String taskId, DispatchCommand command, String skipReason) {
        public static DispatchPreparation ready(String taskId, DispatchCommand command) {
            return new DispatchPreparation(taskId, command, null);
        }

        public static DispatchPreparation skipped(String taskId, String reason) {
            return new DispatchPreparation(taskId, null, reason);
        }

        public boolean executable() {
            return command != null;
        }
    }

    public record CancelPreparation(
            String taskId, CancelCommand command, String skipReason) {
        public static CancelPreparation ready(String taskId, CancelCommand command) {
            return new CancelPreparation(taskId, command, null);
        }

        public static CancelPreparation skipped(String taskId, String reason) {
            return new CancelPreparation(taskId, null, reason);
        }

        public boolean executable() {
            return command != null;
        }
    }
}
