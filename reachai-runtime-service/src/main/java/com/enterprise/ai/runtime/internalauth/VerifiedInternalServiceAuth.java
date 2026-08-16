package com.enterprise.ai.runtime.internalauth;

/**
 * Cryptographically verified Control→Runtime identity binding.
 * Built only after HMAC + skew + nonce checks succeed.
 */
public record VerifiedInternalServiceAuth(
        String caller,
        String identitySource,
        String identityTenantId,
        String identityUserId
) {
    public static final String REQUEST_ATTR = VerifiedInternalServiceAuth.class.getName();

    public VerifiedInternalServiceAuth(String caller, String identitySource, String identityUserId) {
        this(caller, identitySource, "default", identityUserId);
    }
}
