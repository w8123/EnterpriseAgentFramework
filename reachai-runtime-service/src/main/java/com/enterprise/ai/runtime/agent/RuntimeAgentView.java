package com.enterprise.ai.runtime.agent;

import java.time.LocalDateTime;

public record RuntimeAgentView(
        String id,
        Long projectId,
        String projectCode,
        String keySlug,
        String name,
        String description,
        String visibility,
        String allowedRolesJson,
        Boolean enabled,
        Long activeConfigVersionId,
        Long displayConfigVersionId,
        Integer displayConfigVersionNo,
        String configStatus,
        String runtimeType,
        Integer workflowToolCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        /** Enabled ACTIVE-config Workflow tools pinned to a version other than the current published one; null when not computed. */
        Integer staleWorkflowToolCount) {

    public RuntimeAgentView(String id, Long projectId, String projectCode, String keySlug, String name,
                            String description, String visibility, String allowedRolesJson, Boolean enabled,
                            Long activeConfigVersionId, Long displayConfigVersionId, Integer displayConfigVersionNo,
                            String configStatus, String runtimeType, Integer workflowToolCount,
                            LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(id, projectId, projectCode, keySlug, name, description, visibility, allowedRolesJson, enabled,
                activeConfigVersionId, displayConfigVersionId, displayConfigVersionNo, configStatus, runtimeType,
                workflowToolCount, createdAt, updatedAt, null);
    }
}
