package com.enterprise.ai.common.capability;

import java.util.LinkedHashMap;
import java.util.Map;

/** Versioned Capability result that remains JSON-compatible with legacy Map consumers. */
public record CapabilityInvocationResponse(
        int contractVersion,
        String invocationId,
        String qualifiedName,
        String toolName,
        String toolTitle,
        CapabilityInvocationStatus status,
        boolean success,
        Object data,
        String code,
        String message,
        CapabilityInvocationFailureCategory failureCategory,
        boolean retryable,
        Long latencyMs,
        Integer attempt,
        String businessCode,
        Map<String, Object> safeMetadata
) {

    private static final java.util.Set<String> SAFE_METADATA_KEYS = java.util.Set.of(
            "statusCode", "transport", "provider", "elapsedMs");
    private static final java.util.regex.Pattern SAFE_INPUT_PATH =
            java.util.regex.Pattern.compile("[A-Za-z0-9_.\\[\\]-]{1,160}");
    private static final java.util.regex.Pattern SAFE_INPUT_REASON =
            java.util.regex.Pattern.compile("[A-Z0-9_]{1,80}");

    public CapabilityInvocationResponse {
        if (contractVersion != CapabilityInvocationRequest.CONTRACT_VERSION) {
            throw new IllegalArgumentException("Unsupported Capability invocation response contract: "
                    + contractVersion);
        }
        status = status == null ? CapabilityInvocationStatus.TECHNICAL_FAILED : status;
        failureCategory = failureCategory == null
                ? CapabilityInvocationFailureCategory.INTERNAL
                : failureCategory;
        safeMetadata = sanitizeMetadata(safeMetadata);
    }

    public Map<String, Object> toLegacyMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contractVersion", contractVersion);
        result.put("invocationId", invocationId);
        result.put("qualifiedName", qualifiedName);
        if (toolName != null) result.put("toolName", toolName);
        if (toolTitle != null) result.put("toolTitle", toolTitle);
        result.put("status", status.name());
        result.put("success", success);
        result.put("data", data);
        if (code != null) result.put("code", code);
        if (message != null) result.put("message", message);
        result.put("failureCategory", failureCategory.name());
        result.put("retryable", retryable);
        if (latencyMs != null) result.put("latencyMs", latencyMs);
        if (attempt != null) result.put("attempt", attempt);
        if (businessCode != null) result.put("businessCode", businessCode);
        result.put("metadata", safeMetadata);
        return result;
    }

    /**
     * Compatibility adapter for in-process Runtime test doubles and staged callers that still
     * implement the former Map boundary. Production Runtime-to-Capability traffic uses the
     * versioned response directly.
     */
    public static CapabilityInvocationResponse fromLegacy(CapabilityInvocationRequest request,
                                                          Map<String, Object> legacy) {
        Map<String, Object> value = legacy == null ? Map.of() : legacy;
        boolean success = !Boolean.FALSE.equals(value.get("success"));
        String code = text(value.get("code"));
        String message = firstText(value.get("message"), value.get("answer"));
        Object data = firstPresent(value.get("data"), value.get("result"), value.get("body"));
        String explicitStatus = text(value.get("status"));
        CapabilityInvocationStatus status = enumValue(
                CapabilityInvocationStatus.class, explicitStatus, null);
        CapabilityInvocationFailureCategory category = enumValue(
                CapabilityInvocationFailureCategory.class,
                text(value.get("failureCategory")), null);
        if (status == null) {
            if (success) {
                status = CapabilityInvocationStatus.SUCCEEDED;
            } else if ("CAPABILITY_BUSINESS_RESPONSE_FAILED".equals(code)) {
                status = CapabilityInvocationStatus.BUSINESS_FAILED;
            } else if (isRejectedCode(code)) {
                status = CapabilityInvocationStatus.REJECTED;
            } else {
                status = CapabilityInvocationStatus.TECHNICAL_FAILED;
            }
        }
        if (category == null) {
            category = switch (status) {
                case SUCCEEDED -> CapabilityInvocationFailureCategory.NONE;
                case BUSINESS_FAILED -> CapabilityInvocationFailureCategory.BUSINESS_RESPONSE;
                case REJECTED -> rejectedCategory(code);
                case TECHNICAL_FAILED -> technicalCategory(code);
            };
        }
        boolean retryable = status == CapabilityInvocationStatus.TECHNICAL_FAILED
                && Boolean.TRUE.equals(value.get("retryable"))
                && (category == CapabilityInvocationFailureCategory.TIMEOUT
                || category == CapabilityInvocationFailureCategory.CONNECTIVITY);
        return new CapabilityInvocationResponse(
                CapabilityInvocationRequest.CONTRACT_VERSION,
                request.invocationId(),
                request.qualifiedName(),
                text(value.get("toolName")),
                text(value.get("toolTitle")),
                status,
                status == CapabilityInvocationStatus.SUCCEEDED,
                data,
                code,
                message,
                category,
                retryable,
                longValue(value.get("latencyMs")),
                intValue(value.get("attempt")),
                text(value.get("businessCode")),
                map(value.get("metadata")));
    }

    private static Map<String, Object> sanitizeMetadata(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String key : SAFE_METADATA_KEYS) {
            Object value = source.get(key);
            if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                safe.put(key, value);
            }
        }
        java.util.List<Map<String, String>> inputDiagnostics = sanitizeInputDiagnostics(source.get("inputDiagnostics"));
        if (!inputDiagnostics.isEmpty()) {
            safe.put("inputDiagnostics", inputDiagnostics);
        }
        return safe.isEmpty()
                ? Map.of()
                : java.util.Collections.unmodifiableMap(safe);
    }

    private static java.util.List<Map<String, String>> sanitizeInputDiagnostics(Object raw) {
        if (!(raw instanceof Iterable<?> values)) return java.util.List.of();
        java.util.List<Map<String, String>> safe = new java.util.ArrayList<>();
        for (Object value : values) {
            if (safe.size() >= 20 || !(value instanceof Map<?, ?> item)) continue;
            Object path = item.get("path");
            Object reason = item.get("reason");
            if (!(path instanceof String safePath) || !(reason instanceof String safeReason)
                    || !SAFE_INPUT_PATH.matcher(safePath).matches()
                    || !SAFE_INPUT_REASON.matcher(safeReason).matches()) {
                continue;
            }
            safe.add(Map.of("path", safePath, "reason", safeReason));
        }
        return safe.isEmpty() ? java.util.List.of() : java.util.List.copyOf(safe);
    }

    private static boolean isRejectedCode(String code) {
        if (code == null) return false;
        return code.contains("NOT_FOUND") || code.contains("DISABLED")
                || code.contains("POLICY") || code.contains("IDENTITY")
                || code.contains("CONFIG");
    }

    private static CapabilityInvocationFailureCategory rejectedCategory(String code) {
        if (code == null) return CapabilityInvocationFailureCategory.POLICY_REJECTED;
        if (code.contains("NOT_FOUND")) return CapabilityInvocationFailureCategory.NOT_FOUND;
        if (code.contains("DISABLED")) return CapabilityInvocationFailureCategory.DISABLED;
        if (code.contains("IDENTITY")) return CapabilityInvocationFailureCategory.IDENTITY_REQUIRED;
        if (code.contains("CONFIG")) return CapabilityInvocationFailureCategory.CONFIGURATION_INVALID;
        return CapabilityInvocationFailureCategory.POLICY_REJECTED;
    }

    private static CapabilityInvocationFailureCategory technicalCategory(String code) {
        if (code != null && code.contains("TIMEOUT")) return CapabilityInvocationFailureCategory.TIMEOUT;
        if (code != null && (code.contains("CONNECT") || code.contains("UNAVAILABLE"))) {
            return CapabilityInvocationFailureCategory.CONNECTIVITY;
        }
        if (code != null && code.contains("RESPONSE")) return CapabilityInvocationFailureCategory.RESPONSE_INVALID;
        return CapabilityInvocationFailureCategory.INTERNAL;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> {
            if (key != null) result.put(String.valueOf(key), item);
        });
        return result;
    }

    private static Object firstPresent(Object... values) {
        if (values == null) return null;
        for (Object value : values) if (value != null) return value;
        return null;
    }

    private static String firstText(Object... values) {
        if (values == null) return null;
        for (Object value : values) {
            String text = text(value);
            if (text != null) return text;
        }
        return null;
    }

    private static String text(Object value) {
        if (value == null) return null;
        String result = String.valueOf(value).trim();
        return result.isEmpty() ? null : result;
    }

    private static Long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            String parsed = text(value);
            return parsed == null ? null : Long.parseLong(parsed);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Integer intValue(Object value) {
        Long parsed = longValue(value);
        return parsed == null ? null : parsed.intValue();
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, E fallback) {
        if (value == null) return fallback;
        try {
            return Enum.valueOf(type, value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
