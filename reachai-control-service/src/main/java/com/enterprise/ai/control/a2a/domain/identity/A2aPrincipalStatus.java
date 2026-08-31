package com.enterprise.ai.control.a2a.domain.identity;

import com.enterprise.ai.control.a2a.domain.A2aDomainText;

public enum A2aPrincipalStatus {
    ACTIVE,
    DISABLED,
    QUARANTINED,
    ARCHIVED;

    public static A2aPrincipalStatus parse(String value) {
        return A2aDomainText.parseEnum(A2aPrincipalStatus.class, value, "principalStatus");
    }
}
