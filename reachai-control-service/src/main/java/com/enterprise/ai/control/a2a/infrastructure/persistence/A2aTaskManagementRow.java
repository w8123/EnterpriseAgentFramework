package com.enterprise.ai.control.a2a.infrastructure.persistence;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class A2aTaskManagementRow {
    private Long taskRefId;
    private String taskId;
    private String direction;
    private String state;
    private String contextId;
    private Long publicationId;
    private String publicationKey;
    private Long publicationRevisionId;
    private Long remoteAgentId;
    private String remoteAgentKey;
    private Long remoteRevisionId;
    private Long principalId;
    private String principalKey;
    private String principalDisplayName;
    private String tenantScope;
    private Long trustProfileId;
    private String trustProfileKey;
    private String executionId;
    private String runtimeRunId;
    private String traceId;
    private String runtimeInteractionId;
    private String statusSummary;
    private String errorCode;
    private String errorSummary;
    private String cancelPhase;
    private String outboundPollStatus;
    private Integer outboundPollAttemptCount;
    private LocalDateTime outboundNextPollAt;
    private LocalDateTime outboundLastPolledAt;
    private String outboundLastPollErrorCode;
    private String outboundLastPollErrorSummary;
    private Integer attemptCount;
    private Long lastEventSequence;
    private LocalDateTime submittedAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime deadlineAt;
    private LocalDateTime retentionExpiresAt;
    private LocalDateTime updatedAt;
}
