package com.enterprise.ai.runtime.credential;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record RuntimeWorkflowCredentialView(
        Long id,
        String credentialRef,
        String name,
        String type,
        Long projectId,
        String projectCode,
        String scope,
        String status,
        Map<String, Object> secretPreview,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public RuntimeWorkflowCredentialView {
        secretPreview = secretPreview == null
                ? null
                : Collections.unmodifiableMap(new LinkedHashMap<>(secretPreview));
    }
}
