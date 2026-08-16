package com.enterprise.ai.control.context;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_memory_erasure_request")
public class MemoryErasureRequestEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String requestId;
    private String clientRequestId;
    private String tenantId;
    private String runtimeUserId;
    private String runtimeUserHash;
    private String reasonCode;
    private String referenceId;
    private String status;
    private Integer attemptCount;
    private LocalDateTime nextAttemptAt;
    private String leaseOwner;
    private LocalDateTime leaseExpiresAt;
    private String lastFailureCode;
    private String requestedByHash;
    private LocalDateTime automatedCompletedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
