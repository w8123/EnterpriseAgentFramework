package com.enterprise.ai.text.tooling.scanner.manifest;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CapabilityMetadata(
        boolean declared,
        String name,
        String title,
        String domain,
        String module,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> tags,
        String sideEffect,
        Boolean agentVisible,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> requiredRoles,
        Integer timeoutMs,
        Integer retryLimit,
        String source
) {
    public CapabilityMetadata {
        tags = tags == null ? List.of() : List.copyOf(tags);
        requiredRoles = requiredRoles == null ? List.of() : List.copyOf(requiredRoles);
    }
}
