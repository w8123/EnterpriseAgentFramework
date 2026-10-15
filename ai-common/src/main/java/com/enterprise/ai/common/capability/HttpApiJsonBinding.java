package com.enterprise.ai.common.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;

/** Bounded JSON values and the explicitly supported, recursively enforced schema subset. */
public final class HttpApiJsonBinding {
    public static final int MAX_DEPTH = 6, MAX_NODES = 4096, MAX_FIELDS = 64, MAX_ITEMS = 1024, MAX_BYTES = 262144;
    private static final Set<String> COMMON = Set.of("type", "enum", "const", "description", "title", "default", "nullable", "readOnly", "writeOnly");
    private static final Set<String> STRING = Set.of("minLength", "maxLength", "format");
    private static final Set<String> NUMBER = Set.of("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf");
    private static final Set<String> OBJECT = Set.of("properties", "required", "additionalProperties", "minProperties", "maxProperties");
    private static final Set<String> ARRAY = Set.of("items", "minItems", "maxItems", "uniqueItems");
    private HttpApiJsonBinding() { }

    public static String unsupportedReason(JsonNode schema) {
        try { checkSchema(schema, 0, new int[]{0}); return null; }
        catch (IllegalArgumentException invalid) { return invalid.getMessage(); }
    }

    public static Object copyWire(Object raw, ObjectMapper json) {
        Object copy = copy(raw, 0, new int[]{0});
        try {
            if (json.writeValueAsBytes(copy).length > MAX_BYTES) throw invalid("请求体超过 256 KiB");
        } catch (IllegalArgumentException invalid) { throw invalid; }
        catch (Exception invalid) { throw invalid("请求体不是合法 JSON"); }
        return copy;
    }

    public static Object bind(JsonNode schema, Object raw, ObjectMapper json) {
        String unsupported = unsupportedReason(schema);
        if (unsupported != null) throw invalid(unsupported);
        Object copy = copyWire(raw, json);
        validate(schema, json.valueToTree(copy), "body", 0);
        return copy;
    }

