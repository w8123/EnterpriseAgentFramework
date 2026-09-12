package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.workflow.aicoding.RuntimeWorkflowAiCodingService;
import com.enterprise.ai.runtime.workflow.aicoding.RuntimeWorkflowAiCodingService.ContextView;
import com.enterprise.ai.runtime.workflow.aicoding.RuntimeWorkflowAiCodingService.CreateRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Runtime-owned idempotency and provenance boundary for trace candidates. */
@Service
@RequiredArgsConstructor
public class RuntimeTraceWorkflowCandidateDraftService {

    private static final String SOURCE = "RUNOPS_TRACE_CANDIDATE";

    private final RuntimeWorkflowAiCodingService workflowAiCodingService;
    private final RuntimeWorkflowReleaseValidationService validationService;
    private final ObjectMapper objectMapper;
    private final RuntimeWorkflowDraftSubmissionService draftSubmissions;

    public ContextView createOrReplace(DraftRequest request) {
        if (request == null) {
            throw new IllegalArgumentException(
                    "trace Workflow candidate request is required");
        }
        String taskId = requireText(request.taskId(), "taskId");
        String traceId = requireText(request.sourceTraceId(), "sourceTraceId");
        String sourceWorkflowId = requireText(
                request.sourceWorkflowId(), "sourceWorkflowId");
        if (request.sourceWorkflowVersionId() == null) {
            throw new IllegalArgumentException(
                    "sourceWorkflowVersionId is required");
        }
        String projectCode = requireText(request.projectCode(), "projectCode");
        String keySlug = requireText(request.keySlug(), "keySlug");
        GraphSpec graphSpec = Objects.requireNonNull(
                request.graphSpec(), "graphSpec is required");

        Map<String, Object> metadata = Map.of(
                "source", SOURCE,
                "taskId", taskId,
                "sourceTraceId", traceId,
                "sourceWorkflowId", sourceWorkflowId,
                "sourceWorkflowVersionId", request.sourceWorkflowVersionId(),
                "sourceWorkflowVersion", requireText(
                        request.sourceWorkflowVersion(),
                        "sourceWorkflowVersion"));
        CreateRequest create = new CreateRequest(
                requireText(request.name(), "name"),
                keySlug,
                request.projectId(),
                projectCode,
                request.description(),
                request.workflowKind(),
                "GRAPH_SPEC",
                request.defaultModelInstanceId(),
                graphSpec,
                request.canvas(),
                metadata,
                List.of(),
                "RunOps trace candidate task " + taskId);
        return draftSubmissions.apply(
                new RuntimeWorkflowDraftSubmissionService.Scope(
                        SOURCE, request.projectId(), projectCode, taskId, traceId),
                create,
                attempt -> {
                    validate(request, projectCode, graphSpec);
                    ContextView applied = attempt.current() == null
                            ? workflowAiCodingService.createWorkflow(attempt.workflowId(), create)
                            : replaceExisting(attempt.current(), request, create, taskId, traceId,
                                    sourceWorkflowId, attempt.baseRevision());
                    return new RuntimeWorkflowDraftSubmissionService.Applied<>(
                            applied.workflow().id(), applied.workflow().updatedAt(), applied);
                }, workflowAiCodingService::context);
    }

    private void validate(DraftRequest request, String projectCode, GraphSpec graphSpec) {
        RuntimeWorkflowDefinitionEntity workflow = new RuntimeWorkflowDefinitionEntity();
        workflow.setProjectId(request.projectId());
        workflow.setProjectCode(projectCode);
        workflow.setWorkflowKind(request.workflowKind());
        workflow.setExecutionEngine("GRAPH_SPEC");
        workflow.setDefaultModelInstanceId(request.defaultModelInstanceId());
        RuntimeWorkflowReleaseValidationResult validation = validationService.validateProposed(workflow, graphSpec);
        if (!validation.valid()) {
            String code = validation.errors().isEmpty() ? "UNKNOWN" : validation.errors().get(0).code();
            throw new IllegalArgumentException("trace Workflow candidate validation failed: " + code);
        }
    }

    private ContextView replaceExisting(
            RuntimeWorkflowDefinitionEntity existing,
            DraftRequest request,
            CreateRequest create,
            String taskId,
            String traceId,
            String sourceWorkflowId,
            String baseRevision) {
        if (!"DRAFT".equalsIgnoreCase(existing.getStatus())) {
            throw new IllegalArgumentException(
                    "task-scoped Workflow is no longer a draft");
        }
        if (!Objects.equals(request.projectId(), existing.getProjectId())
                || !create.projectCode().equalsIgnoreCase(
                existing.getProjectCode())) {
            throw new IllegalArgumentException(
                    "existing Workflow does not belong to the selected project");
        }
        JsonNode metadata = readMetadata(existing.getExtraJson());
        if (!SOURCE.equals(metadata.path("source").asText())
                || !taskId.equals(metadata.path("taskId").asText())
                || !traceId.equals(metadata.path("sourceTraceId").asText())
                || !sourceWorkflowId.equals(
                metadata.path("sourceWorkflowId").asText())
                || request.sourceWorkflowVersionId().longValue()
                != metadata.path("sourceWorkflowVersionId").asLong(-1L)) {
            throw new IllegalArgumentException(
                    "existing Workflow is not owned by this trace candidate task");
        }
        return workflowAiCodingService.replaceDraft(existing.getId(), create, baseRevision);
    }

    private JsonNode readMetadata(String value) {
        if (!StringUtils.hasText(value)) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException(
                    "trace Workflow candidate metadata is invalid", ex);
        }
    }

    private static String requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    public record DraftRequest(
            String taskId,
            String sourceTraceId,
            String sourceWorkflowId,
            Long sourceWorkflowVersionId,
            String sourceWorkflowVersion,
            Long projectId,
            String projectCode,
            String name,
            String keySlug,
            String description,
            String workflowKind,
            String defaultModelInstanceId,
            GraphSpec graphSpec,
            Map<String, Object> canvas) {
    }
}
