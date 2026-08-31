package com.enterprise.ai.control.a2a.domain;

import java.util.Set;

/** Immutable, server-attested identity passed explicitly into every A2A application command. */
public record A2aPrincipalContext(
        long principalId,
        String principalKey,
        A2aPrincipalType principalType,
        String tenantScope,
        A2aAuthenticationMethod authenticationMethod,
        A2aTrustLevel trustLevel,
        Set<String> scopes,
        DelegatedUserAttestation delegatedUser) {

    public A2aPrincipalContext {
        if (principalId <= 0) {
            throw new A2aDomainException("A2A_PRINCIPAL_INVALID", "principalId must be positive");
        }
        principalKey = A2aDomainText.requireText(principalKey, "principalKey");
        if (principalType == null || authenticationMethod == null || trustLevel == null) {
            throw new A2aDomainException("A2A_PRINCIPAL_INVALID", "principal identity attributes are required");
        }
        tenantScope = tenantScope == null ? "" : tenantScope.trim();
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }

    public boolean hasScope(String requiredScope) {
        return requiredScope != null && scopes.contains(requiredScope);
    }

    public record DelegatedUserAttestation(
            String delegatedUserId,
            String attestationType,
            String attestationReference) {

        public DelegatedUserAttestation {
            delegatedUserId = A2aDomainText.requireText(delegatedUserId, "delegatedUserId");
            attestationType = A2aDomainText.requireText(attestationType, "attestationType");
            attestationReference = A2aDomainText.requireText(attestationReference, "attestationReference");
        }
    }
}
