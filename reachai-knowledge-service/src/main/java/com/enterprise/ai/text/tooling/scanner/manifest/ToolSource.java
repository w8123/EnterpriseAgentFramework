package com.enterprise.ai.text.tooling.scanner.manifest;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolSource(
        String scanner,
        String location
) {
}
