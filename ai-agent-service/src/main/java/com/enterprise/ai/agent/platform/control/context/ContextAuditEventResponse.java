package com.enterprise.ai.agent.platform.control.context;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ContextAuditEventResponse {

    private Long id;
    private String eventType;
    private Long itemId;
    private Long namespaceId;
    private String actorType;
    private String actorId;
    private String tenantId;
    private Long projectId;
    private String projectCode;
    private String agentId;
    private String workflowId;
    private String sessionId;
    private String traceId;
    private String decision;
    private String reason;
    private String metadataJson;
    private LocalDateTime createdAt;
}
