package com.enterprise.ai.runtime.workflow.authoring;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.workflow.draft.RuntimeWorkflowDraftEditOperationView;

import java.util.List;
import java.util.Map;

public record WorkflowAuthoringResult(
        Status status,
        String provider,
        String summary,
        List<RuntimeWorkflowDraftEditOperationView> operations,
        GraphSpec graphSpec,
        List<String> warnings,
        List<String> validationErrors,
        int attempts,
        String failureCode,
        List<Map<String, Object>> toolCallSummaries,
        String authoringId) {

    public static final String PROVIDER = "AGENTSCOPE_AUTHORING";

    public enum Status {
        SUCCEEDED,
        FAILED
    }

    public WorkflowAuthoringResult {
        provider = provider == null ? PROVIDER : provider;
        summary = summary == null ? "" : summary;
        operations = operations == null ? List.of() : List.copyOf(operations);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        validationErrors = validationErrors == null ? List.of() : List.copyOf(validationErrors);
        toolCallSummaries = toolCallSummaries == null ? List.of() : List.copyOf(toolCallSummaries);
    }

    public boolean succeeded() {
        return status == Status.SUCCEEDED;
    }
}
