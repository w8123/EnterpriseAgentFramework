package com.enterprise.ai.control.aicoding.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_ai_coding_task")
public class AiCodingTaskEntity {

    @TableId(type = IdType.INPUT)
    private String taskId;
    private Long projectId;
    private String projectCode;
    private String capabilityKey;
    private String taskKind;
    private String protocolVersion;
    private String executorProvider;
    private String executionMode;
    private String managedExecutionId;
    private String sandboxProfile;
    private String managedExecutionStatus;
    private String managedPendingInteractionId;
    private String title;
    private String objective;
    private String accessMode;
    private String executionStatus;
    private String resultContractKey;
    private String resultContractVersion;
    private String contextSnapshotJson;
    private String lastMessage;
    @Version
    private Long lockVersion;
    private String createdBy;
    private LocalDateTime startedAt;
    private LocalDateTime resultSubmittedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
