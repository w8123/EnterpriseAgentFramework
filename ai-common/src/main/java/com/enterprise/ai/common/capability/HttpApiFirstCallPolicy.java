package com.enterprise.ai.common.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative first-call gate shared by the owner read model and Runtime dispatch. */
public final class HttpApiFirstCallPolicy {
    private static final Pattern ROUTE_PARAMETER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_.-]*)}");
    private static final int MAX_PARAM_LENGTH = 2048;
    private HttpApiFirstCallPolicy() { }

    public static String unsupportedReason(JsonNode contract) {
        if (contract == null || !contract.isObject()) return "已接纳契约不可读取";
        JsonNode identity = contract.path("identity");
        if (!"GET".equals(identity.path("method").asText())) return "首批仅支持只读 GET 试调用";
        String sideEffect = contract.path("sideEffect").asText();
        if (!Set.of("NONE", "READ_ONLY").contains(sideEffect)) return "来源未确认此 API 为只读操作";
        if (contract.hasNonNull("requestBody")) return "首批不支持请求体";
        if (contract.path("identity").path("mappingConditions").path("consumes").size() > 0) {
            return "首批不支持额外的请求映射条件";
        }
        return requestShapeReason(contract);
    }

    // Shared bounded route/parameter/response/auth validation, without an execution-scope grant.
    static String requestShapeReason(JsonNode contract) {
        JsonNode identity = contract.path("identity");
        String route = identity.path("routeTemplate").asText();
        if (!route.startsWith("/") || route.startsWith("//") || route.contains("?") || route.contains("#")
                || route.contains("\\") || route.contains("%") || route.chars().anyMatch(ch -> ch < 32 || ch == 127)) {
            return "API 完整路径不符合首批试调用要求";
        }
        for (String segment : route.split("/")) {
            if (".".equals(segment) || "..".equals(segment)) return "API 路径不能包含相对路径段";
        }
        JsonNode mapping = identity.path("mappingConditions");
        if (mapping.path("conditions").size() > 0) {
            return "首批不支持额外的请求映射条件";
        }
        if (!contract.path("parameters").isArray()) return "请求参数契约不可读取";
        Set<String> declaredPath = new LinkedHashSet<>();
        for (JsonNode parameter : contract.path("parameters")) {
            String location = parameter.path("location").asText();
            String name = parameter.path("name").asText();
            if (!Set.of("PATH", "QUERY").contains(location)) return "首批仅支持 path/query 参数";
            if (name.isBlank() || !Set.of("string", "integer", "number", "boolean")
                    .contains(parameter.path("schema").path("type").asText())) {
                return "首批仅支持有明确类型的标量参数";
            }
            // Do not run a source-provided regex on a request thread. Java regex can be
            // catastrophic even for short input; later batches can add a bounded engine.
            if (parameter.path("schema").has("pattern")) return "首批不支持正则参数约束";
            if ("PATH".equals(location)) declaredPath.add(name);
        }
        Matcher matcher = ROUTE_PARAMETER.matcher(route);
        Set<String> routePath = new LinkedHashSet<>();
        while (matcher.find()) routePath.add(matcher.group(1));
        if (!routePath.equals(declaredPath) || route.replaceAll(ROUTE_PARAMETER.pattern(), "").matches(".*[{}].*")) {
            return "路径占位符与声明参数不一致";
        }
        if (!contract.path("responses").isArray() || contract.path("responses").isEmpty()) {
            return "缺少可比较的 JSON 响应契约";
        }
        boolean jsonObject = false;
        for (JsonNode response : contract.path("responses")) {
            if (!"object".equals(response.path("schema").path("type").asText())) continue;
            for (JsonNode media : response.path("contentTypes")) {
                if (media.asText().toLowerCase(Locale.ROOT).contains("json")) jsonObject = true;
            }
        }
        if (!jsonObject) return "首批仅支持 JSON 对象响应";
        JsonNode auth = contract.path("authentication");
        String state = auth.path("state").asText();
        if ("REQUIRED".equals(state)) {
            if (auth.path("schemes").size() != 1 || auth.path("requiredHeaderNames").size() != 1) {
                return "首批仅支持单个请求头 API Key 或 Bearer 认证";
            }
            String scheme = auth.path("schemes").get(0).asText().toLowerCase(Locale.ROOT);
            String header = auth.path("requiredHeaderNames").get(0).asText();
            if (!(scheme.startsWith("api_key:") || scheme.startsWith("http:bearer:"))) {
                return "认证方式不在首批试调用范围";
            }
            if (scheme.startsWith("http:bearer:") && !"Authorization".equalsIgnoreCase(header)) {
                return "Bearer 认证请求头不匹配";
            }
        } else if (!Set.of("NONE", "UNKNOWN").contains(state)) {
            return "认证声明无法确认";
        }
        return null;
    }

    public static String connectionAuthReason(JsonNode contract, String mode, String credentialType,
                                              String credentialHeaderName) {
        if (unsupportedReason(contract) != null) return unsupportedReason(contract);
        return authReason(contract, mode, credentialType, credentialHeaderName);
    }

    static String authReason(JsonNode contract, String mode, String credentialType, String credentialHeaderName) {
        if (!Set.of("NONE", "API_KEY_HEADER", "BEARER").contains(mode)) return "连接认证方式不在首批支持范围";
        JsonNode auth = contract.path("authentication");
        String state = auth.path("state").asText();
        if ("REQUIRED".equals(state) && "NONE".equals(mode)) return "API 声明必须使用项目凭据";
        if ("NONE".equals(state) && !"NONE".equals(mode)) return "API 声明无需认证，不允许附加凭据";
        if (!"NONE".equals(mode) && !mode.equals(credentialType)) return "项目凭据类型与连接认证方式不一致";
        if ("REQUIRED".equals(state)) {
            String scheme = auth.path("schemes").get(0).asText().toLowerCase(Locale.ROOT);
            String header = auth.path("requiredHeaderNames").get(0).asText();
            if (scheme.startsWith("api_key:") && (!"API_KEY_HEADER".equals(mode)
                    || credentialHeaderName == null || !header.equalsIgnoreCase(credentialHeaderName))) {
                return "项目 API Key 请求头与来源声明不匹配";
            }
            if (scheme.startsWith("http:bearer:") && !"BEARER".equals(mode)) {
                return "项目 Bearer 凭据与来源声明不匹配";
            }
        }
        return null;
    }

    public static BoundRequest bind(JsonNode contract, Map<String, Object> pathParams,
                                    Map<String, Object> queryParams, ObjectMapper mapper) {
        String unsupported = unsupportedReason(contract);
        if (unsupported != null) throw new IllegalArgumentException(unsupported);
        return bindParameters(contract, pathParams, queryParams, mapper);
    }

    static BoundRequest bindParameters(JsonNode contract, Map<String, Object> pathParams,
                                       Map<String, Object> queryParams, ObjectMapper mapper) {
        Map<String, Object> path = pathParams == null ? Map.of() : pathParams;
        Map<String, Object> query = queryParams == null ? Map.of() : queryParams;
        Set<String> declaredPath = new LinkedHashSet<>();
        Set<String> declaredQuery = new LinkedHashSet<>();
        Map<String, String> boundPath = new LinkedHashMap<>();
        Map<String, String> boundQuery = new LinkedHashMap<>();
        for (JsonNode parameter : contract.path("parameters")) {
            String name = parameter.path("name").asText();
            boolean isPath = "PATH".equals(parameter.path("location").asText());
            if (isPath) declaredPath.add(name); else declaredQuery.add(name);
            Map<String, Object> input = isPath ? path : query;
            Object value = input.get(name);
            if (value == null) {
                if (isPath || parameter.path("required").asBoolean()) {
                    throw new IllegalArgumentException("缺少必填参数：" + name);
                }
                continue;
            }
            String normalized = scalar(value, parameter.path("schema"), name, mapper);
            if (isPath) boundPath.put(name, normalized); else boundQuery.put(name, normalized);
        }
        if (!declaredPath.containsAll(path.keySet()) || !declaredQuery.containsAll(query.keySet())) {
            throw new IllegalArgumentException("请求包含契约未声明的参数");
        }
        String route = contract.path("identity").path("routeTemplate").asText();
        for (Map.Entry<String, String> entry : boundPath.entrySet()) {
            route = route.replace("{" + entry.getKey() + "}",
                    URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8).replace("+", "%20"));
        }
        if (route.indexOf('{') >= 0 || route.indexOf('}') >= 0) {
            throw new IllegalArgumentException("路径参数尚未填全");
        }
        return new BoundRequest(route, Map.copyOf(boundQuery));
    }

    static String scalar(Object value, JsonNode schema, String name, ObjectMapper mapper) {
        String type = schema.path("type").asText();
        String formatted;
        switch (type) {
            case "string" -> {
                if (!(value instanceof String text)) throw new IllegalArgumentException("参数类型错误：" + name);
                formatted = text;
                if (schema.has("minLength") && formatted.length() < schema.path("minLength").asInt()) {
                    throw new IllegalArgumentException("参数长度不足：" + name);
                }
                if (schema.has("maxLength") && formatted.length() > schema.path("maxLength").asInt()) {
                    throw new IllegalArgumentException("参数过长：" + name);
                }
            }
            case "integer" -> {
                if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                        || number.doubleValue() != number.longValue()) throw new IllegalArgumentException("参数类型错误：" + name);
                formatted = String.valueOf(number.longValue());
            }
            case "number" -> {
                if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
                    throw new IllegalArgumentException("参数类型错误：" + name);
                }
                formatted = new BigDecimal(number.toString()).toPlainString();
            }
            case "boolean" -> {
                if (!(value instanceof Boolean flag)) throw new IllegalArgumentException("参数类型错误：" + name);
                formatted = flag.toString();
            }
            default -> throw new IllegalArgumentException("不支持的参数类型：" + name);
        }
        if (formatted.length() > MAX_PARAM_LENGTH || formatted.chars().anyMatch(ch -> ch < 32 || ch == 127)) {
            throw new IllegalArgumentException("参数包含不可用字符或过长：" + name);
        }
        JsonNode actual = mapper.valueToTree(value);
        if (schema.has("const") && !schema.get("const").equals(actual)) throw new IllegalArgumentException("参数不符合固定值：" + name);
        if (schema.path("enum").isArray()) {
            boolean matched = false;
            for (JsonNode option : schema.path("enum")) if (option.equals(actual)) matched = true;
            if (!matched) throw new IllegalArgumentException("参数不在允许值中：" + name);
        }
        if (value instanceof Number && schema.has("minimum")
                && new BigDecimal(formatted).compareTo(schema.path("minimum").decimalValue()) < 0) {
            throw new IllegalArgumentException("参数低于最小值：" + name);
        }
        if (value instanceof Number && schema.has("maximum")
                && new BigDecimal(formatted).compareTo(schema.path("maximum").decimalValue()) > 0) {
            throw new IllegalArgumentException("参数高于最大值：" + name);
        }
        return formatted;
    }

    public record BoundRequest(String encodedRoute, Map<String, String> queryParams) { }
}
