package com.enterprise.ai.runtime.compat;

public record RuntimeWorkflowRuntimeValidationRequest(
        String workflowId,
        String graphSpecJson,
        String runtimeType) {
}
