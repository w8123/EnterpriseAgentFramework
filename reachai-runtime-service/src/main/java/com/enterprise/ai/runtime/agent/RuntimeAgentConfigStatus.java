package com.enterprise.ai.runtime.agent;

/** Agent 配置生命周期；已发布执行必须明确属于 ACTIVE 或 ARCHIVED。 */
enum RuntimeAgentConfigStatus {
    DRAFT, ACTIVE, ARCHIVED;

    static boolean isPublished(String value) {
        return ACTIVE.matches(value) || ARCHIVED.matches(value);
    }

    static boolean isKnown(String value) {
        return DRAFT.matches(value) || isPublished(value);
    }

    private boolean matches(String value) {
        return value != null && name().equalsIgnoreCase(value.trim());
    }
}
