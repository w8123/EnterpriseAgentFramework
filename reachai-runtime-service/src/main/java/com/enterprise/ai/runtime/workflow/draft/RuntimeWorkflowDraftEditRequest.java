package com.enterprise.ai.runtime.workflow.draft;

import com.enterprise.ai.agent.graph.GraphSpec;

import java.util.List;
import java.util.Map;

public record RuntimeWorkflowDraftEditRequest(
        String workflowId,
        String agentId,
        String agentName,
        String instruction,
        String projectCode,
        String modelInstanceId,
        Map<String, Object> currentCanvas,
        GraphSpec currentGraphSpec,
        List<String> selectedNodeIds,
        List<String> selectedEdgeIds,
        List<RuntimeWorkflowDraftResourceView> tools,
        List<RuntimeWorkflowDraftResourceView> capabilities,
        List<RuntimeWorkflowDraftResourceView> knowledgeBases) {

    public RuntimeWorkflowDraftEditRequest(String agentId,
                                           String agentName,
                                           String instruction,
                                           String projectCode,
                                           String modelInstanceId,
                                           Map<String, Object> currentCanvas,
                                           List<String> selectedNodeIds,
                                           List<String> selectedEdgeIds,
                                           List<RuntimeWorkflowDraftResourceView> tools,
                                           List<RuntimeWorkflowDraftResourceView> capabilities,
                                           List<RuntimeWorkflowDraftResourceView> knowledgeBases) {
        this(null, agentId, agentName, instruction, projectCode, modelInstanceId, currentCanvas, null,
                selectedNodeIds, selectedEdgeIds, tools, capabilities, knowledgeBases);
    }
}
