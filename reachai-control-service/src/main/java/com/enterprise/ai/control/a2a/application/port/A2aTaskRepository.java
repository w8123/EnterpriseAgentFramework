package com.enterprise.ai.control.a2a.application.port;

import com.enterprise.ai.control.a2a.domain.A2aDirection;
import com.enterprise.ai.control.a2a.domain.A2aTaskState;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Owner-scoped persistence boundary for A2A protocol resources. */
public interface A2aTaskRepository {

    Optional<ContextRecord> findContext(
            A2aDirection direction, long principalId, String tenantScope, String contextId);

    ContextRecord saveContext(ContextRecord context);

    void touchContext(long contextRefId, LocalDateTime lastActivityAt);

    Optional<TaskRecord> findTask(
            A2aDirection direction, long principalId, String tenantScope, String taskId);

    Optional<TaskRecord> lockTask(
            A2aDirection direction, long principalId, String tenantScope, String taskId);

    Optional<TaskRecord> findTaskByRefId(
            A2aDirection direction, long principalId, String tenantScope, long taskRefId);

    Optional<TaskRecord> findTaskByExecutionId(String executionId);

    Optional<TaskRecord> lockTaskByExecutionId(String executionId);

    Optional<TaskRecord> lockTaskByRefId(long taskRefId);

    TaskRecord saveTask(TaskRecord task);

    Optional<MessageRecord> findMessage(
            A2aDirection direction, long principalId, String tenantScope, String messageId);

    MessageRecord saveMessage(MessageRecord message);

    List<MessageRecord> findMessages(
            A2aDirection direction, long principalId, String tenantScope,
            long taskRefId, int limit);

    Optional<MessageRecord> findLatestMessage(
            A2aDirection direction, long principalId, String tenantScope,
            long taskRefId, String role);

    List<ArtifactRecord> findArtifacts(
            A2aDirection direction, long principalId, String tenantScope, long taskRefId);

    ArtifactRecord saveArtifact(ArtifactRecord artifact);

    TaskPage findTasks(
            A2aDirection direction, long principalId, String tenantScope,
            Long publicationId, String contextId, A2aTaskState state,
            LocalDateTime statusTimestampAfter, int limit, int offset);

    long countNonTerminalTasks(long principalId, Long publicationId);

    long countNonTerminalOutboundTasks(long principalId, long remoteAgentId);

    List<String> findDueExecutionIds(LocalDateTime now, int limit);

    List<String> findDueOutboundExecutionIds(LocalDateTime now, int limit);

    void saveEvent(TaskEventRecord event);

    void saveOutbox(OutboxRecord outbox);

    record ContextRecord(
            Long id,
            String contextId,
            A2aDirection direction,
            long principalId,
            String tenantScope,
            Long publicationId,
            Long remoteAgentId,
            Long remoteRevisionId,
            String remoteContextId,
            String runtimeSessionId,
            String status,
            LocalDateTime lastActivityAt,
            LocalDateTime expiresAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    record TaskRecord(
            Long id,
            String taskId,
            A2aDirection direction,
            long principalId,
            String tenantScope,
            long contextRefId,
            String contextId,
            Long publicationId,
            Long publicationRevisionId,
            Long remoteAgentId,
            Long remoteRevisionId,
            String remoteTaskId,
            String originMessageId,
            String originPayloadSha256,
            A2aTaskState state,
            int stateVersion,
            long lastEventSequence,
            String executionId,
            String runtimeRunId,
            String traceId,
            String runtimeInteractionId,
            String statusMessageSummary,
            String errorCode,
            String errorSummary,
            String cancelPhase,
            LocalDateTime cancelRequestedAt,
            int attemptCount,
            LocalDateTime submittedAt,
            LocalDateTime startedAt,
            LocalDateTime completedAt,
            LocalDateTime retentionExpiresAt,
            LocalDateTime deadlineAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    record MessageRecord(
            Long id,
            String messageId,
            A2aDirection direction,
            long principalId,
            String tenantScope,
            long contextRefId,
            Long taskRefId,
            String role,
            String payloadCiphertext,
            String payloadObjectRef,
            String encryptionKeyId,
            String encryptionNonce,
            String payloadSha256,
            String idempotencySha256,
            long payloadBytes,
            String contentClassification,
            String safeSummary,
            String safeMetadataJson,
            LocalDateTime retentionExpiresAt,
            LocalDateTime contentDeletedAt,
            LocalDateTime createdAt) {
    }

    record ArtifactRecord(
            Long id,
            long taskRefId,
            String artifactId,
            String name,
            String description,
            String payloadCiphertext,
            String payloadObjectRef,
            String encryptionKeyId,
            String encryptionNonce,
            String payloadSha256,
            long payloadBytes,
            String mediaTypesJson,
            String contentClassification,
            String safeSummary,
            int appendRevision,
            boolean lastChunk,
            LocalDateTime retentionExpiresAt,
            LocalDateTime contentDeletedAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    record TaskEventRecord(
            Long id,
            long taskRefId,
            long sequenceNo,
            String eventId,
            String eventType,
            A2aTaskState fromState,
            A2aTaskState toState,
            String actorType,
            String actorId,
            String resourceType,
            String resourceId,
            String safeSummary,
            String safePayloadJson,
            Long runtimeSequence,
            String traceId,
            LocalDateTime createdAt) {
    }

    record OutboxRecord(
            Long id,
            String eventId,
            String aggregateType,
            String aggregateId,
            String eventType,
            String resourceRefJson,
            String status,
            int attemptCount,
            LocalDateTime nextAttemptAt,
            String leaseOwner,
            LocalDateTime leaseUntil,
            String lastErrorCode,
            String lastErrorSummary,
            LocalDateTime createdAt,
            LocalDateTime deliveredAt,
            LocalDateTime updatedAt) {
    }

    record TaskPage(List<TaskRecord> tasks, long total) {
        public TaskPage {
            tasks = tasks == null ? List.of() : List.copyOf(tasks);
        }
    }
}
