package com.enterprise.ai.agent.platform.control.context;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class ContextItemResponse {

    private Long id;
    private String itemKey;
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
    private String status;
    private LocalDateTime effectiveFrom;
    private LocalDateTime expiresAt;
    private LocalDateTime lastVerifiedAt;
    private LocalDateTime staleAfter;
    private String createdBy;
    private String updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
