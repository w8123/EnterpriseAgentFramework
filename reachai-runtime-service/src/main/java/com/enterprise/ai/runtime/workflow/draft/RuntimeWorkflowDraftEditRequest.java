package com.enterprise.ai.runtime.workflow.draft;

import java.util.List;
import java.util.Map;

public record RuntimeWorkflowDraftEditRequest(
        String agentId,
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
}
