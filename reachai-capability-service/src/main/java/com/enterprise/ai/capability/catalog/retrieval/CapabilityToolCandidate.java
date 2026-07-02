package com.enterprise.ai.capability.catalog.retrieval;

/**
 * Tool retrieval hit returned by the Capability Catalog service.
 */
public record CapabilityToolCandidate(
        Long toolId,
        String toolName,
        Long projectId,
        Long moduleId,
        float score,
        String text
) {
}
