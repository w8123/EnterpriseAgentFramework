package com.enterprise.ai.runtime.internal;

import com.enterprise.ai.runtime.workflow.aicoding.AiCodingAttachmentException;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowAgentAttachmentPort;

import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowAttachmentService;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.AgentConfigVersionView;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.WorkflowToolRequest;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.WorkflowToolView;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionView;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowManagementService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowResourceBindingService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class RuntimeAgentSupervisorWorkflowAttachmentService implements RuntimeWorkflowAgentAttachmentPort {

    private static final Set<String> SUPPORTED_WORKFLOW_KINDS = Set.of("GENERAL", "PAGE_ASSISTANT");
    private final RuntimeCapabilityCatalogClient capabilityClient;
    private final RuntimeWorkflowManagementService workflowDefinitionService;
    private final RuntimeAgentWorkflowAttachmentService agentAttachments;
    private final ObjectMapper objectMapper;

    public AttachmentResult attach(Long pathProjectId, AttachRequest request) {
        if (request == null || !StringUtils.hasText(request.workflowId())) {
            throw new AiCodingAttachmentException("WORKFLOW_NOT_FOUND", "workflowId is required");
        }
        ProjectRef project = resolveProject(pathProjectId, null);
        RuntimeWorkflowDefinitionView workflow = workflowDefinitionService.findById(request.workflowId().trim())
                .orElseThrow(() -> new AiCodingAttachmentException(
                        "WORKFLOW_NOT_FOUND", "workflow not found: " + request.workflowId(), HttpStatus.NOT_FOUND));
        validateWorkflowProject(workflow, project);
        String workflowKind = workflow.getWorkflowKind();
        if (!SUPPORTED_WORKFLOW_KINDS.contains(
                workflowKind == null ? "" : workflowKind.trim().toUpperCase(Locale.ROOT))) {
            throw new AiCodingAttachmentException(
                    "WORKFLOW_KIND_NOT_SUPPORTED",
                    "workflow kind is not supported by the generic attach endpoint: " + workflow.getWorkflowKind(),
                    HttpStatus.BAD_REQUEST,
                    Map.of("workflowKind", String.valueOf(workflow.getWorkflowKind()),
                            "supported", SUPPORTED_WORKFLOW_KINDS));
        }
        if (!"ACTIVE".equalsIgnoreCase(String.valueOf(workflow.getStatus()))) {
            throw new AiCodingAttachmentException(
                    "WORKFLOW_NOT_ACTIVE",
                    "Workflow must be ACTIVE before attachment.",
                    HttpStatus.BAD_REQUEST,
                    Map.of("workflowId", workflow.getId(), "status", String.valueOf(workflow.getStatus())));
        }
        validateReplacementTarget(project, workflow, request.replaceWorkflowId());

        if (StringUtils.hasText(request.agentId()) && StringUtils.hasText(request.agentKeySlug())) {
            throw new AiCodingAttachmentException(
                    "AGENT_IDENTIFIER_CONFLICT",
                    "Provide either agentId or agentKeySlug, not both.");
        }

        try {
            boolean defaultReadOnly = !"PAGE_ASSISTANT".equalsIgnoreCase(workflowKind);
            WorkflowToolRequest tool = new WorkflowToolRequest(workflow.getId(),
                    firstText(request.toolName(), fallbackToolName(workflow.getKeySlug())),
                    firstText(request.descriptionOverride(), workflow.getDescription()),
                    writeJsonOrNull(request.inputSchema()), writeJsonOrNull(request.outputSchema()),
                    firstText(request.riskLevel(), defaultReadOnly ? "READ" : "PAGE_ACTION"),
                    firstText(request.permissionKey(), "workflow:" + workflow.getKeySlug()),
                    request.readOnly() == null ? defaultReadOnly : request.readOnly(), true, request.priority());
            var result = agentAttachments.attach(new RuntimeAgentWorkflowAttachmentService.ProjectRef(
                            project.projectId(), project.projectCode(), project.visibility()),
                    new RuntimeAgentWorkflowAttachmentService.Command(request.agentId(), request.agentKeySlug(),
                            request.modelInstanceId(), firstText(request.publishedBy(), "Cursor"), tool,
                            firstText(request.replaceWorkflowId()), !hasToolOverrides(request)));
            return toResult(project, new AgentRef(result.agentId(), result.agentKeySlug()), workflow,
                    result.config(), !result.reused(), result.reused(), firstText(request.replaceWorkflowId()));
        } catch (RuntimeAgentWorkflowAttachmentService.Failure ex) {
            throw new AiCodingAttachmentException(ex.code(), ex.getMessage(), ex.status());
        } catch (IllegalArgumentException ex) {
            String message = ex.getMessage() == null ? "attachment publish failed" : ex.getMessage();
            if (message.contains("ACTIVE published workflow") || message.contains("ACTIVE workflow")) {
                throw new AiCodingAttachmentException("WORKFLOW_NOT_ACTIVE", message);
            }
            if (message.contains("replaceWorkflowId")
                    || message.contains("replacement Workflow")) {
                throw new AiCodingAttachmentException(
                        "WORKFLOW_REPLACEMENT_INVALID",
                        message,
                        HttpStatus.CONFLICT);
            }
            throw new AiCodingAttachmentException("ATTACHMENT_PUBLISH_FAILED", message, HttpStatus.CONFLICT);
        }
    }

    public AttachmentResult attachPageAssistantOnly(String workflowId, PageAssistantAttachRequest request) {
        if (request == null) {
            throw new AiCodingAttachmentException("WORKFLOW_PROJECT_MISSING", "request body is required");
        }
        ProjectRef project = resolveProject(request.projectId(), request.projectCode());
        RuntimeWorkflowDefinitionView workflow = workflowDefinitionService.findById(
                        requireText(workflowId, "WORKFLOW_NOT_FOUND", "workflow id is required"))
                .orElseThrow(() -> new AiCodingAttachmentException(
                        "WORKFLOW_NOT_FOUND", "workflow not found: " + workflowId, HttpStatus.NOT_FOUND));
        if (!"PAGE_ASSISTANT".equalsIgnoreCase(String.valueOf(workflow.getWorkflowKind()))) {
            throw new AiCodingAttachmentException(
                    "WORKFLOW_KIND_NOT_SUPPORTED",
                    "Page Assistant attach endpoint only accepts PAGE_ASSISTANT, got: " + workflow.getWorkflowKind(),
                    HttpStatus.BAD_REQUEST,
                    Map.of("workflowKind", String.valueOf(workflow.getWorkflowKind())));
        }
        return attach(project.projectId(), new AttachRequest(
                workflow.getId(),
                request.agentId(),
                null,
                request.modelInstanceId(),
                request.publishedBy(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                request.replaceWorkflowId()));
    }

    private AttachmentResult toResult(ProjectRef project,
                                      AgentRef agent,
                                      RuntimeWorkflowDefinitionView workflow,
                                      AgentConfigVersionView config,
                                      boolean created,
                                      boolean reused,
                                      String replacedWorkflowId) {
        return new AttachmentResult(
                "workflow-tool-attachment.v1",
                project.projectId(),
                project.projectCode(),
                agent,
                new WorkflowRef(workflow.getId(), workflow.getKeySlug(), workflow.getWorkflowKind()),
                new ActiveConfigRef(config.id(), config.versionNo(), config.status()),
                resolveToolName(config, workflow),
                created,
                reused,
                replacedWorkflowId);
    }

    private void validateReplacementTarget(
            ProjectRef project,
            RuntimeWorkflowDefinitionView workflow,
            String replaceWorkflowId) {
        if (!StringUtils.hasText(replaceWorkflowId)) {
            return;
        }
        String oldWorkflowId = replaceWorkflowId.trim();
        if (oldWorkflowId.equals(workflow.getId())) {
            throw replacementInvalid(
                    workflow.getId(), oldWorkflowId,
                    "replaceWorkflowId must identify a different Workflow");
        }
        RuntimeWorkflowDefinitionView replaced = workflowDefinitionService
                .findById(oldWorkflowId)
                .orElseThrow(() -> replacementInvalid(
                        workflow.getId(), oldWorkflowId,
                        "replaceWorkflowId does not reference an existing Workflow"));
        boolean sameProject = Objects.equals(project.projectId(), replaced.getProjectId())
                && StringUtils.hasText(replaced.getProjectCode())
                && project.projectCode().equalsIgnoreCase(
                        replaced.getProjectCode().trim());
        if (!sameProject) {
            throw replacementInvalid(
                    workflow.getId(), oldWorkflowId,
                    "replacement Workflow must belong to the same project");
        }
        String workflowKind = String.valueOf(workflow.getWorkflowKind());
        if (!workflowKind.equalsIgnoreCase(
                String.valueOf(replaced.getWorkflowKind()))) {
            throw replacementInvalid(
                    workflow.getId(), oldWorkflowId,
                    "replacement Workflow must have the same workflowKind");
        }
        if ("PAGE_ASSISTANT".equalsIgnoreCase(workflowKind)) {
            String targetPage = exactTargetPage(workflow.getId());
            String replacedTargetPage = exactTargetPage(oldWorkflowId);
            if (targetPage == null
                    || replacedTargetPage == null
                    || !targetPage.equals(replacedTargetPage)) {
                throw replacementInvalid(
                        workflow.getId(), oldWorkflowId,
                        "PAGE_ASSISTANT replacement requires the same exact TARGET PAGE binding");
            }
        }
    }

    private String exactTargetPage(String workflowId) {
        List<RuntimeWorkflowResourceBindingService.BindingView> targets =
                workflowDefinitionService.listResourceBindings(workflowId)
                        .stream()
                        .filter(binding -> RuntimeWorkflowResourceBindingService.RESOURCE_PAGE
                                .equalsIgnoreCase(binding.resourceType()))
                        .filter(binding -> RuntimeWorkflowResourceBindingService.ROLE_TARGET
                                .equalsIgnoreCase(binding.bindingRole()))
                        .toList();
        return targets.size() == 1 ? targets.get(0).resourceKey() : null;
    }

    private AiCodingAttachmentException replacementInvalid(
            String workflowId,
            String replaceWorkflowId,
            String message) {
        return new AiCodingAttachmentException(
                "WORKFLOW_REPLACEMENT_INVALID",
                message,
                HttpStatus.CONFLICT,
                Map.of(
                        "workflowId", workflowId,
                        "replaceWorkflowId", replaceWorkflowId));
    }

    private String resolveToolName(AgentConfigVersionView config, RuntimeWorkflowDefinitionView workflow) {
        if (config != null && config.tools() != null) {
            for (WorkflowToolView tool : config.tools()) {
                if (tool != null
                        && workflow.getId().equals(tool.workflowId())
                        && StringUtils.hasText(tool.toolName())) {
                    return tool.toolName().trim();
                }
            }
        }
        return fallbackToolName(workflow.getKeySlug());
    }

    private String fallbackToolName(String keySlug) {
        String source = StringUtils.hasText(keySlug) ? keySlug.trim() : "workflow_tool";
        String normalized = source.replaceAll("[^A-Za-z0-9_]", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
        if (normalized.isEmpty() || !Character.isLetter(normalized.charAt(0))) {
            normalized = "workflow_" + normalized;
        }
        if (normalized.length() < 2) {
            normalized = "workflow_tool";
        }
        return normalized.length() <= 128 ? normalized : normalized.substring(0, 128);
    }

    private ProjectRef resolveProject(Long projectId, String projectCode) {
        Map<String, Object> body;
        try {
            if (projectId != null) {
                body = capabilityClient.getProjectById(projectId);
            } else if (StringUtils.hasText(projectCode)) {
                body = capabilityClient.getProject(projectCode.trim());
            } else {
                throw new AiCodingAttachmentException(
                        "WORKFLOW_PROJECT_MISSING", "projectId or projectCode is required");
            }
        } catch (AiCodingAttachmentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AiCodingAttachmentException(
                    "RUNTIME_DEPENDENCY_UNAVAILABLE",
                    "Capability project lookup failed: " + ex.getMessage(),
                    HttpStatus.BAD_GATEWAY);
        }
        Long resolvedId = longValue(body.get("projectId"));
        String resolvedCode = firstText(stringValue(body.get("projectCode")), projectCode);
        if (resolvedId == null || !StringUtils.hasText(resolvedCode)) {
            throw new AiCodingAttachmentException(
                    "RUNTIME_DEPENDENCY_UNAVAILABLE",
                    "Capability project lookup response is incomplete");
        }
        return new ProjectRef(resolvedId, resolvedCode, body.get("visibility"));
    }

    private void validateWorkflowProject(RuntimeWorkflowDefinitionView workflow, ProjectRef project) {
        if (workflow.getProjectId() == null && !StringUtils.hasText(workflow.getProjectCode())) {
            throw new AiCodingAttachmentException(
                    "WORKFLOW_PROJECT_MISSING", "workflow is missing project ownership");
        }
        if (workflow.getProjectId() != null && !Objects.equals(workflow.getProjectId(), project.projectId())) {
            throw new AiCodingAttachmentException(
                    "WORKFLOW_PROJECT_MISMATCH", "workflow does not belong to the current project");
        }
        if (StringUtils.hasText(workflow.getProjectCode())
                && !project.projectCode().equalsIgnoreCase(workflow.getProjectCode().trim())) {
            throw new AiCodingAttachmentException(
                    "WORKFLOW_PROJECT_MISMATCH", "workflow project code mismatch");
        }
    }

    private String requireText(String value, String code, String message) {
        if (!StringUtils.hasText(value)) throw new AiCodingAttachmentException(code, message);
        return value.trim();
    }

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            return Long.parseLong(text.trim());
        }
        return null;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new AiCodingAttachmentException(
                    "ATTACHMENT_PUBLISH_FAILED", "failed to serialize attachment metadata");
        }
    }

    private String writeJsonOrNull(Object value) {
        return value == null ? null : writeJson(value);
    }

    private boolean hasToolOverrides(AttachRequest request) {
        return request != null && (StringUtils.hasText(request.toolName())
                || StringUtils.hasText(request.descriptionOverride())
                || request.inputSchema() != null
                || request.outputSchema() != null
                || StringUtils.hasText(request.riskLevel())
                || StringUtils.hasText(request.permissionKey())
                || request.readOnly() != null
                || request.priority() != null
                || StringUtils.hasText(request.replaceWorkflowId()));
    }

    private record ProjectRef(Long projectId, String projectCode, Object visibility) {
    }
}
