package com.enterprise.ai.runtime.memory;

/**
 * @param contextWindow the Agent's published session window in messages; {@code 0} falls back to
 *                      {@code reachai.runtime.session-memory.max-context-messages}
 */
public record RuntimeSessionMemoryKey(
        boolean persistent,
        String tenantId,
        String trustedUserId,
        String agentId,
        String publicSessionId,
        String stateUserKey,
        String stateSessionKey,
        String identitySource,
        int contextWindow) {

    public RuntimeSessionMemoryKey(boolean persistent,
                                   String tenantId,
                                   String trustedUserId,
                                   String agentId,
                                   String publicSessionId,
                                   String stateUserKey,
                                   String stateSessionKey,
                                   String identitySource) {
        this(persistent, tenantId, trustedUserId, agentId, publicSessionId,
                stateUserKey, stateSessionKey, identitySource, 0);
    }
}
