package com.enterprise.ai.control.pageworkbench.domain;

import org.springframework.util.StringUtils;

import java.util.Locale;

public final class PageWorkbenchValues {

    private PageWorkbenchValues() {
    }

    public enum PageSource {
        AI_SCAN,
        MANUAL,
        SDK
    }

    public enum PageLifecycle {
        ACTIVE,
        ARCHIVED
    }

    public enum ResourceType {
        ROUTE,
        COMPONENT,
        API,
        CONFIG,
        PERMISSION,
        STORE,
        STYLE,
        TEST
    }

    public enum ResourceAccess {
        READ_ONLY,
        READ_WRITE
    }

    public enum FindingStatus {
        UNREAD,
        KEPT,
        IGNORED
    }

    public static <E extends Enum<E>> E requiredEnum(Class<E> enumType, String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        try {
            return Enum.valueOf(enumType, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(field + " is invalid: " + value, ex);
        }
    }
}
