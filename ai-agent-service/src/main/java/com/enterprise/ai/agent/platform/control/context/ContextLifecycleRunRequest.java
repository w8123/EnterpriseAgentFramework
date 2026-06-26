package com.enterprise.ai.agent.platform.control.context;

import lombok.Data;

@Data
public class ContextLifecycleRunRequest {

    private String tenantId;
    private String projectCode;
    private Long projectId;
    private Boolean dryRun;
    private Boolean includeRuntimeUserItems;
    private Integer candidateExpireLimit;
    private Integer itemStaleLimit;
}
