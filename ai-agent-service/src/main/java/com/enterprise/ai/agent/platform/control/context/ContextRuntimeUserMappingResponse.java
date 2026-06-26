package com.enterprise.ai.agent.platform.control.context;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ContextRuntimeUserMappingResponse {

    private Long id;
    private String tenantId;
    private Long platformUserId;
    private String runtimeUserId;
    private String globalUserId;
    private String externalUserId;
    private Long projectId;
    private String projectCode;
    private String status;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
}
