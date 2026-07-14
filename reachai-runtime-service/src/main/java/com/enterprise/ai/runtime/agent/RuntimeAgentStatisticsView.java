package com.enterprise.ai.runtime.agent;

public record RuntimeAgentStatisticsView(
        long totalAgents,
        long enabledAgents,
        long workflowToolAgents,
        long activeWorkflowTools) {
}
