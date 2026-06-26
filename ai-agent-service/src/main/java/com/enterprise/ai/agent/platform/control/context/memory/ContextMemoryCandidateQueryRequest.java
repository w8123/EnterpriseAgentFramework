package com.enterprise.ai.agent.platform.control.context.memory;

import lombok.Data;

@Data
public class ContextMemoryCandidateQueryRequest {

    private String tenantId;
    private String userId;
    private Long projectId;
    private String projectCode;
    private String memoryLane;
    private String status;
    private String traceId;
    private Long namespaceId;
    private String candidateType;
    private String sourceType;
    private String pageInstanceId;
    private String origin;
    private Boolean includeExpired;
    private Integer limit;
}
