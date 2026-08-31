package com.enterprise.ai.control.a2a.application.task;

import com.enterprise.ai.control.a2a.application.identity.A2aInboundCallContext;
import com.enterprise.ai.control.a2a.application.port.A2aContentCipher;
import com.enterprise.ai.control.a2a.application.port.A2aPrincipalRepository;
import com.enterprise.ai.control.a2a.application.port.A2aTaskRepository;
import com.enterprise.ai.control.a2a.application.port.A2aTaskRepository.ArtifactRecord;
import com.enterprise.ai.control.a2a.application.port.A2aTaskRepository.ContextRecord;
import com.enterprise.ai.control.a2a.application.port.A2aTaskRepository.MessageRecord;
import com.enterprise.ai.control.a2a.application.port.A2aTaskRepository.OutboxRecord;
import com.enterprise.ai.control.a2a.application.port.A2aTaskRepository.TaskEventRecord;
import com.enterprise.ai.control.a2a.application.port.A2aTaskRepository.TaskRecord;
import com.enterprise.ai.control.a2a.domain.A2aDirection;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.domain.A2aTaskLifecycle;
import com.enterprise.ai.control.a2a.domain.A2aTaskState;
import com.enterprise.ai.control.a2a.infrastructure.A2aHubProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static com.enterprise.ai.control.a2a.application.task.A2aTaskContracts.ListQuery;
import static com.enterprise.ai.control.a2a.application.task.A2aTaskContracts.ListResult;
import static com.enterprise.ai.control.a2a.application.task.A2aTaskContracts.SendCommand;
import static com.enterprise.ai.control.a2a.application.task.A2aTaskContracts.TaskResource;
import static com.enterprise.ai.control.a2a.domain.A2aProtocolOperation.TASKS_CANCEL;
import static com.enterprise.ai.control.a2a.domain.A2aProtocolOperation.TASKS_GET;
import static com.enterprise.ai.control.a2a.domain.A2aProtocolOperation.TASKS_LIST;

@Service
@RequiredArgsConstructor
public class A2aTaskApplicationService {

    private static final A2aDirection INBOUND = A2aDirection.INBOUND;

    private final A2aTaskRepository repository;
    private final A2aPrincipalRepository principalRepository;
    private final A2aContentCipher contentCipher;
    private final A2aTaskPolicyAuthorizer authorizer;
    private final A2aHubProperties properties;
    private final Clock clock;

    @Transactional
    public TaskResource send(A2aInboundCallContext call, SendCommand command) {
        authorizer.requireSend(call, command);
        requireIdentifier(command.messageId(), "messageId");
        requireOptionalIdentifier(command.contextId(), "contextId");
        requireOptionalIdentifier(command.taskId(), "taskId");
        lockPrincipal(call);

        MessageRecord duplicate = repository.findMessage(
                INBOUND, call.principal().principalId(), tenant(call), command.messageId()).orElse(null);
        if (duplicate != null) {
            if (!duplicate.idempotencySha256().equals(command.payloadSha256())) {
                throw new A2aDomainException("A2A_MESSAGE_IDEMPOTENCY_CONFLICT",
                        "messageId was already used with different content");
            }
            if (duplicate.taskRefId() == null) {
                throw new A2aDomainException("A2A_MESSAGE_RESOURCE_INVALID",
                        "the idempotent message is not associated with a task");
            }
            TaskRecord existing = repository.findTaskByRefId(
                            INBOUND, call.principal().principalId(), tenant(call), duplicate.taskRefId())
                    .orElseThrow(() -> new A2aDomainException("A2A_TASK_NOT_FOUND",
                            "the task associated with this message is no longer accessible"));
            return resource(call, existing, historyLimit(command.historyLength()), true);
        }

        if (command.taskId() != null && !command.taskId().isBlank()) {
            return continueTask(call, command);
        }
        if (repository.countNonTerminalTasks(
                call.principal().principalId(), call.publication().publicationId())
                >= call.trustProfile().maxConcurrentTasks()) {
            throw new A2aDomainException("A2A_CONCURRENT_TASK_LIMIT_EXCEEDED",
                    "the Trust Profile concurrent task limit has been reached");
        }
        return createTask(call, command);
    }

    @Transactional(readOnly = true)
    public TaskResource get(
            A2aInboundCallContext call, String taskId, String requestedTenant, Integer historyLength) {
        authorizer.requireOperation(call, TASKS_GET, requestedTenant);
        requireIdentifier(taskId, "taskId");
        TaskRecord task = requireOwnedTask(call, taskId);
        return resource(call, task, historyLimit(historyLength), true);
    }

