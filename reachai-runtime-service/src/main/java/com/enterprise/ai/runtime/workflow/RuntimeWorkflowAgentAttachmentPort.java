package com.enterprise.ai.runtime.workflow;

import java.util.Map;

/** Workflow delivery requests an Agent attachment without depending on Agent configuration storage. */
public interface RuntimeWorkflowAgentAttachmentPort {
    AttachmentResult attach(Long projectId, AttachRequest request);
    AttachmentResult attachPageAssistantOnly(String workflowId, PageAssistantAttachRequest request);

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
            String replaceWorkflowId
    ) {
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
            String replaceWorkflowId
    ) {
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

    public record AgentRef(String id, String keySlug) {
    }

    public record WorkflowRef(String id, String keySlug, String workflowKind) {
    }

    public record ActiveConfigRef(Long id, Integer version, String status) {
    }

}
