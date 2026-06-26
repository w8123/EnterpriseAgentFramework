package com.enterprise.ai.agent.platform.control.context;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class ContextItemCreateRequest {

    private String namespaceKey;
    private Long namespaceId;
    private String itemType;
    private String memoryLane;
    private String title;
    private String content;
    private String summary;
    private String metadataJson;
    private String sourceType;
    private String sourceRef;
    private BigDecimal confidence;
    private String trustLevel;
    private String visibility;
    private LocalDateTime effectiveFrom;
    private LocalDateTime expiresAt;
    private LocalDateTime staleAfter;
    private String tenantId;
    private Long projectId;
    private String projectCode;
    private String createdBy;
    /** Identity scope for RUNTIME_USER create/query alignment. */
    private String userId;
    private String sessionId;
    private String agentId;
    private String pageInstanceId;
    private String workflowId;
    private List<ContextBindingRequest> bindings;
    private List<ContextEvidenceRequest> evidence;
}
