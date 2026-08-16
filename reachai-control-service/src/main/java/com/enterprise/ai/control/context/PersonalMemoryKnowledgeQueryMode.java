package com.enterprise.ai.control.context;

import java.util.Locale;

/** Rollout mode for the rebuildable Knowledge personal-memory projection. */
public enum PersonalMemoryKnowledgeQueryMode {
    OFF,
    SHADOW,
    ACTIVE;

    public static PersonalMemoryKnowledgeQueryMode parse(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "", "FALSE", "OFF" -> OFF;
            case "SHADOW" -> SHADOW;
            case "TRUE", "ACTIVE" -> ACTIVE;
            default -> throw new IllegalArgumentException(
                    "Unsupported personal-memory knowledge query mode: " + value);
        };
    }

    public boolean queriesKnowledge() {
        return this != OFF;
    }

    public boolean affectsRanking() {
        return this == ACTIVE;
    }
}
