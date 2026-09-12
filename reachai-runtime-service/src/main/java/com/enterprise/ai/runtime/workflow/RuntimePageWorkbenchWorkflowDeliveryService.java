package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowReleaseValidationResult.Item;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowResourceBindingService.BindingInput;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowResourceBindingService.BindingView;
import com.enterprise.ai.runtime.workflow.aicoding.RuntimeWorkflowAiCodingService;
import com.enterprise.ai.runtime.workflow.aicoding.RuntimeWorkflowAiCodingService.ContextView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Runtime-owned delivery boundary for Page Workbench Workflow engineering.
 *
 * <p>AI Coding artifacts may only create a DRAFT. Publishing the Workflow and
 * attaching it to the page copilot is a separate, explicitly invoked operation.
 * Both delivery writes run in the same Runtime transaction.</p>
 */
@Service
@RequiredArgsConstructor
public class RuntimePageWorkbenchWorkflowDeliveryService {

    public static final String DRAFT_SCHEMA =
            "reachai.page-workbench.workflow-engineering-draft.v1";
    public static final String DELIVERY_SCHEMA =
            "reachai.page-workbench.workflow-delivery.v1";

    private final RuntimeWorkflowAiCodingService workflowAiCodingService;
    private final RuntimeWorkflowDefinitionService workflowDefinitionService;
    private final RuntimeWorkflowResourceBindingService resourceBindingService;
    private final RuntimeWorkflowVersionService workflowVersionService;
    private final RuntimePageAssistantWorkflowAttachmentService attachmentService;
    private final ObjectMapper objectMapper;
    private final RuntimeWorkflowDraftSubmissionService draftSubmissions;

    public EngineeringDraftView createDraft(
            String routeProjectCode,
            EngineeringDraftRequest request) {
        if (request == null) {
            throw new IllegalArgumentException(
                    "workflow engineering draft request is required");
        }
        String projectCode = requireMatchingProjectCode(
                routeProjectCode,
                request.projectCode());
        Long projectId = Objects.requireNonNull(
                request.projectId(),
                "projectId is required");
        String taskId = requireText(request.taskId(), "taskId");
        String pageKey = requireText(request.pageKey(), "pageKey");
        String summary = requireText(request.summary(), "summary");
        WorkflowInput input = Objects.requireNonNull(
                request.workflow(),
                "workflow is required");
        String name = requireText(input.name(), "workflow.name");
        GraphSpec graphSpec = Objects.requireNonNull(
                input.graphSpec(),
                "workflow.graphSpec is required");
        List<String> actionKeys = requireTextList(
                request.selectedActionKeys(),
                "selectedActionKeys");
        List<String> referencedFiles = requireTextList(
                request.referencedFiles(),
                "referencedFiles");
        List<String> acceptanceCriteria = requireTextList(
                request.acceptanceCriteria(),
                "acceptanceCriteria");
        String replaceWorkflowId = textOrNull(request.replaceWorkflowId());
        List<String> remainingQuestions = normalizedTextList(
                request.remainingQuestions());
        String keySlug = taskScopedKeySlug(input.keySlug(), taskId);
        RuntimeWorkflowAiCodingService.CreateRequest draftRequest =
                new RuntimeWorkflowAiCodingService.CreateRequest(
                        name,
                        keySlug,
                        projectId,
                        projectCode,
                        input.description(),
                        WorkflowSemanticValues.KIND_PAGE_ASSISTANT,
                        WorkflowSemanticValues.ENGINE_GRAPH_SPEC,
                        input.defaultModelInstanceId(),
                        graphSpec,
                        input.canvas(),
                        draftMetadata(
                                taskId,
                                pageKey,
                                actionKeys,
                                referencedFiles,
                                acceptanceCriteria),
                        List.of(new BindingInput(
                                projectId,
                                projectCode,
                                RuntimeWorkflowResourceBindingService.RESOURCE_PAGE,
                                pageKey,
                                RuntimeWorkflowResourceBindingService.ROLE_TARGET)),
                        "Page Workbench task " + taskId);

        ContextView context = draftSubmissions.apply(
                new RuntimeWorkflowDraftSubmissionService.Scope(
                        "PAGE_WORKBENCH", projectId, projectCode, taskId, pageKey),
                java.util.Arrays.asList(draftRequest, summary, replaceWorkflowId, remainingQuestions),
                attempt -> {
                    ContextView applied = attempt.current() == null
                            ? workflowAiCodingService.createWorkflow(attempt.workflowId(), draftRequest)
                            : replaceExistingDraft(attempt.current(), projectId, projectCode, taskId,
                                    pageKey, draftRequest, attempt.baseRevision());
                    return new RuntimeWorkflowDraftSubmissionService.Applied<>(
                            applied.workflow().id(), applied.workflow().updatedAt(), applied);
                }, workflowAiCodingService::context);
        return toDraftView(
                context,
                taskId,
                pageKey,
                summary,
                actionKeys,
                referencedFiles,
                acceptanceCriteria,
                replaceWorkflowId,
                remainingQuestions);
    }

