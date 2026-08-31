package com.enterprise.ai.control.a2a.infrastructure.persistence;

import com.enterprise.ai.control.a2a.application.port.A2aTaskManagementReader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class MybatisA2aTaskManagementReader implements A2aTaskManagementReader {

    private final A2aTaskManagementMapper mapper;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    @Override
    public Page findPage(Query query) {
        return new Page(mapper.findPage(query).stream().map(this::task).toList(),
                mapper.countPage(query));
    }

    @Override
    public List<TaskRow> findByTaskId(String taskId, String direction) {
        return mapper.findByTaskId(taskId, direction).stream().map(this::task).toList();
    }

    @Override
    public List<EventRow> findEvents(long taskRefId) {
        return mapper.findEvents(taskRefId).stream().map(value -> new EventRow(
                number(value.getSequenceNo()), value.getEventId(), value.getEventType(),
                value.getFromState(), value.getToState(), value.getActorType(), value.getActorId(),
                value.getResourceType(), value.getResourceId(), value.getSafeSummary(),
                value.getRuntimeSequence(), value.getTraceId(), value.getCreatedAt())).toList();
    }

    @Override
    public List<MessageRow> findMessages(long taskRefId) {
        LocalDateTime now = LocalDateTime.now(clock);
        return mapper.findMessages(taskRefId).stream().map(value -> new MessageRow(
                value.getMessageId(), value.getRole(), number(value.getPayloadBytes()),
                value.getPayloadSha256(), value.getContentClassification(), value.getSafeSummary(),
                available(value.getPayloadCiphertext(), value.getPayloadObjectRef(),
                        value.getContentDeletedAt(), value.getRetentionExpiresAt(), now),
                value.getRetentionExpiresAt(), value.getCreatedAt())).toList();
    }

    @Override
    public List<ArtifactRow> findArtifacts(long taskRefId) {
        LocalDateTime now = LocalDateTime.now(clock);
        return mapper.findArtifacts(taskRefId).stream().map(value -> new ArtifactRow(
                value.getArtifactId(), value.getName(), value.getDescription(),
                number(value.getPayloadBytes()), value.getPayloadSha256(), mediaTypes(value.getMediaTypesJson()),
                value.getContentClassification(), value.getSafeSummary(), integer(value.getAppendRevision()),
                Boolean.TRUE.equals(value.getLastChunk()),
                available(value.getPayloadCiphertext(), value.getPayloadObjectRef(),
                        value.getContentDeletedAt(), value.getRetentionExpiresAt(), now),
                value.getRetentionExpiresAt(), value.getUpdatedAt())).toList();
    }

    private TaskRow task(A2aTaskManagementRow value) {
        return new TaskRow(
                number(value.getTaskRefId()), value.getTaskId(), value.getDirection(), value.getState(),
                value.getContextId(), value.getPublicationId(), value.getPublicationKey(),
                value.getPublicationRevisionId(), value.getRemoteAgentId(), value.getRemoteAgentKey(),
                value.getRemoteRevisionId(), number(value.getPrincipalId()), value.getPrincipalKey(),
                value.getPrincipalDisplayName(), value.getTenantScope(), value.getTrustProfileId(),
                value.getTrustProfileKey(), value.getExecutionId(), value.getRuntimeRunId(),
                value.getTraceId(), value.getRuntimeInteractionId(), value.getStatusSummary(),
                value.getErrorCode(), value.getErrorSummary(), value.getCancelPhase(),
                value.getOutboundPollStatus(), integer(value.getOutboundPollAttemptCount()),
                value.getOutboundNextPollAt(), value.getOutboundLastPolledAt(),
                value.getOutboundLastPollErrorCode(), value.getOutboundLastPollErrorSummary(),
                integer(value.getAttemptCount()), number(value.getLastEventSequence()),
                value.getSubmittedAt(), value.getStartedAt(), value.getCompletedAt(), value.getDeadlineAt(),
                value.getRetentionExpiresAt(), value.getUpdatedAt());
    }

    private boolean available(
            String ciphertext, String objectRef, LocalDateTime deletedAt,
            LocalDateTime expiresAt, LocalDateTime now) {
        return deletedAt == null && (ciphertext != null || objectRef != null)
                && (expiresAt == null || !expiresAt.isBefore(now));
    }

    private long number(Long value) {
        return value == null ? 0L : value;
    }

    private int integer(Integer value) {
        return value == null ? 0 : value;
    }

    private List<String> mediaTypes(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            List<String> parsed = objectMapper.readValue(value, STRING_LIST);
            return parsed == null ? List.of() : List.copyOf(parsed);
        } catch (Exception ignored) {
            return List.of();
        }
    }
}
