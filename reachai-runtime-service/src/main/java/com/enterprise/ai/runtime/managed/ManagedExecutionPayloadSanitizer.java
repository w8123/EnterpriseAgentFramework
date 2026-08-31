package com.enterprise.ai.runtime.managed;

import com.enterprise.ai.runtime.managed.ManagedExecutionViews.WorkerEventV1;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class ManagedExecutionPayloadSanitizer {

    private static final Set<String> EVENT_TYPES = Set.of(
            "THREAD_STARTED", "TURN_STARTED", "COMMAND_STARTED", "COMMAND_COMPLETED",
            "FILE_CHANGE_STARTED", "FILE_CHANGE_COMPLETED", "TOOL_STARTED", "TOOL_COMPLETED",
            "DIFF_UPDATED", "FINAL_MESSAGE", "APPROVAL_REQUESTED", "APPROVAL_RESOLVED",
            "TURN_COMPLETED", "APP_SERVER_ERROR");
    private static final Set<String> PHASES = Set.of(
            "PROVISIONING", "RUNNING", "WAITING_APPROVAL", "FINALIZING",
            "SUCCEEDED", "FAILED", "CANCELLED");
    private static final Pattern SECRET_FIELD = Pattern.compile(
            "authorization|cookie|password|passwd|secret|token|api[_-]?key|access[_-]?key|private[_-]?key|credential",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{8,}");
    private static final Pattern OPENAI_KEY = Pattern.compile("\\bsk-[A-Za-z0-9_-]{12,}\\b");
    private static final Pattern GITHUB_TOKEN = Pattern.compile("(?i)\\bgh[pousr]_[A-Za-z0-9]{20,}\\b");
    private static final Pattern AWS_ACCESS_KEY = Pattern.compile("\\b(?:AKIA|ASIA)[A-Z0-9]{16}\\b");
    private static final Pattern KEY_VALUE_SECRET = Pattern.compile(
            "(?i)\\b(?:api[_-]?key|access[_-]?token|refresh[_-]?token|password|passwd|secret|authorization|cookie)"
                    + "\\b\\s*[:=]\\s*(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;]+)");
    private static final Pattern PRIVATE_KEY = Pattern.compile(
            "-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z0-9 ]*PRIVATE KEY-----",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern WINDOWS_PATH = Pattern.compile("\\b[A-Za-z]:[\\\\/][^\\s\\r\\n\\t\\\"'<>|]+");
    private static final Pattern UNIX_PATH = Pattern.compile(
            "(?<![A-Za-z0-9_$])/(?:[A-Za-z0-9._-]+/)+[A-Za-z0-9._-]+");

    private final ObjectMapper objectMapper;
    private final ManagedExecutorProperties properties;

    public ManagedExecutionPayloadSanitizer(ObjectMapper objectMapper,
                                            ManagedExecutorProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public SanitizedEvent sanitize(String expectedExecutionId, WorkerEventV1 event, Instant now) {
        if (event == null || !"reachai.managed-execution.event.v1".equals(event.schema())) {
            throw invalid("Managed execution event schema is invalid");
        }
        if (!expectedExecutionId.equals(event.executionId()) || event.sequence() == null || event.sequence() < 1) {
            throw invalid("Managed execution event identity or sequence is invalid");
        }
        if (!(expectedExecutionId + ":" + event.sequence()).equals(event.eventId())) {
            throw invalid("Managed execution eventId must match executionId and sequence");
        }
        String type = enumValue(event.type(), EVENT_TYPES, "event type");
        String phase = enumValue(event.phase(), PHASES, "event phase");
        String visibility = enumValue(event.visibility(), Set.of("OPERATOR", "PUBLIC"), "event visibility");
        if (!"DURABLE".equals(event.persistence())) throw invalid("Managed execution event must be durable");
        if (event.occurredAt() == null
                || event.occurredAt().isAfter(now.plus(15, ChronoUnit.MINUTES))
                || event.occurredAt().isBefore(now.minus(24, ChronoUnit.HOURS))) {
            throw invalid("Managed execution event timestamp is outside the accepted window");
        }
        String message = sanitizeText(event.message(), 2_000);
        Map<String, Object> data = sanitizeMap(event.data());
        String dataJson = json(data);
        LinkedHashMap<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("schema", event.schema());
        canonical.put("executionId", event.executionId());
        canonical.put("sequence", event.sequence());
        canonical.put("eventId", event.eventId());
        canonical.put("occurredAt", event.occurredAt().toString());
        canonical.put("type", type);
        canonical.put("phase", phase);
        canonical.put("visibility", visibility);
        canonical.put("persistence", "DURABLE");
        canonical.put("message", message);
        canonical.put("data", data);
        byte[] payload = json(canonical).getBytes(StandardCharsets.UTF_8);
        if (payload.length > properties.maxEventBytes()) {
            throw new ManagedExecutionException(413, "MANAGED_EVENT_TOO_LARGE",
                    "Managed execution event exceeds the byte limit");
        }
        return new SanitizedEvent(
                event.sequence(), event.eventId(), event.occurredAt(), type, phase,
                visibility, message, dataJson, sha256(payload));
    }

    private Map<String, Object> sanitizeMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) return Map.of();
        LinkedHashMap<String, Object> output = new LinkedHashMap<>();
        int count = 0;
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (count++ >= 64) break;
            String key = sanitizeText(entry.getKey(), 256);
            output.put(key, sanitizeValue(entry.getValue(), key, 1));
        }
        return output;
    }

    private Object sanitizeValue(Object value, String key, int depth) {
        if (SECRET_FIELD.matcher(key == null ? "" : key).find()) return "<redacted>";
        if (value == null || value instanceof Boolean || value instanceof Number) return value;
        if (value instanceof CharSequence) return sanitizeText(String.valueOf(value), 16_000);
        if (depth >= 5) return "<max-depth>";
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> output = new LinkedHashMap<>();
            int count = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (count++ >= 64) break;
                String childKey = sanitizeText(String.valueOf(entry.getKey()), 256);
                output.put(childKey, sanitizeValue(entry.getValue(), childKey, depth + 1));
            }
            return output;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> output = new ArrayList<>();
            for (Object item : iterable) {
                if (output.size() >= 50) break;
                output.add(sanitizeValue(item, "", depth + 1));
            }
            return output;
        }
        return "<unsupported:" + value.getClass().getSimpleName().toLowerCase(Locale.ROOT) + ">";
    }

    private String sanitizeText(String value, int maximum) {
        if (value == null) return "";
        String sanitized = value.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "");
        sanitized = PRIVATE_KEY.matcher(sanitized).replaceAll("<redacted-private-key>");
        sanitized = BEARER.matcher(sanitized).replaceAll("Bearer <redacted>");
        sanitized = OPENAI_KEY.matcher(sanitized).replaceAll("<redacted-openai-key>");
        sanitized = GITHUB_TOKEN.matcher(sanitized).replaceAll("<redacted-github-token>");
        sanitized = AWS_ACCESS_KEY.matcher(sanitized).replaceAll("<redacted-aws-key>");
        sanitized = KEY_VALUE_SECRET.matcher(sanitized).replaceAll("secret=<redacted>");
        sanitized = WINDOWS_PATH.matcher(sanitized).replaceAll("<external-path>");
        sanitized = UNIX_PATH.matcher(sanitized).replaceAll("<external-path>");
        int[] codePoints = sanitized.codePoints().limit(maximum).toArray();
        String bounded = new String(codePoints, 0, codePoints.length);
        return codePoints.length < sanitized.codePointCount(0, sanitized.length()) ? bounded + "…" : bounded;
    }

    private String enumValue(String value, Set<String> allowed, String label) {
        if (!StringUtils.hasText(value)) throw invalid("Managed execution " + label + " is required");
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw invalid("Managed execution " + label + " is invalid");
        return normalized;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException serialization) {
            throw invalid("Managed execution event payload is not serializable");
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static ManagedExecutionException invalid(String message) {
        return new ManagedExecutionException(400, "MANAGED_EVENT_INVALID", message);
    }

    public record SanitizedEvent(
            int sequence,
            String eventId,
            Instant occurredAt,
            String type,
            String phase,
            String visibility,
            String message,
            String dataJson,
            String payloadSha256) {
    }
}
