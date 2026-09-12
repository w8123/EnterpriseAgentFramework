package com.enterprise.ai.runtime.workflow;

import lombok.Builder;
import lombok.Value;

/** Immutable execution data exported by its owning module. */
@Value
@Builder
public class RuntimeWorkflowExecutionView {
    String id;
    Long projectId;
    String projectCode;
    String keySlug;
    String name;
    String description;
    String workflowKind;
    String executionEngine;
    String graphSpecJson;
    String inputSchemaJson;
    String outputSchemaJson;
    String defaultModelInstanceId;
    String defaultResourceConfigJson;
    String status;
    String definitionAuthority;

    public static RuntimeWorkflowExecutionView fromEntity(RuntimeWorkflowDefinitionEntity entity) {
        if (entity == null) return null;
        return RuntimeWorkflowExecutionView.builder()
                .id(entity.getId())
                .projectId(entity.getProjectId())
                .projectCode(entity.getProjectCode())
                .keySlug(entity.getKeySlug())
                .name(entity.getName())
                .description(entity.getDescription())
                .workflowKind(entity.getWorkflowKind())
                .executionEngine(entity.getExecutionEngine())
                .graphSpecJson(entity.getGraphSpecJson())
                .inputSchemaJson(entity.getInputSchemaJson())
                .outputSchemaJson(entity.getOutputSchemaJson())
                .defaultModelInstanceId(entity.getDefaultModelInstanceId())
                .defaultResourceConfigJson(entity.getDefaultResourceConfigJson())
                .status(entity.getStatus())
                .definitionAuthority(entity.getDefinitionAuthority())
                .build();
    }
}
