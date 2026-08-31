package com.enterprise.ai.control.a2a.domain;

public enum A2aDirection {
    INBOUND,
    OUTBOUND,
    BIDIRECTIONAL;

    public static A2aDirection parse(String value) {
        return A2aDomainText.parseEnum(A2aDirection.class, value, "direction");
    }

    public boolean accepts(A2aDirection requested) {
        return this == BIDIRECTIONAL || this == requested;
    }
}
