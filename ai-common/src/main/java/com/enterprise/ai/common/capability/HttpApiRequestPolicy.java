package com.enterprise.ai.common.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Deterministic request shape/binding only; no Console grants, identity, runtime or ledger semantics. */
public final class HttpApiRequestPolicy {
    private static final Set<String> OBJECT_KEYS = Set.of("type", "properties", "required", "additionalProperties",
            "description", "title");
    private static final Set<String> SCALAR_KEYS = Set.of("type", "enum", "const", "minLength", "maxLength",
            "minimum", "maximum", "default", "description", "title", "format", "nullable", "readOnly", "writeOnly");
    private HttpApiRequestPolicy() { }

    public static String unsupportedReason(JsonNode contract) {
        if (contract == null || !contract.isObject()) return "已接纳契约不可读取";
        if ("GET".equals(contract.path("identity").path("method").asText())) {
            return HttpApiFirstCallPolicy.unsupportedReason(contract);
        }
        if (!"POST".equals(contract.path("identity").path("method").asText())
                || !"WRITE".equals(contract.path("sideEffect").asText())) {
            return "仅支持只读 GET 或明确 WRITE 的 POST JSON";
        }
        JsonNode body = contract.path("requestBody");
        JsonNode schema = body.path("schema");
        // Capability's accepted canonical contract has no raw scanner `location` field.
        if (!body.isObject() || !onlyJson(body.path("contentTypes"))
                || !"object".equals(schema.path("type").asText()) || !schema.path("properties").isObject()
                || schema.path("properties").size() > 64 || !onlyKeys(schema, OBJECT_KEYS)) {
            return "POST 仅支持 application/json 扁平标量对象请求体";
        }
        JsonNode consumes = contract.path("identity").path("mappingConditions").path("consumes");
        if (!consumes.isMissingNode() && !consumes.isEmpty() && !onlyJson(consumes)) {
            return "POST 不支持此请求媒体类型映射";
        }
        if (schema.has("additionalProperties") && !schema.path("additionalProperties").isBoolean()) {
            return "不支持动态请求体字段契约";
        }
        Set<String> names = new LinkedHashSet<>();
        var properties = schema.path("properties").fields();
        while (properties.hasNext()) {
            var field = properties.next();
            JsonNode scalar = field.getValue();
            if (field.getKey().isBlank() || field.getKey().length() > 128 || field.getKey().contains(".")
                    || !Set.of("string", "integer", "number", "boolean").contains(scalar.path("type").asText())
                    || !onlyKeys(scalar, SCALAR_KEYS) || scalar.path("nullable").asBoolean()
                    || scalar.path("readOnly").asBoolean()) {
                return "请求体字段仅支持当前标量约束，不支持嵌套、数组、组合或 nullable/readOnly 契约";
            }
            names.add(field.getKey());
        }
        if (schema.has("required")) {
            if (!schema.path("required").isArray()) return "请求体 required 契约无效";
            for (JsonNode required : schema.path("required")) {
                if (!required.isTextual() || !names.contains(required.asText())) return "请求体 required 字段未声明";
            }
        }
        return HttpApiFirstCallPolicy.requestShapeReason(contract);
    }

    public static String connectionAuthReason(JsonNode contract, String mode, String type, String header) {
        String unsupported = unsupportedReason(contract);
        return unsupported == null ? HttpApiFirstCallPolicy.authReason(contract, mode, type, header) : unsupported;
    }

    public static BoundRequest bind(JsonNode contract, Map<String, Object> path, Map<String, Object> query,
                                     Map<String, Object> body, ObjectMapper mapper) {
        String unsupported = unsupportedReason(contract);
        if (unsupported != null) throw new IllegalArgumentException(unsupported);
        var bound = HttpApiFirstCallPolicy.bindParameters(contract, path, query, mapper);
        String method = contract.path("identity").path("method").asText();
        Map<String, Object> normalized = new LinkedHashMap<>();
        if ("GET".equals(method)) {
            if (body != null) throw new IllegalArgumentException("只读 GET 不接受请求体");
        } else {
            if (body == null && contract.path("requestBody").path("required").asBoolean()) {
                throw new IllegalArgumentException("缺少必填请求体");
            }
            if (body == null) return new BoundRequest(method, contract.path("sideEffect").asText(),
                    bound.encodedRoute(), bound.queryParams(), null);
            JsonNode schema = contract.path("requestBody").path("schema");
            Map<String, Object> input = body == null ? Map.of() : body;
            Set<String> names = new LinkedHashSet<>();
            var fields = schema.path("properties").fields();
            while (fields.hasNext()) {
                var field = fields.next(); names.add(field.getKey());
                Object value = input.get(field.getKey());
                boolean required = false;
                for (JsonNode name : schema.path("required")) if (field.getKey().equals(name.asText())) required = true;
                // JSON body absence is not an explicit null. This bounded policy rejects nullable contracts.
                // Source defaults remain descriptive; never delete or substitute confirmed input to make it valid.
                if (!input.containsKey(field.getKey())) {
                    if (required) throw new IllegalArgumentException("缺少必填请求体字段：" + field.getKey());
                } else {
                    if (value == null) throw new IllegalArgumentException("请求体字段不能为 null：" + field.getKey());
                    HttpApiFirstCallPolicy.scalar(value, field.getValue(), field.getKey(), mapper);
                    normalized.put(field.getKey(), value);
                }
            }
            if (!names.containsAll(input.keySet())) throw new IllegalArgumentException("请求体包含契约未声明的字段");
        }
        return new BoundRequest(method, contract.path("sideEffect").asText(), bound.encodedRoute(), bound.queryParams(),
                "POST".equals(method) && body != null ? java.util.Collections.unmodifiableMap(normalized) : null);
    }

    private static boolean onlyJson(JsonNode media) {
        return media.isArray() && media.size() == 1 && "application/json".equalsIgnoreCase(media.get(0).asText());
    }

    private static boolean onlyKeys(JsonNode node, Set<String> allowed) {
        if (!node.isObject()) return false;
        var keys = node.fieldNames();
        while (keys.hasNext()) if (!allowed.contains(keys.next())) return false;
        return true;
    }

    public record BoundRequest(String method, String sideEffect, String encodedRoute,
                               Map<String, String> queryParams, Map<String, Object> jsonBody) { }
}
