package com.enterprise.ai.runtime.agent;

import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.AgentConfigVersionView;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowToolCatalogQuery.Entry;

import java.util.List;
import java.util.Objects;

/**
 * Read models that compare the Workflow version pinned by an Agent's ACTIVE
 * configuration with the Workflow's current published version. Published Agent
 * configurations stay immutable; these views only make a stale pin visible.
 */
public final class RuntimeAgentWorkflowFreshnessViews {

    private RuntimeAgentWorkflowFreshnessViews() {
    }

    /**
     * A pin is stale when the Workflow currently has a different executable
     * published version than the one the ACTIVE Agent configuration runs.
     */
    public static boolean isStale(Long pinnedWorkflowVersionId, Entry current) {
        return current != null
                && current.activePublishedWorkflow()
                && current.version().executable()
                && !Objects.equals(current.version().id(), pinnedWorkflowVersionId);
    }

    public record WorkflowPinFreshness(
            String workflowId,
            String workflowKeySlug,
            String workflowName,
            String toolName,
            Long pinnedWorkflowVersionId,
            String pinnedWorkflowVersion,
            Long latestWorkflowVersionId,
            String latestWorkflowVersion,
            boolean stale) {
    }

    public record AgentWorkflowFreshnessView(
            String agentId,
            String agentName,
            String keySlug,
            Long projectId,
            String projectCode,
            Boolean agentEnabled,
            Long activeConfigVersionId,
            Integer activeConfigVersionNo,
            int staleCount,
            List<WorkflowPinFreshness> tools) {
    }

    public record WorkflowAgentReferenceView(
            String agentId,
            String agentName,
            String keySlug,
            Long projectId,
            String projectCode,
            Boolean agentEnabled,
            Long activeConfigVersionId,
            Integer activeConfigVersionNo,
            String toolName,
            Long pinnedWorkflowVersionId,
            String pinnedWorkflowVersion,
            Long latestWorkflowVersionId,
            String latestWorkflowVersion,
            boolean stale) {
    }

    public record WorkflowAgentReferencesView(
            String workflowId,
            Long latestWorkflowVersionId,
            String latestWorkflowVersion,
            int staleCount,
            List<WorkflowAgentReferenceView> agents) {
    }

    public record PublishLatestWorkflowsRequest(Long expectedActiveConfigVersionId, String publishedBy) {
    }

    /**
     * {@code published=false} means the ACTIVE configuration already used every
     * current Workflow version, so no new Agent configuration was created.
     */
    public record PublishLatestWorkflowsResult(
            boolean published,
            AgentConfigVersionView config,
            List<WorkflowPinFreshness> updatedTools) {
    }
}
