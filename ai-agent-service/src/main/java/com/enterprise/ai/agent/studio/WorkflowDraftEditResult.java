package com.enterprise.ai.agent.studio;

import com.enterprise.ai.agent.graph.AgentGraphSpec;

import java.util.List;
import java.util.Map;

public record WorkflowDraftEditResult(
        String provider,
        String summary,
        List<WorkflowDraftEditOperation> operations,
        Map<String, Object> canvasSnapshot,
        AgentGraphSpec graphSpec,
        List<String> warnings,
        List<WorkflowDraftPlaceholder> placeholderNodes,
        List<String> validationErrors) {

    public WorkflowDraftEditResult {
        operations = operations == null ? List.of() : List.copyOf(operations);
        canvasSnapshot = canvasSnapshot == null ? Map.of() : Map.copyOf(canvasSnapshot);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        placeholderNodes = placeholderNodes == null ? List.of() : List.copyOf(placeholderNodes);
        validationErrors = validationErrors == null ? List.of() : List.copyOf(validationErrors);
    }
}
