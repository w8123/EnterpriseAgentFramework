package com.enterprise.ai.runtime.managed;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("runtime_managed_execution_event")
public class ManagedExecutionEventEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String executionId;
    private Integer sequence;
    private String eventId;
    private String eventType;
    private String phase;
    private String visibility;
    private String persistence;
    private String message;
    private String dataJson;
    private String payloadSha256;
    private LocalDateTime occurredAt;
    private LocalDateTime receivedAt;
}
