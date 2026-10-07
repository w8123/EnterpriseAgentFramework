package com.enterprise.ai.common.capability;

import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Versioned, server-to-server contracts for a single Console business-method invocation.
 * Browser input is intentionally represented separately from the attested Runtime command.
 */
public final class ConsoleCapabilityInvocationContracts {

    public static final int CONTRACT_VERSION = 1;
    private static final Pattern CONTRACT_HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern QUALIFIED_NAME = Pattern.compile("[A-Za-z0-9._:-]{1,200}");
    private static final Set<String> PUBLIC_REQUEST_FIELDS = Set.of(
            "invocationId", "expectedContractHash", "expectedExecutionRevision", "input", "confirmedSideEffect");

    private ConsoleCapabilityInvocationContracts() {
    }

    /** Safe owner-supplied parameter declaration; sensitive examples/defaults are removed by Capability. */
    public record Parameter(
            String name,
            String type,
            String description,
            boolean required,
            String location,
            List<Parameter> children,
            Map<String, Object> metadata) {
        public Parameter {
            name = trim(name);
            type = trim(type);
            description = trim(description);
            location = trim(location);
            children = children == null || children.isEmpty() ? List.of() : List.copyOf(children);
            metadata = immutableMap(metadata);
        }
    }

    /** Capability-owned target snapshot returned through an attested internal read. */
    public record InvocationContext(
            int contractVersion,
            String name,
            String qualifiedName,
            String sourceQualifiedName,
            String assetType,
            Long projectId,
            String projectCode,
            String currentContractHash,
            String acceptedContractHash,
            String sourceContractHash,
            String sourceAvailability,
            boolean enabled,
            String sideEffect,
            List<Parameter> parameters,
            String requestBodyType,
            String responseType,
            String targetDescription,
            String targetInstanceId,
            String targetInstanceStatus,
            boolean credentialAvailable,
            boolean businessIdentityRequired,
            boolean executable,
            String blockingCode,
            String blockingMessage,
            long timeoutMs,
            String executionRevision) {
        public InvocationContext {
            requireVersion(contractVersion);
            name = trim(name);
            qualifiedName = trim(qualifiedName);
            sourceQualifiedName = trim(sourceQualifiedName);
            assetType = trim(assetType);
            projectCode = trim(projectCode);
            currentContractHash = trim(currentContractHash);
            acceptedContractHash = trim(acceptedContractHash);
            sourceContractHash = trim(sourceContractHash);
            sourceAvailability = trim(sourceAvailability);
            sideEffect = trim(sideEffect);
            parameters = parameters == null || parameters.isEmpty() ? List.of() : List.copyOf(parameters);
            requestBodyType = trim(requestBodyType);
            responseType = trim(responseType);
            targetDescription = trim(targetDescription);
            targetInstanceId = trim(targetInstanceId);
            targetInstanceStatus = trim(targetInstanceStatus);
            executionRevision = trim(executionRevision);
            blockingCode = trim(blockingCode);
            blockingMessage = trim(blockingMessage);
            if (timeoutMs < 0L) {
                throw new IllegalArgumentException("timeoutMs is invalid");
            }
        }
    }

