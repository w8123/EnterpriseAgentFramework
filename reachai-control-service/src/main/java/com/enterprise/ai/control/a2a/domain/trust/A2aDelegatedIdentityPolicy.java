package com.enterprise.ai.control.a2a.domain.trust;

import com.enterprise.ai.control.a2a.domain.A2aDomainText;

public enum A2aDelegatedIdentityPolicy {
    DENY,
    ATTESTED_ONLY;

    public static A2aDelegatedIdentityPolicy parse(String value) {
        return A2aDomainText.parseEnum(A2aDelegatedIdentityPolicy.class, value,
                "delegatedIdentityPolicy");
    }
}
