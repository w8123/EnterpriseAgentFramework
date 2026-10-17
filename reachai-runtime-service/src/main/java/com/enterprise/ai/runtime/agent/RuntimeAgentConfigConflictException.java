package com.enterprise.ai.runtime.agent;

/** The caller's expected ACTIVE Agent configuration no longer matches the persisted one. */
public class RuntimeAgentConfigConflictException extends RuntimeException {

    public static final String CODE = "AGENT_ACTIVE_CONFIG_CHANGED";

    public RuntimeAgentConfigConflictException(String message) {
        super(message);
    }
}
