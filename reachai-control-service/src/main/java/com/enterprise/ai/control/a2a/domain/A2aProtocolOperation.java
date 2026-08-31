package com.enterprise.ai.control.a2a.domain;

/**
 * Canonical A2A HTTP+JSON operation names and the least-privilege Principal
 * scope required to invoke each operation.
 */
public enum A2aProtocolOperation {
    MESSAGE_SEND("message:send", "a2a:message:send"),
    TASKS_GET("tasks:get", "a2a:task:read"),
    TASKS_LIST("tasks:list", "a2a:task:read"),
    TASKS_CANCEL("tasks:cancel", "a2a:task:cancel");

    private final String wireName;
    private final String requiredScope;

    A2aProtocolOperation(String wireName, String requiredScope) {
        this.wireName = wireName;
        this.requiredScope = requiredScope;
    }

    public String wireName() {
        return wireName;
    }

    public String requiredScope() {
        return requiredScope;
    }
}
