package com.enterprise.ai.runtime.agent;

import java.time.LocalDateTime;

public record RuntimeAgentEntryView(
        String id,
        Long projectId,
        String projectCode,
        String keySlug,
        String name,
        String description,
        String agentKind,
        String visibility,
        String systemPrompt,
        String modelInstanceId,
        String allowedRolesJson,
        String entryConfigJson,
        Boolean enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