    private static Object copy(Object raw, int depth, int[] count) {
        bounded(depth, count);
        if (raw == null || raw instanceof Boolean) return raw;
        if (raw instanceof String text) {
            if (text.length() > 32768) throw invalid("请求体字符串超过 32768 字符");
            return text;
        }
        if (raw instanceof Number number) {
            if (!Double.isFinite(number.doubleValue())) throw invalid("请求体数字必须有限");
            return number;
        }
        if (raw instanceof Map<?, ?> map) {
            if (map.size() > MAX_FIELDS) throw invalid("请求体对象超过 64 字段");
            Map<String,Object> out = new TreeMap<>();
            map.forEach((key, value) -> {
                if (!(key instanceof String name) || name.isBlank() || name.length() > 128
                        || name.chars().anyMatch(ch -> ch < 32 || ch == 127)) throw invalid("请求体字段名称无效");
                out.put(name, copy(value, depth + 1, count));
            });
            return Collections.unmodifiableMap(out);
        }
        if (raw instanceof List<?> list) {
            if (list.size() > MAX_ITEMS) throw invalid("请求体数组超过 1024 项");
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) out.add(copy(item, depth + 1, count));
            return Collections.unmodifiableList(out);
        }
        throw invalid("请求体只能包含 JSON 对象、数组和标量");
    }

    private static void checkSchema(JsonNode schema, int depth, int[] count) {
        bounded(depth, count);
        if (schema == null || !schema.isObject()) throw invalid("请求体 schema 不可读取");
        if (schema.isEmpty()) return; // An empty schema declares no type, not an invented object.
        String type = schema.path("type").asText();
        if (!Set.of("object", "array", "string", "integer", "number", "boolean").contains(type)) throw invalid("请求体类型不在当前支持范围");
        Set<String> allowed = new HashSet<>(COMMON);
        allowed.addAll(switch (type) { case "object" -> OBJECT; case "array" -> ARRAY; case "string" -> STRING; case "number", "integer" -> NUMBER; default -> Set.of(); });
        var keys = schema.fieldNames();
        while (keys.hasNext()) if (!allowed.contains(keys.next())) throw invalid("请求体包含尚不支持的 schema 约束");
        for (String flag : List.of("nullable", "readOnly", "writeOnly")) if (schema.has(flag) && !schema.path(flag).isBoolean()) throw invalid("schema 标记必须是布尔值");
        if (schema.path("readOnly").asBoolean()) throw invalid("请求体不能写入 readOnly 字段");
        if (schema.has("enum") && (!schema.path("enum").isArray() || schema.path("enum").isEmpty())) throw invalid("enum 约束无效");
        if (schema.has("enum")) checkLiteral(schema.get("enum"), 0, new int[]{0});
        if (schema.has("const")) checkLiteral(schema.get("const"), 0, new int[]{0});
        if ("string".equals(type)) {
            nonNegative(schema, "minLength", "maxLength");
            String format = schema.path("format").asText();
            if (!Set.of("", "password", "uuid", "date", "date-time").contains(format)) throw invalid("请求体格式尚不支持；binary/multipart 不按 JSON 发送");
        }
        if ("number".equals(type) || "integer".equals(type)) {
            for (String key : NUMBER) if (schema.has(key) && !schema.path(key).isNumber()) throw invalid("数值约束必须是数字");
            if (schema.has("multipleOf") && schema.path("multipleOf").decimalValue().signum() <= 0) throw invalid("multipleOf 必须大于零");
        }
        if ("array".equals(type)) {
            nonNegative(schema, "minItems", "maxItems");
            if (schema.has("uniqueItems") && !schema.path("uniqueItems").isBoolean()) throw invalid("uniqueItems 约束无效");
            if (!schema.has("items")) throw invalid("数组缺少可验证的 items 契约");
            checkSchema(schema.get("items"), depth + 1, count);
        }
        if ("object".equals(type)) {
            nonNegative(schema, "minProperties", "maxProperties");
            JsonNode fields = schema.path("properties");
            if (schema.has("properties") && (!fields.isObject() || fields.size() > MAX_FIELDS)) throw invalid("对象 properties 无效或过多");
            if (schema.has("required")) {
                if (!schema.path("required").isArray()) throw invalid("required 约束无效");
                for (JsonNode required : schema.path("required")) if (!required.isTextual() || !fields.has(required.asText())) throw invalid("required 字段未声明");
            }
            var entries = fields.fields();
            while (entries.hasNext()) {
                var field = entries.next();
                if (field.getKey().isBlank() || field.getKey().length() > 128) throw invalid("schema 字段名称无效");
                checkSchema(field.getValue(), depth + 1, count);
            }
            if (schema.has("additionalProperties")) {
                JsonNode additional = schema.get("additionalProperties");
                if (!additional.isBoolean()) checkSchema(additional, depth + 1, count);
            }
        }
    }

    private static void validate(JsonNode schema, JsonNode value, String path, int depth) {
        if (schema.isEmpty()) return;
        if (schema.has("const") && !equal(schema.get("const"), value)) throw invalid("请求体字段不符合固定值：" + path);
        if (schema.has("enum")) {
            boolean match = false; for (JsonNode option : schema.path("enum")) if (equal(option, value)) match = true;
            if (!match) throw invalid("请求体字段不在允许值中：" + path);
        }
        if (value.isNull()) {
            if (!schema.path("nullable").asBoolean()) throw invalid("请求体字段不能为 null：" + path);
            return;
        }
        String type = schema.path("type").asText();
        boolean correct = switch (type) {
            case "object" -> value.isObject(); case "array" -> value.isArray(); case "string" -> value.isTextual();
            case "integer" -> value.isNumber() && value.decimalValue().stripTrailingZeros().scale() <= 0;
            case "number" -> value.isNumber(); case "boolean" -> value.isBoolean(); default -> false;
        };
        if (!correct) throw invalid("请求体字段类型错误：" + path);
        if (value.isTextual()) {
            checkRange(value.textValue().length(), schema, "minLength", "maxLength", path);
            try {
                switch (schema.path("format").asText()) {
                    case "uuid" -> UUID.fromString(value.textValue());
                    case "date" -> java.time.LocalDate.parse(value.textValue());
                    case "date-time" -> java.time.OffsetDateTime.parse(value.textValue());
                    default -> { }
                }
            } catch (RuntimeException invalid) { throw invalid("请求体字段格式错误：" + path); }
        }
        if (value.isNumber()) {
            BigDecimal actual = value.decimalValue();
            if (schema.has("minimum") && actual.compareTo(schema.path("minimum").decimalValue()) < 0
                    || schema.has("maximum") && actual.compareTo(schema.path("maximum").decimalValue()) > 0
                    || schema.has("exclusiveMinimum") && actual.compareTo(schema.path("exclusiveMinimum").decimalValue()) <= 0
                    || schema.has("exclusiveMaximum") && actual.compareTo(schema.path("exclusiveMaximum").decimalValue()) >= 0
                    || schema.has("multipleOf") && actual.remainder(schema.path("multipleOf").decimalValue()).signum() != 0) throw invalid("请求体数字不符合范围：" + path);
        }
        if (value.isArray()) {
            checkRange(value.size(), schema, "minItems", "maxItems", path);
            for (int index=0; index<value.size(); index++) {
                validate(schema.get("items"), value.get(index), path + "[" + index + "]", depth + 1);
                if (schema.path("uniqueItems").asBoolean()) for (int previous=0; previous<index; previous++)
                    if (equal(value.get(previous), value.get(index))) throw invalid("请求体数组项重复：" + path);
            }
        }
        if (value.isObject()) {
            checkRange(value.size(), schema, "minProperties", "maxProperties", path);
            for (JsonNode required : schema.path("required")) if (!value.has(required.asText())) throw invalid("缺少必填请求体字段：" + path + "." + required.asText());
            var fields = value.fields();
            while (fields.hasNext()) {
                var field = fields.next(); JsonNode declared = schema.path("properties").get(field.getKey());
                if (declared != null) validate(declared, field.getValue(), path + "." + field.getKey(), depth + 1);
                else {
                    JsonNode additional = schema.get("additionalProperties");
                    if (additional == null || additional.isBoolean() && !additional.booleanValue()) throw invalid("请求体包含契约未声明的字段：" + path + "." + field.getKey());
                    if (additional.isObject()) validate(additional, field.getValue(), path + "." + field.getKey(), depth + 1);
                }
            }
        }
    }

    private static boolean equal(JsonNode left, JsonNode right) {
        if (left.isNumber() && right.isNumber()) return left.decimalValue().compareTo(right.decimalValue()) == 0;
        if (left.isArray() && right.isArray()) {
            if (left.size() != right.size()) return false;
            for (int index = 0; index < left.size(); index++) if (!equal(left.get(index), right.get(index))) return false;
            return true;
        }
        if (left.isObject() && right.isObject()) {
            if (left.size() != right.size()) return false;
            var fields = left.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (!right.has(field.getKey()) || !equal(field.getValue(), right.get(field.getKey()))) return false;
            }
            return true;
        }
        return left.equals(right);
    }
    private static void checkLiteral(JsonNode value, int depth, int[] count) {
        bounded(depth, count);
        if (value.isTextual() && value.textValue().length() > 32768
                || value.isArray() && value.size() > MAX_ITEMS
                || value.isObject() && value.size() > MAX_FIELDS) throw invalid("enum/const 超过 JSON 值限制");
        if (value.isContainerNode()) for (JsonNode child : value) checkLiteral(child, depth + 1, count);
    }
    private static void nonNegative(JsonNode schema, String... keys) {
        for (String key : keys) if (schema.has(key) && (!schema.path(key).isIntegralNumber() || !schema.path(key).canConvertToInt() || schema.path(key).intValue() < 0)) throw invalid("长度或数量约束无效");
    }
    private static void checkRange(int size, JsonNode schema, String minimum, String maximum, String path) {
        if (schema.has(minimum) && size < schema.path(minimum).intValue() || schema.has(maximum) && size > schema.path(maximum).intValue()) throw invalid("请求体长度或数量不符合范围：" + path);
    }
    private static void bounded(int depth, int[] count) {
        if (depth > MAX_DEPTH || ++count[0] > MAX_NODES) throw invalid("请求体或 schema 超过深度 6 或节点 4096 限制");
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
