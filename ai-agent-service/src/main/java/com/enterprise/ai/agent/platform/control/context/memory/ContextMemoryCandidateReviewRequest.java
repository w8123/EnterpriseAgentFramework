package com.enterprise.ai.agent.platform.control.context.memory;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class ContextMemoryCandidateReviewRequest {

    private String tenantId;
    private String userId;
    private Long projectId;
    private String projectCode;
    private String memoryLane;
    private String reviewedBy;
    private String reviewReason;
    private BigDecimal confidence;
    private String trustLevel;
}
