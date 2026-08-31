package com.enterprise.ai.runtime.automation;

import java.util.Locale;
import java.util.Set;

final class RuntimeAutomationTypes {

    static final Set<String> ACTIVE_STATES = Set.of("ACTIVE");
    static final Set<String> MUTABLE_STATES = Set.of("DRAFT", "ACTIVE", "PAUSED");
    static final Set<String> TARGET_TYPES = Set.of("AGENT", "WORKFLOW");
    static final Set<String> TRIGGER_TYPES = Set.of("CRON", "ONCE");
    static final Set<String> MISFIRE_POLICIES = Set.of("SKIP", "FIRE_ONCE", "CATCH_UP");
    static final Set<String> CONCURRENCY_POLICIES = Set.of("SKIP", "QUEUE", "ALLOW");
    static final Set<String> TERMINAL_OCCURRENCE_STATES = Set.of(
            "SUCCEEDED", "FAILED", "DEAD", "CANCELLED", "SKIPPED");

    static String upper(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private RuntimeAutomationTypes() {
    }
}
