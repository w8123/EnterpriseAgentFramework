package com.enterprise.ai.runtime.credential;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record RuntimeWorkflowCredentialRequest(
        String credentialRef,
        String name,
        String type,
        Long projectId,
        String projectCode,
        String scope,
        String status,
        Map<String, Object> secret
) {
    public RuntimeWorkflowCredentialRequest {
        secret = secret == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(secret));
    }
}
