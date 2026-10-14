package com.enterprise.ai.capability.catalog.retrieval;

import java.util.List;

/**
 * Capability-local keyword retrieval filters for management testing.
 */
public record CapabilityRetrievalScope(
        List<Long> projectIds,
        List<Long> moduleIds,
        List<Long> toolWhitelist,
        boolean enabledOnly
) {
}
