package com.enterprise.ai.runtime.execution.kernel;

import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Decides whether a successful node produced "nothing" for {@code empty} / {@code not_empty} edges.
 * Business list APIs usually wrap rows as {@code {total: 0, list: []}}, optionally under {@code data},
 * so those shapes count as empty too.
 */
final class RuntimeOutputEmptiness {

    private static final List<String> ROW_KEYS = List.of("list", "records", "rows", "items");

    private RuntimeOutputEmptiness() {
    }

    static boolean isEmpty(RuntimeGraphSpecExecutionResult result) {
        if (result == null) {
            return true;
        }
        Map<String, Object> metadata = result.metadata();
        if (metadata != null) {
            if (metadata.get("empty") instanceof Boolean empty) {
                return empty;
            }
            if (metadata.containsKey("structuredOutput")) {
                return isEmptyValue(metadata.get("structuredOutput"), 0);
            }
        }
        return !StringUtils.hasText(result.answer());
    }

    static boolean isEmptyValue(Object value, int depth) {
        if (value == null) {
            return true;
        }
        if (value instanceof CharSequence text) {
            return !StringUtils.hasText(text);
        }
        if (value instanceof Collection<?> collection) {
            return collection.isEmpty();
        }
        if (value.getClass().isArray()) {
            return java.lang.reflect.Array.getLength(value) == 0;
        }
        if (!(value instanceof Map<?, ?> map)) {
            return false;
        }
        if (map.isEmpty()) {
            return true;
        }
        if (depth < 3) {
            if (map.get("empty") instanceof Boolean empty) {
                return empty;
            }
            if (map.get("total") instanceof Number total) {
                return total.longValue() == 0L;
            }
            for (String key : ROW_KEYS) {
                if (map.get(key) instanceof Collection<?> rows) {
                    return rows.isEmpty();
                }
            }
            if (map.containsKey("data")) {
                return isEmptyValue(map.get("data"), depth + 1);
            }
        }
        return false;
    }
}
