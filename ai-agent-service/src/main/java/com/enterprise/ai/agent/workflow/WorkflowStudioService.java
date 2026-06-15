package com.enterprise.ai.agent.workflow;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
public class WorkflowStudioService {

    private final WorkflowDefinitionService workflowDefinitionService;
    private final ObjectMapper objectMapper;

    public WorkflowStudioState getStudioState(String workflowId) {
        WorkflowDefinitionEntity workflow = requireWorkflow(workflowId);
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
                workflow.getExtraJson());
    }

    public WorkflowDefinitionEntity saveStudioDraft(String workflowId, WorkflowStudioSaveRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("studio draft is required");
        }
        String graphSpecJson = requireGraphSpec(request.graphSpecJson());
        validateJson("graphSpecJson", graphSpecJson, GraphSpec.class);
        if (StringUtils.hasText(request.canvasJson())) {
            validateJson("canvasJson", request.canvasJson(), Object.class);
        }
        if (StringUtils.hasText(request.extraJson())) {
            validateJson("extraJson", request.extraJson(), Object.class);
        }
        requireWorkflow(workflowId);

        WorkflowDefinitionEntity update = new WorkflowDefinitionEntity();
        update.setGraphSpecJson(graphSpecJson);
        update.setCanvasJson(request.canvasJson());
        update.setExtraJson(request.extraJson());
        return workflowDefinitionService.update(workflowId, update);
    }

    public WorkflowRuntimeValidationResult validateRuntime(WorkflowRuntimeValidationRequest request) {
        if (request == null) {
            return WorkflowRuntimeValidationResult.invalid(List.of(
                    new ValidationItem("WORKFLOW_REQUEST_EMPTY", null, "workflow runtime validation request is required")));
        }
        String graphSpecJson = request.graphSpecJson();
        if (!StringUtils.hasText(graphSpecJson) && StringUtils.hasText(request.workflowId())) {
            graphSpecJson = requireWorkflow(request.workflowId()).getGraphSpecJson();
        }
        if (!StringUtils.hasText(graphSpecJson)) {
            return WorkflowRuntimeValidationResult.invalid(List.of(
                    new ValidationItem("GRAPH_SPEC_MISSING", null, "GraphSpec is required")));
        }
        try {
            objectMapper.readValue(graphSpecJson, GraphSpec.class);
            return WorkflowRuntimeValidationResult.ok();
        } catch (Exception ex) {
            return WorkflowRuntimeValidationResult.invalid(List.of(
                    new ValidationItem("GRAPH_SPEC_INVALID", null, ex.getMessage())));
        }
    }

    private WorkflowDefinitionEntity requireWorkflow(String workflowId) {
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
            objectMapper.readValue(json, type);
        } catch (Exception ex) {
            throw new IllegalArgumentException(field + " is not valid JSON", ex);
        }
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
                                      String extraJson) {
    }

    public record WorkflowStudioSaveRequest(String graphSpecJson,
                                            String canvasJson,
                                            String extraJson) {
    }

    public record WorkflowRuntimeValidationRequest(String workflowId,
                                                   String graphSpecJson,
                                                   String runtimeType) {
    }

    public record WorkflowRuntimeValidationResult(boolean valid,
                                                  List<ValidationItem> errors) {
        public static WorkflowRuntimeValidationResult ok() {
            return new WorkflowRuntimeValidationResult(true, List.of());
        }

        public static WorkflowRuntimeValidationResult invalid(List<ValidationItem> errors) {
            return new WorkflowRuntimeValidationResult(false, errors == null ? List.of() : errors);
        }
    }

    public record ValidationItem(String code,
                                 String target,
                                 String message) {
    }
}
