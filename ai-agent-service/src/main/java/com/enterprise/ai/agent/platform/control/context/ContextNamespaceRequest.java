package com.enterprise.ai.agent.platform.control.context;

import lombok.Data;

@Data
public class ContextNamespaceRequest {

    private String namespaceKey;
    private String namespaceType;
    private String tenantId;
    private Long projectId;
    private String projectCode;
    private String ownerType;
    private String ownerId;
    private String displayName;
    private String description;
    private String createdBy;
}
