package com.enterprise.ai.agent.workflow;

public record AgentWorkflowResolveRequest(
        String agentId,
        String projectCode,
        String pageKey,
        String route,
        String actionKey,
        String intentType) {
}
