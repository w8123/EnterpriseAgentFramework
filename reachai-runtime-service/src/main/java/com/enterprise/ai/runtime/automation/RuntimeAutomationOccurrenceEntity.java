package com.enterprise.ai.runtime.automation;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("runtime_automation_occurrence")
public class RuntimeAutomationOccurrenceEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String occurrenceKey;
    private Long automationId;
    private Long automationVersionId;
    private String sourceType;
    private LocalDateTime scheduledAt;
    private LocalDateTime availableAt;
    private String status;
    private Integer priority;
    private Integer attemptCount;
    private Integer maxAttempts;
    private String leaseOwner;
    private String leaseToken;
    private LocalDateTime leasedUntil;
    private String traceId;
    private String interactionId;
    private String inputSnapshotJson;
    private String principalSnapshotJson;
    private String lastErrorCode;
    private String lastErrorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
