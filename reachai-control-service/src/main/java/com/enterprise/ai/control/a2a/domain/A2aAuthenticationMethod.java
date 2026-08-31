package com.enterprise.ai.control.a2a.domain;

public enum A2aAuthenticationMethod {
    API_KEY,
    BEARER,
    HMAC,
    OAUTH2_CLIENT_CREDENTIALS,
    MTLS,
    ANONYMOUS;

    public static A2aAuthenticationMethod parse(String value) {
        return A2aDomainText.parseEnum(A2aAuthenticationMethod.class, value, "authenticationMethod");
    }
}
