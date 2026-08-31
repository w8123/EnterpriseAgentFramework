package com.enterprise.ai.control.a2a.application.identity;

import com.enterprise.ai.control.a2a.domain.identity.A2aCredential;

import java.time.LocalDateTime;

public final class A2aCredentialContracts {
    private A2aCredentialContracts() {
    }

    public record CreateApiKeyRequest(String credentialKey, String name, LocalDateTime expiresAt) {
    }

    public record StoreOutboundSecretRequest(
            String credentialKey,
            String name,
            String credentialType,
            String secret,
            LocalDateTime expiresAt) {
    }

    public record RotateOutboundSecretRequest(String secret, LocalDateTime expiresAt) {
    }

    public record CredentialView(
            String schema,
            Long id,
            String credentialKey,
            String name,
            String direction,
            String credentialType,
            String materialMode,
            String fingerprint,
            int versionNo,
            String status,
            LocalDateTime notBefore,
            LocalDateTime expiresAt,
            LocalDateTime lastUsedAt,
            Long rotatedFromId,
            String createdBy,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {

        public static CredentialView from(A2aCredential credential) {
            return new CredentialView(
                    "reachai.a2a-hub.credential.v1", credential.id(), credential.credentialKey(),
                    credential.name(), credential.direction().name(), credential.credentialType().name(),
                    credential.materialMode(), credential.fingerprint(), credential.versionNo(),
                    credential.status().name(), credential.notBefore(), credential.expiresAt(),
                    credential.lastUsedAt(), credential.rotatedFromId(), credential.createdBy(),
                    credential.createdAt(), credential.updatedAt());
        }
    }

    /** The raw API key is intentionally present only in this one-time response. */
    public record ApiKeyIssueView(
            String schema,
            CredentialView credential,
            String apiKeyOnce,
            String nextAction) {
    }

    /** Outbound secrets are accepted once and are never returned by any API. */
    public record OutboundSecretStoredView(
            String schema,
            CredentialView credential,
            String nextAction) {
    }
}
