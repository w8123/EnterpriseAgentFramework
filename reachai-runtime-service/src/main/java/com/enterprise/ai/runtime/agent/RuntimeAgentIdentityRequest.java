package com.enterprise.ai.runtime.agent;

/**
 * Agent identity and entry-surface contract.
 * Supervisor runtime configuration is versioned separately through
 * {@code /api/agents/{agentId}/config-versions/draft}.
 */
public record RuntimeAgentIdentityRequest(
        String id,
        Long projectId,
        String projectCode,
        String keySlug,
        String name,
        String description,
        String visibility,
        String allowedRolesJson,
        Boolean enabled) {
}
