package com.enterprise.ai.control.aicoding.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_ai_coding_task_handoff")
public class AiCodingTaskHandoffEntity {

    @TableId(type = IdType.INPUT)
    private String handoffId;
    private String taskId;
    private String activationCodeHash;
    private String activationStatus;
    private Integer activationAttempts;
    private LocalDateTime lastActivationAttemptAt;
    private LocalDateTime activationExpiresAt;
    private String taskTokenHash;
    private LocalDateTime tokenExpiresAt;
    private String clientProvider;
    private String clientSessionRef;
    private LocalDateTime activatedAt;
    private LocalDateTime lastSeenAt;
    private LocalDateTime leaseExpiresAt;
    private LocalDateTime closedAt;
    private String closeReason;
    private String issuedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
