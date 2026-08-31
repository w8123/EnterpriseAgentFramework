package com.enterprise.ai.control.a2a.domain.trust;

import java.util.Set;

/** Explicit allowlists. An empty set means no access, never unrestricted access. */
public record A2aAuthorizationPolicy(
        Set<String> publicationKeys,
        Set<String> remoteAgentKeys,
        Set<String> protocolSkillIds,
        Set<String> operations,
        Set<String> tenantScopes) {

    public A2aAuthorizationPolicy {
        publicationKeys = normalized(publicationKeys);
        remoteAgentKeys = normalized(remoteAgentKeys);
        protocolSkillIds = normalized(protocolSkillIds);
        operations = normalized(operations);
        tenantScopes = normalized(tenantScopes);
    }

    private static Set<String> normalized(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
