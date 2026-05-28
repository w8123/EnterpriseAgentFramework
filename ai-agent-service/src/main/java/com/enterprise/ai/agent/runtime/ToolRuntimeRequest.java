package com.enterprise.ai.agent.runtime;

import lombok.Builder;

import java.util.Map;

@Builder
public record ToolRuntimeRequest(
        String qualifiedName,
        Map<String, Object> args,
        Map<String, Object> context
) {
}
