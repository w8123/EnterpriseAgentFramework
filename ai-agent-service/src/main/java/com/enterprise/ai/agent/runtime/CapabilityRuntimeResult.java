package com.enterprise.ai.agent.runtime;

import java.util.Map;

public record CapabilityRuntimeResult(
        boolean success,
        String status,
        String qualifiedName,
        Object output,
        String errorMessage,
        Map<String, Object> metadata
) {

    public static CapabilityRuntimeResult success(String qualifiedName, Object output, Map<String, Object> metadata) {
        return new CapabilityRuntimeResult(true, "SUCCESS", qualifiedName, output, null, metadata == null ? Map.of() : metadata);
    }

    public static CapabilityRuntimeResult waitingUser(String qualifiedName, Map<String, Object> metadata) {
        return new CapabilityRuntimeResult(false, "WAITING_USER", qualifiedName, null, null, metadata == null ? Map.of() : metadata);
    }

    public static CapabilityRuntimeResult failure(String qualifiedName, String errorMessage, Map<String, Object> metadata) {
        return new CapabilityRuntimeResult(false, "FAILED", qualifiedName, null, errorMessage, metadata == null ? Map.of() : metadata);
    }
}
