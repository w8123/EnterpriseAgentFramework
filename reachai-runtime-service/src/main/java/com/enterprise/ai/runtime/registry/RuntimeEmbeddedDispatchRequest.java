package com.enterprise.ai.runtime.registry;

import java.util.Map;

public record RuntimeEmbeddedDispatchRequest(
        String projectCode,
        String instanceId,
        String agentKey,
        String message,
        String sessionId,
        String userId,
        Map<String, Object> context,
        Map<String, Object> graphSpec
) {
    public RuntimeEmbeddedDispatchRequest {
        context = context == null ? Map.of() : Map.copyOf(context);
        graphSpec = graphSpec == null ? Map.of() : Map.copyOf(graphSpec);
    }
}
