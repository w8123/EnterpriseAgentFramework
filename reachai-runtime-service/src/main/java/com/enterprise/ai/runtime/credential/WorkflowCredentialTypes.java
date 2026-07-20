package com.enterprise.ai.runtime.credential;

import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Canonical Workflow credential types and secret-shape validation.
 * Historical aliases are normalized only in this layer.
 */
public final class WorkflowCredentialTypes {

    public static final String BEARER = "BEARER";
    public static final String BASIC = "BASIC";
    public static final String API_KEY_HEADER = "API_KEY_HEADER";
    public static final String API_KEY_QUERY = "API_KEY_QUERY";
    public static final String CUSTOM_HEADERS = "CUSTOM_HEADERS";

    private static final Set<String> CANONICAL = Set.of(
            BEARER, BASIC, API_KEY_HEADER, API_KEY_QUERY, CUSTOM_HEADERS);

    private static final Set<String> PROTECTED_HEADERS = Set.of(
            "host", "content-length", "transfer-encoding", "connection",
            "keep-alive", "upgrade", "te", "trailer", "proxy-connection");

    private static final Pattern HEADER_NAME = Pattern.compile("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$");
    private static final Pattern PARAM_NAME = Pattern.compile("^[A-Za-z0-9_.-]{1,64}$");

    private WorkflowCredentialTypes() {
    }

    public static boolean isCanonical(String type) {
        return StringUtils.hasText(type) && CANONICAL.contains(type.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * Normalize storage/runtime type to canonical form. Aliases are accepted only here.
     */
    public static String normalizeType(String rawType) {
        String type = required(rawType, "type").toUpperCase(Locale.ROOT);
        return switch (type) {
            case BEARER, "TOKEN" -> BEARER;
            case BASIC -> BASIC;
            case API_KEY_HEADER, "API_KEY", "HEADER" -> API_KEY_HEADER;
            case API_KEY_QUERY, "QUERY" -> API_KEY_QUERY;
            case CUSTOM_HEADERS, "HEADERS", "CUSTOM" -> CUSTOM_HEADERS;
            default -> throw new IllegalArgumentException(
                    "Unsupported credential type: " + type + ". Canonical types: "
                            + String.join(", ", CANONICAL));
        };
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> validateAndNormalizeSecret(String canonicalType, Map<String, Object> secret) {
        Map<String, Object> source = secret == null ? Map.of() : secret;
        return switch (canonicalType) {
            case BEARER -> {
                String token = firstText(source, "token", "accessToken", "value");
                if (!StringUtils.hasText(token)) {
                    throw new IllegalArgumentException("BEARER secret requires token");
                }
                rejectInjection(token, "token");
                yield Map.of("token", token);
            }
            case BASIC -> {
                String username = firstText(source, "username", "user");
                String password = firstText(source, "password", "secret");
                if (!StringUtils.hasText(username)) {
                    throw new IllegalArgumentException("BASIC secret requires username");
                }
                rejectInjection(username, "username");
                if (password != null) {
                    rejectInjection(password, "password");
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("username", username);
                out.put("password", password == null ? "" : password);
                yield out;
            }
            case API_KEY_HEADER -> {
                String headerName = firstText(source, "headerName", "name");
                String apiKey = firstText(source, "apiKey", "value", "token");
                if (!StringUtils.hasText(headerName) || !HEADER_NAME.matcher(headerName).matches()) {
                    throw new IllegalArgumentException("API_KEY_HEADER secret requires valid headerName");
                }
                if (PROTECTED_HEADERS.contains(headerName.toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("API_KEY_HEADER headerName is protected: " + headerName);
                }
                if (!StringUtils.hasText(apiKey)) {
                    throw new IllegalArgumentException("API_KEY_HEADER secret requires apiKey");
                }
                rejectInjection(headerName, "headerName");
                rejectInjection(apiKey, "apiKey");
                yield Map.of("headerName", headerName, "apiKey", apiKey);
            }
            case API_KEY_QUERY -> {
                String paramName = firstText(source, "paramName", "name");
                String apiKey = firstText(source, "apiKey", "value", "token");
                if (!StringUtils.hasText(paramName) || !PARAM_NAME.matcher(paramName).matches()) {
                    throw new IllegalArgumentException("API_KEY_QUERY secret requires valid paramName");
                }
                if (!StringUtils.hasText(apiKey)) {
                    throw new IllegalArgumentException("API_KEY_QUERY secret requires apiKey");
                }
                rejectInjection(paramName, "paramName");
                rejectInjection(apiKey, "apiKey");
                yield Map.of("paramName", paramName, "apiKey", apiKey);
            }
            case CUSTOM_HEADERS -> {
                Object headersRaw = source.get("headers");
                if (!(headersRaw instanceof Map<?, ?> headersMap) || headersMap.isEmpty()) {
                    throw new IllegalArgumentException("CUSTOM_HEADERS secret requires headers map");
                }
                Map<String, Object> headers = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : headersMap.entrySet()) {
                    String key = entry.getKey() == null ? "" : String.valueOf(entry.getKey()).trim();
                    if (!StringUtils.hasText(key) || !HEADER_NAME.matcher(key).matches()) {
                        throw new IllegalArgumentException("CUSTOM_HEADERS contains invalid header name");
                    }
                    if (PROTECTED_HEADERS.contains(key.toLowerCase(Locale.ROOT))) {
                        throw new IllegalArgumentException("CUSTOM_HEADERS contains protected header: " + key);
                    }
                    if (entry.getValue() == null) {
                        throw new IllegalArgumentException("CUSTOM_HEADERS values must be strings");
                    }
                    String value = String.valueOf(entry.getValue());
                    rejectInjection(key, "headerName");
                    rejectInjection(value, "headerValue");
                    headers.put(key, value);
                }
                yield Map.of("headers", headers);
            }
            default -> throw new IllegalArgumentException("Unsupported credential type: " + canonicalType);
        };
    }

    public static Set<String> sensitiveHeaderNames(Map<String, Object> secret, String canonicalType) {
        Set<String> names = new java.util.LinkedHashSet<>();
        names.add("authorization");
        names.add("proxy-authorization");
        names.add("cookie");
        names.add("set-cookie");
        names.add("x-api-key");
        names.add("api-key");
        if (!StringUtils.hasText(canonicalType) || secret == null) {
            return names;
        }
        if (API_KEY_HEADER.equals(canonicalType)) {
            String headerName = firstText(secret, "headerName", "name");
            if (StringUtils.hasText(headerName)) {
                names.add(headerName.toLowerCase(Locale.ROOT));
            }
        }
        if (CUSTOM_HEADERS.equals(canonicalType)) {
            Object headers = secret.get("headers");
            if (headers instanceof Map<?, ?> map) {
                for (Object key : map.keySet()) {
                    if (key != null) {
                        names.add(String.valueOf(key).toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        return names;
    }

    private static void rejectInjection(String value, String field) {
        if (value != null && (value.contains("\r") || value.contains("\n"))) {
            throw new IllegalArgumentException("Credential " + field + " must not contain CRLF");
        }
    }

    private static String firstText(Map<String, Object> secret, String... keys) {
        for (String key : keys) {
            Object value = secret.get(key);
            if (value != null && StringUtils.hasText(String.valueOf(value))) {
                return String.valueOf(value).trim();
            }
        }
        return null;
    }

    private static String required(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("Credential " + field + " is required");
        }
        return value.trim();
    }
}
