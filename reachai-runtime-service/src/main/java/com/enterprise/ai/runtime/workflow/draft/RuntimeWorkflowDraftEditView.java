package com.enterprise.ai.runtime.workflow.draft;

import com.enterprise.ai.agent.graph.GraphSpec;

import java.util.List;
import java.util.Map;

public record RuntimeWorkflowDraftEditView(
        String status,
        String provider,
        String summary,
        List<RuntimeWorkflowDraftEditOperationView> operations,
        Map<String, Object> canvasSnapshot,
        GraphSpec graphSpec,
        List<String> warnings,
        List<RuntimeWorkflowDraftPlaceholderView> placeholderNodes,
        List<String> validationErrors,
        Integer attempts,
        String failureCode,
        String authoringId) {

    public RuntimeWorkflowDraftEditView {
        status = status == null ? (validationErrors == null || validationErrors.isEmpty() ? "SUCCEEDED" : "FAILED") : status;
        operations = operations == null ? List.of() : List.copyOf(operations);
        canvasSnapshot = canvasSnapshot == null ? Map.of() : Map.copyOf(canvasSnapshot);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        placeholderNodes = placeholderNodes == null ? List.of() : List.copyOf(placeholderNodes);
        validationErrors = validationErrors == null ? List.of() : List.copyOf(validationErrors);
    }

    /** Backward-compatible constructor used by older tests/callers. */
    public RuntimeWorkflowDraftEditView(String provider,
                                        String summary,
                                        List<RuntimeWorkflowDraftEditOperationView> operations,
                                        Map<String, Object> canvasSnapshot,
                                        GraphSpec graphSpec,
                                        List<String> warnings,
                                        List<RuntimeWorkflowDraftPlaceholderView> placeholderNodes,
                                        List<String> validationErrors) {
        this(
                validationErrors == null || validationErrors.isEmpty() ? "SUCCEEDED" : "FAILED",
                provider,
                summary,
                operations,
                canvasSnapshot,
                graphSpec,
                warnings,
                placeholderNodes,
                validationErrors,
                null,
                validationErrors == null || validationErrors.isEmpty() ? null : "VALIDATION_FAILED",
                null);
    }

    public RuntimeWorkflowDraftEditView(String status,
                                        String provider,
                                        String summary,
                                        List<RuntimeWorkflowDraftEditOperationView> operations,
                                        Map<String, Object> canvasSnapshot,
                                        GraphSpec graphSpec,
                                        List<String> warnings,
                                        List<RuntimeWorkflowDraftPlaceholderView> placeholderNodes,
                                        List<String> validationErrors,
                                        Integer attempts,
                                        String failureCode) {
        this(status, provider, summary, operations, canvasSnapshot, graphSpec, warnings, placeholderNodes,
                validationErrors, attempts, failureCode, null);
    }

    public boolean succeeded() {
        return "SUCCEEDED".equalsIgnoreCase(status);
    }
}
