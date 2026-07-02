package com.enterprise.ai.runtime.workflow.draft;

import com.enterprise.ai.agent.graph.GraphSpec;

import java.util.List;
import java.util.Map;

public record RuntimeWorkflowDraftGenerationView(
        String provider,
        Map<String, Object> canvasSnapshot,
        GraphSpec graphSpec,
        List<String> warnings,
        List<RuntimeWorkflowDraftPlaceholderView> placeholderNodes,
        List<String> validationErrors) {

    public RuntimeWorkflowDraftGenerationView {
        canvasSnapshot = canvasSnapshot == null ? Map.of() : Map.copyOf(canvasSnapshot);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        placeholderNodes = placeholderNodes == null ? List.of() : List.copyOf(placeholderNodes);
        validationErrors = validationErrors == null ? List.of() : List.copyOf(validationErrors);
    }
}
