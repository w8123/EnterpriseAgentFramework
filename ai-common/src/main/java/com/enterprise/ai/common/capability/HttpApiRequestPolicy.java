package com.enterprise.ai.common.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;

/** Shared request binding only. Console confirmation and Studio read-only grants remain separate. */
public final class HttpApiRequestPolicy {
    private HttpApiRequestPolicy() { }

    public static boolean requiresWriteConfirmation(String sideEffect) {
        return !Set.of("NONE", "READ_ONLY").contains(sideEffect == null ? "" : sideEffect);
    }

    public static String unsupportedReason(JsonNode contract) {
        if (contract == null || !contract.isObject()) return "已接纳契约不可读取";
        String method = contract.path("identity").path("method").asText();
        if (!Set.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(method)) return "当前 Console 支持 GET/POST/PUT/PATCH/DELETE";
        if (!Set.of("NONE", "READ_ONLY", "IDEMPOTENT_WRITE", "WRITE", "IRREVERSIBLE").contains(contract.path("sideEffect").asText())) return "操作性质尚不能确认";
        JsonNode body = contract.get("requestBody");
        if (body != null && !body.isNull()) {
            if ("GET".equals(method)) return "GET 不支持请求体";
            String media = bodyContentType(contract);
            if (media == null) return "请求体媒体类型尚未确认，或是尚不支持的 multipart/binary 传输";
            String schemaReason = HttpApiJsonBinding.unsupportedReason(body.get("schema"));
            if (schemaReason != null) return schemaReason;
            if ("text/plain".equals(media) && !"string".equals(body.path("schema").path("type").asText())) return "text/plain 请求体必须声明 string 类型";
        }
        JsonNode consumes = contract.path("identity").path("mappingConditions").path("consumes");
        if (consumes.size() > 0 && (body == null || body.isNull() || consumes.size() != 1
                || !consumes.get(0).asText().equalsIgnoreCase(bodyContentType(contract)))) return "请求映射的媒体条件不在当前传输范围";
        return HttpApiFirstCallPolicy.requestShapeReason(contract, true);
    }

    /** A successful write must return the declared JSON container before its outcome is confirmed. */
    public static boolean confirmsResponse(JsonNode contract, Object parsedBody) {
        for (JsonNode response : contract.path("responses")) {
            boolean json = false;
            for (JsonNode media : response.path("contentTypes")) if (media.asText().toLowerCase(java.util.Locale.ROOT).contains("json")) json = true;
            if (json && ("object".equals(response.path("schema").path("type").asText()) && parsedBody instanceof Map<?, ?>
                    || "array".equals(response.path("schema").path("type").asText()) && parsedBody instanceof java.util.List<?>)) return true;
        }
        return false;
    }

    public static String bodyContentType(JsonNode contract) {
        JsonNode media = contract.path("requestBody").path("contentTypes");
        if (!media.isArray() || media.size() != 1 || !media.get(0).isTextual()) return null;
        String type = media.get(0).asText().toLowerCase(java.util.Locale.ROOT);
        return Set.of("application/json", "text/plain").contains(type) ? type : null;
    }

    public static String connectionAuthReason(JsonNode contract, String mode, String type, String header) {
        String unsupported = unsupportedReason(contract);
        return unsupported == null ? HttpApiFirstCallPolicy.authReason(contract, mode, type, header) : unsupported;
    }

    public static BoundRequest bind(JsonNode contract, Map<String,Object> path, Map<String,Object> query,
                                     Object body, ObjectMapper mapper) {
        String unsupported = unsupportedReason(contract);
        if (unsupported != null) throw new IllegalArgumentException(unsupported);
        var parameters = HttpApiFirstCallPolicy.bindParameters(contract, path, query, mapper);
        JsonNode declaration = contract.get("requestBody");
        Object boundBody = null;
        if (declaration == null || declaration.isNull()) {
            if (body != null) throw new IllegalArgumentException("当前契约不接受请求体");
        } else if (body == null) {
            if (declaration.path("required").asBoolean()) throw new IllegalArgumentException("缺少必填请求体");
        } else boundBody = HttpApiJsonBinding.bind(declaration.get("schema"), body, mapper);
        return new BoundRequest(contract.path("identity").path("method").asText(), contract.path("sideEffect").asText(),
                parameters.encodedRoute(), parameters.queryParams(), boundBody, bodyContentType(contract));
    }

    public record BoundRequest(String method, String sideEffect, String encodedRoute,
                               Map<String,String> queryParams, Object jsonBody, String bodyContentType) { }
}
