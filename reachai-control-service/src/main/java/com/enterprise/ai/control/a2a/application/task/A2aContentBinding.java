package com.enterprise.ai.control.a2a.application.task;

import com.enterprise.ai.control.a2a.domain.A2aDirection;

/** Stable AES-GCM additional-authenticated-data contract for persisted A2A resources. */
public final class A2aContentBinding {

    public static String message(
            A2aDirection direction, long principalId, String tenantScope, String messageId) {
        return "a2a-message|" + direction.name() + "|" + principalId + "|"
                + tenant(tenantScope) + "|" + messageId;
    }

    public static String artifact(
            A2aDirection direction, long principalId, String tenantScope,
            String taskId, String artifactId) {
        return "a2a-artifact|" + direction.name() + "|" + principalId + "|"
                + tenant(tenantScope) + "|" + taskId + "|" + artifactId;
    }

    private static String tenant(String value) {
        return value == null ? "" : value.trim();
    }

    private A2aContentBinding() {
    }
}
