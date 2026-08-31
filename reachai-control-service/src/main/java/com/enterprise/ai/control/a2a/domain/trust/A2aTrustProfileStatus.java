package com.enterprise.ai.control.a2a.domain.trust;

import com.enterprise.ai.control.a2a.domain.A2aDomainText;

public enum A2aTrustProfileStatus {
    ACTIVE,
    DISABLED,
    ARCHIVED;

    public static A2aTrustProfileStatus parse(String value) {
        return A2aDomainText.parseEnum(A2aTrustProfileStatus.class, value, "status");
    }
}
