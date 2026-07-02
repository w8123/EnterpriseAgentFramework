package com.enterprise.ai.runtime.compat;

public record RuntimeWorkflowStudioSaveRequest(
        String graphSpecJson,
        String canvasJson,
        String extraJson) {
}
