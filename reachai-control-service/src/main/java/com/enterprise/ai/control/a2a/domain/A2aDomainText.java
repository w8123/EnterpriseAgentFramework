package com.enterprise.ai.control.a2a.domain;

import java.util.Locale;

public final class A2aDomainText {

    public static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new A2aDomainException("A2A_REQUIRED_FIELD", field + " is required");
        }
        return value.trim();
    }

    public static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String field) {
        String normalized = requireText(value, field).toUpperCase(Locale.ROOT);
        try {
            return Enum.valueOf(type, normalized);
        } catch (IllegalArgumentException exception) {
            throw new A2aDomainException("A2A_INVALID_ENUM", field + " is invalid: " + value);
        }
    }

    private A2aDomainText() {
    }
}
