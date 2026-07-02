package com.enterprise.ai.runtime.workflow;

public record RuntimeAgentWorkflowBindingResolveRequest(
        String agentId,
        String projectCode,
        String pageKey,
        String route,
        String actionKey,
        String intentType) {
}
