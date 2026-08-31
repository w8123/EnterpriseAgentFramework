package com.enterprise.ai.common.capability;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Versioned internal Runtime-to-Capability request; never accepts trusted Java marker objects. */
public record CapabilityInvocationRequest(
        int contractVersion,
        String invocationId,
        String qualifiedName,
        Map<String, Object> input,
        Map<String, Object> context,
        Map<String, Object> constraints,
        Map<String, Object> evaluationPolicy,
        Long deadlineEpochMs,
        String idempotencyKey,
        Map<String, Object> traceContext
) {

    public static final int CONTRACT_VERSION = 1;

    public CapabilityInvocationRequest {
        if (contractVersion != CONTRACT_VERSION) {
            throw new IllegalArgumentException("Unsupported Capability invocation contract: " + contractVersion);
        }
        invocationId = normalize(invocationId);
        qualifiedName = normalize(qualifiedName);
        if (invocationId == null) {
            throw new IllegalArgumentException("Capability invocationId is required");
        }
        if (qualifiedName == null) {
            throw new IllegalArgumentException("Capability qualifiedName is required");
        }
        input = immutableMap(input);
        context = immutableMap(context);
        constraints = immutableMap(constraints);
        evaluationPolicy = immutableMap(evaluationPolicy);
        traceContext = immutableMap(traceContext);
        idempotencyKey = normalize(idempotencyKey);
    }

    public static CapabilityInvocationRequest fromRuntime(String qualifiedName,
                                                          Map<String, Object> wireValues) {
        Map<String, Object> values = wireValues == null ? Map.of() : wireValues;
        Integer suppliedVersion = integerValue(values.get("contractVersion"));
        if (values.containsKey("contractVersion")
                && (suppliedVersion == null || suppliedVersion != CONTRACT_VERSION)) {
            throw new IllegalArgumentException("Unsupported Capability invocation contract: "
                    + values.get("contractVersion"));
        }
        String bodyQualifiedName = text(values.get("qualifiedName"));
        String pathQualifiedName = normalize(qualifiedName);
        if (bodyQualifiedName != null && !bodyQualifiedName.equals(pathQualifiedName)) {
            throw new IllegalArgumentException("Capability qualifiedName does not match the signed path");
        }
        return new CapabilityInvocationRequest(
                CONTRACT_VERSION,
                firstText(values.get("invocationId"), UUID.randomUUID().toString()),
                pathQualifiedName,
                map(values.get("input")),
                map(values.get("context")),
                map(values.get("constraints")),
                map(values.get("evaluationPolicy")),
                strictLongValue(values, "deadlineEpochMs"),
                text(values.get("idempotencyKey")),
                map(values.get("traceContext")));
    }

    /** Strict parser for the canonical HTTP endpoint; no server-generated correlation fields. */
    public static CapabilityInvocationRequest fromWire(Map<String, Object> wireValues) {
        Map<String, Object> values = wireValues == null ? Map.of() : wireValues;
        Integer version = integerValue(values.get("contractVersion"));
        if (version == null || version != CONTRACT_VERSION) {
            throw new IllegalArgumentException("Unsupported Capability invocation contract: "
                    + values.get("contractVersion"));
        }
        return new CapabilityInvocationRequest(
                version,
                text(values.get("invocationId")),
                text(values.get("qualifiedName")),
                map(values.get("input")),
                map(values.get("context")),
                map(values.get("constraints")),
                map(values.get("evaluationPolicy")),
                strictLongValue(values, "deadlineEpochMs"),
                text(values.get("idempotencyKey")),
                map(values.get("traceContext")));
    }

    public Map<String, Object> toWireMap() {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("contractVersion", contractVersion);
        wire.put("invocationId", invocationId);
        wire.put("qualifiedName", qualifiedName);
        wire.put("input", input);
        wire.put("context", context);
        if (!constraints.isEmpty()) {
            wire.put("constraints", constraints);
        }
        if (!evaluationPolicy.isEmpty()) {
            wire.put("evaluationPolicy", evaluationPolicy);
        }
        if (deadlineEpochMs != null) {
            wire.put("deadlineEpochMs", deadlineEpochMs);
        }
        if (idempotencyKey != null) {
            wire.put("idempotencyKey", idempotencyKey);
        }
        if (!traceContext.isEmpty()) {
            wire.put("traceContext", traceContext);
        }
        return wire;
    }

    public Map<String, Object> toLegacyExecutionMap() {
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("invocationId", invocationId);
        legacy.put("input", input);
        legacy.put("context", context);
        if (!constraints.isEmpty()) {
            legacy.put("constraints", constraints);
        }
        if (!evaluationPolicy.isEmpty()) {
            legacy.put("evaluationPolicy", evaluationPolicy);
        }
        if (deadlineEpochMs != null) {
            legacy.put("deadlineEpochMs", deadlineEpochMs);
        }
        if (idempotencyKey != null) {
            legacy.put("idempotencyKey", idempotencyKey);
        }
        if (!traceContext.isEmpty()) {
            legacy.put("traceContext", traceContext);
        }
        return legacy;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> {
            if (key != null) {
                result.put(String.valueOf(key), item);
            }
        });
        return result;
    }

    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        return source == null || source.isEmpty()
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static String firstText(Object first, String fallback) {
        String value = text(first);
        return value == null ? fallback : value;
    }

    private static String text(Object value) {
        return normalize(value == null ? null : String.valueOf(value));
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String result = value.trim();
        return result.isEmpty() ? null : result;
    }

    private static Long strictLongValue(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null) return null;
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            String parsed = text(value);
            return parsed == null ? null : Long.parseLong(parsed);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Capability " + key + " is invalid");
        }
    }

    private static Integer integerValue(Object value) {
        if (value instanceof Number number) return number.intValue();
        try {
            String parsed = text(value);
            return parsed == null ? null : Integer.parseInt(parsed);
        } catch (NumberFormatException invalid) {
            return null;
        }
    }
}
