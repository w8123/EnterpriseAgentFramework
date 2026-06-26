package com.enterprise.ai.agent.platform.control.context;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ContextNamespaceResponse {

    private Long id;
    private String namespaceKey;
    private String namespaceType;
    private String tenantId;
    private Long projectId;
    private String projectCode;
    private String ownerType;
    private String ownerId;
    private String displayName;
    private String description;
    private String status;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
