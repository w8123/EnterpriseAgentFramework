package com.enterprise.ai.capability.catalog.retrieval;

import java.util.List;

/**
 * Capability-local retrieval filters for management testing and rebuild smoke flows.
 */
public record CapabilityRetrievalScope(
        List<Long> projectIds,
        List<Long> moduleIds,
        List<Long> toolWhitelist,
        boolean enabledOnly,
        boolean agentVisibleOnly
) {
}
