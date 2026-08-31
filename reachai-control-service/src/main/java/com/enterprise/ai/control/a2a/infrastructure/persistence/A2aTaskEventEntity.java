package com.enterprise.ai.control.a2a.infrastructure.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_a2a_task_event")
public class A2aTaskEventEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskRefId;
    private Long sequenceNo;
    private String eventId;
    private String eventType;
    private String fromState;
    private String toState;
    private String actorType;
    private String actorId;
    private String resourceType;
    private String resourceId;
    private String safeSummary;
    private String safePayloadJson;
    private Long runtimeSequence;
    private String traceId;
    private LocalDateTime createdAt;
}
