package com.enterprise.ai.common.util;

/**
 * Small text helpers shared by the ReachAI services.
 */
public final class Texts {

    private Texts() {
    }

    /**
     * Returns the first value that contains non-whitespace text, trimmed, or {@code null} when none does.
     */
    public static String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
