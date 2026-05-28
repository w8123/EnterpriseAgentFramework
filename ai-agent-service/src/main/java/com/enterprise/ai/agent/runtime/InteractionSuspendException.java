package com.enterprise.ai.agent.runtime;

import java.util.Map;

class InteractionSuspendException extends RuntimeException {

    private final CapabilityRuntimeResult result;

    InteractionSuspendException(CapabilityRuntimeResult result) {
        super("interaction waiting for user input");
        this.result = result;
    }

    CapabilityRuntimeResult result() {
        return result;
    }

    static InteractionSuspendException waiting(String qualifiedName,
                                               String sessionId,
                                               String nodeId,
                                               Map<String, Object> uiRequest,
                                               Map<String, Object> metadata) {
        Map<String, Object> out = new java.util.LinkedHashMap<>(metadata == null ? Map.of() : metadata);
        out.put("interactionSessionId", sessionId);
        out.put("nodeId", nodeId);
        out.put("uiRequest", uiRequest);
        return new InteractionSuspendException(CapabilityRuntimeResult.waitingUser(qualifiedName, out));
    }
}
