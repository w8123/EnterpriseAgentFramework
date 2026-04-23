package com.enterprise.ai.text.tooling.scanner.manifest;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolParameterDefinition(
        String name,
        String type,
        String description,
        boolean required,
        ParameterLocation location
) {
}
