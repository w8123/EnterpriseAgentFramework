package com.enterprise.ai.agent.tools.definition;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolDefinitionParameter(
        String name,
        String type,
        String description,
        boolean required,
        String location
) {
}
