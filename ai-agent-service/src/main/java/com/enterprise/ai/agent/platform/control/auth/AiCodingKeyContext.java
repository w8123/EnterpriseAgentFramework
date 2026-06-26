package com.enterprise.ai.agent.platform.control.auth;

/**
 * Thread-local holder for the current request's AI Coding access key.
 */
public final class AiCodingKeyContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private AiCodingKeyContext() {
    }

    public static void set(String accessKey) {
        if (accessKey == null || accessKey.isBlank()) {
            CURRENT.remove();
            return;
        }
        CURRENT.set(accessKey.trim());
    }

    public static String get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
