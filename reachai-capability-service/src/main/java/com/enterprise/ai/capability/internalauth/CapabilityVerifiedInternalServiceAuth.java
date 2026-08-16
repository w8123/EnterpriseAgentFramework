package com.enterprise.ai.capability.internalauth;

/** Cryptographically verified identity for a Capability internal call. */
public record CapabilityVerifiedInternalServiceAuth(
        String caller,
        String identitySource,
        String identityTenantId,
        String identityUserId
) {
    public static final String REQUEST_ATTR = CapabilityVerifiedInternalServiceAuth.class.getName();
}
