package com.enterprise.ai.agent.platform.control.auth;

public final class PlatformAuthContext {

    private static final ThreadLocal<PlatformPrincipal> CURRENT = new ThreadLocal<>();

    private PlatformAuthContext() {
    }

    public static void set(PlatformPrincipal principal) {
        CURRENT.set(principal);
    }

    public static PlatformPrincipal get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
