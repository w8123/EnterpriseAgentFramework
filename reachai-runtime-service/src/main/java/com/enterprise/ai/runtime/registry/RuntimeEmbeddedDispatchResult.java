package com.enterprise.ai.runtime.registry;

import java.util.List;
import java.util.Map;

public record RuntimeEmbeddedDispatchResult(
        boolean success,
        String answer,
        String projectCode,
        String instanceId,
        String dispatchUrl,
        List<String> steps,
        Map<String, Object> metadata,
        String errorCode,
        String errorMessage
) {
    public RuntimeEmbeddedDispatchResult {
        steps = steps == null ? List.of() : List.copyOf(steps);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static RuntimeEmbeddedDispatchResult failure(String projectCode,
                                                        String instanceId,
                                                        String errorCode,
                                                        String errorMessage) {
        return new RuntimeEmbeddedDispatchResult(
                false,
                errorMessage,
                projectCode,
                instanceId,
                null,
                List.of(),
                Map.of(),
                errorCode,
                errorMessage);
    }
}
