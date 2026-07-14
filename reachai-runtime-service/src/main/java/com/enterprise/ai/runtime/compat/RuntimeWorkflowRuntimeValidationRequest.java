package com.enterprise.ai.runtime.compat;

public record RuntimeWorkflowRuntimeValidationRequest(
        String workflowId,
        String graphSpecJson,
        String runtimeType,
        String defaultModelInstanceId) {

    public RuntimeWorkflowRuntimeValidationRequest(String workflowId,
                                                    String graphSpecJson,
                                                    String runtimeType) {
        this(workflowId, graphSpecJson, runtimeType, null);
    }
}
