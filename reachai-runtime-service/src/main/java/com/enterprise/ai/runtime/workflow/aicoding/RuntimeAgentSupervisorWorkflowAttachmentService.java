package com.enterprise.ai.runtime.workflow.aicoding;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigService;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.AgentConfigDraftRequest;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.AgentConfigVersionView;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.WorkflowToolRequest;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.WorkflowToolView;
import com.enterprise.ai.runtime.agent.RuntimeAgentEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentMapper;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelCatalogClient;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowResourceBindingService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class RuntimeAgentSupervisorWorkflowAttachmentService {

    private static final Pattern UNSAFE_KEY_CHARS = Pattern.compile("[^A-Za-z0-9_-]+");
    private static final Set<String> SUPPORTED_WORKFLOW_KINDS = Set.of("GENERAL", "PAGE_ASSISTANT");
    private static final String DEFAULT_PAGE_COPILOT_DESCRIPTION =
            "项目页面副驾驶 Agent，用于嵌入式对话、页面理解和 Workflow 路由。";
    private static final String DEFAULT_PAGE_COPILOT_SYSTEM_PROMPT =
            "你是当前项目的页面副驾驶 Supervisor。理解用户请求并制定计划，"
                    + "从允许的 Workflow 中选择一个或多个作为 Tool 执行。"
                    + "当前页面的数据、列表、筛选、统计、可见行和详情都属于页面请求；"
                    + "即使用户只说查、查询、统计、多少、哪些或筛选，也应使用匹配的页面 Workflow，"
                    + "不能因为用户没有说打开或操作页面就拒绝。"
                    + "名称、说明和回复默认使用简体中文；Token、MCP、AI、Agent、"
                    + "Supervisor、Workflow、Tool、API、SDK 等熟知专业术语和技术标识可保留英文。";
    private static final Set<String> LEGACY_PAGE_COPILOT_DESCRIPTIONS = Set.of(
            "Project page copilot Agent for embedded chat and Workflow routing.",
            "Project page copilot Agent for embedded chat, page understanding, and Workflow routing.");
    private static final Set<String> LEGACY_PAGE_COPILOT_SYSTEM_PROMPTS = Set.of(
            "You are the project's page copilot Supervisor. Understand the request, plan, and select one or more permitted Workflows as tools. Use page-action Workflows only when the user explicitly asks to open, navigate, query, or operate a page.",
            "You are the project's page copilot. Understand the user's intent and select the permitted Workflows as tools.");

    private final RuntimeCapabilityCatalogClient capabilityClient;
    private final RuntimeModelCatalogClient modelCatalogClient;
    private final RuntimeWorkflowDefinitionService workflowDefinitionService;
    private final RuntimeWorkflowVersionService workflowVersionService;
    private final RuntimeAgentMapper agentMapper;
    private final RuntimeAgentConfigService agentConfigService;
    private final ObjectMapper objectMapper;

    @Transactional
    public AttachmentResult attach(Long pathProjectId, AttachRequest request) {
        if (request == null || !StringUtils.hasText(request.workflowId())) {
            throw new AiCodingAttachmentException("WORKFLOW_NOT_FOUND", "workflowId is required");
        }
        ProjectRef project = resolveProject(pathProjectId, null);
        RuntimeWorkflowDefinitionEntity workflow = workflowDefinitionService.findById(request.workflowId().trim())
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

        RuntimeAgentEntity agent = resolveAgent(project, request.agentId(), request.agentKeySlug());
        String modelInstanceId = resolveModelInstanceId(agent, request.modelInstanceId());
        String toolName = StringUtils.hasText(request.toolName())
                ? request.toolName().trim()
                : fallbackToolName(workflow.getKeySlug());

        Optional<com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionEntity> activeEntity =
                agentConfigService.resolveActive(agent.getId());
        boolean legacyActiveSystemPrompt = activeEntity
                .map(com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionEntity::getSystemPrompt)
                .filter(StringUtils::hasText)
                .filter(LEGACY_PAGE_COPILOT_SYSTEM_PROMPTS::contains)
                .isPresent();
        if (activeEntity.isPresent()) {
            List<WorkflowToolView> tools = agentConfigService.listTools(agent.getId(), activeEntity.get().getId());
            Optional<WorkflowToolView> attachedTool = tools.stream()
                    .filter(tool -> workflow.getId().equals(tool.workflowId()))
                    .findFirst();
            if (attachedTool.isPresent() && !hasToolOverrides(request)
                    && modelMatches(activeEntity.get().getModelInstanceId(), modelInstanceId)
                    && !legacyActiveSystemPrompt) {
                RuntimeWorkflowVersionEntity activeWorkflowVersion =
                        workflowVersionService.resolveActive(workflow.getId());
                if (activeWorkflowVersion != null
                        && Objects.equals(attachedTool.get().workflowVersionId(), activeWorkflowVersion.getId())) {
                    AgentConfigVersionView activeView = agentConfigService.list(agent.getId()).stream()
                            .filter(view -> Objects.equals(view.id(), activeEntity.get().getId()))
                            .findFirst()
                            .orElseThrow(() -> new AiCodingAttachmentException(
                                    "ATTACHMENT_PUBLISH_FAILED", "ACTIVE config view missing after reuse check"));
                    return toResult(project, agent, workflow, activeView, false, true, null);
                }

                AgentConfigVersionView draft = agentConfigService.saveDraft(agent.getId(), new AgentConfigDraftRequest(
                        null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null));
                AgentConfigVersionView published = agentConfigService.publish(
                        agent.getId(), draft.id(), firstText(request.publishedBy(), "Cursor"));
                return toResult(project, agent, workflow, published, true, false, null);
            }
        }

        try {
            agentConfigService.saveDraft(agent.getId(), new AgentConfigDraftRequest(
                    "AGENTSCOPE",
                    legacyActiveSystemPrompt
                            ? DEFAULT_PAGE_COPILOT_SYSTEM_PROMPT
                            : null,
                    modelInstanceId,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null));
            boolean defaultReadOnly = !"PAGE_ASSISTANT".equalsIgnoreCase(workflowKind);
            String riskLevel = firstText(request.riskLevel(), defaultReadOnly ? "READ" : "PAGE_ACTION");
            boolean readOnly = request.readOnly() == null ? defaultReadOnly : request.readOnly();
            WorkflowToolRequest requestedTool = new WorkflowToolRequest(
                            workflow.getId(),
                            toolName,
                            firstText(request.descriptionOverride(), workflow.getDescription()),
                            writeJsonOrNull(request.inputSchema()),
                            writeJsonOrNull(request.outputSchema()),
                            riskLevel,
                            firstText(request.permissionKey(), "workflow:" + workflow.getKeySlug()),
                            readOnly,
                            true,
                            request.priority());
            AgentConfigVersionView draft = StringUtils.hasText(request.replaceWorkflowId())
                    ? agentConfigService.replaceWorkflowToolInDraft(
                            agent.getId(), request.replaceWorkflowId().trim(), requestedTool)
                    : agentConfigService.upsertWorkflowToolInDraft(
                            agent.getId(), requestedTool);
            AgentConfigVersionView published = agentConfigService.publish(
                    agent.getId(), draft.id(), firstText(request.publishedBy(), "Cursor"));
            return toResult(
                    project,
                    agent,
                    workflow,
                    published,
                    true,
                    false,
                    firstText(request.replaceWorkflowId()));
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

    @Transactional
    public AttachmentResult attachPageAssistantOnly(String workflowId, PageAssistantAttachRequest request) {
        if (request == null) {
            throw new AiCodingAttachmentException("WORKFLOW_PROJECT_MISSING", "request body is required");
        }
        ProjectRef project = resolveProject(request.projectId(), request.projectCode());
        RuntimeWorkflowDefinitionEntity workflow = workflowDefinitionService.findById(
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
                                      RuntimeAgentEntity agent,
                                      RuntimeWorkflowDefinitionEntity workflow,
                                      AgentConfigVersionView config,
                                      boolean created,
                                      boolean reused,
                                      String replacedWorkflowId) {
        return new AttachmentResult(
                "workflow-tool-attachment.v1",
                project.projectId(),
                project.projectCode(),
                new AgentRef(agent.getId(), agent.getKeySlug()),
                new WorkflowRef(workflow.getId(), workflow.getKeySlug(), workflow.getWorkflowKind()),
                new ActiveConfigRef(config.id(), config.versionNo(), config.status()),
                resolveToolName(config, workflow),
                created,
                reused,
                replacedWorkflowId);
    }

    private void validateReplacementTarget(
            ProjectRef project,
            RuntimeWorkflowDefinitionEntity workflow,
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
        RuntimeWorkflowDefinitionEntity replaced = workflowDefinitionService
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

    private String resolveToolName(AgentConfigVersionView config, RuntimeWorkflowDefinitionEntity workflow) {
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

    private RuntimeAgentEntity resolveAgent(ProjectRef project, String agentId, String agentKeySlug) {
        if (StringUtils.hasText(agentId)) {
            RuntimeAgentEntity agent = agentMapper.selectById(agentId.trim());
            if (agent == null) {
                throw new AiCodingAttachmentException(
                        "AGENT_NOT_FOUND", "agent not found by agentId: " + agentId, HttpStatus.NOT_FOUND);
            }
            validateAgentProject(agent, project);
            return agent;
        }
        if (StringUtils.hasText(agentKeySlug)) {
            RuntimeAgentEntity agent = agentMapper.selectOne(Wrappers.<RuntimeAgentEntity>lambdaQuery()
                    .eq(RuntimeAgentEntity::getKeySlug, agentKeySlug.trim())
                    .last("LIMIT 1"));
            if (agent == null) {
                throw new AiCodingAttachmentException(
                        "AGENT_NOT_FOUND", "agent not found by agentKeySlug: " + agentKeySlug, HttpStatus.NOT_FOUND);
            }
            validateAgentProject(agent, project);
            return agent;
        }
        return findOrCreatePageCopilotAgent(project);
    }

    private RuntimeAgentEntity findOrCreatePageCopilotAgent(ProjectRef project) {
        String keySlug = pageCopilotKeySlug(project.projectCode());
        RuntimeAgentEntity existing = agentMapper.selectOne(Wrappers.<RuntimeAgentEntity>lambdaQuery()
                .eq(RuntimeAgentEntity::getKeySlug, keySlug)
                .last("LIMIT 1"));
        if (existing != null) {
            validateAgentProject(existing, project);
            localizeLegacyAgentDefaults(existing);
            return existing;
        }
        RuntimeAgentEntity entity = new RuntimeAgentEntity();
        entity.setId(newId());
        entity.setProjectId(project.projectId());
        entity.setProjectCode(project.projectCode());
        entity.setKeySlug(keySlug);
        entity.setName(project.projectCode() + " 页面副驾驶 Agent");
        entity.setDescription(DEFAULT_PAGE_COPILOT_DESCRIPTION);
        entity.setVisibility(firstText(stringValue(project.visibility()), "PROJECT"));
        entity.setEnabled(true);
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        agentMapper.insert(entity);
        agentConfigService.createInitialDraft(
                entity.getId(),
                DEFAULT_PAGE_COPILOT_SYSTEM_PROMPT,
                null,
                writeJson(Map.of(
                        "source", "ai-coding-attach",
                        "purpose", "page-copilot",
                        "routing", "supervisor-workflow-tools"
                )));
        return entity;
    }

    private void localizeLegacyAgentDefaults(
            RuntimeAgentEntity existing) {
        boolean changed = false;
        String currentName = existing.getName();
        if (StringUtils.hasText(currentName)
                && currentName.endsWith(" Page Copilot")) {
            existing.setName(
                    currentName.substring(
                            0,
                            currentName.length()
                                    - " Page Copilot".length())
                            + " 页面副驾驶 Agent");
            changed = true;
        }
        String currentDescription = existing.getDescription();
        if (StringUtils.hasText(currentDescription)
                && LEGACY_PAGE_COPILOT_DESCRIPTIONS.contains(
                        currentDescription)) {
            existing.setDescription(
                    DEFAULT_PAGE_COPILOT_DESCRIPTION);
            changed = true;
        }
        if (changed) {
            existing.setUpdatedAt(LocalDateTime.now());
            agentMapper.updateById(existing);
        }
    }

    private String resolveModelInstanceId(RuntimeAgentEntity agent, String requested) {
        try {
            if (StringUtils.hasText(requested)) {
                String modelId = requested.trim();
                if (!modelCatalogClient.isActiveLlm(modelId)) {
                    throw new AiCodingAttachmentException(
                            "MODEL_INSTANCE_NOT_ACTIVE",
                            "modelInstanceId is missing or not an ACTIVE LLM: " + modelId);
                }
                return modelId;
            }
            Optional<com.enterprise.ai.runtime.agent.RuntimeAgentConfigVersionEntity> active =
                    agentConfigService.resolveActive(agent.getId());
            if (active.isPresent() && StringUtils.hasText(active.get().getModelInstanceId())) {
                String existing = active.get().getModelInstanceId().trim();
                if (modelCatalogClient.isActiveLlm(existing)) {
                    return existing;
                }
            }
            String selected = modelCatalogClient.firstActiveLlmId();
            if (!StringUtils.hasText(selected)) {
                throw new AiCodingAttachmentException(
                        "NO_ACTIVE_LLM",
                        "No ACTIVE LLM model instance is available for Supervisor publish.");
            }
            return selected;
        } catch (AiCodingAttachmentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AiCodingAttachmentException(
                    "RUNTIME_DEPENDENCY_UNAVAILABLE",
                    "Model catalog lookup failed: " + ex.getMessage(),
                    HttpStatus.BAD_GATEWAY);
        }
    }

    private void validateWorkflowProject(RuntimeWorkflowDefinitionEntity workflow, ProjectRef project) {
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

    private void validateAgentProject(RuntimeAgentEntity agent, ProjectRef project) {
        if (agent.getProjectId() != null && !Objects.equals(agent.getProjectId(), project.projectId())) {
            throw new AiCodingAttachmentException(
                    "AGENT_PROJECT_MISMATCH", "agent does not belong to the current project");
        }
        if (StringUtils.hasText(agent.getProjectCode())
                && !project.projectCode().equalsIgnoreCase(agent.getProjectCode().trim())) {
            throw new AiCodingAttachmentException(
                    "AGENT_PROJECT_MISMATCH", "agent project code mismatch");
        }
    }

    private boolean modelMatches(String current, String expected) {
        if (!StringUtils.hasText(expected)) {
            return true;
        }
        return expected.equals(current);
    }

    private String pageCopilotKeySlug(String projectCode) {
        return limitKey(safeKey(requireText(projectCode, "WORKFLOW_PROJECT_MISSING", "project code is required"))
                + "-page-copilot");
    }

    private String safeKey(String value) {
        String normalized = UNSAFE_KEY_CHARS.matcher(value.trim().replace('.', '_')).replaceAll("-");
        normalized = normalized.replaceAll("[-_]{2,}", "-");
        normalized = normalized.replaceAll("^[^A-Za-z0-9]+", "");
        normalized = normalized.replaceAll("[^A-Za-z0-9]+$", "");
        if (normalized.length() < 2) {
            normalized = "agent-" + normalized;
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private String limitKey(String key) {
        return key.length() <= 128 ? key : key.substring(0, 128);
    }

    private String requireText(String value, String code, String message) {
        if (!StringUtils.hasText(value)) {
            throw new AiCodingAttachmentException(code, message);
        }
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

    private String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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

    public record AttachRequest(
            String workflowId,
            String agentId,
            String agentKeySlug,
            String modelInstanceId,
            String publishedBy,
            String toolName,
            String descriptionOverride,
            Map<String, Object> inputSchema,
            Map<String, Object> outputSchema,
            String riskLevel,
            String permissionKey,
            Boolean readOnly,
            Integer priority,
            String replaceWorkflowId
    ) {
        public AttachRequest(String workflowId,
                             String agentId,
                             String agentKeySlug,
                             String modelInstanceId,
                             String publishedBy) {
            this(workflowId, agentId, agentKeySlug, modelInstanceId, publishedBy,
                    null, null, null, null, null, null, null, null, null);
        }

        public AttachRequest(
                String workflowId,
                String agentId,
                String agentKeySlug,
                String modelInstanceId,
                String publishedBy,
                String toolName,
                String descriptionOverride,
                Map<String, Object> inputSchema,
                Map<String, Object> outputSchema,
                String riskLevel,
                String permissionKey,
                Boolean readOnly,
                Integer priority) {
            this(workflowId, agentId, agentKeySlug, modelInstanceId, publishedBy,
                    toolName, descriptionOverride, inputSchema, outputSchema,
                    riskLevel, permissionKey, readOnly, priority, null);
        }
    }

    public record PageAssistantAttachRequest(
            Long projectId,
            String projectCode,
            String agentId,
            String modelInstanceId,
            String publishedBy,
            String replaceWorkflowId
    ) {
        public PageAssistantAttachRequest(
                Long projectId,
                String projectCode,
                String agentId,
                String modelInstanceId,
                String publishedBy) {
            this(projectId, projectCode, agentId, modelInstanceId, publishedBy, null);
        }
    }

    public record AttachmentResult(
            String schema,
            Long projectId,
            String projectCode,
            AgentRef agent,
            WorkflowRef workflow,
            ActiveConfigRef activeConfig,
            String toolName,
            boolean created,
            boolean reused,
            String replacedWorkflowId
    ) {
        public AttachmentResult(
                String schema,
                Long projectId,
                String projectCode,
                AgentRef agent,
                WorkflowRef workflow,
                ActiveConfigRef activeConfig,
                String toolName,
                boolean created,
                boolean reused) {
            this(schema, projectId, projectCode, agent, workflow, activeConfig,
                    toolName, created, reused, null);
        }
    }

    public record AgentRef(String id, String keySlug) {
    }

    public record WorkflowRef(String id, String keySlug, String workflowKind) {
    }

    public record ActiveConfigRef(Long id, Integer version, String status) {
    }

    private record ProjectRef(Long projectId, String projectCode, Object visibility) {
    }
}