    @Transactional(readOnly = true)
    public TaskResource resultForSend(
            A2aInboundCallContext call, String taskId, Integer historyLength) {
        requireIdentifier(taskId, "taskId");
        return resource(call, requireOwnedTask(call, taskId), historyLimit(historyLength), true);
    }

    @Transactional(readOnly = true)
    public ListResult list(A2aInboundCallContext call, ListQuery query) {
        if (query == null) {
            throw new A2aDomainException("A2A_LIST_REQUEST_REQUIRED", "list request is required");
        }
        authorizer.requireOperation(call, TASKS_LIST, query.tenant());
        requireOptionalIdentifier(query.contextId(), "contextId");
        int pageSize = Math.max(1, Math.min(query.pageSize(), properties.getMaxPageSize()));
        int historyLength = Math.max(0, Math.min(query.historyLength(), properties.getMaxHistoryMessages()));
        int offset = Math.max(0, query.offset());
        A2aTaskRepository.TaskPage page = repository.findTasks(
                INBOUND, call.principal().principalId(), tenant(call),
                call.publication().publicationId(), query.contextId(), query.state(),
                query.statusTimestampAfter(), pageSize, offset);
        List<TaskResource> tasks = page.tasks().stream()
                .map(task -> resource(call, task, historyLength, query.includeArtifacts()))
                .toList();
        return new ListResult(tasks, page.total(), pageSize, offset);
    }

    @Transactional
    public TaskResource cancel(A2aInboundCallContext call, String taskId, String requestedTenant) {
        authorizer.requireOperation(call, TASKS_CANCEL, requestedTenant);
        requireIdentifier(taskId, "taskId");
        lockPrincipal(call);
        TaskRecord current = requireOwnedTaskForUpdate(call, taskId);
        if (current.state().terminal()) {
            throw new A2aDomainException("A2A_TASK_NOT_CANCELABLE",
                    "the task is already in a terminal state");
        }
        if ("REQUESTED".equals(current.cancelPhase()) || "ACCEPTED".equals(current.cancelPhase())) {
            return resource(call, current, 0, true);
        }
        LocalDateTime now = now();
        long sequence = current.lastEventSequence() + 1;
        TaskRecord updated = repository.saveTask(copyTask(
                current, current.state(), sequence, "REQUESTED", now,
                current.statusMessageSummary(), current.errorCode(), current.errorSummary(),
                current.startedAt(), current.completedAt(), current.deadlineAt()));
        repository.saveEvent(new TaskEventRecord(
                null, updated.id(), sequence, opaque("evt"), "CANCEL_REQUESTED",
                updated.state(), updated.state(), "PRINCIPAL", call.principal().principalKey(),
                "TASK", updated.taskId(), "Cancellation requested", null, null,
                updated.traceId(), now));
        repository.saveOutbox(outbox(updated, "EXECUTION_CANCEL_REQUESTED", now));
        return resource(call, updated, 0, true);
    }

    private TaskResource createTask(A2aInboundCallContext call, SendCommand command) {
        LocalDateTime now = now();
        LocalDateTime retention = retentionExpiry(call, now);
        ContextRecord context = resolveNewTaskContext(call, command.contextId(), now, retention);
        String taskId = opaque("task");
        String executionId = opaque("exec");
        A2aContentCipher.EncryptedContent encrypted = contentCipher.encrypt(
                command.canonicalMessage(), A2aContentBinding.message(
                        INBOUND, call.principal().principalId(), tenant(call), command.messageId()));
        TaskRecord created = repository.saveTask(new TaskRecord(
                null, taskId, INBOUND, call.principal().principalId(), tenant(call),
                context.id(), context.contextId(), call.publication().publicationId(),
                call.publication().revisionId(), null, null, null,
                command.messageId(), command.payloadSha256(), A2aTaskState.TASK_STATE_SUBMITTED,
                0, 2, executionId, null, null, null, command.safeSummary(), null, null,
                "NONE", null, 0, now, null, null, retention,
                now.plus(Duration.ofMillis(call.trustProfile().taskTimeoutMs())), null, null));
        repository.saveMessage(message(call, command, context, created, encrypted, retention, now));
        repository.saveEvent(new TaskEventRecord(
                null, created.id(), 1, opaque("evt"), "TASK_CREATED", null,
                A2aTaskState.TASK_STATE_SUBMITTED, "PRINCIPAL", call.principal().principalKey(),
                "TASK", created.taskId(), "Task accepted", null, null, null, now));
        repository.saveEvent(new TaskEventRecord(
                null, created.id(), 2, opaque("evt"), "MESSAGE_ADDED", null, null,
                "PRINCIPAL", call.principal().principalKey(), "MESSAGE", command.messageId(),
                command.safeSummary(), null, null, null, now));
        repository.saveOutbox(outbox(created, "EXECUTION_DISPATCH_REQUESTED", now));
        repository.touchContext(context.id(), now);
        return resource(call, created, historyLimit(command.historyLength()), true);
    }

