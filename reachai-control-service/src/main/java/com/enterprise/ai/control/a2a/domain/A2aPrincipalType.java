package com.enterprise.ai.control.a2a.domain;

public enum A2aPrincipalType {
    REMOTE_AGENT,
    REMOTE_CLIENT,
    LOCAL_AGENT;

    public static A2aPrincipalType parse(String value) {
        return A2aDomainText.parseEnum(A2aPrincipalType.class, value, "principalType");
    }
}
