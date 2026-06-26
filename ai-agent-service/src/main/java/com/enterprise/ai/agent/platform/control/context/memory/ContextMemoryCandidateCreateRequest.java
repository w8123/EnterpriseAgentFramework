package com.enterprise.ai.agent.platform.control.context.memory;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class ContextMemoryCandidateCreateRequest {

    private String tenantId;
    private Long projectId;
    private String projectCode;
    private Long namespaceId;
    private String namespaceKey;
    /**
     * Defaults to RUNTIME_USER for existing embed/runtime memory flows.
     * External AI Coding context-scan submissions set PROJECT_DEV.
     */
    private String memoryLane;
    private String candidateType;
    private String title;
    private String content;
    private String summary;
    private String reason;
    private String sourceType;
    private String sourceRef;
    private String traceId;
    private String sessionId;
    private String userId;
    private String externalUserId;
    private String globalUserId;
    private String agentId;
    private String agentKey;
    private String workflowId;
    private String workflowKey;
    private String pageInstanceId;
    private String origin;
    private BigDecimal confidence;
    private String trustLevel;
    private String visibility;
    private String proposedBy;
    private LocalDateTime expiresAt;
    private String metadataJson;
}
