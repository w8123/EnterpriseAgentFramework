package com.enterprise.ai.runtime.workflow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;

/** Manual editing input. Source and status fields are assertions, never assignments. */
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public record RuntimeWorkflowWriteCommand(
        Long projectId,
        String projectCode,
        String keySlug,
        String name,
        String description,
        String workflowKind,
        String executionEngine,
        String graphSpecJson,
        String canvasJson,
        String inputSchemaJson,
        String outputSchemaJson,
        String defaultModelInstanceId,
        String defaultResourceConfigJson,
        String status,
        String definitionAuthority,
        String creationChannel,
        String extraJson,
        String baseRevision) {
}
