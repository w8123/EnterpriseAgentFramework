package com.enterprise.ai.common.internalauth;

/** Public project-credential request headers. The body digest is mandatory. */
public final class ProjectRequestAuthHeaders {

    public static final String APP_KEY = "X-ReachAI-App-Key";
    public static final String TIMESTAMP = "X-ReachAI-Timestamp";
    public static final String NONCE = "X-ReachAI-Nonce";
    public static final String BODY_SHA256 = "X-ReachAI-Body-SHA256";
    public static final String SIGNATURE = "X-ReachAI-Signature";

    private ProjectRequestAuthHeaders() {
    }
}
