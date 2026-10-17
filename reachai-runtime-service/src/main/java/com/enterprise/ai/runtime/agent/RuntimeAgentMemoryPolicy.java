package com.enterprise.ai.runtime.agent;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Agent-owned memory policy published with each configuration version. Runtime enforces it for
 * session memory and personal-memory reads; Control reads {@link #toMetadata()} from the execute
 * response to decide whether the turn may write personal memory.
 */
public record RuntimeAgentMemoryPolicy(
        boolean sessionMemoryEnabled,
        int sessionMemoryWindow,
        boolean personalMemoryRead,
        String personalMemoryWrite) {

    public static final String WRITE_OFF = "OFF";
    public static final String WRITE_EXPLICIT = "EXPLICIT";
    public static final String WRITE_EXPLICIT_AND_CANDIDATE = "EXPLICIT_AND_CANDIDATE";
    public static final int DEFAULT_SESSION_WINDOW = 40;
    public static final int MIN_SESSION_WINDOW = 4;
    public static final int MAX_SESSION_WINDOW = 100;
    private static final Set<String> WRITE_MODES = Set.of(WRITE_OFF, WRITE_EXPLICIT, WRITE_EXPLICIT_AND_CANDIDATE);

    public static final RuntimeAgentMemoryPolicy DEFAULT =
            new RuntimeAgentMemoryPolicy(true, DEFAULT_SESSION_WINDOW, false, WRITE_OFF);

    public RuntimeAgentMemoryPolicy {
        sessionMemoryWindow = Math.max(MIN_SESSION_WINDOW, Math.min(sessionMemoryWindow, MAX_SESSION_WINDOW));
        personalMemoryWrite = normalizeWrite(personalMemoryWrite);
    }

    public static RuntimeAgentMemoryPolicy of(RuntimeAgentConfigSnapshot config) {
        if (config == null) {
            return DEFAULT;
        }
        return new RuntimeAgentMemoryPolicy(
                !Boolean.FALSE.equals(config.getSessionMemoryEnabled()),
                config.getSessionMemoryWindow() == null ? DEFAULT_SESSION_WINDOW : config.getSessionMemoryWindow(),
                Boolean.TRUE.equals(config.getPersonalMemoryRead()),
                config.getPersonalMemoryWrite());
    }

    /** Unknown or blank modes fail closed. */
    public static String normalizeWrite(String value) {
        if (value == null || value.isBlank()) {
            return WRITE_OFF;
        }
        String mode = value.trim().toUpperCase(Locale.ROOT);
        return WRITE_MODES.contains(mode) ? mode : WRITE_OFF;
    }

    /** Validates a console-submitted write mode instead of silently downgrading it. */
    public static String requireWrite(String value) {
        String mode = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!WRITE_MODES.contains(mode)) {
            throw new IllegalArgumentException("personalMemoryWrite must be one of " + WRITE_MODES);
        }
        return mode;
    }

    public static int requireWindow(int value) {
        if (value < MIN_SESSION_WINDOW || value > MAX_SESSION_WINDOW) {
            throw new IllegalArgumentException("sessionMemoryWindow must be between "
                    + MIN_SESSION_WINDOW + " and " + MAX_SESSION_WINDOW);
        }
        return value;
    }

    public Map<String, Object> toMetadata() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("sessionMemoryEnabled", sessionMemoryEnabled);
        value.put("sessionMemoryWindow", sessionMemoryWindow);
        value.put("personalMemoryRead", personalMemoryRead);
        value.put("personalMemoryWrite", personalMemoryWrite);
        return Map.copyOf(value);
    }
}