    private TaskResource continueTask(A2aInboundCallContext call, SendCommand command) {
        TaskRecord current = requireOwnedTask(call, command.taskId());
        if (current.publicationId() == null
                || current.publicationId().longValue() != call.publication().publicationId()) {
            throw new A2aDomainException("A2A_TASK_NOT_FOUND", "task was not found");
        }
        if (command.contextId() != null && !command.contextId().isBlank()
                && !current.contextId().equals(command.contextId().trim())) {
            throw new A2aDomainException("A2A_CONTEXT_TASK_MISMATCH",
                    "message contextId does not match the task context");
        }
        if (current.state().terminal()) {
            throw new A2aDomainException("A2A_UNSUPPORTED_OPERATION",
                    "messages cannot be sent to a terminal task");
        }
        if (current.state() == A2aTaskState.TASK_STATE_AUTH_REQUIRED) {
            throw new A2aDomainException("A2A_UNSUPPORTED_OPERATION",
                    "AUTH_REQUIRED must be resolved through an attested authorization channel");
        }
        ContextRecord context = repository.findContext(
                        INBOUND, call.principal().principalId(), tenant(call), current.contextId())
                .filter(value -> value.id() == current.contextRefId())
                .orElseThrow(() -> new A2aDomainException("A2A_CONTEXT_NOT_FOUND",
                        "the task context is no longer active"));
        LocalDateTime now = now();
        LocalDateTime retention = retentionExpiry(call, now);
        A2aContentCipher.EncryptedContent encrypted = contentCipher.encrypt(
                command.canonicalMessage(), A2aContentBinding.message(
                        INBOUND, call.principal().principalId(), tenant(call), command.messageId()));
        A2aTaskState nextState = current.state() == A2aTaskState.TASK_STATE_INPUT_REQUIRED
                ? A2aTaskState.TASK_STATE_WORKING : current.state();
        if (nextState != current.state()) {
            A2aTaskLifecycle.requireTransition(current.state(), nextState);
        }
        long messageSequence = current.lastEventSequence() + 1;
        long lastSequence = nextState == current.state() ? messageSequence : messageSequence + 1;
        TaskRecord updated = repository.saveTask(copyTask(
                current, nextState, lastSequence, current.cancelPhase(), current.cancelRequestedAt(),
                command.safeSummary(), null, null,
                current.startedAt() == null && nextState == A2aTaskState.TASK_STATE_WORKING
                        ? now : current.startedAt(), current.completedAt(),
                now.plus(Duration.ofMillis(call.trustProfile().taskTimeoutMs()))));
        repository.saveMessage(message(call, command, context, updated, encrypted, retention, now));
        repository.saveEvent(new TaskEventRecord(
                null, updated.id(), messageSequence, opaque("evt"), "MESSAGE_ADDED", null, null,
                "PRINCIPAL", call.principal().principalKey(), "MESSAGE", command.messageId(),
                command.safeSummary(), null, null, updated.traceId(), now));
        if (nextState != current.state()) {
            repository.saveEvent(new TaskEventRecord(
                    null, updated.id(), lastSequence, opaque("evt"), "STATE_CHANGED",
                    current.state(), nextState, "PRINCIPAL", call.principal().principalKey(),
                    "TASK", updated.taskId(), "Task resumed with additional input", null,
                    null, updated.traceId(), now));
        }
        repository.saveOutbox(outbox(updated, "EXECUTION_RESUME_REQUESTED", now));
        repository.touchContext(context.id(), now);
        return resource(call, updated, historyLimit(command.historyLength()), true);
    }

