package com.enterprise.ai.runtime.workflow.proposal;

import com.enterprise.ai.agent.graph.GraphSpec;

import java.util.List;
import java.util.Map;

public record RuntimeWorkflowProposalGenerationView(
        String provider,
        Map<String, Object> canvasSnapshot,
        GraphSpec graphSpec,
        List<String> warnings,
        List<RuntimeWorkflowProposalPlaceholderView> placeholderNodes,
        List<String> validationErrors) {

    public RuntimeWorkflowProposalGenerationView {
        canvasSnapshot = canvasSnapshot == null ? Map.of() : Map.copyOf(canvasSnapshot);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        placeholderNodes = placeholderNodes == null ? List.of() : List.copyOf(placeholderNodes);
        validationErrors = validationErrors == null ? List.of() : List.copyOf(validationErrors);
    }
}
