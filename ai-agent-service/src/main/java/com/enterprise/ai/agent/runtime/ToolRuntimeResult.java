package com.enterprise.ai.agent.runtime;

import java.util.Map;

public record ToolRuntimeResult(
        boolean success,
        String qualifiedName,
        Object output,
        String errorMessage,
        Map<String, Object> metadata
) {

    public static ToolRuntimeResult success(String qualifiedName, Object output) {
        return new ToolRuntimeResult(true, qualifiedName, output, null, Map.of());
    }

    public static ToolRuntimeResult failure(String qualifiedName, String errorMessage) {
        return new ToolRuntimeResult(false, qualifiedName, null, errorMessage, Map.of());
    }
}
