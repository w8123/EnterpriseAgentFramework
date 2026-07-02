package com.enterprise.ai.runtime.credential;

import java.util.Map;

public record RuntimeWorkflowCredentialRuntime(
        String credentialRef,
        String name,
        String type,
        Map<String, Object> secret
) {
}
