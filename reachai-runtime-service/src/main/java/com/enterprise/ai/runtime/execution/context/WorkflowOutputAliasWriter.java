package com.enterprise.ai.runtime.execution.context;

import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Central writer for {@code outputAlias} → {@code var.&lt;alias&gt;}.
 * Handlers must not implement their own alias write logic.
 */
public final class WorkflowOutputAliasWriter {

    private WorkflowOutputAliasWriter() {
    }

    public static void writeAlias(Map<String, Object> context, String rawAlias, Object output) {
        if (context == null || !StringUtils.hasText(rawAlias)) {
            return;
        }
        String alias = rawAlias.trim();
        if (!WorkflowVariableNamespaces.isValidAlias(alias)
                || WorkflowVariableNamespaces.isReservedAlias(alias)) {
            return;
        }
        writePath(context, WorkflowVariableNamespaces.varPath(alias), output);
    }

    public static void writeBusinessPath(Map<String, Object> context, String normalizedVarPath, Object output) {
        if (context == null || !StringUtils.hasText(normalizedVarPath)) {
            return;
        }
        writePath(context, normalizedVarPath, output);
    }

    @SuppressWarnings("unchecked")
    private static void writePath(Map<String, Object> context, String path, Object output) {
        context.put(path, output);
        String[] parts = path.split("\\.");
        if (parts.length == 0) {
            return;
        }
        Map<String, Object> cursor = context;
        for (int i = 0; i < parts.length - 1; i++) {
            Object child = cursor.get(parts[i]);
            Map<String, Object> next;
            if (child instanceof Map<?, ?> existing) {
                next = new LinkedHashMap<>();
                existing.forEach((key, value) -> next.put(String.valueOf(key), value));
            } else {
                next = new LinkedHashMap<>();
            }
            cursor.put(parts[i], next);
            cursor = next;
        }
        cursor.put(parts[parts.length - 1], output);
        flatten(context, path, output);
    }

    private static void flatten(Map<String, Object> context, String path, Object output) {
        if (!(output instanceof Map<?, ?> map)) {
            return;
        }
        map.forEach((key, value) -> {
            String childPath = path + "." + key;
            context.put(childPath, value);
            flatten(context, childPath, value);
        });
    }
}
