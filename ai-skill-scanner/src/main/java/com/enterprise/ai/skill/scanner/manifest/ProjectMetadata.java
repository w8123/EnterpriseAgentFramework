package com.enterprise.ai.skill.scanner.manifest;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProjectMetadata(
        String name,
        String baseUrl,
        String contextPath
) {
}
