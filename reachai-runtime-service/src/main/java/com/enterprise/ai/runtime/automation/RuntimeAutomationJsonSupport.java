package com.enterprise.ai.runtime.automation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
final class RuntimeAutomationJsonSupport {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private static final int MAX_INPUT_BYTES = 64 * 1024;
    private static final Set<String> FORBIDDEN_KEY_PARTS = Set.of(
            "password", "passwd", "secret", "token", "apikey", "api_key", "credential", "privatekey");

    private final ObjectMapper mapper;

    RuntimeAutomationJsonSupport(ObjectMapper objectMapper) {
        this.mapper = objectMapper.copy()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
    }

    Map<String, Object> normalizeInput(Map<String, Object> value) {
        Map<String, Object> copy = deepMap(value == null ? Map.of() : value);
        rejectSecrets(copy, "input");
        byte[] encoded = bytes(copy);
        if (encoded.length > MAX_INPUT_BYTES) {
            throw RuntimeAutomationException.badRequest(
                    "AUTOMATION_INPUT_TOO_LARGE", "automation input must not exceed 64 KiB");
        }
        return copy;
    }

    Map<String, Object> readMap(String value) {
        if (!StringUtils.hasText(value)) return new LinkedHashMap<>();
        try {
            return mapper.readValue(value, MAP);
        } catch (Exception ex) {
            throw new IllegalStateException("stored Automation JSON is invalid", ex);
        }
    }

    String write(Object value) {
        try {
            return mapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception ex) {
            throw RuntimeAutomationException.badRequest(
                    "AUTOMATION_JSON_INVALID", "automation data cannot be serialized");
        }
    }

    String fingerprint(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes(value)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    String limit(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max);
    }

    private byte[] bytes(Object value) {
        return write(value).getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deepMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), deepValue(value)));
        return result;
    }

    private Object deepValue(Object value) {
        if (value instanceof Map<?, ?> map) return deepMap(map);
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            list.forEach(item -> result.add(deepValue(item)));
            return result;
        }
        return value;
    }

    private void rejectSecrets(Object value, String path) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                String normalized = key.replace("-", "").replace("_", "")
                        .toLowerCase(Locale.ROOT);
                if (FORBIDDEN_KEY_PARTS.stream()
                        .map(part -> part.replace("_", ""))
                        .anyMatch(normalized::contains)) {
                    throw RuntimeAutomationException.badRequest(
                            "AUTOMATION_INPUT_SECRET_FORBIDDEN",
                            "scheduled input cannot contain credential-like field: " + path + "." + key);
                }
                rejectSecrets(entry.getValue(), path + "." + key);
            }
        } else if (value instanceof Iterable<?> values) {
            int index = 0;
            for (Object item : values) rejectSecrets(item, path + "[" + index++ + "]");
        }
    }
}
