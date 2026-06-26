package com.enterprise.ai.agent.platform.control.context.memory;

import com.enterprise.ai.agent.workflow.AgentEntryEntity;
import com.enterprise.ai.agent.workflow.WorkflowDefinitionEntity;
import org.springframework.util.StringUtils;

/**
 * Typed runtime identity for memory candidate generation; avoids magic strings in metadata.
 */
public record RuntimeMemoryCandidateIdentity(
        String agentId,
        String agentKey,
        String workflowId,
        String workflowKey
) {

    public static RuntimeMemoryCandidateIdentity fromAgent(AgentEntryEntity agent) {
        if (agent == null) {
            return empty();
        }
        return new RuntimeMemoryCandidateIdentity(
                agent.getId(),
                agent.getKeySlug(),
                null,
                null);
    }

    public static RuntimeMemoryCandidateIdentity fromAgentWorkflow(AgentEntryEntity agent,
                                                                   WorkflowDefinitionEntity workflow) {
        return new RuntimeMemoryCandidateIdentity(
                agent == null ? null : agent.getId(),
                agent == null ? null : agent.getKeySlug(),
                workflow == null ? null : workflow.getId(),
                workflow == null ? null : workflow.getKeySlug());
    }

    public static RuntimeMemoryCandidateIdentity empty() {
        return new RuntimeMemoryCandidateIdentity(null, null, null, null);
    }

    public boolean hasWorkflow() {
        return StringUtils.hasText(workflowId) || StringUtils.hasText(workflowKey);
    }
}
