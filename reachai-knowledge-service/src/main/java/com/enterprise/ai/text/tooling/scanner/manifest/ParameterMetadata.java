package com.enterprise.ai.text.tooling.scanner.manifest;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ParameterMetadata(
        String example,
        String sourceHint,
        String dictType,
        Boolean sensitive,
        String businessKey,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> canBeSourceFor,
        String source
) {
    public ParameterMetadata {
        canBeSourceFor = canBeSourceFor == null ? List.of() : List.copyOf(canBeSourceFor);
    }
}
