package com.enterprise.ai.control.a2a.domain.trust;

import com.enterprise.ai.control.a2a.domain.A2aDomainText;

public enum A2aEnvironment {
    DEVELOPMENT,
    TEST,
    STAGING,
    PRODUCTION;

    public static A2aEnvironment parse(String value) {
        return A2aDomainText.parseEnum(A2aEnvironment.class, value, "environment");
    }

    public boolean isProduction() {
        return this == PRODUCTION;
    }
}
