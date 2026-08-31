package com.enterprise.ai.control.a2a.domain.identity;

import com.enterprise.ai.control.a2a.domain.A2aDomainText;

public enum A2aCredentialType {
    API_KEY,
    HMAC,
    OAUTH2_CLIENT,
    MTLS,
    BEARER;

    public static A2aCredentialType parse(String value) {
        return A2aDomainText.parseEnum(A2aCredentialType.class, value, "credentialType");
    }
}
