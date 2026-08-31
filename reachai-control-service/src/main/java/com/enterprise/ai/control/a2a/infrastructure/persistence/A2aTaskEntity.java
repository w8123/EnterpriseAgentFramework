package com.enterprise.ai.control.a2a.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_a2a_task")
public class A2aTaskEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String taskId;
    private String direction;
    private Long principalId;
    private String tenantScope;
    private Long contextRefId;
    private String contextId;
    private Long publicationId;
    private Long publicationRevisionId;
    private Long remoteAgentId;
    private Long remoteRevisionId;
    private String remoteTaskId;
    private String originMessageId;
    private String originPayloadSha256;
    private String state;
    @Version
    private Integer stateVersion;
    private Long lastEventSequence;
    private String executionId;
    private String runtimeRunId;
    private String traceId;
    private String runtimeInteractionId;
    private String statusMessageSummary;
    private String errorCode;
    private String errorSummary;
    private String cancelPhase;
    private LocalDateTime cancelRequestedAt;
    private Integer attemptCount;
    private LocalDateTime submittedAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime retentionExpiresAt;
    private LocalDateTime deadlineAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
