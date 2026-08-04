package com.enterprise.ai.runtime.workflow.node;

/**
 * Unified Workflow node capability descriptor shared by Studio, release validation and web AI authoring.
 */
public record RuntimeWorkflowNodeCapabilityDescriptor(
        String type,
        String canvasKind,
        String canvasCategory,
        String family,
        boolean retryable,
        WorkflowNodeMaturity maturity,
        boolean runtimeExecutable,
        boolean publishable,
        boolean studioEnabled,
        boolean aiAuthoringEnabled,
        String unavailableReason
) {
}
