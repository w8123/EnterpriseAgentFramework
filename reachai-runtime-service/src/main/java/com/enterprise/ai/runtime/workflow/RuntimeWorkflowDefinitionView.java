package com.enterprise.ai.runtime.workflow;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/** Detached management projection; persistence metadata cannot become HTTP input. */
@Value
@Builder
public class RuntimeWorkflowDefinitionView {
    String id;
    Long projectId;
    String projectCode;
    String keySlug;
    String name;
    String description;
    String workflowKind;
    String executionEngine;
    String graphSpecJson;
    String canvasJson;
    String inputSchemaJson;
    String outputSchemaJson;
    String defaultModelInstanceId;
    String defaultResourceConfigJson;
    String status;
    String definitionAuthority;
    String creationChannel;
    String extraJson;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
    Boolean deletable;

    static RuntimeWorkflowDefinitionView fromEntity(RuntimeWorkflowDefinitionEntity entity) {
        return builder().id(entity.getId()).projectId(entity.getProjectId()).projectCode(entity.getProjectCode())
                .keySlug(entity.getKeySlug()).name(entity.getName()).description(entity.getDescription())
                .workflowKind(entity.getWorkflowKind()).executionEngine(entity.getExecutionEngine())
                .graphSpecJson(entity.getGraphSpecJson()).canvasJson(entity.getCanvasJson())
                .inputSchemaJson(entity.getInputSchemaJson()).outputSchemaJson(entity.getOutputSchemaJson())
                .defaultModelInstanceId(entity.getDefaultModelInstanceId()).defaultResourceConfigJson(entity.getDefaultResourceConfigJson())
                .status(entity.getStatus()).definitionAuthority(entity.getDefinitionAuthority()).creationChannel(entity.getCreationChannel())
                .extraJson(entity.getExtraJson()).createdAt(entity.getCreatedAt()).updatedAt(entity.getUpdatedAt())
                .deletable(entity.getDeletable()).build();
    }
}
