package com.enterprise.ai.text.tooling.scanner.manifest;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolDefinition(
        String name,
        String description,
        String method,
        String path,
        String endpoint,
        List<ToolParameterDefinition> parameters,
        String requestBodyType,
        String responseType,
        ToolSource source
) {
    public ToolDefinition {
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }
}
