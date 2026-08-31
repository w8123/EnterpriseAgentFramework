package com.enterprise.ai.control.a2a.application.identity;

import com.enterprise.ai.control.a2a.domain.A2aDomainException;

public final class A2aCredentialBinding {

    public static String outbound(String credentialKey, int versionNo) {
        if (credentialKey == null || credentialKey.isBlank() || versionNo <= 0) {
            throw new A2aDomainException("A2A_CREDENTIAL_AAD_INVALID",
                    "outbound credential encryption requires a stable key and version");
        }
        return "reachai:a2a:credential:outbound:" + credentialKey.trim() + ":" + versionNo;
    }

    private A2aCredentialBinding() {
    }
}
