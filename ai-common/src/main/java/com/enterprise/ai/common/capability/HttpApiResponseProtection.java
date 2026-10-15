package com.enterprise.ai.common.capability;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Pure response protection. Capture before dispatch so source changes cannot erase sensitive declarations. */
public final class HttpApiResponseProtection {
    private static final Set<String> SENSITIVE_NAMES = Set.of("password", "secret", "token", "apikey",
            "authorization", "cookie", "credential", "privatekey");
    private final List<String> secrets;

    private HttpApiResponseProtection(List<String> secrets) { this.secrets = List.copyOf(secrets); }

    public static HttpApiResponseProtection prepare(JsonNode contract, Map<String, Object> path,
                                                    Map<String, Object> query, Object body,
                                                    Map<String, Object> credential) {
        List<String> secrets = new ArrayList<>();
        collectInputSecrets(path, secrets, null);
        collectInputSecrets(query, secrets, null);
        collectBodySecrets(body, secrets, contract.path("requestBody").path("schema"));
        if (credential != null) for (String key : List.of("token", "apiKey", "password")) {
            Object value = credential.get(key);
            if (value instanceof String text && !text.isBlank()) secrets.add(text);
        }
        return new HttpApiResponseProtection(secrets);
    }

    public Object protect(Object value) { return redact(value, secrets, null); }

    public static void collectInputSecrets(Map<String, Object> input, List<String> secrets, JsonNode properties) {
        if (input == null) return;
        input.forEach((name, value) -> {
            JsonNode field = properties == null ? null : properties.path(name);
            boolean declared = field != null && ("password".equals(field.path("format").asText())
                    || field.path("writeOnly").isBoolean() && field.path("writeOnly").asBoolean());
            if ((sensitive(name) || declared) && value != null && !String.valueOf(value).isBlank()) {
                secrets.add(String.valueOf(value));
            }
        });
    }

    /** Capture nested and root-array input secrets under the accepted schema before dispatch. */
    public static void collectBodySecrets(Object input, List<String> secrets, JsonNode schema) {
        collectBodySecrets(input, secrets, schema, false);
    }

    private static void collectBodySecrets(Object input, List<String> secrets, JsonNode schema, boolean inherited) {
        boolean declared = inherited || schema != null && ("password".equals(schema.path("format").asText())
                || schema.path("writeOnly").asBoolean(false));
        if (input instanceof Map<?, ?> map) {
            map.forEach((key, value) -> {
                String name = String.valueOf(key);
                JsonNode field = schema == null ? null : schema.path("properties").get(name);
                if (field == null && schema != null && schema.path("additionalProperties").isObject())
                    field = schema.path("additionalProperties");
                collectBodySecrets(value, secrets, field, declared || sensitive(name));
            });
        } else if (input instanceof Iterable<?> items) {
            for (Object item : items) collectBodySecrets(item, secrets, schema == null ? null : schema.get("items"), declared);
        } else if (declared && input != null && !String.valueOf(input).isBlank()) secrets.add(String.valueOf(input));
    }

    public static Object redact(Object value, List<String> secrets, String name) {
        if (name != null && sensitive(name)) return "[redacted]";
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> safe = new LinkedHashMap<>();
            map.forEach((key, item) -> safe.put(String.valueOf(key), redact(item, secrets, String.valueOf(key))));
            return safe;
        }
        if (value instanceof Iterable<?> items) {
            List<Object> safe = new ArrayList<>();
            for (Object item : items) safe.add(redact(item, secrets, name));
            return safe;
        }
        if (value instanceof String text) {
            String safe = text;
            for (String secret : secrets) safe = safe.replace(secret, "[redacted]");
            return safe;
        }
        return value != null && secrets.contains(String.valueOf(value)) ? "[redacted]" : value;
    }

    public static boolean sensitive(String name) {
        String normalized = name.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        return SENSITIVE_NAMES.stream().anyMatch(normalized::contains);
    }
}
