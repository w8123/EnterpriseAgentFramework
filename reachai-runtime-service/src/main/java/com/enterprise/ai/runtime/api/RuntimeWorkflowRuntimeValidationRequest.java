package com.enterprise.ai.runtime.api;

public record RuntimeWorkflowRuntimeValidationRequest(
        String workflowId,
        String graphSpecJson,
        String executionEngine,
        String defaultModelInstanceId) {

    public RuntimeWorkflowRuntimeValidationRequest(String workflowId,
                                                    String graphSpecJson,
                                                    String executionEngine) {
        this(workflowId, graphSpecJson, executionEngine, null);
    }
}
