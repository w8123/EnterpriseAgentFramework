package com.enterprise.ai.control.a2a.domain;

public enum A2aTrustLevel {
    UNTRUSTED,
    KNOWN,
    TRUSTED,
    PRIVILEGED;

    public static A2aTrustLevel parse(String value) {
        return A2aDomainText.parseEnum(A2aTrustLevel.class, value, "trustLevel");
    }
}
