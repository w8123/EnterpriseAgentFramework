package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.agent.graph.GraphSpecToolContract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Execution configuration owned by one immutable Workflow release. */
public record RuntimePublishedWorkflowSnapshot(
        String workflowId,
        Long versionId,
        String graphSpecJson,
        String workflowKind,
        Long projectId,
        String projectCode,
        String defaultModelInstanceId,
        String defaultResourceConfigJson) {

    private static final ObjectMapper JSON = new ObjectMapper();

    public static RuntimePublishedWorkflowSnapshot read(RuntimeWorkflowVersionEntity version) {
        return read(RuntimeWorkflowPublishedVersionView.fromEntity(version));
    }

    public static RuntimePublishedWorkflowSnapshot read(RuntimeWorkflowPublishedVersionView version) {
        if (version == null || version.getId() == null || version.getId() <= 0
                || version.getWorkflowId() == null || version.getWorkflowId().isBlank()
                || !Set.of("ACTIVE", "RETIRED").contains(String.valueOf(version.getStatus()))) {
            throw invalid("发布版本不存在或状态不可执行");
        }
        try {
            JsonNode snapshot = version.getSnapshotJson() == null ? null : JSON.readTree(version.getSnapshotJson());
            if (snapshot == null || !snapshot.isObject() || !snapshot.has("defaultModelInstanceId")) {
                throw invalid("发布快照缺少执行配置，请重新发布 Workflow");
            }
            String snapshotId = optionalText(snapshot, "id");
            if (snapshotId != null && !version.getWorkflowId().equals(snapshotId)) {
                throw invalid("发布快照与 Workflow 不匹配");
            }
            Long projectId = null;
            JsonNode project = snapshot.get("projectId");
            if (project != null && !project.isNull()) {
                if (!project.isIntegralNumber() || !project.canConvertToLong() || project.longValue() <= 0) {
                    throw invalid("发布快照的项目范围无效");
                }
                projectId = project.longValue();
            }
            return new RuntimePublishedWorkflowSnapshot(
                    version.getWorkflowId(), version.getId(),
                    GraphSpecToolContract.requirePublishedPins(version.getGraphSpecSnapshotJson(), JSON),
                    optionalText(snapshot, "workflowKind"),
                    projectId, optionalText(snapshot, "projectCode"),
                    optionalText(snapshot, "defaultModelInstanceId"), optionalText(snapshot, "defaultResourceConfigJson"));
        } catch (IllegalArgumentException invalid) {
            throw invalid;
        } catch (Exception invalid) {
            throw invalid("发布快照无法读取，请重新发布 Workflow");
        }
    }

    /** An explicit null default also overrides caller-supplied model routing fields. */
    public Map<String, Object> executionInput(Map<String, Object> businessInput) {
        Map<String, Object> input = new LinkedHashMap<>(businessInput == null ? Map.of() : businessInput);
        input.put("workflowDefaultModelInstanceId", defaultModelInstanceId);
        return input;
    }

    public RuntimeWorkflowDefinitionEntity validationDefinition() {
        var definition = new RuntimeWorkflowDefinitionEntity();
        definition.setId(workflowId);
        definition.setProjectId(projectId);
        definition.setProjectCode(projectCode);
        definition.setWorkflowKind(workflowKind);
        definition.setGraphSpecJson(graphSpecJson);
        definition.setDefaultModelInstanceId(defaultModelInstanceId);
        definition.setDefaultResourceConfigJson(defaultResourceConfigJson);
        return definition;
    }

    private static String optionalText(JsonNode snapshot, String field) {
        JsonNode value = snapshot.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw invalid("发布快照字段类型无效: " + field);
        return value.asText().isBlank() ? null : value.asText().trim();
    }

    private static IllegalArgumentException invalid(String detail) {
        return new IllegalArgumentException("PUBLISHED_WORKFLOW_SNAPSHOT_INVALID: " + detail);
    }
}
