package com.enterprise.ai.runtime.workflow.proposal;

import com.enterprise.ai.agent.graph.GraphSpec;

import java.util.List;
import java.util.Map;

public record RuntimeWorkflowProposalEditRequest(
        String workflowId,
        String workflowName,
        String instruction,
        String projectCode,
        String workflowKind,
        String modelInstanceId,
        Map<String, Object> currentCanvas,
        GraphSpec currentGraphSpec,
        List<String> selectedNodeIds,
        List<String> selectedEdgeIds,
        List<RuntimeWorkflowProposalResourceView> tools,
        List<RuntimeWorkflowProposalResourceView> capabilities,
        List<RuntimeWorkflowProposalResourceView> knowledgeBases) {
}
