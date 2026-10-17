package com.enterprise.ai.control.memory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Content-free audit of personal memory changes. */
@Data
@TableName("control_personal_memory_audit")
public class PersonalMemoryAuditEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String ownerId;
    private Long memoryId;
    private String eventType;
    private String actorType;
    private String agentId;
    private String sessionId;
    private String traceId;
    private String decision;
    private String reason;
    private String metadataJson;
    private LocalDateTime createdAt;
}