    private ContextRecord resolveNewTaskContext(
            A2aInboundCallContext call, String requestedContextId,
            LocalDateTime now, LocalDateTime retention) {
        if (requestedContextId != null && !requestedContextId.isBlank()) {
            ContextRecord existing = repository.findContext(
                            INBOUND, call.principal().principalId(), tenant(call), requestedContextId.trim())
                    .orElseThrow(() -> new A2aDomainException("A2A_CONTEXT_NOT_FOUND",
                            "contextId is not accessible; omit it to create a new context"));
            if (!"ACTIVE".equals(existing.status())
                    || existing.publicationId() == null
                    || existing.publicationId().longValue() != call.publication().publicationId()) {
                throw new A2aDomainException("A2A_CONTEXT_NOT_FOUND", "contextId is not accessible");
            }
            return existing;
        }
        return repository.saveContext(new ContextRecord(
                null, opaque("ctx"), INBOUND, call.principal().principalId(), tenant(call),
                call.publication().publicationId(), null, null, null, null, "ACTIVE", now,
                retention, null, null));
    }

    private MessageRecord message(
            A2aInboundCallContext call, SendCommand command, ContextRecord context,
            TaskRecord task, A2aContentCipher.EncryptedContent encrypted,
            LocalDateTime retention, LocalDateTime now) {
        return new MessageRecord(
                null, command.messageId(), INBOUND, call.principal().principalId(), tenant(call),
                context.id(), task.id(), "ROLE_USER", encrypted.ciphertext(), null,
                encrypted.keyId(), encrypted.nonce(), command.payloadSha256(),
                command.payloadSha256(), command.payloadBytes(),
                classification(command.contentClassification()), command.safeSummary(),
                command.safeMetadataJson(), retention, null, now);
    }

    private TaskResource resource(
            A2aInboundCallContext call, TaskRecord task, int historyLength, boolean includeArtifacts) {
        List<String> history = repository.findMessages(
                        INBOUND, call.principal().principalId(), tenant(call), task.id(), historyLength)
                .stream().map(message -> decryptMessage(call, message)).filter(value -> value != null).toList();
        List<String> artifacts = null;
        if (includeArtifacts) {
            artifacts = repository.findArtifacts(
                            INBOUND, call.principal().principalId(), tenant(call), task.id())
                    .stream().map(artifact -> decryptArtifact(call, task, artifact))
                    .filter(value -> value != null).toList();
        }
        LocalDateTime timestamp = task.updatedAt() == null ? task.submittedAt() : task.updatedAt();
        return new TaskResource(task.taskId(), task.contextId(), task.state(), timestamp,
                task.statusMessageSummary(), task.runtimeRunId(), task.traceId(),
                task.cancelPhase(), history, artifacts);
    }

    private String decryptMessage(A2aInboundCallContext call, MessageRecord message) {
        if (!retained(message.retentionExpiresAt(), message.contentDeletedAt())
                || message.payloadCiphertext() == null) {
            return null;
        }
        byte[] plaintext = contentCipher.decrypt(new A2aContentCipher.EncryptedContent(
                message.encryptionKeyId(), message.encryptionNonce(), message.payloadCiphertext()),
                A2aContentBinding.message(
                        INBOUND, call.principal().principalId(), tenant(call), message.messageId()));
        return new String(plaintext, StandardCharsets.UTF_8);
    }

    private String decryptArtifact(
            A2aInboundCallContext call, TaskRecord task, ArtifactRecord artifact) {
        if (!retained(artifact.retentionExpiresAt(), artifact.contentDeletedAt())
                || artifact.payloadCiphertext() == null) {
            return null;
        }
        String aad = A2aContentBinding.artifact(
                INBOUND, call.principal().principalId(), tenant(call),
                task.taskId(), artifact.artifactId());
        byte[] plaintext = contentCipher.decrypt(new A2aContentCipher.EncryptedContent(
                artifact.encryptionKeyId(), artifact.encryptionNonce(), artifact.payloadCiphertext()), aad);
        return new String(plaintext, StandardCharsets.UTF_8);
    }

    private boolean retained(LocalDateTime expiresAt, LocalDateTime deletedAt) {
        return deletedAt == null && (expiresAt == null || !expiresAt.isBefore(now()));
    }

    private TaskRecord requireOwnedTask(A2aInboundCallContext call, String taskId) {
        return repository.findTask(INBOUND, call.principal().principalId(), tenant(call), taskId.trim())
                .filter(task -> task.publicationId() != null
                        && task.publicationId().longValue() == call.publication().publicationId())
                .orElseThrow(() -> new A2aDomainException("A2A_TASK_NOT_FOUND",
                        "the specified task does not exist or is not accessible"));
    }