    /** Strict public POST payload. It deliberately has no target, URL, identity, role or context fields. */
    public record PublicInvocationRequest(
            String invocationId,
            String expectedContractHash,
            String expectedExecutionRevision,
            Map<String, Object> input,
            boolean confirmedSideEffect) {

        public PublicInvocationRequest {
            invocationId = normalizeInvocationId(invocationId);
            expectedContractHash = requireHash(expectedContractHash, "expectedContractHash");
            expectedExecutionRevision = requireHash(expectedExecutionRevision, "expectedExecutionRevision");
            input = immutableMap(input);
        }

        public static PublicInvocationRequest fromWire(Map<String, Object> body) {
            Map<String, Object> value = body == null ? Map.of() : body;
            Set<String> unknown = new LinkedHashSet<>(value.keySet());
            unknown.removeAll(PUBLIC_REQUEST_FIELDS);
            if (!unknown.isEmpty()) {
                throw new IllegalArgumentException("Unsupported Console invocation fields: " + unknown);
            }
            Object rawInput = value.get("input");
            if (!(rawInput instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("input must be a JSON object");
            }
            Object rawConfirmation = value.get("confirmedSideEffect");
            if (rawConfirmation != null && !(rawConfirmation instanceof Boolean)) {
                throw new IllegalArgumentException("confirmedSideEffect must be boolean");
            }
            return new PublicInvocationRequest(
                    text(value.get("invocationId")),
                    text(value.get("expectedContractHash")),
                    text(value.get("expectedExecutionRevision")),
                    stringMap(map),
                    Boolean.TRUE.equals(rawConfirmation));
        }
    }

    /** Exact Control-to-Runtime command. The HMAC caller identity must equal platformActorId. */
    public record InvocationCommand(
            int contractVersion,
            String invocationId,
            String platformActorId,
            Long projectId,
            String projectCode,
            String qualifiedName,
            String expectedContractHash,
            String expectedExecutionRevision,
            Map<String, Object> input,
            List<String> sensitiveInputNames,
            String sideEffect,
            boolean confirmedSideEffect,
            long deadlineEpochMs) {
        public InvocationCommand {
            requireVersion(contractVersion);
            invocationId = normalizeInvocationId(invocationId);
            platformActorId = requireText(platformActorId, "platformActorId", 128);
            if (projectId == null || projectId <= 0) {
                throw new IllegalArgumentException("projectId must be positive");
            }
            projectCode = requireText(projectCode, "projectCode", 96);
            qualifiedName = requireQualifiedName(qualifiedName);
            expectedContractHash = requireHash(expectedContractHash, "expectedContractHash");
            expectedExecutionRevision = requireHash(expectedExecutionRevision, "expectedExecutionRevision");
            input = immutableMap(input);
            sensitiveInputNames = normalizedNames(sensitiveInputNames);
            sideEffect = requireText(sideEffect, "sideEffect", 32).toUpperCase(java.util.Locale.ROOT);
            if (deadlineEpochMs <= 0L) {
                throw new IllegalArgumentException("deadlineEpochMs must be positive");
            }
        }
    }

    /** Safe Runtime response used for synchronous POST results and later GET reads. */
    public record InvocationOutcome(
            int contractVersion,
            String invocationId,
            Long runId,
            String traceId,
            String projectCode,
            Long projectId,
            String qualifiedName,
            String identityMode,
            String status,
            String dispatchStage,
            boolean terminal,
            String code,
            String message,
            Object result,
            boolean resultTruncated,
            Long resultExpiresAtEpochMs,
            Long latencyMs) {
        public InvocationOutcome {
            requireVersion(contractVersion);
            invocationId = normalizeInvocationId(invocationId);
            projectCode = trim(projectCode);
            qualifiedName = trim(qualifiedName);
            identityMode = trim(identityMode);
            status = trim(status);
            dispatchStage = trim(dispatchStage);
            code = trim(code);
            message = trim(message);
            traceId = trim(traceId);
        }
    }

    public static boolean hasValidContractHash(String value) {
        return value != null && CONTRACT_HASH.matcher(value.trim()).matches();
    }

    private static void requireVersion(int version) {
        if (version != CONTRACT_VERSION) {
            throw new IllegalArgumentException("Unsupported Console Capability invocation contract: " + version);
        }
    }

    private static String normalizeInvocationId(String value) {
        String text = requireText(value, "invocationId", 64);
        try {
            return UUID.fromString(text).toString();
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("invocationId must be a UUID", invalid);
        }
    }

    private static String requireHash(String value, String field) {
        String normalized = requireText(value, field, 64);
        if (!CONTRACT_HASH.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256 hex hash");
        }
        return normalized;
    }

    private static String requireQualifiedName(String value) {
        String normalized = requireText(value, "qualifiedName", 200);
        if (!QUALIFIED_NAME.matcher(normalized).matches()) {
            throw new IllegalArgumentException("qualifiedName contains unsupported characters");
        }
        return normalized;
    }

    private static String requireText(String value, String field, int maximum) {
        String normalized = trim(value);
        if (!StringUtils.hasText(normalized)) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (normalized.length() > maximum || normalized.indexOf('\r') >= 0 || normalized.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String trim(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        return source == null || source.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static Map<String, Object> stringMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key == null) {
                throw new IllegalArgumentException("input field name is required");
            }
            String name = String.valueOf(key);
            if (!StringUtils.hasText(name) || name.length() > 192 || name.indexOf('\r') >= 0 || name.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("input field name is invalid");
            }
            result.put(name, value);
        });
        return result;
    }

    private static List<String> normalizedNames(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String value : values) {
            String name = requireText(value, "sensitiveInputNames", 192);
            names.add(name);
        }
        return List.copyOf(names);
    }
}
