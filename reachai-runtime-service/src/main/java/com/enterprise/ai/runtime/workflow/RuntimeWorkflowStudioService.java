package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class RuntimeWorkflowStudioService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final RuntimeWorkflowManagementService workflowManagementService;
    private final RuntimeWorkflowVersionService workflowVersionService;
    private final ObjectMapper objectMapper;

    public WorkflowWorkingCopyState getWorkingCopy(String workflowId) {
        RuntimeWorkflowDefinitionView workflow = requireWorkflow(workflowId);
        return toWorkingCopyState(workflow);
    }

    @Transactional
    public WorkflowWorkingCopyState saveWorkingCopy(String workflowId, SaveWorkingCopyCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("workflow working copy is required");
        }
        String graphSpecJson = requireGraphSpec(command.graphSpecJson());
        validateJson("graphSpecJson", graphSpecJson, GraphSpec.class);
        validateOptionalJson("canvasJson", command.canvasJson());
        validateOptionalJson("inputSchemaJson", command.inputSchemaJson());
        validateOptionalJson("outputSchemaJson", command.outputSchemaJson());
        validateOptionalJson("defaultResourceConfigJson", command.defaultResourceConfigJson());
        validateOptionalJson("extraJson", command.extraJson());

        RuntimeWorkflowDefinitionView saved = workflowManagementService.update(workflowId,
                RuntimeWorkflowWriteCommand.builder()
                        .keySlug(command.keySlug()).name(command.name()).description(command.description())
                        .workflowKind(command.workflowKind()).executionEngine(command.executionEngine())
                        .definitionAuthority(command.definitionAuthority()).creationChannel(command.creationChannel())
                        .graphSpecJson(graphSpecJson).canvasJson(command.canvasJson())
                        .inputSchemaJson(command.inputSchemaJson()).outputSchemaJson(command.outputSchemaJson())
                        .defaultModelInstanceId(command.defaultModelInstanceId())
                        .defaultResourceConfigJson(command.defaultResourceConfigJson()).extraJson(command.extraJson())
                        .baseRevision(command.baseRevision()).build());
        return toWorkingCopyState(saved);
    }

    private WorkflowWorkingCopyState toWorkingCopyState(RuntimeWorkflowDefinitionView workflow) {
        RuntimeWorkflowVersionEntity activeVersion = workflowVersionService.resolveActive(workflow.getId());
        return new WorkflowWorkingCopyState(
                workflow.getId(),
                workflow.getProjectId(),
                workflow.getProjectCode(),
                workflow.getKeySlug(),
                workflow.getName(),
                workflow.getDescription(),
                workflow.getGraphSpecJson(),
                workflow.getCanvasJson(),
                workflow.getWorkflowKind(),
                workflow.getExecutionEngine(),
                workflow.getDefinitionAuthority(),
                workflow.getCreationChannel(),
                workflow.getDefaultModelInstanceId(),
                workflow.getDefaultResourceConfigJson(),
                workflow.getStatus(),
                workflow.getExtraJson(),
                workflow.getId(),
                workflow.getInputSchemaJson(),
                workflow.getOutputSchemaJson(),
                workflow.getCreatedAt(),
                workflow.getUpdatedAt(),
                workflow.getDeletable(),
                revision(workflow),
                toActiveVersionSummary(activeVersion),
                hasUnpublishedChanges(workflow, activeVersion));
    }

    private RuntimeWorkflowDefinitionView requireWorkflow(String workflowId) {
        if (!StringUtils.hasText(workflowId)) {
            throw new IllegalArgumentException("workflowId is required");
        }
        return workflowManagementService.findById(workflowId)
                .orElseThrow(() -> new IllegalArgumentException("workflow not found: " + workflowId));
    }

    private String requireGraphSpec(String graphSpecJson) {
        if (!StringUtils.hasText(graphSpecJson)) {
            throw new IllegalArgumentException("graphSpecJson is required");
        }
        return graphSpecJson.trim();
    }

    private void validateJson(String field, String json, Class<?> type) {
        try {
            Object value = objectMapper.readValue(json, type);
            if (GraphSpec.class.equals(type) && value == null) {
                throw new IllegalArgumentException(field + " must be a JSON object");
            }
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException(field + " is not valid JSON", ex);
        }
    }

    private void validateOptionalJson(String field, String json) {
        if (StringUtils.hasText(json)) {
            validateJson(field, json, Object.class);
        }
    }

    private String revision(RuntimeWorkflowDefinitionView workflow) {
        return workflow.getUpdatedAt() == null ? null : workflow.getUpdatedAt().toString();
    }

    private ActiveVersionSummary toActiveVersionSummary(RuntimeWorkflowVersionEntity activeVersion) {
        if (activeVersion == null) {
            return null;
        }
        return new ActiveVersionSummary(
                activeVersion.getId(),
                activeVersion.getVersion(),
                activeVersion.getRolloutPercent(),
                activeVersion.getStatus(),
                activeVersion.getPublishedBy(),
                activeVersion.getPublishedAt(),
                activeVersion.getNote());
    }

    private boolean hasUnpublishedChanges(RuntimeWorkflowDefinitionView workflow,
                                          RuntimeWorkflowVersionEntity activeVersion) {
        if (activeVersion == null) {
            return true;
        }
        Map<String, Object> snapshot = readSnapshot(activeVersion.getSnapshotJson());
        String originalGraph = snapshot != null && snapshot.get("draftGraphSpec") instanceof String draft
                ? draft : activeVersion.getGraphSpecSnapshotJson();
        if (!jsonEquals(workflow.getGraphSpecJson(), originalGraph)
                || !jsonEquals(workflow.getCanvasJson(), activeVersion.getCanvasSnapshotJson())) {
            return true;
        }
        if (snapshot == null) {
            return true;
        }
        return differsWhenPresent(snapshot, "projectId", workflow.getProjectId())
                || differsWhenPresent(snapshot, "projectCode", workflow.getProjectCode())
                || differsWhenPresent(snapshot, "keySlug", workflow.getKeySlug())
                || differsWhenPresent(snapshot, "name", workflow.getName())
                || differsWhenPresent(snapshot, "description", workflow.getDescription())
                || differsWhenPresent(snapshot, "workflowKind", workflow.getWorkflowKind())
                || differsWhenPresent(snapshot, "executionEngine", workflow.getExecutionEngine())
                || differsJsonWhenPresent(snapshot, "inputSchemaJson", workflow.getInputSchemaJson())
                || differsJsonWhenPresent(snapshot, "outputSchemaJson", workflow.getOutputSchemaJson())
                || differsWhenPresent(snapshot, "defaultModelInstanceId", workflow.getDefaultModelInstanceId())
                || differsJsonWhenPresent(snapshot, "defaultResourceConfigJson", workflow.getDefaultResourceConfigJson())
                || differsWhenPresent(snapshot, "definitionAuthority", workflow.getDefinitionAuthority())
                || differsWhenPresent(snapshot, "creationChannel", workflow.getCreationChannel())
                || differsJsonWhenPresent(snapshot, "extraJson", workflow.getExtraJson());
    }

    private boolean jsonEquals(String left, String right) {
        if (!StringUtils.hasText(left) || !StringUtils.hasText(right)) {
            return !StringUtils.hasText(left) && !StringUtils.hasText(right);
        }
        try {
            JsonNode leftNode = objectMapper.readTree(left);
            JsonNode rightNode = objectMapper.readTree(right);
            return Objects.equals(leftNode, rightNode);
        } catch (Exception ignored) {
            return Objects.equals(left.trim(), right.trim());
        }
    }

    private Map<String, Object> readSnapshot(String snapshotJson) {
        if (!StringUtils.hasText(snapshotJson)) {
            return null;
        }
        try {
            return objectMapper.readValue(snapshotJson, MAP_TYPE);
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean differsWhenPresent(Map<String, Object> snapshot, String key, Object currentValue) {
        if (!snapshot.containsKey(key)) {
            return false;
        }
        Object snapshotValue = snapshot.get(key);
        if (snapshotValue instanceof Number || currentValue instanceof Number) {
            return !Objects.equals(
                    snapshotValue == null ? null : snapshotValue.toString(),
                    currentValue == null ? null : currentValue.toString());
        }
        return !Objects.equals(snapshotValue, currentValue);
    }

    private boolean differsJsonWhenPresent(Map<String, Object> snapshot, String key, String currentValue) {
        if (!snapshot.containsKey(key)) {
            return false;
        }
        Object snapshotValue = snapshot.get(key);
        return !jsonEquals(snapshotValue == null ? null : snapshotValue.toString(), currentValue);
    }

    public record WorkflowWorkingCopyState(String workflowId,
                                           Long projectId,
                                           String projectCode,
                                           String keySlug,
                                           String name,
                                           String description,
                                           String graphSpecJson,
                                           String canvasJson,
                                           String workflowKind,
                                           String executionEngine,
                                           String definitionAuthority,
                                           String creationChannel,
                                           String defaultModelInstanceId,
                                           String defaultResourceConfigJson,
                                           String status,
                                           String extraJson,
                                           String id,
                                           String inputSchemaJson,
                                           String outputSchemaJson,
                                           LocalDateTime createdAt,
                                           LocalDateTime updatedAt,
                                           Boolean deletable,
                                           String revision,
                                           ActiveVersionSummary activeVersion,
                                           boolean hasUnpublishedChanges) {

    }

    public record SaveWorkingCopyCommand(String graphSpecJson,
                                         String canvasJson,
                                         String extraJson,
                                         String baseRevision,
                                         String keySlug,
                                         String name,
                                         String description,
                                         String inputSchemaJson,
                                         String outputSchemaJson,
                                         String defaultModelInstanceId,
                                         String defaultResourceConfigJson,
                                         String workflowKind,
                                         String executionEngine,
                                         String definitionAuthority,
                                         String creationChannel) {

        public SaveWorkingCopyCommand(String graphSpecJson,
                                      String canvasJson,
                                      String extraJson) {
            this(graphSpecJson, canvasJson, extraJson, null, null, null, null, null, null,
                    null, null, null, null, null, null);
        }
    }

    public record ActiveVersionSummary(Long id,
                                       String version,
                                       Integer rolloutPercent,
                                       String status,
                                       String publishedBy,
                                       LocalDateTime publishedAt,
                                       String note) {
    }
}
