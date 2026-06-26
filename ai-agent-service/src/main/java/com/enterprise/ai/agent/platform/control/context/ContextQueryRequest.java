package com.enterprise.ai.agent.platform.control.context;

import lombok.Data;

import java.util.List;

@Data
public class ContextQueryRequest {

    private String tenantId;
    private String projectCode;
    private Long projectId;
    private String userId;
    private String agentId;
    private String workflowId;
    private String pageInstanceId;
    private String sessionId;
    /** Required: PROJECT_DEV or RUNTIME_USER */
    private String memoryLane;
    private List<String> itemTypes;
    private String query;
    /** Optional: KEYWORD (default) or HYBRID. HYBRID enables deterministic token fallback. */
    private String retrievalMode;
    private Integer topK;
    private String sortBy;
    private String actorType;
    private String actorId;
    private String traceId;
}
