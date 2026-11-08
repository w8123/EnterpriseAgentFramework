package com.enterprise.ai.runtime.execution.kernel;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Isolated copies of execution variables for parallel branches and iterations. */
final class RuntimeContextCopies {

    private static final int MAX_DEPTH = 32;

    private RuntimeContextCopies() {
    }

    /**
     * Copies maps and lists recursively so a branch writing {@code var.a.b} never mutates another branch's view.
     * Leaf values and server-owned objects (trusted identity, eval context) are shared by reference.
     */
    static Map<String, Object> copy(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (source == null) return copy;
        IdentityHashMap<Object, Object> seen = new IdentityHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            copy.put(entry.getKey(), copyValue(entry.getValue(), 0, seen));
        }
        return copy;
    }

    private static Object copyValue(Object value, int depth, IdentityHashMap<Object, Object> seen) {
        if (depth > MAX_DEPTH || value == null) return value;
        if (value instanceof Map<?, ?> map) {
            Object existing = seen.get(value);
            if (existing != null) return existing;
            Map<String, Object> nested = new LinkedHashMap<>();
            seen.put(value, nested);
            map.forEach((key, item) -> nested.put(String.valueOf(key), copyValue(item, depth + 1, seen)));
            return nested;
        }
        if (value instanceof List<?> list) {
            Object existing = seen.get(value);
            if (existing != null) return existing;
            List<Object> nested = new ArrayList<>(list.size());
            seen.put(value, nested);
            for (Object item : list) nested.add(copyValue(item, depth + 1, seen));
            return nested;
        }
        return value;
    }
}