    @Transactional
    public DeliveryView deliver(
            String routeProjectCode,
            String workflowId,
            DeliveryRequest request) {
        if (request == null) {
            throw new IllegalArgumentException(
                    "workflow delivery request is required");
        }
        String projectCode = requireMatchingProjectCode(
                routeProjectCode,
                request.projectCode());
        Long projectId = Objects.requireNonNull(
                request.projectId(),
                "projectId is required");
        String taskId = requireText(request.taskId(), "taskId");
        String pageKey = requireText(request.pageKey(), "pageKey");
        String targetWorkflowId = requireText(workflowId, "workflowId");
        RuntimeWorkflowDefinitionEntity workflow = workflowDefinitionService
                .findById(targetWorkflowId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "workflow not found: " + targetWorkflowId));
        requireOwnedTaskDraft(
                workflow,
                projectId,
                projectCode,
                taskId,
                pageKey);
        requireExactPageBinding(workflow, pageKey);
        String replaceWorkflowId = textOrNull(request.replaceWorkflowId());
        if (replaceWorkflowId != null) {
            requireValidReplacementTarget(
                    replaceWorkflowId,
                    targetWorkflowId,
                    projectId,
                    projectCode,
                    pageKey);
        }

        RuntimeWorkflowReleaseValidationResult validation =
                workflowVersionService.validateRelease(targetWorkflowId);
        if (!validation.valid()) {
            String code = validation.errors().isEmpty()
                    ? "UNKNOWN"
                    : validation.errors().get(0).code();
            throw new IllegalArgumentException(
                    "workflow release validation failed: " + code);
        }

        String version = defaultText(request.version(), "v1.0.0");
        RuntimeWorkflowVersionEntity publishedVersion =
                findActiveVersion(targetWorkflowId, version);
        if (publishedVersion == null) {
            if (!"DRAFT".equalsIgnoreCase(workflow.getStatus())) {
                throw new IllegalArgumentException(
                        "workflow is already active with another version");
            }
            publishedVersion = workflowVersionService.publish(
                    targetWorkflowId,
                    version,
                    100,
                    "Delivered from Page Workbench task " + taskId,
                    "system:page-workbench",
                    workflow.getUpdatedAt().toString());
        }

