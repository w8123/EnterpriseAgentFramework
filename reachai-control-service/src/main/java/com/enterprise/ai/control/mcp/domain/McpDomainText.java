package com.enterprise.ai.control.mcp.domain;

import java.util.Locale;

public final class McpDomainText {

    public static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new McpDomainException("MCP_REQUIRED_FIELD", field + " is required");
        }
        return value.trim();
    }

    public static String optionalText(String value) {
        return value == null ? "" : value.trim();
    }

    public static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String field) {
        String normalized = requireText(value, field).toUpperCase(Locale.ROOT);
        try {
            return Enum.valueOf(type, normalized);
        } catch (IllegalArgumentException exception) {
            throw new McpDomainException("MCP_INVALID_ENUM", field + " is invalid: " + value);
        }
    }

    private McpDomainText() {
    }
}
