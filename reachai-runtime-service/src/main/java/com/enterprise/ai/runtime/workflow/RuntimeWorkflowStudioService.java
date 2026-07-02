package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class RuntimeWorkflowStudioService {

    private final RuntimeWorkflowDefinitionService workflowDefinitionService;
    private final ObjectMapper objectMapper;

    public WorkflowStudioState getStudioState(String workflowId) {
        RuntimeWorkflowDefinitionEntity workflow = requireWorkflow(workflowId);
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

    public RuntimeWorkflowDefinitionEntity saveStudioDraft(String workflowId, WorkflowStudioSaveRequest request) {
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

        RuntimeWorkflowDefinitionEntity update = new RuntimeWorkflowDefinitionEntity();
        update.setGraphSpecJson(graphSpecJson);
        update.setCanvasJson(request.canvasJson());
        update.setExtraJson(request.extraJson());
        return workflowDefinitionService.update(workflowId, update);
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
}
