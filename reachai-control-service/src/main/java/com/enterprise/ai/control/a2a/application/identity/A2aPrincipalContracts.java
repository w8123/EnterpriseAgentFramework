package com.enterprise.ai.control.a2a.application.identity;

import com.enterprise.ai.control.a2a.domain.identity.A2aPrincipal;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

public final class A2aPrincipalContracts {
    private A2aPrincipalContracts() {
    }

    public record CreateRequest(
            String principalKey,
            String principalType,
            String runtimeAgentId,
            String displayName,
            String tenantScope,
            Long trustProfileId,
            Long credentialId,
            Set<String> scopes,
            Map<String, String> attributes) {
    }

    public record StatusRequest(String status) {
    }

    public record View(
            String schema,
            Long id,
            String principalKey,
            String principalType,
            String displayName,
            String tenantScope,
            String authenticatedSubject,
            long trustProfileId,
            Long credentialId,
            Set<String> scopes,
            Map<String, String> attributes,
            String status,
            LocalDateTime lastAuthenticatedAt,
            int version,
            String createdBy,
            String updatedBy,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {

        public static View from(A2aPrincipal principal) {
            return new View(
                    "reachai.a2a-hub.principal.v1", principal.id(), principal.principalKey(),
                    principal.principalType().name(), principal.displayName(), principal.tenantScope(),
                    principal.authenticatedSubject(), principal.trustProfileId(), principal.credentialId(),
                    principal.scopes(), principal.attributes(), principal.status().name(),
                    principal.lastAuthenticatedAt(), principal.version(), principal.createdBy(),
                    principal.updatedBy(), principal.createdAt(), principal.updatedAt());
        }
    }
}
