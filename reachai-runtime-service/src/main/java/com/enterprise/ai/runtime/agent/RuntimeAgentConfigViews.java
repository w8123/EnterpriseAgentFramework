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
            List<WorkflowToolView> tools,
            List<SkillBindingView> skills,
            List<RemoteAgentBindingView> remoteAgents) {

        /** Source-compatible constructor for callers without remote A2A bindings. */
        public AgentConfigVersionView(
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
                List<WorkflowToolView> tools,
                List<SkillBindingView> skills) {
            this(id, agentId, versionNo, status, runtimeType, systemPrompt, modelInstanceId,
                    maxPlanSteps, maxWorkflowCalls, maxReplans, totalTimeoutMs, workflowTimeoutMs,
                    pageBridgeTimeoutMs, parallelReadOnly, policyProfile, toolCatalogMode, configJson,
                    publishedBy, publishedAt, createdAt, updatedAt, tools, skills, List.of());
        }

        /** Source-compatible constructor for callers that do not configure Skills. */
        public AgentConfigVersionView(
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
            this(id, agentId, versionNo, status, runtimeType, systemPrompt, modelInstanceId,
                    maxPlanSteps, maxWorkflowCalls, maxReplans, totalTimeoutMs, workflowTimeoutMs,
                    pageBridgeTimeoutMs, parallelReadOnly, policyProfile, toolCatalogMode, configJson,
                    publishedBy, publishedAt, createdAt, updatedAt, tools, List.of(), List.of());
        }
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
            List<WorkflowToolRequest> tools,
            List<SkillBindingRequest> skills,
            List<RemoteAgentBindingRequest> remoteAgents) {

        /** Source-compatible constructor for callers without remote A2A bindings. */
        public AgentConfigDraftRequest(
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
                List<WorkflowToolRequest> tools,
                List<SkillBindingRequest> skills) {
            this(runtimeType, systemPrompt, modelInstanceId, maxPlanSteps, maxWorkflowCalls,
                    maxReplans, totalTimeoutMs, workflowTimeoutMs, pageBridgeTimeoutMs,
                    parallelReadOnly, policyProfile, toolCatalogMode, configJson, tools, skills, null);
        }

        /** Source-compatible constructor for existing Workflow-only callers. */
        public AgentConfigDraftRequest(
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
            this(runtimeType, systemPrompt, modelInstanceId, maxPlanSteps, maxWorkflowCalls,
                    maxReplans, totalTimeoutMs, workflowTimeoutMs, pageBridgeTimeoutMs,
                    parallelReadOnly, policyProfile, toolCatalogMode, configJson, tools, null, null);
        }
    }

    /** Control-attested immutable snapshot of one approved A2A remote revision. */
    public record RemoteAgentBindingRequest(
            Long principalId,
            Long remoteAgentId,
            Long remoteAgentRevisionId,
            String remoteAgentKey,
            String toolName,
            String description,
            List<String> allowedSkillIds,
            List<String> inputModes,
            List<String> outputModes,
            String riskLevel,
            String permissionKey,
            Long timeoutMs,
            Boolean enabled,
            Integer priority) {
    }

    public record RemoteAgentBindingView(
            Long id,
            String agentId,
            Long agentConfigVersionId,
            Long principalId,
            Long remoteAgentId,
            Long remoteAgentRevisionId,
            String remoteAgentKey,
            String toolName,
            String description,
            List<String> allowedSkillIds,
            List<String> inputModes,
            List<String> outputModes,
            String riskLevel,
            String permissionKey,
            Long timeoutMs,
            Boolean enabled,
            Integer priority,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    /**
     * Control-attested published Skill version snapshot. Client-facing Control
     * APIs accept only skillId/skillVersionId plus binding policy and replace
     * the catalog-owned fields before forwarding this request to Runtime.
     */
    public record SkillBindingRequest(
            Long skillId,
            Long skillVersionId,
            String publisher,
            String name,
            String displayName,
            String version,
            String catalogStatus,
            String sourceSha256,
            String contentTreeSha256,
            String sourceRoot,
            String packageManifestJson,
            String riskReportJson,
            Boolean hasScripts,
            String activationMode,
            String scriptPolicy,
            Boolean required,
            Boolean enabled,
            Integer priority,
            String visibility,
            String projectCode) {
    }

    public record SkillBindingView(
            Long id,
            String agentId,
            Long agentConfigVersionId,
            Long skillId,
            Long skillVersionId,
            String publisher,
            String name,
            String displayName,
            String version,
            String sourceSha256,
            String contentTreeSha256,
            String sourceRoot,
            String packageManifestJson,
            String riskReportJson,
            Boolean hasScripts,
            String activationMode,
            String scriptPolicy,
            Boolean required,
            Boolean enabled,
            Integer priority,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            String visibility,
            String projectCode) {
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
