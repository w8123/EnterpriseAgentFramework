package com.enterprise.ai.runtime.workflow;

import java.util.Map;

/** Workflow delivery requests an Agent attachment without depending on Agent configuration storage. */
public interface RuntimeWorkflowAgentAttachmentPort {
    AttachmentResult attach(Long projectId, AttachRequest request);

    /**
     * Attaches within a project the caller already read from Runtime-owned records (for example the
     * Agent's own project), so no Capability project lookup is needed.
     */
    AttachmentResult attachInKnownProject(Long projectId, String projectCode, AttachRequest request);
    /** Page Workbench delivery only; it still auto-selects the target without a revision (see docs/status.md). */
    AttachmentResult attachPageAssistantOnly(String workflowId, PageAssistantAttachRequest request);

    /** Signed people/AI path: requires the page copilot baseRevision, or createTarget when it does not exist. */
    AttachmentResult attachPageAssistantReviewed(String workflowId, PageAssistantAttachRequest request);

    /** What a person or AI must confirm before syncing a published Page Assistant into the project page copilot. */
    PageAssistantContext pageAssistantContext(String workflowId, Long projectId, String projectCode);

    public record AttachRequest(
            String workflowId,
            String agentId,
            String agentKeySlug,
            String modelInstanceId,
            String publishedBy,
            String toolName,
            String descriptionOverride,
            Map<String, Object> inputSchema,
            Map<String, Object> outputSchema,
            String riskLevel,
            String permissionKey,
            Boolean readOnly,
            Integer priority,
            String replaceWorkflowId,
            String baseRevision
    ) {
        public AttachRequest(String workflowId, String agentId, String agentKeySlug, String modelInstanceId, String publishedBy,
                             String toolName, String descriptionOverride, Map<String, Object> inputSchema, Map<String, Object> outputSchema,
                             String riskLevel, String permissionKey, Boolean readOnly, Integer priority, String replaceWorkflowId) {
            this(workflowId, agentId, agentKeySlug, modelInstanceId, publishedBy, toolName, descriptionOverride, inputSchema,
                    outputSchema, riskLevel, permissionKey, readOnly, priority, replaceWorkflowId, null);
        }
        public AttachRequest(String workflowId,
                             String agentId,
                             String agentKeySlug,
                             String modelInstanceId,
                             String publishedBy) {
            this(workflowId, agentId, agentKeySlug, modelInstanceId, publishedBy,
                    null, null, null, null, null, null, null, null, null);
        }

        public AttachRequest(
                String workflowId,
                String agentId,
                String agentKeySlug,
                String modelInstanceId,
                String publishedBy,
                String toolName,
                String descriptionOverride,
                Map<String, Object> inputSchema,
                Map<String, Object> outputSchema,
                String riskLevel,
                String permissionKey,
                Boolean readOnly,
                Integer priority) {
            this(workflowId, agentId, agentKeySlug, modelInstanceId, publishedBy,
                    toolName, descriptionOverride, inputSchema, outputSchema,
                    riskLevel, permissionKey, readOnly, priority, null);
        }
    }

    public record PageAssistantAttachRequest(
            Long projectId,
            String projectCode,
            String agentId,
            String modelInstanceId,
            String publishedBy,
            String replaceWorkflowId,
            String baseRevision,
            boolean createTarget
    ) {
        public PageAssistantAttachRequest(
                Long projectId,
                String projectCode,
                String agentId,
                String modelInstanceId,
                String publishedBy,
                String replaceWorkflowId) {
            this(projectId, projectCode, agentId, modelInstanceId, publishedBy, replaceWorkflowId, null, false);
        }

        public PageAssistantAttachRequest(
                Long projectId,
                String projectCode,
                String agentId,
                String modelInstanceId,
                String publishedBy) {
            this(projectId, projectCode, agentId, modelInstanceId, publishedBy, null);
        }
    }

    public record AttachmentResult(
            String schema,
            Long projectId,
            String projectCode,
            AgentRef agent,
            WorkflowRef workflow,
            ActiveConfigRef activeConfig,
            String toolName,
            boolean created,
            boolean reused,
            String replacedWorkflowId
    ) {
        public AttachmentResult(
                String schema,
                Long projectId,
                String projectCode,
                AgentRef agent,
                WorkflowRef workflow,
                ActiveConfigRef activeConfig,
                String toolName,
                boolean created,
                boolean reused) {
            this(schema, projectId, projectCode, agent, workflow, activeConfig,
                    toolName, created, reused, null);
        }
    }

    /**
     * targetExists=false means attaching initializes the default page copilot (createTarget=true);
     * otherwise the attach must carry this revision.
     */
    public record PageAssistantContext(
            int contractVersion,
            Long projectId,
            String projectCode,
            String workflowId,
            String workflowKeySlug,
            String workflowName,
            String workflowStatus,
            Long workflowActiveVersionId,
            boolean targetExists,
            String agentId,
            String agentKeySlug,
            String revision,
            Long activeConfigId,
            Integer activeConfigVersionNo,
            String activeModelInstanceId,
            boolean attached,
            Long attachedWorkflowVersionId
    ) {
    }

    public record AgentRef(String id, String keySlug) {
    }

    public record WorkflowRef(String id, String keySlug, String workflowKind) {
    }

    public record ActiveConfigRef(Long id, Integer version, String status) {
    }

}
