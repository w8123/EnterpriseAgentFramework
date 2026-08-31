package com.enterprise.ai.control.a2a.domain.identity;

import com.enterprise.ai.control.a2a.domain.A2aDomainText;

public enum A2aCredentialStatus {
    ACTIVE,
    GRACE,
    REVOKED,
    EXPIRED;

    public static A2aCredentialStatus parse(String value) {
        return A2aDomainText.parseEnum(A2aCredentialStatus.class, value, "credentialStatus");
    }
}
