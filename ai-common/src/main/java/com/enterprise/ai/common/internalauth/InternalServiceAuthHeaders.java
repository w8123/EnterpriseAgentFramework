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
    public static final String IDENTITY_TENANT_ID = "X-ReachAI-Internal-Identity-TenantId";
    public static final String IDENTITY_USER_ID = "X-ReachAI-Internal-Identity-UserId";
    public static final String BODY_SHA256 = "X-ReachAI-Internal-Body-SHA256";
    public static final String SIGNATURE = "X-ReachAI-Internal-Signature";

    public static final String CALLER_CONTROL = "reachai-control-service";
    public static final String CALLER_RUNTIME = "reachai-runtime-service";

    /** Control-attested platform administrator identity for governance-only internal APIs. */
    public static final String IDENTITY_SOURCE_PLATFORM_SESSION = "PLATFORM_SESSION";

    /** Control-attested external A2A Principal entering the Runtime execution boundary. */
    public static final String IDENTITY_SOURCE_A2A_REMOTE_AGENT = "A2A_REMOTE_AGENT";

    /** Control-attested external MCP Client entering the Runtime execution boundary. */
    public static final String IDENTITY_SOURCE_MCP_REMOTE_CLIENT = "MCP_REMOTE_CLIENT";

    /** Control asks Capability (the credential owner) to verify a project-signed public request. */
    public static final String IDENTITY_SOURCE_PROJECT_CREDENTIAL_VERIFICATION =
            "PROJECT_CREDENTIAL_VERIFICATION";

    /** Capability-verified business project identity forwarded from Control to an owning service. */
    public static final String IDENTITY_SOURCE_PROJECT_CREDENTIAL = "PROJECT_CREDENTIAL";

    public static final String IDENTITY_SOURCE_RUNTIME_TRUSTED = "RUNTIME_TRUSTED_IDENTITY";
    public static final String IDENTITY_SOURCE_RUNTIME_UNTRUSTED = "RUNTIME_UNTRUSTED";

    private InternalServiceAuthHeaders() {
    }
}
