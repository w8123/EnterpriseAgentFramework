package com.enterprise.ai.common.internalauth;

/**
 * Control → Runtime internal service authentication headers (HMAC-SHA256).
 * Path name alone is never attestation. Shared by Control and Runtime — do not duplicate.
 */
public final class InternalServiceAuthHeaders {

    public static final String CALLER = "X-ReachAI-Internal-Caller";
    public static final String TIMESTAMP = "X-ReachAI-Internal-Timestamp";
    public static final String NONCE = "X-ReachAI-Internal-Nonce";
    public static final String IDENTITY_SOURCE = "X-ReachAI-Internal-Identity-Source";
    public static final String IDENTITY_USER_ID = "X-ReachAI-Internal-Identity-UserId";
    public static final String BODY_SHA256 = "X-ReachAI-Internal-Body-SHA256";
    public static final String SIGNATURE = "X-ReachAI-Internal-Signature";

    public static final String CALLER_CONTROL = "reachai-control-service";

    private InternalServiceAuthHeaders() {
    }
}
