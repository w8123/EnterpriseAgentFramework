package com.enterprise.ai.agent.platform.control.context;

import lombok.Data;

@Data
public class ContextRuntimeUserMappingQueryRequest {

    private String tenantId;
    private Long platformUserId;
    private String runtimeUserId;
    private Long projectId;
    private String projectCode;
    private String status;
    private Integer limit;
}
