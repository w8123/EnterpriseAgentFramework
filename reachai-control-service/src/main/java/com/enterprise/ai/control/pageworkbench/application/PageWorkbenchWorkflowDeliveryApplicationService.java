package com.enterprise.ai.control.pageworkbench.application;

import com.enterprise.ai.control.aicoding.application.AiCodingTaskApplicationService;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.ArtifactView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskDetailView;
import com.enterprise.ai.control.aicoding.domain.AiCodingTaskModels.TaskTargetView;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.WorkflowDeliveryView;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Control-owned approval boundary for delivering a Workflow created by a
 * Page Workbench AI Coding task.
 */
@Service
@RequiredArgsConstructor
public class PageWorkbenchWorkflowDeliveryApplicationService {

    private final AiCodingTaskApplicationService taskService;
    private final RuntimeProxyClient runtimeClient;

    public WorkflowDeliveryView deliver(
            String projectCode,
            String taskId,
            DeliveryCommand command) {
        if (command == null) {
            throw new IllegalArgumentException(
                    "workflow delivery command is required");
        }
        String targetProjectCode = requireText(projectCode, "projectCode");
        String targetTaskId = requireText(taskId, "taskId");
        TaskDetailView detail = taskService.detail(targetTaskId);
        if (!targetProjectCode.equalsIgnoreCase(
                detail.task().projectCode())) {
            throw new IllegalArgumentException(
                    "task does not belong to the requested project");
        }
        if (!PageWorkbenchTaskProvider.WORKFLOW_ENGINEERING.equals(
                detail.task().taskKind())) {
            throw new IllegalArgumentException(
                    "task is not a Workflow engineering task");
        }
        if (!"COMPLETED".equals(detail.task().executionStatus())) {
            throw new IllegalStateException(
                    "Workflow engineering task must be accepted before delivery");
        }
        String pageKey = primaryPageKey(detail);
        if (StringUtils.hasText(command.pageKey())
                && !pageKey.equals(command.pageKey().trim())) {
            throw new IllegalArgumentException(
                    "delivery pageKey does not match the task PAGE target");
        }

        JsonNode engineering = latestAppliedEngineeringResult(detail);
        String workflowId = engineering.path("workflow")
                .path("id")
                .asText(null);
        if (!StringUtils.hasText(workflowId)) {
            throw new IllegalStateException(
                    "accepted task does not contain an applied Workflow draft");
        }
        if (!targetTaskId.equals(engineering.path("taskId").asText())
                || !pageKey.equals(engineering.path("pageKey").asText())) {
            throw new IllegalStateException(
                    "Workflow draft result does not match the accepted task");
        }

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("projectId", detail.task().projectId());
        request.put("projectCode", targetProjectCode);
        request.put("taskId", targetTaskId);
        request.put("pageKey", pageKey);
        request.put(
                "version",
                defaultText(command.version(), "v1.0.0"));
        request.put("agentId", textOrNull(command.agentId()));
        request.put(
                "modelInstanceId",
                textOrNull(command.modelInstanceId()));
        request.put(
                "publishedBy",
                defaultText(command.publishedBy(), "ReachAI Page Workbench"));
        WorkflowDeliveryView delivered = runtimeClient
                .deliverPageWorkbenchWorkflow(
                        targetProjectCode,
                        workflowId,
                        request)
                .getBody();
        if (delivered == null || !delivered.published()) {
            throw new IllegalStateException(
                    "Runtime did not confirm Workflow delivery");
        }
        return delivered;
    }

    private JsonNode latestAppliedEngineeringResult(TaskDetailView detail) {
        return detail.artifacts().stream()
                .filter(artifact -> "APPLIED".equals(
                        artifact.processingStatus()))
                .filter(artifact -> artifact.applicationResult() != null
                        && artifact.applicationResult()
                        .hasNonNull("workflowEngineering"))
                .max(Comparator.comparing(
                        ArtifactView::artifactId,
                        Comparator.nullsFirst(Long::compareTo)))
                .map(ArtifactView::applicationResult)
                .map(result -> result.path("workflowEngineering"))
                .orElseThrow(() -> new IllegalStateException(
                        "task does not contain an applied Workflow engineering result"));
    }

    private String primaryPageKey(TaskDetailView detail) {
        return detail.task().targets().stream()
                .filter(target -> "PRIMARY".equals(target.targetRole()))
                .filter(target -> "PAGE".equals(target.targetType()))
                .map(TaskTargetView::targetKey)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Workflow engineering task has no primary PAGE target"));
    }

    private static String requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private static String textOrNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    public record DeliveryCommand(
            String pageKey,
            String version,
            String agentId,
            String modelInstanceId,
            String publishedBy) {
    }
}
