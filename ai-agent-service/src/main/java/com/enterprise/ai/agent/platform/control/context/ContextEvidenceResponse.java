package com.enterprise.ai.agent.platform.control.context;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class ContextEvidenceResponse {

    private Long id;
    private Long itemId;
    private String evidenceType;
    private String evidenceRef;
    private String evidenceExcerpt;
    private String traceId;
    private BigDecimal confidence;
    private String metadataJson;
    private LocalDateTime createdAt;
}
