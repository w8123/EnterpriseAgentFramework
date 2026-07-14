package com.enterprise.ai.runtime.agent;

import java.time.LocalDateTime;
import java.util.List;

public final class RuntimeAgentConfigViews {

    private RuntimeAgentConfigViews() {
    }

    public record AgentConfigVersionView(
            Long id,
            String agentId,
            Integer versionNo,
            String status,
            String runtimeType,
            String systemPrompt,
            String modelInstanceId,
            Integer maxPlanSteps,
            Integer maxWorkflowCalls,
            Integer maxReplans,
            Integer totalTimeoutMs,
            Integer workflowTimeoutMs,
            Integer pageBridgeTimeoutMs,
            Boolean parallelReadOnly,
            String policyProfile,
            String toolCatalogMode,
            String configJson,
            String publishedBy,
            LocalDateTime publishedAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            List<WorkflowToolView> tools) {
    }

    public record AgentConfigDraftRequest(
            String runtimeType,
            String systemPrompt,
            String modelInstanceId,
            Integer maxPlanSteps,
            Integer maxWorkflowCalls,
            Integer maxReplans,
            Integer totalTimeoutMs,
            Integer workflowTimeoutMs,
            Integer pageBridgeTimeoutMs,
            Boolean parallelReadOnly,
            String policyProfile,
            String toolCatalogMode,
            String configJson,
            List<WorkflowToolRequest> tools) {
    }

    public record WorkflowToolRequest(
            String workflowId,
            String toolName,
            String descriptionOverride,
            String inputSchemaOverrideJson,
            String outputSchemaOverrideJson,
            String riskLevel,
            String permissionKey,
            Boolean readOnly,
            Boolean enabled,
            Integer priority) {
    }

    public record WorkflowToolView(
            Long id,
            String agentId,
            Long agentConfigVersionId,
            String workflowId,
            String workflowKeySlug,
            String workflowName,
            String workflowVersion,
            Long workflowVersionId,
            String toolName,
            String descriptionOverride,
            String description,
            String inputSchemaOverrideJson,
            String inputSchemaJson,
            String outputSchemaOverrideJson,
            String outputSchemaJson,
            String riskLevel,
            String permissionKey,
            Boolean readOnly,
            Boolean enabled,
            Integer priority,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    public record PublishRequest(String publishedBy) {
    }
}
