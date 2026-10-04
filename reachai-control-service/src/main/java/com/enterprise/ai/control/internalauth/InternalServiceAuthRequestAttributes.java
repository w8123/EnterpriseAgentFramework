package com.enterprise.ai.control.internalauth;

/**
 * Request attributes that carry a server-attested identity to narrowly scoped
 * outbound internal-service calls.
 */
public final class InternalServiceAuthRequestAttributes {

    public static final String PLATFORM_SESSION_ACTOR_ID =
            "reachai.internal-auth.platform-session-actor-id";

    private InternalServiceAuthRequestAttributes() {
    }
}
