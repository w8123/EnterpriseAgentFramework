package com.enterprise.ai.agent.platform.control.context;

import lombok.Data;

@Data
public class ContextItemListRequest {

    private String tenantId;
    private String projectCode;
    private Long projectId;
    /** Required: PROJECT_DEV or RUNTIME_USER */
    private String memoryLane;
    private Long namespaceId;
    private String itemType;
    private String status;
    private String keyword;
    private Integer limit;
    private Integer offset;
}
