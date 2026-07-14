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

    private final RuntimeWorkflowDefinitionService workflowDefinitionService;
    private final RuntimeWorkflowVersionService workflowVersionService;
    private final ObjectMapper objectMapper;

    public WorkflowStudioState getStudioState(String workflowId) {
        RuntimeWorkflowDefinitionEntity workflow = requireWorkflow(workflowId);
        return toStudioState(workflow);
    }

    @Transactional
    public WorkflowStudioState saveStudioDraft(String workflowId, WorkflowStudioSaveRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("studio draft is required");
        }
        String graphSpecJson = requireGraphSpec(request.graphSpecJson());
        validateJson("graphSpecJson", graphSpecJson, GraphSpec.class);
        validateOptionalJson("canvasJson", request.canvasJson());
        validateOptionalJson("inputSchemaJson", request.inputSchemaJson());
        validateOptionalJson("outputSchemaJson", request.outputSchemaJson());
        validateOptionalJson("defaultResourceConfigJson", request.defaultResourceConfigJson());
        validateOptionalJson("extraJson", request.extraJson());

        RuntimeWorkflowDefinitionEntity update = new RuntimeWorkflowDefinitionEntity();
        update.setKeySlug(request.keySlug());
        update.setName(request.name());
        update.setDescription(request.description());
        update.setWorkflowType(request.workflowType());
        update.setRuntimeType(request.runtimeType());
        update.setGraphSpecJson(graphSpecJson);
        update.setCanvasJson(request.canvasJson());
        update.setInputSchemaJson(request.inputSchemaJson());
        update.setOutputSchemaJson(request.outputSchemaJson());
        update.setDefaultModelInstanceId(request.defaultModelInstanceId());
        update.setDefaultResourceConfigJson(request.defaultResourceConfigJson());
        update.setExtraJson(request.extraJson());
        RuntimeWorkflowDefinitionEntity saved = workflowDefinitionService.update(
                workflowId,
                update,
                request.baseRevision());
        return toStudioState(saved);
    }

    private WorkflowStudioState toStudioState(RuntimeWorkflowDefinitionEntity workflow) {
        RuntimeWorkflowVersionEntity activeVersion = workflowVersionService.resolveActive(workflow.getId());
        return new WorkflowStudioState(
                workflow.getId(),
                workflow.getProjectId(),
                workflow.getProjectCode(),
                workflow.getKeySlug(),
                workflow.getName(),
                workflow.getDescription(),
                workflow.getGraphSpecJson(),
                workflow.getCanvasJson(),
                workflow.getWorkflowType(),
                workflow.getRuntimeType(),
                workflow.getDefaultModelInstanceId(),
                workflow.getDefaultResourceConfigJson(),
                workflow.getStatus(),
                workflow.getManagedBy(),
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

    private RuntimeWorkflowDefinitionEntity requireWorkflow(String workflowId) {
        if (!StringUtils.hasText(workflowId)) {
            throw new IllegalArgumentException("workflowId is required");
        }
        return workflowDefinitionService.findById(workflowId)
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

    private String revision(RuntimeWorkflowDefinitionEntity workflow) {
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

    private boolean hasUnpublishedChanges(RuntimeWorkflowDefinitionEntity workflow,
                                          RuntimeWorkflowVersionEntity activeVersion) {
        if (activeVersion == null) {
            return true;
        }
        if (!jsonEquals(workflow.getGraphSpecJson(), activeVersion.getGraphSpecSnapshotJson())
                || !jsonEquals(workflow.getCanvasJson(), activeVersion.getCanvasSnapshotJson())) {
            return true;
        }
        Map<String, Object> snapshot = readSnapshot(activeVersion.getSnapshotJson());
        if (snapshot == null) {
            return true;
        }
        return differsWhenPresent(snapshot, "projectId", workflow.getProjectId())
                || differsWhenPresent(snapshot, "projectCode", workflow.getProjectCode())
                || differsWhenPresent(snapshot, "keySlug", workflow.getKeySlug())
                || differsWhenPresent(snapshot, "name", workflow.getName())
                || differsWhenPresent(snapshot, "description", workflow.getDescription())
                || differsWhenPresent(snapshot, "workflowType", workflow.getWorkflowType())
                || differsWhenPresent(snapshot, "runtimeType", workflow.getRuntimeType())
                || differsJsonWhenPresent(snapshot, "inputSchemaJson", workflow.getInputSchemaJson())
                || differsJsonWhenPresent(snapshot, "outputSchemaJson", workflow.getOutputSchemaJson())
                || differsWhenPresent(snapshot, "defaultModelInstanceId", workflow.getDefaultModelInstanceId())
                || differsJsonWhenPresent(snapshot, "defaultResourceConfigJson", workflow.getDefaultResourceConfigJson())
                || differsWhenPresent(snapshot, "managedBy", workflow.getManagedBy())
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

    public record WorkflowStudioState(String workflowId,
                                      Long projectId,
                                      String projectCode,
                                      String keySlug,
                                      String name,
                                      String description,
                                      String graphSpecJson,
                                      String canvasJson,
                                      String workflowType,
                                      String runtimeType,
                                      String defaultModelInstanceId,
                                      String defaultResourceConfigJson,
                                      String status,
                                      String managedBy,
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

        public WorkflowStudioState(String workflowId,
                                   Long projectId,
                                   String projectCode,
                                   String keySlug,
                                   String name,
                                   String description,
                                   String graphSpecJson,
                                   String canvasJson,
                                   String workflowType,
                                   String runtimeType,
                                   String defaultModelInstanceId,
                                   String defaultResourceConfigJson,
                                   String status,
                                   String managedBy,
                                   String extraJson) {
            this(workflowId, projectId, projectCode, keySlug, name, description, graphSpecJson, canvasJson,
                    workflowType, runtimeType, defaultModelInstanceId, defaultResourceConfigJson, status, managedBy,
                    extraJson, workflowId, null, null, null, null, null, null, null, true);
        }
    }

    public record WorkflowStudioSaveRequest(String graphSpecJson,
                                            String canvasJson,
                                            String extraJson,
                                            String baseRevision,
                                            String keySlug,
                                            String name,
                                            String description,
                                            String workflowType,
                                            String runtimeType,
                                            String inputSchemaJson,
                                            String outputSchemaJson,
                                            String defaultModelInstanceId,
                                            String defaultResourceConfigJson) {

        public WorkflowStudioSaveRequest(String graphSpecJson,
                                         String canvasJson,
                                         String extraJson) {
            this(graphSpecJson, canvasJson, extraJson, null, null, null, null, null, null,
                    null, null, null, null);
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
