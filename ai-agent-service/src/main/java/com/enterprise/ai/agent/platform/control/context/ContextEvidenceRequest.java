package com.enterprise.ai.agent.platform.control.context;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class ContextEvidenceRequest {

    private String evidenceType;
    private String evidenceRef;
    private String evidenceExcerpt;
    private String traceId;
    private BigDecimal confidence;
    private String metadataJson;
}
