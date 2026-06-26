package com.enterprise.ai.agent.platform.control.context.runtime;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class RuntimeContextIdentity {

    private String tenantId;
    private Long projectId;
    private String projectCode;
    private String appId;
    private String userId;
    private String externalUserId;
    private String globalUserId;
    private String sessionId;
    private String agentId;
    private String agentKey;
    private String workflowId;
    private String workflowKey;
    private String pageInstanceId;
    private String query;
    private String traceId;
    private String origin;
    private String runtimePlacement;
    private List<String> roles;
}
