package com.enterprise.ai.runtime.managed;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("runtime_managed_execution")
public class ManagedExecutionEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String executionId;
    private String tenantId;
    private String projectCode;
    private String requestedByUserId;
    private String sourceType;
    private String sourceRef;
    private String executorProvider;
    private String sandboxProfile;
    private String modelRef;
    private String acceptanceProfile;
    private String objectiveText;
    private String objectiveSha256;
    private String status;
    private String cleanupStatus;
    private String sandboxRef;
    private Integer cleanupAttemptCount;
    private LocalDateTime cleanupAvailableAt;
    private String cleanupError;
    private Integer priority;
    private Integer maxWallTimeSeconds;
    private Integer approvalTimeoutSeconds;
    private Integer approvalCount;
    private Long commandSequence;
    private String pendingApprovalRequestId;
    private String pendingInteractionId;
    private String approvalDecision;
    private LocalDateTime approvalDecidedAt;
    private Integer lastEventSequence;
    private Integer provisionAttemptCount;
    private Integer provisionMaxAttempts;
    private LocalDateTime provisionAvailableAt;
    private String workerTokenDigest;
    private LocalDateTime workerTokenExpiresAt;
    private String leaseOwner;
    private LocalDateTime leaseExpiresAt;
    private LocalDateTime lastHeartbeatAt;
    private LocalDateTime cancelRequestedAt;
    private String errorCode;
    private String errorMessage;
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime startedAt;
    private LocalDateTime finalizingAt;
    private LocalDateTime completedAt;
}
