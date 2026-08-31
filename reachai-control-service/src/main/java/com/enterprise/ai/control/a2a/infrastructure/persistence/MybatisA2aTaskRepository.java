package com.enterprise.ai.control.a2a.infrastructure.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.control.a2a.application.port.A2aTaskRepository;
import com.enterprise.ai.control.a2a.domain.A2aDirection;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.domain.A2aTaskState;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class MybatisA2aTaskRepository implements A2aTaskRepository {

    private final A2aContextMapper contextMapper;
    private final A2aTaskMapper taskMapper;
    private final A2aMessageMapper messageMapper;
    private final A2aArtifactMapper artifactMapper;
    private final A2aTaskEventMapper eventMapper;
    private final A2aOutboxMapper outboxMapper;

    @Override
    public Optional<ContextRecord> findContext(
            A2aDirection direction, long principalId, String tenantScope, String contextId) {
        return Optional.ofNullable(contextMapper.findOwned(
                direction.name(), principalId, scope(tenantScope), contextId)).map(this::toContext);
    }

    @Override
    public ContextRecord saveContext(ContextRecord context) {
        A2aContextEntity entity = toEntity(context);
        try {
            if (entity.getId() == null) {
                contextMapper.insert(entity);
            } else if (contextMapper.updateById(entity) != 1) {
                throw persistenceConflict("A2A_CONTEXT_CONFLICT", "context changed concurrently");
            }
        } catch (DuplicateKeyException exception) {
            ContextRecord existing = findContext(
                    context.direction(), context.principalId(), context.tenantScope(), context.contextId())
                    .orElseThrow(() -> persistenceConflict(
                            "A2A_CONTEXT_CONFLICT", "context owner key is already in use"));
            return existing;
        }
        A2aContextEntity reloaded = contextMapper.selectById(entity.getId());
        if (reloaded == null) {
            throw persistenceFailure("context");
        }
        return toContext(reloaded);
    }

    @Override
    public void touchContext(long contextRefId, LocalDateTime lastActivityAt) {
        if (contextMapper.update(null,
                Wrappers.<A2aContextEntity>lambdaUpdate()
                        .eq(A2aContextEntity::getId, contextRefId)
                        .eq(A2aContextEntity::getStatus, "ACTIVE")
                        .set(A2aContextEntity::getLastActivityAt, lastActivityAt)) != 1) {
            throw persistenceConflict("A2A_CONTEXT_NOT_ACTIVE", "context is not active");
        }
    }

    @Override
    public Optional<TaskRecord> findTask(
            A2aDirection direction, long principalId, String tenantScope, String taskId) {
        return Optional.ofNullable(taskMapper.findOwned(
                direction.name(), principalId, scope(tenantScope), taskId)).map(this::toTask);
    }

    @Override
    public Optional<TaskRecord> lockTask(
            A2aDirection direction, long principalId, String tenantScope, String taskId) {
        return Optional.ofNullable(taskMapper.lockOwned(
                direction.name(), principalId, scope(tenantScope), taskId)).map(this::toTask);
    }

    @Override
    public Optional<TaskRecord> findTaskByRefId(
            A2aDirection direction, long principalId, String tenantScope, long taskRefId) {
        return Optional.ofNullable(taskMapper.findOwnedByRefId(
                direction.name(), principalId, scope(tenantScope), taskRefId)).map(this::toTask);
    }

    @Override
    public Optional<TaskRecord> findTaskByExecutionId(String executionId) {
        return Optional.ofNullable(taskMapper.findByExecutionId(executionId)).map(this::toTask);
    }

    @Override
    public Optional<TaskRecord> lockTaskByExecutionId(String executionId) {
        return Optional.ofNullable(taskMapper.lockByExecutionId(executionId)).map(this::toTask);
    }

    @Override
    public Optional<TaskRecord> lockTaskByRefId(long taskRefId) {
        return Optional.ofNullable(taskMapper.lockByRefId(taskRefId)).map(this::toTask);
    }

    @Override
    public TaskRecord saveTask(TaskRecord task) {
        A2aTaskEntity entity = toEntity(task);
        try {
            if (entity.getId() == null) {
                taskMapper.insert(entity);
            } else if (taskMapper.updateById(entity) != 1) {
                throw persistenceConflict("A2A_TASK_VERSION_CONFLICT",
                        "task changed concurrently; reload and retry");
            }
        } catch (DuplicateKeyException exception) {
            throw persistenceConflict("A2A_TASK_CONFLICT",
                    "task id, message idempotency key, or execution id is already in use");
        }
        A2aTaskEntity reloaded = taskMapper.selectById(entity.getId());
        if (reloaded == null) {
            throw persistenceFailure("task");
        }
        return toTask(reloaded);
    }

    @Override
    public Optional<MessageRecord> findMessage(
            A2aDirection direction, long principalId, String tenantScope, String messageId) {
        return Optional.ofNullable(messageMapper.findOwned(
                direction.name(), principalId, scope(tenantScope), messageId)).map(this::toMessage);
    }

    @Override
    public MessageRecord saveMessage(MessageRecord message) {
        A2aMessageEntity entity = toEntity(message);
        try {
            if (entity.getId() == null) {
                messageMapper.insert(entity);
            } else if (messageMapper.updateById(entity) != 1) {
                throw persistenceConflict("A2A_MESSAGE_CONFLICT", "message changed concurrently");
            }
        } catch (DuplicateKeyException exception) {
            throw persistenceConflict("A2A_MESSAGE_CONFLICT",
                    "messageId is already in use for this Principal");
        }
        A2aMessageEntity reloaded = messageMapper.selectById(entity.getId());
        if (reloaded == null) {
            throw persistenceFailure("message");
        }
        return toMessage(reloaded);
    }

    @Override
    public List<MessageRecord> findMessages(
            A2aDirection direction, long principalId, String tenantScope, long taskRefId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return messageMapper.findRecentForTask(
                        direction.name(), principalId, scope(tenantScope), taskRefId, limit)
                .stream().map(this::toMessage).toList();
    }

    @Override
    public Optional<MessageRecord> findLatestMessage(
            A2aDirection direction, long principalId, String tenantScope,
            long taskRefId, String role) {
        return Optional.ofNullable(messageMapper.findLatestForTask(
                direction.name(), principalId, scope(tenantScope), taskRefId, role))
                .map(this::toMessage);
    }

    @Override
    public List<ArtifactRecord> findArtifacts(
            A2aDirection direction, long principalId, String tenantScope, long taskRefId) {
        return artifactMapper.findForTask(direction.name(), principalId, scope(tenantScope), taskRefId)
                .stream().map(this::toArtifact).toList();
    }

    @Override
    public ArtifactRecord saveArtifact(ArtifactRecord artifact) {
        A2aArtifactEntity entity = toEntity(artifact);
        try {
            if (entity.getId() == null) {
                artifactMapper.insert(entity);
            } else if (artifactMapper.updateById(entity) != 1) {
                throw persistenceConflict("A2A_ARTIFACT_CONFLICT", "artifact changed concurrently");
            }
        } catch (DuplicateKeyException exception) {
            throw persistenceConflict("A2A_ARTIFACT_CONFLICT",
                    "artifactId is already in use for this task");
        }
        A2aArtifactEntity reloaded = artifactMapper.selectById(entity.getId());
        if (reloaded == null) {
            throw persistenceFailure("artifact");
        }
        return toArtifact(reloaded);
    }

    @Override
    public TaskPage findTasks(
            A2aDirection direction, long principalId, String tenantScope,
            Long publicationId, String contextId, A2aTaskState state,
            LocalDateTime statusTimestampAfter, int limit, int offset) {
        String persistedState = state == null ? null : state.name();
        String normalizedContext = blankToNull(contextId);
        List<TaskRecord> tasks = taskMapper.findOwnedPage(
                        direction.name(), principalId, scope(tenantScope), publicationId,
                        normalizedContext, persistedState, statusTimestampAfter, limit, offset)
                .stream().map(this::toTask).toList();
        long total = taskMapper.countOwned(
                direction.name(), principalId, scope(tenantScope), publicationId,
                normalizedContext, persistedState, statusTimestampAfter);
        return new TaskPage(tasks, total);
    }

    @Override
    public long countNonTerminalTasks(long principalId, Long publicationId) {
        return taskMapper.countNonTerminal(principalId, publicationId);
    }

    @Override
    public long countNonTerminalOutboundTasks(long principalId, long remoteAgentId) {
        return taskMapper.countNonTerminalOutbound(principalId, remoteAgentId);
    }

    @Override
    public List<String> findDueExecutionIds(LocalDateTime now, int limit) {
        return taskMapper.findDueExecutionIds(now, Math.max(1, Math.min(limit, 500)));
    }

    @Override
    public List<String> findDueOutboundExecutionIds(LocalDateTime now, int limit) {
        return taskMapper.findDueOutboundExecutionIds(now, Math.max(1, Math.min(limit, 500)));
    }

    @Override
    public void saveEvent(TaskEventRecord event) {
        try {
            eventMapper.insert(toEntity(event));
        } catch (DuplicateKeyException exception) {
            throw persistenceConflict("A2A_TASK_EVENT_CONFLICT",
                    "task event sequence or event id is already in use");
        }
    }

    @Override
    public void saveOutbox(OutboxRecord outbox) {
        try {
            outboxMapper.insert(toEntity(outbox));
        } catch (DuplicateKeyException exception) {
            throw persistenceConflict("A2A_OUTBOX_CONFLICT",
                    "outbox event id is already in use");
        }
    }

    private ContextRecord toContext(A2aContextEntity entity) {
        return new ContextRecord(entity.getId(), entity.getContextId(),
                A2aDirection.parse(entity.getDirection()), value(entity.getPrincipalId()),
                scope(entity.getTenantScope()), entity.getPublicationId(), entity.getRemoteAgentId(),
                entity.getRemoteRevisionId(), entity.getRemoteContextId(), entity.getRuntimeSessionId(), entity.getStatus(),
                entity.getLastActivityAt(), entity.getExpiresAt(), entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private A2aContextEntity toEntity(ContextRecord value) {
        A2aContextEntity entity = new A2aContextEntity();
        entity.setId(value.id());
        entity.setContextId(value.contextId());
        entity.setDirection(value.direction().name());
        entity.setPrincipalId(value.principalId());
        entity.setTenantScope(scope(value.tenantScope()));
        entity.setPublicationId(value.publicationId());
        entity.setRemoteAgentId(value.remoteAgentId());
        entity.setRemoteRevisionId(value.remoteRevisionId());
        entity.setRemoteContextId(value.remoteContextId());
        entity.setRuntimeSessionId(value.runtimeSessionId());
        entity.setStatus(value.status());
        entity.setLastActivityAt(value.lastActivityAt());
        entity.setExpiresAt(value.expiresAt());
        entity.setCreatedAt(value.createdAt());
        entity.setUpdatedAt(value.updatedAt());
        return entity;
    }

    private TaskRecord toTask(A2aTaskEntity entity) {
        return new TaskRecord(entity.getId(), entity.getTaskId(),
                A2aDirection.parse(entity.getDirection()), value(entity.getPrincipalId()),
                scope(entity.getTenantScope()), value(entity.getContextRefId()), entity.getContextId(),
                entity.getPublicationId(), entity.getPublicationRevisionId(), entity.getRemoteAgentId(),
                entity.getRemoteRevisionId(), entity.getRemoteTaskId(), entity.getOriginMessageId(),
                entity.getOriginPayloadSha256(), A2aTaskState.parse(entity.getState()),
                integer(entity.getStateVersion()), value(entity.getLastEventSequence()),
                entity.getExecutionId(), entity.getRuntimeRunId(), entity.getTraceId(),
                entity.getRuntimeInteractionId(),
                entity.getStatusMessageSummary(), entity.getErrorCode(), entity.getErrorSummary(),
                entity.getCancelPhase(), entity.getCancelRequestedAt(), integer(entity.getAttemptCount()),
                entity.getSubmittedAt(), entity.getStartedAt(), entity.getCompletedAt(),
                entity.getRetentionExpiresAt(), entity.getDeadlineAt(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private A2aTaskEntity toEntity(TaskRecord value) {
        A2aTaskEntity entity = new A2aTaskEntity();
        entity.setId(value.id());
        entity.setTaskId(value.taskId());
        entity.setDirection(value.direction().name());
        entity.setPrincipalId(value.principalId());
        entity.setTenantScope(scope(value.tenantScope()));
        entity.setContextRefId(value.contextRefId());
        entity.setContextId(value.contextId());
        entity.setPublicationId(value.publicationId());
        entity.setPublicationRevisionId(value.publicationRevisionId());
        entity.setRemoteAgentId(value.remoteAgentId());
        entity.setRemoteRevisionId(value.remoteRevisionId());
        entity.setRemoteTaskId(value.remoteTaskId());
        entity.setOriginMessageId(value.originMessageId());
        entity.setOriginPayloadSha256(value.originPayloadSha256());
        entity.setState(value.state().name());
        entity.setStateVersion(value.stateVersion());
        entity.setLastEventSequence(value.lastEventSequence());
        entity.setExecutionId(value.executionId());
        entity.setRuntimeRunId(value.runtimeRunId());
        entity.setTraceId(value.traceId());
        entity.setRuntimeInteractionId(value.runtimeInteractionId());
        entity.setStatusMessageSummary(value.statusMessageSummary());
        entity.setErrorCode(value.errorCode());
        entity.setErrorSummary(value.errorSummary());
        entity.setCancelPhase(value.cancelPhase());
        entity.setCancelRequestedAt(value.cancelRequestedAt());
        entity.setAttemptCount(value.attemptCount());
        entity.setSubmittedAt(value.submittedAt());
        entity.setStartedAt(value.startedAt());
        entity.setCompletedAt(value.completedAt());
        entity.setRetentionExpiresAt(value.retentionExpiresAt());
        entity.setDeadlineAt(value.deadlineAt());
        entity.setCreatedAt(value.createdAt());
        entity.setUpdatedAt(value.updatedAt());
        return entity;
    }

    private MessageRecord toMessage(A2aMessageEntity entity) {
        return new MessageRecord(entity.getId(), entity.getMessageId(),
                A2aDirection.parse(entity.getDirection()), value(entity.getPrincipalId()),
                scope(entity.getTenantScope()), value(entity.getContextRefId()), entity.getTaskRefId(),
                entity.getRole(), entity.getPayloadCiphertext(), entity.getPayloadObjectRef(),
                entity.getEncryptionKeyId(), entity.getEncryptionNonce(), entity.getPayloadSha256(),
                entity.getIdempotencySha256(), value(entity.getPayloadBytes()),
                entity.getContentClassification(), entity.getSafeSummary(),
                entity.getSafeMetadataJson(), entity.getRetentionExpiresAt(), entity.getContentDeletedAt(),
                entity.getCreatedAt());
    }

    private A2aMessageEntity toEntity(MessageRecord value) {
        A2aMessageEntity entity = new A2aMessageEntity();
        entity.setId(value.id());
        entity.setMessageId(value.messageId());
        entity.setDirection(value.direction().name());
        entity.setPrincipalId(value.principalId());
        entity.setTenantScope(scope(value.tenantScope()));
        entity.setContextRefId(value.contextRefId());
        entity.setTaskRefId(value.taskRefId());
        entity.setRole(value.role());
        entity.setPayloadCiphertext(value.payloadCiphertext());
        entity.setPayloadObjectRef(value.payloadObjectRef());
        entity.setEncryptionKeyId(value.encryptionKeyId());
        entity.setEncryptionNonce(value.encryptionNonce());
        entity.setPayloadSha256(value.payloadSha256());
        entity.setIdempotencySha256(value.idempotencySha256());
        entity.setPayloadBytes(value.payloadBytes());
        entity.setContentClassification(value.contentClassification());
        entity.setSafeSummary(value.safeSummary());
        entity.setSafeMetadataJson(value.safeMetadataJson());
        entity.setRetentionExpiresAt(value.retentionExpiresAt());
        entity.setContentDeletedAt(value.contentDeletedAt());
        entity.setCreatedAt(value.createdAt());
        return entity;
    }

    private ArtifactRecord toArtifact(A2aArtifactEntity entity) {
        return new ArtifactRecord(entity.getId(), value(entity.getTaskRefId()), entity.getArtifactId(),
                entity.getName(), entity.getDescription(), entity.getPayloadCiphertext(),
                entity.getPayloadObjectRef(), entity.getEncryptionKeyId(), entity.getEncryptionNonce(),
                entity.getPayloadSha256(), value(entity.getPayloadBytes()), entity.getMediaTypesJson(),
                entity.getContentClassification(), entity.getSafeSummary(), integer(entity.getAppendRevision()),
                Boolean.TRUE.equals(entity.getLastChunk()), entity.getRetentionExpiresAt(),
                entity.getContentDeletedAt(), entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private A2aArtifactEntity toEntity(ArtifactRecord value) {
        A2aArtifactEntity entity = new A2aArtifactEntity();
        entity.setId(value.id());
        entity.setTaskRefId(value.taskRefId());
        entity.setArtifactId(value.artifactId());
        entity.setName(value.name());
        entity.setDescription(value.description());
        entity.setPayloadCiphertext(value.payloadCiphertext());
        entity.setPayloadObjectRef(value.payloadObjectRef());
        entity.setEncryptionKeyId(value.encryptionKeyId());
        entity.setEncryptionNonce(value.encryptionNonce());
        entity.setPayloadSha256(value.payloadSha256());
        entity.setPayloadBytes(value.payloadBytes());
        entity.setMediaTypesJson(value.mediaTypesJson());
        entity.setContentClassification(value.contentClassification());
        entity.setSafeSummary(value.safeSummary());
        entity.setAppendRevision(value.appendRevision());
        entity.setLastChunk(value.lastChunk());
        entity.setRetentionExpiresAt(value.retentionExpiresAt());
        entity.setContentDeletedAt(value.contentDeletedAt());
        entity.setCreatedAt(value.createdAt());
        entity.setUpdatedAt(value.updatedAt());
        return entity;
    }

    private A2aTaskEventEntity toEntity(TaskEventRecord value) {
        A2aTaskEventEntity entity = new A2aTaskEventEntity();
        entity.setId(value.id());
        entity.setTaskRefId(value.taskRefId());
        entity.setSequenceNo(value.sequenceNo());
        entity.setEventId(value.eventId());
        entity.setEventType(value.eventType());
        entity.setFromState(value.fromState() == null ? null : value.fromState().name());
        entity.setToState(value.toState() == null ? null : value.toState().name());
        entity.setActorType(value.actorType());
        entity.setActorId(value.actorId());
        entity.setResourceType(value.resourceType());
        entity.setResourceId(value.resourceId());
        entity.setSafeSummary(value.safeSummary());
        entity.setSafePayloadJson(value.safePayloadJson());
        entity.setRuntimeSequence(value.runtimeSequence());
        entity.setTraceId(value.traceId());
        entity.setCreatedAt(value.createdAt());
        return entity;
    }

    private A2aOutboxEntity toEntity(OutboxRecord value) {
        A2aOutboxEntity entity = new A2aOutboxEntity();
        entity.setId(value.id());
        entity.setEventId(value.eventId());
        entity.setAggregateType(value.aggregateType());
        entity.setAggregateId(value.aggregateId());
        entity.setEventType(value.eventType());
        entity.setResourceRefJson(value.resourceRefJson());
        entity.setStatus(value.status());
        entity.setAttemptCount(value.attemptCount());
        entity.setNextAttemptAt(value.nextAttemptAt());
        entity.setLeaseOwner(value.leaseOwner());
        entity.setLeaseUntil(value.leaseUntil());
        entity.setLastErrorCode(value.lastErrorCode());
        entity.setLastErrorSummary(value.lastErrorSummary());
        entity.setCreatedAt(value.createdAt());
        entity.setDeliveredAt(value.deliveredAt());
        entity.setUpdatedAt(value.updatedAt());
        return entity;
    }

    private long value(Long value) {
        return value == null ? 0L : value;
    }

    private int integer(Integer value) {
        return value == null ? 0 : value;
    }

    private String scope(String value) {
        return value == null ? "" : value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private A2aDomainException persistenceConflict(String code, String detail) {
        return new A2aDomainException(code, detail);
    }

    private A2aDomainException persistenceFailure(String resource) {
        return new A2aDomainException("A2A_TASK_PERSISTENCE_FAILED",
                resource + " could not be reloaded after persistence");
    }
}
