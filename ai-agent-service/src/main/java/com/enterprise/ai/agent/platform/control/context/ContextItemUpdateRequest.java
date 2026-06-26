package com.enterprise.ai.agent.platform.control.context;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class ContextItemUpdateRequest {

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
    private String updatedBy;
    private String tenantId;
    private String projectCode;
    private String memoryLane;
}