        RuntimePageAssistantWorkflowAttachment attachment =
                attachmentService.attachPublishedPageWorkflow(
                        targetWorkflowId,
                        new RuntimePageAssistantWorkflowAttachRequest(
                                projectId,
                                projectCode,
                                request.agentId(),
                                request.modelInstanceId(),
                                defaultText(
                                        request.publishedBy(),
                                        "ReachAI Page Workbench"),
                                replaceWorkflowId));
        return new DeliveryView(
                DELIVERY_SCHEMA,
                taskId,
                pageKey,
                workflow.getId(),
                workflow.getKeySlug(),
                workflow.getName(),
                publishedVersion.getId(),
                publishedVersion.getVersion(),
                publishedVersion.getPublishedAt(),
                attachment.agentId(),
                attachment.agentKeySlug(),
                attachment.configVersionId(),
                attachment.configVersionNo(),
                attachment.toolName(),
                attachment.configStatus(),
                attachment.published(),
                attachment.replacedWorkflowId());
    }

    private void requireValidReplacementTarget(
            String replaceWorkflowId,
            String targetWorkflowId,
            Long projectId,
            String projectCode,
            String pageKey) {
        if (replaceWorkflowId.equals(targetWorkflowId)) {
            throw new IllegalArgumentException(
                    "replaceWorkflowId must identify a different Workflow");
        }
        RuntimeWorkflowDefinitionEntity replaced = workflowDefinitionService
                .findById(replaceWorkflowId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "replacement Workflow not found: " + replaceWorkflowId));
        if (!Objects.equals(projectId, replaced.getProjectId())
                || !projectCode.equalsIgnoreCase(
                defaultText(replaced.getProjectCode(), ""))) {
            throw new IllegalArgumentException(
                    "replacement Workflow does not belong to the requested project");
        }
        if (!WorkflowSemanticValues.KIND_PAGE_ASSISTANT.equalsIgnoreCase(
                defaultText(replaced.getWorkflowKind(), ""))) {
            throw new IllegalArgumentException(
                    "replacement Workflow is not PAGE_ASSISTANT");
        }
        requireExactPageBinding(replaced, pageKey);
    }

    private ContextView replaceExistingDraft(
            RuntimeWorkflowDefinitionEntity workflow,
            Long projectId,
            String projectCode,
            String taskId,
            String pageKey,
            RuntimeWorkflowAiCodingService.CreateRequest draftRequest,
            String baseRevision) {
        requireOwnedTaskDraft(
                workflow,
                projectId,
                projectCode,
                taskId,
                pageKey);
        if (!"DRAFT".equalsIgnoreCase(workflow.getStatus())) {
            throw new IllegalArgumentException(
                    "task-scoped workflow is no longer a draft");
        }
        requireExactPageBinding(workflow, pageKey);
        return workflowAiCodingService.replaceDraft(workflow.getId(), draftRequest, baseRevision);
    }

    private void requireOwnedTaskDraft(
            RuntimeWorkflowDefinitionEntity workflow,
            Long projectId,
            String projectCode,
            String taskId,
            String pageKey) {
        if (!Objects.equals(projectId, workflow.getProjectId())
                || !projectCode.equalsIgnoreCase(
                defaultText(workflow.getProjectCode(), ""))) {
            throw new IllegalArgumentException(
                    "workflow does not belong to the requested project");
        }
        if (!WorkflowSemanticValues.KIND_PAGE_ASSISTANT.equalsIgnoreCase(
                defaultText(workflow.getWorkflowKind(), ""))) {
            throw new IllegalArgumentException(
                    "workflow is not PAGE_ASSISTANT");
        }
        JsonNode metadata = readMetadata(workflow.getExtraJson());
        if (!"PAGE_WORKBENCH".equals(metadata.path("source").asText())
                || !taskId.equals(metadata.path("taskId").asText())
                || !pageKey.equals(metadata.path("pageKey").asText())) {
            throw new IllegalArgumentException(
                    "workflow is not owned by the requested Page Workbench task");
        }
    }

    private void requireExactPageBinding(
            RuntimeWorkflowDefinitionEntity workflow,
            String pageKey) {
        List<BindingView> targetPages = resourceBindingService
                .list(workflow.getId())
                .stream()
                .filter(binding -> RuntimeWorkflowResourceBindingService.RESOURCE_PAGE
                        .equalsIgnoreCase(binding.resourceType()))
                .filter(binding -> RuntimeWorkflowResourceBindingService.ROLE_TARGET
                        .equalsIgnoreCase(binding.bindingRole()))
                .toList();
        if (targetPages.size() != 1
                || !pageKey.equals(targetPages.get(0).resourceKey())) {
            throw new IllegalArgumentException(
                    "workflow must have exactly one matching TARGET PAGE binding");
        }
    }

    private RuntimeWorkflowVersionEntity findActiveVersion(
            String workflowId,
            String version) {
        return workflowVersionService.listVersions(workflowId)
                .stream()
                .filter(candidate -> version.equals(candidate.getVersion()))
                .filter(candidate -> "ACTIVE".equalsIgnoreCase(
                        candidate.getStatus()))
                .findFirst()
                .orElse(null);
    }

    private EngineeringDraftView toDraftView(
            ContextView context,
            String taskId,
            String pageKey,
            String summary,
            List<String> actionKeys,
            List<String> referencedFiles,
            List<String> acceptanceCriteria,
            String replaceWorkflowId,
            List<String> remainingQuestions) {
        RuntimeWorkflowAiCodingService.WorkflowSnapshot workflow =
                context.workflow();
        RuntimeWorkflowAiCodingService.ValidationView validation =
                context.validation();
        return new EngineeringDraftView(
                DRAFT_SCHEMA,
                taskId,
                pageKey,
                summary,
                actionKeys,
                referencedFiles,
                acceptanceCriteria,
                replaceWorkflowId,
                remainingQuestions,
                new WorkflowView(
                        workflow.id(),
                        workflow.keySlug(),
                        workflow.name(),
                        workflow.status(),
                        workflow.updatedAt()),
                new ValidationView(
                        validation.valid(),
                        validation.errors(),
                        validation.warnings()));
    }

    private Map<String, Object> draftMetadata(
            String taskId,
            String pageKey,
            List<String> actionKeys,
            List<String> referencedFiles,
            List<String> acceptanceCriteria) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "PAGE_WORKBENCH");
        metadata.put("taskId", taskId);
        metadata.put("pageKey", pageKey);
        metadata.put("selectedActionKeys", actionKeys);
        metadata.put("referencedFiles", referencedFiles);
        metadata.put("acceptanceCriteria", acceptanceCriteria);
        return metadata;
    }

    private JsonNode readMetadata(String value) {
        if (!StringUtils.hasText(value)) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException(
                    "workflow Page Workbench metadata is invalid", ex);
        }
    }

    private static String taskScopedKeySlug(String requested, String taskId) {
        String base = defaultText(requested, "page-assistant")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^[-_]+|[-_]+$", "");
        if (base.length() < 2) {
            base = "page-assistant";
        }
        String suffix = shortHash(taskId);
        int maxBaseLength = 128 - suffix.length() - 1;
        if (base.length() > maxBaseLength) {
            base = base.substring(0, maxBaseLength)
                    .replaceAll("[-_]+$", "");
        }
        return base + "-" + suffix;
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 5);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static String requireMatchingProjectCode(
            String routeProjectCode,
            String bodyProjectCode) {
        String route = requireText(routeProjectCode, "projectCode");
        String body = requireText(bodyProjectCode, "request.projectCode");
        if (!route.equalsIgnoreCase(body)) {
            throw new IllegalArgumentException(
                    "request projectCode does not match route projectCode");
        }
        return route;
    }

    private static List<String> requireTextList(
            List<String> values,
            String field) {
        List<String> normalized = normalizedTextList(values);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    field + " requires at least one item");
        }
        return normalized;
    }

    private static List<String> normalizedTextList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                String text = value.trim();
                if (!normalized.contains(text)) {
                    normalized.add(text);
                }
            }
        }
        return List.copyOf(normalized);
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

    public record EngineeringDraftRequest(
            Long projectId,
            String projectCode,
            String taskId,
            String pageKey,
            String summary,
            WorkflowInput workflow,
            List<String> selectedActionKeys,
            List<String> referencedFiles,
            List<String> acceptanceCriteria,
            String replaceWorkflowId,
            List<String> remainingQuestions) {
        public EngineeringDraftRequest(
                Long projectId,
                String projectCode,
                String taskId,
                String pageKey,
                String summary,
                WorkflowInput workflow,
                List<String> selectedActionKeys,
                List<String> referencedFiles,
                List<String> acceptanceCriteria,
                List<String> remainingQuestions) {
            this(projectId, projectCode, taskId, pageKey, summary, workflow,
                    selectedActionKeys, referencedFiles, acceptanceCriteria,
                    null, remainingQuestions);
        }
    }

    public record WorkflowInput(
            String name,
            String keySlug,
            String description,
            String defaultModelInstanceId,
            GraphSpec graphSpec,
            Map<String, Object> canvas) {
    }

    public record EngineeringDraftView(
            String schema,
            String taskId,
            String pageKey,
            String summary,
            List<String> selectedActionKeys,
            List<String> referencedFiles,
            List<String> acceptanceCriteria,
            String replaceWorkflowId,
            List<String> remainingQuestions,
            WorkflowView workflow,
            ValidationView validation) {
        public EngineeringDraftView(
                String schema,
                String taskId,
                String pageKey,
                String summary,
                List<String> selectedActionKeys,
                List<String> referencedFiles,
                List<String> acceptanceCriteria,
                List<String> remainingQuestions,
                WorkflowView workflow,
                ValidationView validation) {
            this(schema, taskId, pageKey, summary, selectedActionKeys,
                    referencedFiles, acceptanceCriteria, null,
                    remainingQuestions, workflow, validation);
        }
    }

    public record WorkflowView(
            String id,
            String keySlug,
            String name,
            String status,
            LocalDateTime updatedAt) {
    }

    public record ValidationView(
            boolean valid,
            List<Item> errors,
            List<Item> warnings) {
    }

    public record DeliveryRequest(
            Long projectId,
            String projectCode,
            String taskId,
            String pageKey,
            String version,
            String agentId,
            String modelInstanceId,
            String publishedBy,
            String replaceWorkflowId) {
        public DeliveryRequest(
                Long projectId,
                String projectCode,
                String taskId,
                String pageKey,
                String version,
                String agentId,
                String modelInstanceId,
                String publishedBy) {
            this(projectId, projectCode, taskId, pageKey, version, agentId,
                    modelInstanceId, publishedBy, null);
        }
    }

    public record DeliveryView(
            String schema,
            String taskId,
            String pageKey,
            String workflowId,
            String workflowKeySlug,
            String workflowName,
            Long workflowVersionId,
            String workflowVersion,
            LocalDateTime publishedAt,
            String agentId,
            String agentKeySlug,
            Long agentConfigVersionId,
            Integer agentConfigVersion,
            String toolName,
            String configStatus,
            boolean published,
            String replacedWorkflowId) {
        public DeliveryView(
                String schema,
                String taskId,
                String pageKey,
                String workflowId,
                String workflowKeySlug,
                String workflowName,
                Long workflowVersionId,
                String workflowVersion,
                LocalDateTime publishedAt,
                String agentId,
                String agentKeySlug,
                Long agentConfigVersionId,
                Integer agentConfigVersion,
                String toolName,
                String configStatus,
                boolean published) {
            this(schema, taskId, pageKey, workflowId, workflowKeySlug,
                    workflowName, workflowVersionId, workflowVersion,
                    publishedAt, agentId, agentKeySlug, agentConfigVersionId,
                    agentConfigVersion, toolName, configStatus, published, null);
        }
    }
}
