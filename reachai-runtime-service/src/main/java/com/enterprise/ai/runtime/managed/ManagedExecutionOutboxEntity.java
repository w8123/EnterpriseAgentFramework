package com.enterprise.ai.runtime.managed;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("runtime_managed_execution_outbox")
public class ManagedExecutionOutboxEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String eventId;
    private String executionId;
    private String eventType;
    private String payloadJson;
    private String status;
    private Integer attemptCount;
    private LocalDateTime availableAt;
    private String leaseOwner;
    private String leaseToken;
    private LocalDateTime leaseExpiresAt;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime publishedAt;
}
