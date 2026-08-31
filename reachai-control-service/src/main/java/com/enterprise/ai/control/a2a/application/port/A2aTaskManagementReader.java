package com.enterprise.ai.control.a2a.application.port;

import java.time.LocalDateTime;
import java.util.List;

public interface A2aTaskManagementReader {

    Page findPage(Query query);

    List<TaskRow> findByTaskId(String taskId, String direction);

    List<EventRow> findEvents(long taskRefId);

    List<MessageRow> findMessages(long taskRefId);

    List<ArtifactRow> findArtifacts(long taskRefId);

    record Query(
            String search,
            String direction,
            String state,
            Long publicationId,
            Long remoteAgentId,
            Long principalId,
            String tenantScope,
            LocalDateTime submittedAfter,
            LocalDateTime submittedBefore,
            int limit,
            int offset) {
    }

    record Page(List<TaskRow> items, long total) {
        public Page {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    record TaskRow(
            long taskRefId,
            String taskId,
            String direction,
            String state,
            String contextId,
            Long publicationId,
            String publicationKey,
            Long publicationRevisionId,
            Long remoteAgentId,
            String remoteAgentKey,
            Long remoteRevisionId,
            long principalId,
            String principalKey,
            String principalDisplayName,
            String tenantScope,
            Long trustProfileId,
            String trustProfileKey,
            String executionId,
            String runtimeRunId,
            String traceId,
            String runtimeInteractionId,
            String statusSummary,
            String errorCode,
            String errorSummary,
            String cancelPhase,
            String outboundPollStatus,
            int outboundPollAttemptCount,
            LocalDateTime outboundNextPollAt,
            LocalDateTime outboundLastPolledAt,
            String outboundLastPollErrorCode,
            String outboundLastPollErrorSummary,
            int attemptCount,
            long lastEventSequence,
            LocalDateTime submittedAt,
            LocalDateTime startedAt,
            LocalDateTime completedAt,
            LocalDateTime deadlineAt,
            LocalDateTime retentionExpiresAt,
            LocalDateTime updatedAt) {
    }

    record EventRow(
            long sequence,
            String eventId,
            String eventType,
            String fromState,
            String toState,
            String actorType,
            String actorId,
            String resourceType,
            String resourceId,
            String safeSummary,
            Long runtimeSequence,
            String traceId,
            LocalDateTime createdAt) {
    }

    record MessageRow(
            String messageId,
            String role,
            long payloadBytes,
            String payloadSha256,
            String contentClassification,
            String safeSummary,
            boolean contentAvailable,
            LocalDateTime retentionExpiresAt,
            LocalDateTime createdAt) {
    }

    record ArtifactRow(
            String artifactId,
            String name,
            String description,
            long payloadBytes,
            String payloadSha256,
            List<String> mediaTypes,
            String contentClassification,
            String safeSummary,
            int appendRevision,
            boolean lastChunk,
            boolean contentAvailable,
            LocalDateTime retentionExpiresAt,
            LocalDateTime updatedAt) {
    }
}
