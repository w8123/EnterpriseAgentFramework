package com.enterprise.ai.model.instance;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 规范化模型连接配置：只保留规范字段，脱敏敏感值，合并编辑时的掩码占位，校验 baseUrl authority。
 */
public final class ConnectionConfigSupport {

    public static final String BASE_URL = "baseUrl";
    public static final String CHAT_PATH = "chatPath";
    public static final String EMBEDDING_PATH = "embeddingPath";
    public static final String RERANK_PATH = "rerankPath";
    public static final String API_KEY = "apiKey";
    public static final String AUTH_HEADER = "authHeader";
    public static final String AUTH_PREFIX = "authPrefix";

    private static final Set<String> CANONICAL_KEYS = Set.of(
            BASE_URL, CHAT_PATH, EMBEDDING_PATH, RERANK_PATH, API_KEY, AUTH_HEADER, AUTH_PREFIX);

    private static final Pattern SK_KEY = Pattern.compile("sk-[A-Za-z0-9_\\-]{8,}");
    private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._\\-+=/]{8,}");
    private static final int ERROR_MAX_LEN = 500;

    private ConnectionConfigSupport() {
    }

    /**
     * 仅保留规范连接字段；丢弃 apiBase/endpoint/apiKeyEnv/token 等别名与未知键。
     */
    public static Map<String, Object> normalize(Map<String, Object> source) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (source == null) {
            return out;
        }
        for (String key : CANONICAL_KEYS) {
            if (source.containsKey(key)) {
                out.put(key, source.get(key));
            }
        }
        return out;
    }

    public static boolean isSensitive(String key) {
        if (key == null) {
            return false;
        }
        String k = key.toLowerCase();
        return k.contains("key") || k.contains("secret") || k.contains("token") || k.contains("password");
    }

    public static Map<String, Object> mask(Map<String, Object> source) {
        Map<String, Object> masked = new LinkedHashMap<>();
        if (source == null) {
            return masked;
        }
        source.forEach((key, value) -> {
            if (value instanceof String s && isSensitive(key)) {
                masked.put(key, maskString(s));
            } else {
                masked.put(key, value);
            }
        });
        return masked;
    }

    /**
     * 合并编辑提交：敏感字段若为 ****** 掩码则保留原值。
     */
    public static Map<String, Object> mergeMasked(Map<String, Object> original, Map<String, Object> incoming) {
        if (incoming == null) {
            return original == null ? new LinkedHashMap<>() : new LinkedHashMap<>(original);
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        if (original != null) {
            merged.putAll(original);
        }
        Map<String, Object> normalizedIncoming = normalize(incoming);
        normalizedIncoming.forEach((key, value) -> {
            if (value instanceof String s && isSensitive(key) && isMaskedValue(s)
                    && original != null && original.containsKey(key)) {
                merged.put(key, original.get(key));
            } else {
                merged.put(key, value);
            }
        });
        return normalize(merged);
    }

    /**
     * 编辑合并：同 authority 走 {@link #mergeMasked}；authority 变更时禁止复用原密钥。
     */
    public static Map<String, Object> mergeForEdit(Map<String, Object> original, Map<String, Object> incoming) {
        Map<String, Object> orig = normalize(original);
        Map<String, Object> in = normalize(incoming);

        String newBaseUrl = resolveBaseUrlValue(in, orig);
        validateBaseUrl(newBaseUrl);

        String originalBaseUrl = stringValue(orig.get(BASE_URL));
        if (originalBaseUrl == null || originalBaseUrl.isBlank()) {
            Map<String, Object> merged = mergeMasked(orig, incoming == null ? in : incoming);
            merged.put(BASE_URL, newBaseUrl);
            return normalize(merged);
        }

        if (sameAuthority(originalBaseUrl, newBaseUrl)) {
            Map<String, Object> merged = mergeMasked(orig, incoming == null ? in : incoming);
            merged.put(BASE_URL, newBaseUrl);
            return normalize(merged);
        }

        assertCredentialsReenteredOnAuthorityChange(orig, in);

        Map<String, Object> merged = new LinkedHashMap<>();
        orig.forEach((key, value) -> {
            if (!isSensitive(key)) {
                merged.put(key, value);
            }
        });
        merged.put(BASE_URL, newBaseUrl);
        in.forEach((key, value) -> {
            if (value instanceof String s && isSensitive(key) && isMaskedValue(s)) {
                return;
            }
            merged.put(key, value);
        });
        merged.put(BASE_URL, newBaseUrl);
        return normalize(merged);
    }

    public static void stripSecrets(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return;
        }
        map.entrySet().removeIf(entry -> isSensitive(entry.getKey()));
    }

    public static void validateBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("connection.baseUrl is required");
        }
        URI uri;
        try {
            uri = URI.create(baseUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("connection.baseUrl is invalid: " + e.getMessage());
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("connection.baseUrl must use http or https");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("connection.baseUrl must not contain userInfo");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("connection.baseUrl host is required");
        }
    }

    /**
     * 小写 scheme + host + 规范化端口（http/80、https/443）。
     */
    public static String authorityKey(String baseUrl) {
        validateBaseUrl(baseUrl);
        URI uri = URI.create(baseUrl.trim());
        String scheme = uri.getScheme().toLowerCase();
        String host = uri.getHost().toLowerCase();
        int port = uri.getPort();
        if (port < 0) {
            port = "https".equals(scheme) ? 443 : 80;
        }
        return scheme + "://" + host + ":" + port;
    }

    public static boolean sameAuthority(String a, String b) {
        return authorityKey(a).equals(authorityKey(b));
    }

    public static String sanitizeError(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        String redacted = SK_KEY.matcher(message).replaceAll("sk-******");
        redacted = BEARER_TOKEN.matcher(redacted).replaceAll("Bearer ******");
        if (redacted.length() > ERROR_MAX_LEN) {
            return redacted.substring(0, ERROR_MAX_LEN) + "...";
        }
        return redacted;
    }

    public static boolean isMaskedValue(String value) {
        return value != null && value.contains("******");
    }

    public static String maskString(String value) {
        if (value == null || value.length() <= 8) {
            return "******";
        }
        return value.substring(0, 4) + "******" + value.substring(value.length() - 4);
    }

    public static String requireBaseUrl(Map<String, Object> connection) {
        Object value = connection == null ? null : connection.get(BASE_URL);
        String baseUrl = value == null ? null : String.valueOf(value).trim();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("connection.baseUrl is required");
        }
        validateBaseUrl(baseUrl);
        return baseUrl;
    }

    private static String resolveBaseUrlValue(Map<String, Object> incoming, Map<String, Object> original) {
        String fromIncoming = stringValue(incoming.get(BASE_URL));
        if (fromIncoming != null && !fromIncoming.isBlank()) {
            return fromIncoming.trim();
        }
        String fromOriginal = stringValue(original.get(BASE_URL));
        if (fromOriginal != null && !fromOriginal.isBlank()) {
            return fromOriginal.trim();
        }
        throw new IllegalArgumentException("connection.baseUrl is required");
    }

    private static void assertCredentialsReenteredOnAuthorityChange(Map<String, Object> original,
                                                                    Map<String, Object> incoming) {
        for (Map.Entry<String, Object> entry : original.entrySet()) {
            String key = entry.getKey();
            if (!isSensitive(key)) {
                continue;
            }
            if (!hasSecretValue(entry.getValue())) {
                continue;
            }
            Object incomingValue = incoming.get(key);
            if (!hasSecretValue(incomingValue)
                    || (incomingValue instanceof String s && isMaskedValue(s))) {
                throw new IllegalArgumentException(
                        "BaseURL authority changed; re-enter credentials (apiKey)");
            }
        }
    }

    private static boolean hasSecretValue(Object value) {
        if (value == null) {
            return false;
        }
        String text = String.valueOf(value).trim();
        return !text.isEmpty();
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
