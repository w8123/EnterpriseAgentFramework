package com.enterprise.ai.control.managed;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_managed_execution_inbox")
public class ControlManagedExecutionInboxEntity {

    @TableId(type = IdType.INPUT)
    private String eventId;
    private String executionId;
    private String eventType;
    private String tenantId;
    private String projectCode;
    private String sourceType;
    private String sourceRef;
    private String runtimeStatus;
    private String payloadSha256;
    private String payloadJson;
    private String projectionStatus;
    private String projectionError;
    private Integer projectionAttemptCount;
    private LocalDateTime projectionAvailableAt;
    private LocalDateTime receivedAt;
    private LocalDateTime appliedAt;
}
