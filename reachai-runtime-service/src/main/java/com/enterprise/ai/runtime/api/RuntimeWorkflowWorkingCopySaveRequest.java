package com.enterprise.ai.runtime.api;

public record RuntimeWorkflowWorkingCopySaveRequest(
        String graphSpecJson,
        String canvasJson,
        String extraJson,
        String baseRevision,
        String keySlug,
        String name,
        String description,
        String inputSchemaJson,
        String outputSchemaJson,
        String defaultModelInstanceId,
        String defaultResourceConfigJson,
        String workflowKind,
        String executionEngine,
        String definitionAuthority,
        String creationChannel) {

    public RuntimeWorkflowWorkingCopySaveRequest(String graphSpecJson,
                                                 String canvasJson,
                                                 String extraJson) {
        this(graphSpecJson, canvasJson, extraJson, null, null, null, null, null, null,
                null, null, null, null, null, null);
    }
}
