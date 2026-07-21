package com.enterprise.ai.runtime.agent;

import java.time.LocalDateTime;

/**
 * Lightweight Agent identity for Runtime execution. Does not carry admin display fields
 * and must never trigger resolveDisplayConfig / listTools.
 */
public record RuntimeAgentExecutionView(
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
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static RuntimeAgentExecutionView fromEntity(RuntimeAgentEntity entity) {
        if (entity == null) {
            return null;
        }
        return new RuntimeAgentExecutionView(
                entity.getId(),
                entity.getProjectId(),
                entity.getProjectCode(),
                entity.getKeySlug(),
                entity.getName(),
                entity.getDescription(),
                entity.getVisibility(),
                entity.getAllowedRolesJson(),
                entity.getEnabled(),
                entity.getActiveConfigVersionId(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    /** Compatibility bridge for Supervisor / RunOps APIs that still accept {@link RuntimeAgentView}. */
    public RuntimeAgentView toRuntimeAgentView(int workflowToolCount, String runtimeType) {
        return new RuntimeAgentView(
                id,
                projectId,
                projectCode,
                keySlug,
                name,
                description,
                visibility,
                allowedRolesJson,
                enabled,
                activeConfigVersionId,
                activeConfigVersionId,
                null,
                "ACTIVE",
                runtimeType,
                workflowToolCount,
                createdAt,
                updatedAt);
    }
}
