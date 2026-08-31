package com.enterprise.ai.control.a2a.application.task;

import com.enterprise.ai.control.a2a.application.A2aPageView;
import com.enterprise.ai.control.a2a.application.port.A2aTaskManagementReader;
import com.enterprise.ai.control.a2a.application.port.A2aTaskManagementReader.ArtifactRow;
import com.enterprise.ai.control.a2a.application.port.A2aTaskManagementReader.EventRow;
import com.enterprise.ai.control.a2a.application.port.A2aTaskManagementReader.MessageRow;
import com.enterprise.ai.control.a2a.application.port.A2aTaskManagementReader.TaskRow;
import com.enterprise.ai.control.a2a.application.outbound.A2aOutboundDelegationService;
import com.enterprise.ai.control.a2a.domain.A2aDirection;
import com.enterprise.ai.control.a2a.domain.A2aDomainException;
import com.enterprise.ai.control.a2a.domain.A2aTaskState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class A2aTaskManagementService {

    private final A2aTaskManagementReader reader;
    private final A2aTaskDispatchService dispatchService;
    private final A2aOutboundDelegationService outboundDelegationService;

    public A2aPageView<TaskSummaryView> list(
            String search,
            String direction,
            String state,
            Long publicationId,
            Long remoteAgentId,
            Long principalId,
            String tenantScope,
            LocalDateTime submittedAfter,
            LocalDateTime submittedBefore,
            Integer limit,
            Integer offset) {
        int pageSize = limit == null ? 50 : Math.max(1, Math.min(limit, 200));
        int pageOffset = offset == null ? 0 : Math.max(0, offset);
        if (submittedAfter != null && submittedBefore != null
                && !submittedAfter.isBefore(submittedBefore)) {
            throw new A2aDomainException("A2A_TASK_TIME_RANGE_INVALID",
                    "submittedAfter must be before submittedBefore");
        }
        A2aTaskManagementReader.Query query = new A2aTaskManagementReader.Query(
                text(search, 128), parseDirection(direction), parseState(state),
                positive(publicationId, "publicationId"), positive(remoteAgentId, "remoteAgentId"),
                positive(principalId, "principalId"), text(tenantScope, 96),
                submittedAfter, submittedBefore, pageSize, pageOffset);
        A2aTaskManagementReader.Page page = reader.findPage(query);
        return A2aPageView.of("reachai.a2a-hub.task-summary.v1",
                page.items().stream().map(TaskSummaryView::from).toList(),
                page.total(), pageSize, pageOffset);
    }

    public TaskDetailView detail(String taskId, String direction) {
        TaskRow task = requireUnique(taskId, direction);
        List<EventRow> events = reader.findEvents(task.taskRefId());
        boolean eventGap = hasEventGap(events, task.lastEventSequence());
        return new TaskDetailView(
                "reachai.a2a-hub.task-detail.v1",
                TaskSummaryView.from(task),
                new IdentityView(task.principalId(), task.principalKey(), task.principalDisplayName(),
                        task.tenantScope(), task.trustProfileId(), task.trustProfileKey()),
                new RuntimeLinkView(task.executionId(), task.runtimeRunId(), task.traceId(),
                        task.runtimeInteractionId()),
                events.stream().map(TaskEventView::from).toList(),
                reader.findMessages(task.taskRefId()).stream().map(MessageSummaryView::from).toList(),
                reader.findArtifacts(task.taskRefId()).stream().map(ArtifactSummaryView::from).toList(),
                eventGap);
    }

    public TaskSummaryView cancel(String taskId, String direction, String actor) {
        TaskRow task = requireUnique(taskId, direction);
        if (A2aDirection.OUTBOUND.name().equals(task.direction())) {
            outboundDelegationService.cancel(task.executionId(), "PLATFORM_USER", actor);
        } else {
            dispatchService.requestCancellation(task.executionId(), "PLATFORM_USER", actor);
        }
        return TaskSummaryView.from(requireUnique(taskId, direction));
    }

    TaskRow requireUnique(String taskId, String direction) {
        String id = text(taskId, 128);
        if (id == null) {
            throw new A2aDomainException("A2A_TASK_ID_INVALID", "taskId is required");
        }
        List<TaskRow> matches = reader.findByTaskId(id, parseDirection(direction));
        if (matches.isEmpty()) {
            throw new A2aDomainException("A2A_TASK_NOT_FOUND", "A2A Task was not found");
        }
        if (matches.size() != 1) {
            throw new A2aDomainException("A2A_TASK_ID_AMBIGUOUS",
                    "taskId exists in multiple directions; specify direction");
        }
        return matches.get(0);
    }

    private boolean hasEventGap(List<EventRow> events, long projectedLastSequence) {
        long expected = 1;
        for (EventRow event : events) {
            if (event.sequence() != expected++) {
                return true;
            }
        }
        return projectedLastSequence != events.size();
    }

    private String parseDirection(String value) {
        return value == null || value.isBlank() ? null : A2aDirection.parse(value).name();
    }

    private String parseState(String value) {
        if (value == null || value.isBlank()) return null;
        A2aTaskState parsed = A2aTaskState.parse(value);
        if (!parsed.persistable()) {
            throw new A2aDomainException("A2A_TASK_STATE_INVALID", "state is not persistable");
        }
        return parsed.name();
    }

    private Long positive(Long value, String field) {
        if (value != null && value <= 0) {
            throw new A2aDomainException("A2A_TASK_FILTER_INVALID", field + " must be positive");
        }
        return value;
    }

    private String text(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > max) {
            throw new A2aDomainException("A2A_TASK_FILTER_INVALID", "filter text is too long");
        }
        return normalized;
    }

    public record TaskSummaryView(
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
            String tenantScope,
            String runtimeRunId,
            String traceId,
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
            LocalDateTime updatedAt) {
        static TaskSummaryView from(TaskRow value) {
            return new TaskSummaryView(
                    value.taskId(), value.direction(), value.state(), value.contextId(),
                    value.publicationId(), value.publicationKey(), value.publicationRevisionId(),
                    value.remoteAgentId(), value.remoteAgentKey(), value.remoteRevisionId(),
                    value.principalId(), value.principalKey(), value.tenantScope(),
                    value.runtimeRunId(), value.traceId(), value.statusSummary(),
                    value.errorCode(), value.errorSummary(), value.cancelPhase(),
                    value.outboundPollStatus(), value.outboundPollAttemptCount(),
                    value.outboundNextPollAt(), value.outboundLastPolledAt(),
                    value.outboundLastPollErrorCode(), value.outboundLastPollErrorSummary(),
                    value.attemptCount(), value.lastEventSequence(), value.submittedAt(),
                    value.startedAt(), value.completedAt(), value.deadlineAt(), value.updatedAt());
        }
    }

    public record TaskDetailView(
            String schema,
            TaskSummaryView task,
            IdentityView identity,
            RuntimeLinkView runtime,
            List<TaskEventView> events,
            List<MessageSummaryView> messages,
            List<ArtifactSummaryView> artifacts,
            boolean eventGapDetected) {
    }

    public record IdentityView(
            long principalId, String principalKey, String principalDisplayName,
            String tenantScope, Long trustProfileId, String trustProfileKey) {
    }

    public record RuntimeLinkView(
            String executionId, String runId, String traceId, String interactionId) {
    }

    public record TaskEventView(
            long sequence, String eventId, String eventType, String fromState, String toState,
            String actorType, String actorId, String resourceType, String resourceId,
            String safeSummary, Long runtimeSequence, String traceId, LocalDateTime createdAt) {
        static TaskEventView from(EventRow value) {
            return new TaskEventView(
                    value.sequence(), value.eventId(), value.eventType(), value.fromState(), value.toState(),
                    value.actorType(), value.actorId(), value.resourceType(), value.resourceId(),
                    value.safeSummary(), value.runtimeSequence(), value.traceId(), value.createdAt());
        }
    }

    public record MessageSummaryView(
            String messageId, String role, long payloadBytes, String payloadSha256,
            String contentClassification, String safeSummary, boolean contentAvailable,
            LocalDateTime retentionExpiresAt, LocalDateTime createdAt) {
        static MessageSummaryView from(MessageRow value) {
            return new MessageSummaryView(
                    value.messageId(), value.role(), value.payloadBytes(), value.payloadSha256(),
                    value.contentClassification(), value.safeSummary(), value.contentAvailable(),
                    value.retentionExpiresAt(), value.createdAt());
        }
    }

    public record ArtifactSummaryView(
            String artifactId, String name, String description, long payloadBytes,
            String payloadSha256, List<String> mediaTypes, String contentClassification,
            String safeSummary, int appendRevision, boolean lastChunk, boolean contentAvailable,
            LocalDateTime retentionExpiresAt, LocalDateTime updatedAt) {
        static ArtifactSummaryView from(ArtifactRow value) {
            return new ArtifactSummaryView(
                    value.artifactId(), value.name(), value.description(), value.payloadBytes(),
                    value.payloadSha256(), value.mediaTypes(), value.contentClassification(),
                    value.safeSummary(), value.appendRevision(), value.lastChunk(),
                    value.contentAvailable(), value.retentionExpiresAt(), value.updatedAt());
        }
    }
}
