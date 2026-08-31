package com.enterprise.ai.control.a2a.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_a2a_outbox")
public class A2aOutboxEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String eventId;
    private String aggregateType;
    private String aggregateId;
    private String eventType;
    private String resourceRefJson;
    private String status;
    private Integer attemptCount;
    private LocalDateTime nextAttemptAt;
    private String leaseOwner;
    private LocalDateTime leaseUntil;
    private String lastErrorCode;
    private String lastErrorSummary;
    private LocalDateTime createdAt;
    private LocalDateTime deliveredAt;
    private LocalDateTime updatedAt;
}
