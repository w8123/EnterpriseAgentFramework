package com.enterprise.ai.agent.platform.control.context;

import lombok.Data;

@Data
public class ContextRuntimeUserMappingCreateRequest {

    private String tenantId;
    private Long platformUserId;
    private String runtimeUserId;
    private String globalUserId;
    private String externalUserId;
    private Long projectId;
    private String projectCode;
    private String createdBy;
}
