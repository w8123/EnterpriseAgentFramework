package com.enterprise.ai.control.a2a.domain.trust;

import com.enterprise.ai.control.a2a.domain.A2aDomainText;

public enum A2aPersonalMemoryPolicy {
    DISABLED,
    ATTESTED_USER_ONLY;

    public static A2aPersonalMemoryPolicy parse(String value) {
        return A2aDomainText.parseEnum(A2aPersonalMemoryPolicy.class, value,
                "personalMemoryPolicy");
    }
}
