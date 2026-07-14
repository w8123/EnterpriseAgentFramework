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
        LocalDateTime updatedAt) {
}
