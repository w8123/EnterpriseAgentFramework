package com.enterprise.ai.agent.graph;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Runtime-neutral USER_INPUT semantics shared by GraphSpec validation and execution. */
public final class GraphSpecUserInputContract {

    private GraphSpecUserInputContract() {
    }

    /** Returns a flat USER_INPUT configuration regardless of the nested editor shape. */
    public static Map<String, Object> config(GraphSpec.Node node) {
        Map<String, Object> config = mutableMap(node == null ? null : node.getConfig());
        Map<String, Object> nested = mutableMap(config.get("userInputConfig"));
        config.putAll(nested);
        return config;
    }

    public static String outputAlias(GraphSpec.Node node) {
        Object alias = config(node).get("outputAlias");
        return alias == null ? "" : String.valueOf(alias).trim();
    }

    public static List<InputField> inputFields(GraphSpec.Node node) {
        Object rawFields = config(node).get("fields");
        if (!(rawFields instanceof List<?> list)) {
            return List.of();
        }
        List<InputField> fields = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> field = mutableMap(item);
            if (field.isEmpty()) {
                fields.add(new InputField("", "", false, "", null));
                continue;
            }
            String name = firstText(field.get("name"), field.get("key"));
            String type = firstText(field.get("type"), "string");
            String source = firstText(field.get("source"), "");
            fields.add(new InputField(name, type, Boolean.TRUE.equals(field.get("required")), source,
                    field.get("defaultValue")));
        }
        return List.copyOf(fields);
    }

    public static Map<String, Object> inputSchema(List<InputField> fields) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (InputField field : fields == null ? List.<InputField>of() : fields) {
            if (field == null || !StringUtils.hasText(field.name())) {
                continue;
            }
            Map<String, Object> property = new LinkedHashMap<>();
            property.put("type", jsonSchemaType(field.type()));
            if (field.defaultValue() != null && StringUtils.hasText(String.valueOf(field.defaultValue()))) {
                property.put("default", field.defaultValue());
            }
            properties.put(field.name(), property);
            if (field.required()) {
                required.add(field.name());
            }
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        schema.put("additionalProperties", false);
        return Map.copyOf(schema);
    }

    public static String jsonSchemaType(String type) {
        String normalized = normalizeType(type);
        return switch (normalized) {
            case "text" -> "string";
            case "bool" -> "boolean";
            case "file" -> "string";
            default -> normalized;
        };
    }

    public static String normalizeType(String type) {
        return StringUtils.hasText(type) ? type.trim().toLowerCase(Locale.ROOT) : "string";
    }

    private static Map<String, Object> mutableMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, item) -> copy.put(String.valueOf(key), item));
        return copy;
    }

    private static String firstText(Object first, Object fallback) {
        String firstValue = text(first);
        return StringUtils.hasText(firstValue) ? firstValue.trim() : text(fallback);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    public record InputField(String name,
                             String type,
                             boolean required,
                             String source,
                             Object defaultValue) {
    }
}
