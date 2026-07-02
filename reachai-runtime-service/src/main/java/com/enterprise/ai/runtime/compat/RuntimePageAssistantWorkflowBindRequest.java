package com.enterprise.ai.runtime.compat;

import java.util.List;

public record RuntimePageAssistantWorkflowBindRequest(
        Long projectId,
        String projectCode,
        String agentId,
        String pageKey,
        String routePattern,
        List<String> actionKeys
) {
}
