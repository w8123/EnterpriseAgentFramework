package com.enterprise.ai.control.context;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_context_audit_event")
public class ContextAuditEventEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String eventType;

    private Long itemId;

    private Long namespaceId;

    private String actorType;

    private String actorId;

    private String tenantId;

    private Long projectId;

    private String projectCode;

    private String workflowId;

    private String sessionId;

    private String traceId;

    private String decision;

    private String reason;

    private String metadataJson;

    private LocalDateTime createdAt;
}
