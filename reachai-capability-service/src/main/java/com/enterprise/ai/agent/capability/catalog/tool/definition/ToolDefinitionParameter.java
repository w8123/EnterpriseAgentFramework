package com.enterprise.ai.agent.capability.catalog.tool.definition;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolDefinitionParameter(
        String name,
        String type,
        String description,
        boolean required,
        String location,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<ToolDefinitionParameter> children,
        Object metadata
) {
    public ToolDefinitionParameter {
        children = children == null ? List.of() : List.copyOf(children);
    }

    public ToolDefinitionParameter(String name,
                                   String type,
                                   String description,
                                   boolean required,
                                   String location) {
        this(name, type, description, required, location, List.of(), null);
    }

    public ToolDefinitionParameter(String name,
                                   String type,
                                   String description,
                                   boolean required,
                                   String location,
                                   List<ToolDefinitionParameter> children) {
        this(name, type, description, required, location, children, null);
    }
}
