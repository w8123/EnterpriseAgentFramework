package com.enterprise.ai.agent.platform.control.context.memory;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RuntimeMemoryExtractionRequest {

    private String modelInstanceId;
    private String tenantId;
    private String projectCode;
    private Long projectId;
    private String userId;
    private String sessionId;
    private String agentId;
    private String agentKey;
    private String workflowId;
    private String workflowKey;
    private String pageInstanceId;
    private String origin;
    private String traceId;
    private String userMessage;
    private String assistantReply;
}
