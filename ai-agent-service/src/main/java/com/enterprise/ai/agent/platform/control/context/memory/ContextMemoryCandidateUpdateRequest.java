package com.enterprise.ai.agent.platform.control.context.memory;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class ContextMemoryCandidateUpdateRequest {

    private String tenantId;
    private String userId;
    private Long projectId;
    private String projectCode;
    private String memoryLane;
    private String updatedBy;
    private String updateReason;

    private Long namespaceId;
    private String namespaceKey;
    private String candidateType;
    private String title;
    private String content;
    private String summary;
    private String reason;
    private String sourceType;
    private String sourceRef;
    private String workflowId;
    private String workflowKey;
    private String pageInstanceId;
    private String origin;
    private BigDecimal confidence;
    private String trustLevel;
    private String visibility;
    private LocalDateTime expiresAt;
    private String metadataJson;
}
