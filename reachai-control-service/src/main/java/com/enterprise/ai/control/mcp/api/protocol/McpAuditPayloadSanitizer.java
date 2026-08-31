package com.enterprise.ai.control.mcp.api.protocol;

import com.enterprise.ai.control.mcp.infrastructure.McpHubProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Opt-in MCP payload capture boundary with recursive secret redaction and a hard size cap. */
@Component
@RequiredArgsConstructor
public class McpAuditPayloadSanitizer {

    private static final Set<String> SECRET_KEY_PARTS = Set.of(
            "authorization", "credential", "password", "passwd", "secret", "token",
            "apikey", "api_key", "cookie", "sessionkey", "privatekey");
    private static final int MAX_DEPTH = 12;
    private static final int MAX_COLLECTION_ITEMS = 200;

    private final ObjectMapper objectMapper;
    private final McpHubProperties properties;

    public String capture(Object payload) {
        if (!properties.isCaptureAuditPayload() || payload == null) {
            return null;
        }
        try {
            String json = objectMapper.writeValueAsString(sanitize(payload, 0));
            int limit = Math.max(1024, Math.min(60_000, properties.getMaxAuditPayloadChars()));
            return json.length() <= limit ? json : json.substring(0, limit);
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }

    private Object sanitize(Object value, int depth) {
        if (value == null || value instanceof String || value instanceof Number
                || value instanceof Boolean || value instanceof Enum<?>) {
            return value;
        }
        if (depth >= MAX_DEPTH) {
            return "[depth-limited]";
        }
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> result = new LinkedHashMap<>();
            int count = 0;
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (entry.getKey() == null || count++ >= MAX_COLLECTION_ITEMS) {
                    continue;
                }
                String key = String.valueOf(entry.getKey());
                result.put(key, secretKey(key) ? "[redacted]" : sanitize(entry.getValue(), depth + 1));
            }
            return result;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> result = new ArrayList<>();
            for (Object item : iterable) {
                if (result.size() >= MAX_COLLECTION_ITEMS) {
                    break;
                }
                result.add(sanitize(item, depth + 1));
            }
            return result;
        }
        return String.valueOf(value);
    }

    private boolean secretKey(String value) {
        String normalized = value == null ? "" : value.replace("-", "")
                .replace(".", "").toLowerCase(Locale.ROOT);
        return SECRET_KEY_PARTS.stream().anyMatch(normalized::contains);
    }
}
