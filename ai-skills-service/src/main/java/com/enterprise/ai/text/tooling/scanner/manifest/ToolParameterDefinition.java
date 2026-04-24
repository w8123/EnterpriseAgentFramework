package com.enterprise.ai.text.tooling.scanner.manifest;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolParameterDefinition(
        String name,
        String type,
        String description,
        boolean required,
        ParameterLocation location,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<ToolParameterDefinition> children
) {
    public ToolParameterDefinition {
        children = children == null ? List.of() : List.copyOf(children);
    }

    public ToolParameterDefinition(String name,
                                   String type,
                                   String description,
                                   boolean required,
                                   ParameterLocation location) {
        this(name, type, description, required, location, List.of());
    }
}
