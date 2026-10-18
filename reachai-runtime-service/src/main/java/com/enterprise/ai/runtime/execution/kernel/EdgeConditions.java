package com.enterprise.ai.runtime.execution.kernel;

import java.util.Locale;
import java.util.Set;

/**
 * Edge condition keywords the engine evaluates besides branch route ids. Studio offers exactly these,
 * and release validation rejects anything else so a drawn edge is never silently ignored.
 */
public final class EdgeConditions {

    public static final Set<String> UNCONDITIONAL = Set.of("always", "success");
    public static final Set<String> ELSE = Set.of("else", "default");
    public static final Set<String> ERROR = Set.of("error", "failure");
    public static final Set<String> EMPTY = Set.of("empty");
    public static final Set<String> NOT_EMPTY = Set.of("not_empty");

    private EdgeConditions() {
    }

    public static boolean matches(String condition, Set<String> keywords) {
        if (condition == null || condition.isBlank()) {
            return false;
        }
        return keywords.contains(condition.trim().toLowerCase(Locale.ROOT));
    }

    /** True for every keyword above; route ids ("route:x" or a bare id) are checked separately. */
    public static boolean isKeyword(String condition) {
        return matches(condition, UNCONDITIONAL)
                || matches(condition, ELSE)
                || matches(condition, ERROR)
                || matches(condition, EMPTY)
                || matches(condition, NOT_EMPTY);
    }
}
