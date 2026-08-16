package com.enterprise.ai.runtime.memory;

public record RuntimeSessionMemoryKey(
        boolean persistent,
        String tenantId,
        String trustedUserId,
        String agentId,
        String publicSessionId,
        String stateUserKey,
        String stateSessionKey,
        String identitySource) {
}
