package com.enterprise.ai.agent.runtime;

import lombok.Builder;

import java.util.Map;

@Builder
public record CapabilityRuntimeRequest(
        String qualifiedName,
        Map<String, Object> params,
        Map<String, Object> context
) {
}