    private TaskRecord requireOwnedTaskForUpdate(A2aInboundCallContext call, String taskId) {
        return repository.lockTask(INBOUND, call.principal().principalId(), tenant(call), taskId.trim())
                .filter(task -> task.publicationId() != null
                        && task.publicationId().longValue() == call.publication().publicationId())
                .orElseThrow(() -> new A2aDomainException("A2A_TASK_NOT_FOUND",
                        "the specified task does not exist or is not accessible"));
    }

    private void lockPrincipal(A2aInboundCallContext call) {
        var locked = principalRepository.lockActiveById(call.principal().principalId())
                .orElseThrow(() -> new A2aDomainException("A2A_AUTHENTICATION_FAILED",
                        "A2A authentication failed"));
        if (!locked.principalKey().equals(call.principal().principalKey())
                || locked.trustProfileId() != call.trustProfile().id()
                || !locked.tenantScope().equals(tenant(call))) {
            throw new A2aDomainException("A2A_AUTHENTICATION_FAILED", "A2A authentication failed");
        }
    }

    private TaskRecord copyTask(
            TaskRecord task, A2aTaskState state, long lastSequence,
            String cancelPhase, LocalDateTime cancelRequestedAt,
            String statusSummary, String errorCode, String errorSummary,
            LocalDateTime startedAt, LocalDateTime completedAt,
            LocalDateTime deadlineAt) {
        return new TaskRecord(
                task.id(), task.taskId(), task.direction(), task.principalId(), task.tenantScope(),
                task.contextRefId(), task.contextId(), task.publicationId(), task.publicationRevisionId(),
                task.remoteAgentId(), task.remoteRevisionId(), task.remoteTaskId(),
                task.originMessageId(), task.originPayloadSha256(), state, task.stateVersion(),
                lastSequence, task.executionId(), task.runtimeRunId(), task.traceId(),
                task.runtimeInteractionId(), statusSummary,
                errorCode, errorSummary, cancelPhase, cancelRequestedAt, task.attemptCount(),
                task.submittedAt(), startedAt, completedAt, task.retentionExpiresAt(),
                deadlineAt, task.createdAt(), task.updatedAt());
    }

    private OutboxRecord outbox(TaskRecord task, String eventType, LocalDateTime now) {
        String eventId = opaque("outbox");
        String safeRef = "{\"executionId\":\"" + task.executionId()
                + "\",\"taskId\":\"" + task.taskId() + "\"}";
        return new OutboxRecord(null, eventId, "A2A_TASK", task.taskId(), eventType,
                safeRef, "PENDING", 0, now, null, null, null, null, now, null, null);
    }

    private LocalDateTime retentionExpiry(A2aInboundCallContext call, LocalDateTime now) {
        int days = call.trustProfile().dataPolicy().payloadRetentionDays();
        if (days > 0) {
            return now.plusDays(days);
        }
        // Zero retention still keeps encrypted content for the bounded execution window;
        // a terminal cleanup worker removes it immediately after execution.
        return now.plus(Duration.ofMillis(call.trustProfile().taskTimeoutMs()));
    }

    private int historyLimit(Integer requested) {
        if (requested != null && requested < 0) {
            throw new A2aDomainException("A2A_HISTORY_LENGTH_INVALID",
                    "historyLength must not be negative");
        }
        int value = requested == null ? properties.getMaxHistoryMessages() : requested;
        return Math.max(0, Math.min(value, properties.getMaxHistoryMessages()));
    }

    private void requireIdentifier(String value, String field) {
        if (value == null || value.isBlank() || value.trim().length() > 128) {
            throw new A2aDomainException("A2A_IDENTIFIER_INVALID",
                    field + " is required and must not exceed 128 characters");
        }
    }

    private void requireOptionalIdentifier(String value, String field) {
        if (value != null && (!value.isBlank() && value.trim().length() > 128)) {
            throw new A2aDomainException("A2A_IDENTIFIER_INVALID",
                    field + " must not exceed 128 characters");
        }
    }

    private String opaque(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    private String tenant(A2aInboundCallContext call) {
        return call.principal().tenantScope() == null ? "" : call.principal().tenantScope().trim();
    }

    private String classification(String value) {
        return value == null || value.isBlank() ? "INTERNAL" : value.trim().toUpperCase(Locale.ROOT);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
