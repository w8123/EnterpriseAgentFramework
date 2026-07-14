package com.enterprise.ai.runtime.compat;

public record RuntimeWorkflowStudioSaveRequest(
        String graphSpecJson,
        String canvasJson,
        String extraJson,
        String baseRevision,
        String keySlug,
        String name,
        String description,
        String workflowType,
        String runtimeType,
        String inputSchemaJson,
        String outputSchemaJson,
        String defaultModelInstanceId,
        String defaultResourceConfigJson) {

    public RuntimeWorkflowStudioSaveRequest(String graphSpecJson,
                                            String canvasJson,
                                            String extraJson) {
        this(graphSpecJson, canvasJson, extraJson, null, null, null, null, null, null,
                null, null, null, null);
    }
}
