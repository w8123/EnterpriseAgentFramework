package com.enterprise.ai.common.page;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Set;

/** Shared fail-closed subset accepted by reviewed page-draft generation and execution. */
public final class TrustedPageDraftInputContract {
    private static final Set<String> RESERVED = Set.of("params", "sys", "env", "var", "input", "message",
            "nodeOutput", "lastOutput", "previousOutput", "projectCode", "pageKey", "agentId", "sessionId",
            "supervisorTraceId", "__proto__", "constructor", "prototype");
    private static final Set<String> ROOT_KEYS = Set.of("type", "properties", "required", "additionalProperties",
            "title", "description", "minProperties", "maxProperties");
    private static final Set<String> FIELD_KEYS = Set.of("type", "title", "description", "minLength", "maxLength");

    private TrustedPageDraftInputContract() { }
    public static boolean literal(String value) {
        return value != null && !value.isBlank() && value.equals(value.trim())
                && !value.contains("${") && !value.contains("#{") && !value.contains("{{")
                && !value.contains("}}") && !value.contains("*");
    }
    public static boolean staticRoute(String value) {
        return literal(value) && value.startsWith("/") && !value.startsWith("//") && !value.contains("\\")
                && !value.contains("%") && value.codePoints().noneMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))
                && !value.contains(":") && !value.contains("{") && !value.contains("}")
                && !value.contains("?") && !value.contains("#");
    }
    public static boolean validFieldName(String field) {
        return field != null && field.matches("[A-Za-z_][A-Za-z0-9_]*") && !RESERVED.contains(field);
    }
    public static boolean validSchema(JsonNode schema) {
        if (schema == null || !schema.isObject() || !"object".equals(schema.path("type").asText())
                || !schema.path("properties").isObject() || schema.path("properties").isEmpty()
                || (schema.has("required") && !schema.path("required").isArray())
                || (schema.has("additionalProperties") && (!schema.get("additionalProperties").isBoolean()
                    || schema.get("additionalProperties").booleanValue()))
                || !onlyKeys(schema, ROOT_KEYS) || !textValuesLiteral(schema)
                || !nonnegative(schema, "minProperties") || !nonnegative(schema, "maxProperties")
                || schema.path("minProperties").asInt(0) > schema.path("maxProperties").asInt(Integer.MAX_VALUE)) return false;
        var required = new HashSet<String>();
        for (JsonNode item : schema.path("required")) if (!item.isTextual() || !required.add(item.textValue())) return false;
        var names = new HashSet<String>();
        var fields = schema.path("properties").fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            String name = entry.getKey(); JsonNode property = entry.getValue();
            if (!validFieldName(name) || !names.add(name.toLowerCase(java.util.Locale.ROOT)) || !property.isObject()
                    || !Set.of("string", "number", "integer", "boolean").contains(property.path("type").asText())
                    || !onlyKeys(property, FIELD_KEYS) || !nonnegative(property, "minLength") || !nonnegative(property, "maxLength")
                    || ((property.has("minLength") || property.has("maxLength")) && !"string".equals(property.path("type").asText()))
                    || property.path("minLength").asInt(0) > property.path("maxLength").asInt(Integer.MAX_VALUE)) return false;
        }
        var exact = new HashSet<String>();
        schema.path("properties").fieldNames().forEachRemaining(exact::add);
        return exact.containsAll(required) && schema.path("minProperties").asInt(0) <= exact.size()
                && schema.path("maxProperties").asInt(Integer.MAX_VALUE) >= Math.max(1, required.size());
    }
    private static boolean onlyKeys(JsonNode node, Set<String> allowed) {
        var keys = node.fieldNames(); while (keys.hasNext()) if (!allowed.contains(keys.next())) return false;
        return true;
    }
    private static boolean nonnegative(JsonNode node, String name) {
        return !node.has(name) || node.path(name).isIntegralNumber() && node.path(name).canConvertToInt() && node.path(name).intValue() >= 0;
    }
    private static boolean textValuesLiteral(JsonNode node) {
        if (node.isTextual()) return literal(node.textValue());
        if (node.isContainerNode()) for (JsonNode value : node) if (!textValuesLiteral(value)) return false;
        return true;
    }
}
