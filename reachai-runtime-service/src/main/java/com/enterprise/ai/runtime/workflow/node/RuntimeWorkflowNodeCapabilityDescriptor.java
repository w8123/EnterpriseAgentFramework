package com.enterprise.ai.runtime.workflow.node;

import java.util.List;

/**
 * Unified Workflow node capability descriptor shared by Studio, release validation and web AI authoring.
 */
public record RuntimeWorkflowNodeCapabilityDescriptor(
        String type,
        String canvasKind,
        String canvasCategory,
        String family,
        boolean retryable,
        List<String> aliases,
        WorkflowNodeMaturity maturity,
        boolean runtimeExecutable,
        boolean publishable,
        boolean studioEnabled,
        boolean aiAuthoringEnabled,
        String unavailableReason
) {
}
