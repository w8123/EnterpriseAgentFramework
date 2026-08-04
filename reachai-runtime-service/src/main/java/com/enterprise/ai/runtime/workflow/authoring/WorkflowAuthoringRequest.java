package com.enterprise.ai.runtime.workflow.authoring;

import com.enterprise.ai.agent.graph.GraphSpec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record WorkflowAuthoringRequest(
        String workflowId,
        String workflowName,
        String projectCode,
        String workflowKind,
        String instruction,
        String modelInstanceId,
        GraphSpec currentGraphSpec,
        List<String> selectedNodeIds,
        List<String> selectedEdgeIds,
        Map<String, Object> availableResources) {

    public WorkflowAuthoringRequest {
        selectedNodeIds = selectedNodeIds == null
                ? List.of()
                : selectedNodeIds.stream().filter(Objects::nonNull).toList();
        selectedEdgeIds = selectedEdgeIds == null
                ? List.of()
                : selectedEdgeIds.stream().filter(Objects::nonNull).toList();
        // Map.copyOf forbids null values; keep a LinkedHashMap snapshot and drop null entries.
        availableResources = copyWithoutNullEntries(availableResources);
    }

    private static Map<String, Object> copyWithoutNullEntries(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copied = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                copied.put(entry.getKey(), entry.getValue());
            }
        }
        return Map.copyOf(copied);
    }
}
