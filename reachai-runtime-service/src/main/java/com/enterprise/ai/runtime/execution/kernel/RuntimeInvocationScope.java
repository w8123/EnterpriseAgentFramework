package com.enterprise.ai.runtime.execution.kernel;

import java.util.Map;
import java.util.UUID;

/** Server-owned identity of a workflow invocation and its nested execution position. */
record RuntimeInvocationScope(String runId, String path) {
    static final String KEY = "__workflowInvocationScope";

    static RuntimeInvocationScope from(Map<String, Object> context) {
        Object value = context.get(KEY);
        return value instanceof RuntimeInvocationScope scope ? scope : new RuntimeInvocationScope(UUID.randomUUID().toString(), "");
    }

    RuntimeInvocationScope child(String segment) {
        return new RuntimeInvocationScope(runId, path + "/" + segment);
    }

    Map<String, String> snapshot() {
        return Map.of("runId", runId, "path", path);
    }

    static RuntimeInvocationScope restore(Object value) {
        if (value instanceof Map<?, ?> map && map.get("runId") instanceof String runId
                && map.get("path") instanceof String path && path.length() <= 4096) {
            try {
                return new RuntimeInvocationScope(UUID.fromString(runId).toString(), path);
            } catch (IllegalArgumentException ignored) {
                // Legacy checkpoints can continue with a fresh run scope.
            }
        }
        return new RuntimeInvocationScope(UUID.randomUUID().toString(), "");
    }
}
