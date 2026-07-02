package com.enterprise.ai.runtime.workflow;

import java.time.LocalDateTime;

public record RuntimeAgentWorkflowBindingView(
        Long id,
        String agentId,
        String workflowId,
        String projectCode,
        String bindingType,
        String pageKey,
        String routePattern,
        String actionKey,
        String intentType,
        Integer priority,
        Boolean enabled,
        String guardConfigJson,
        String metadataJson,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
