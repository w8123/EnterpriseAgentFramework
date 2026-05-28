package com.enterprise.ai.skill.interaction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InteractionSpec {

    private InteractionSpec() {
    }

    public static Builder collectInput() {
        return new Builder(InteractionType.COLLECT_INPUT);
    }

    public static Builder presentOutput() {
        return new Builder(InteractionType.PRESENT_OUTPUT);
    }

    public static final class Builder {
        private final Map<String, Object> spec = new LinkedHashMap<>();
        private final List<Map<String, Object>> fields = new ArrayList<>();

        private Builder(InteractionType type) {
            spec.put("interactionType", type.name());
        }

        public Builder title(String title) {
            if (title != null && !title.isBlank()) {
                spec.put("title", title.trim());
            }
            return this;
        }

        public Builder field(String key, String type, boolean required, String label) {
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("key", key);
            field.put("name", key);
            field.put("type", type == null || type.isBlank() ? "string" : type.trim());
            field.put("required", required);
            if (label != null && !label.isBlank()) {
                field.put("label", label.trim());
            }
            fields.add(field);
            return this;
        }

        public Builder component(String component) {
            if (component != null && !component.isBlank()) {
                spec.put("component", component.trim().toUpperCase());
            }
            return this;
        }

        public Builder dataExpression(String expression) {
            if (expression != null && !expression.isBlank()) {
                spec.put("dataExpression", expression.trim());
            }
            return this;
        }

        public Builder renderSchema(Map<String, Object> schema) {
            if (schema != null && !schema.isEmpty()) {
                spec.put("renderSchema", new LinkedHashMap<>(schema));
            }
            return this;
        }

        public Builder renderSchema(String key, Object value) {
            if (key != null && !key.isBlank() && value != null) {
                childMap("renderSchema").put(key.trim(), value);
            }
            return this;
        }

        public Builder dataSources(Map<String, Object> dataSources) {
            if (dataSources != null && !dataSources.isEmpty()) {
                spec.put("dataSources", new LinkedHashMap<>(dataSources));
            }
            return this;
        }

        public Builder dataSource(String key, Object value) {
            if (key != null && !key.isBlank() && value != null) {
                childMap("dataSources").put(key.trim(), value);
            }
            return this;
        }

        public Builder rendererKey(String rendererKey) {
            if (rendererKey != null && !rendererKey.isBlank()) {
                childMap("renderSchema").put("rendererKey", rendererKey.trim());
            }
            return this;
        }

        public Builder behavior(String key, Object value) {
            if (key != null && !key.isBlank() && value != null) {
                childMap("behavior").put(key.trim(), value);
            }
            return this;
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> childMap(String key) {
            Object existing = spec.get(key);
            if (existing instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                map.forEach((rawKey, value) -> copy.put(String.valueOf(rawKey), value));
                spec.put(key, copy);
                return copy;
            }
            Map<String, Object> created = new LinkedHashMap<>();
            spec.put(key, created);
            return created;
        }

        public Map<String, Object> build() {
            if (!fields.isEmpty()) {
                spec.put("fields", List.copyOf(fields));
            }
            return Map.copyOf(spec);
        }
    }
}
