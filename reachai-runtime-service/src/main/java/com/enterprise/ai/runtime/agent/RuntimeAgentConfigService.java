package com.enterprise.ai.runtime.agent;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.AgentConfigDraftRequest;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.AgentConfigVersionView;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.SkillBindingRequest;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.SkillBindingView;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.RemoteAgentBindingRequest;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.RemoteAgentBindingView;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.WorkflowToolRequest;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.WorkflowToolView;
import com.enterprise.ai.runtime.agent.RuntimeAgentEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentMapper;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowToolCatalogQuery;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowToolCatalogQuery.Entry;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowToolCatalogQuery.Reference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class RuntimeAgentConfigService {

    private static final Pattern TOOL_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{1,127}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> SKILL_ACTIVATION_MODES = Set.of("MODEL_SELECTED", "ALWAYS", "EXPLICIT");
    private static final Set<String> SKILL_SCRIPT_POLICIES = Set.of("DENY", "SANDBOX_REVIEWED");
    private static final Set<String> SKILL_VISIBILITIES = Set.of("PRIVATE", "PROJECT", "SHARED", "PUBLIC");
    private static final Set<String> A2A_RISK_LEVELS = Set.of("READ", "WRITE", "IRREVERSIBLE");
    private static final Set<String> MANAGED_EXECUTOR_TOOLS = Set.of(
            "managed_executor.start", "managed_executor.status", "managed_executor.read_result");
    private static final Set<String> MANAGED_EXECUTOR_CONFIG_FIELDS = Set.of(
            "enabled", "autoRouteEnabled", "allowedTools", "sandboxProfile", "modelRef",
            "acceptanceProfile", "priority", "maxWallTimeSeconds", "approvalTimeoutSeconds",
            "maxDelegationsPerRun");
    private static final Pattern MANAGED_EXECUTOR_IDENTIFIER = Pattern.compile("[A-Za-z0-9._:-]+");
    private static final int DEFAULT_TOTAL_TIMEOUT_MS = 300_000;
    private static final int DEFAULT_WORKFLOW_TIMEOUT_MS = 180_000;
    private static final int DEFAULT_PAGE_BRIDGE_TIMEOUT_MS = 30_000;

    private final RuntimeAgentConfigVersionMapper configMapper;
    private final RuntimeAgentWorkflowToolMapper toolMapper;
    private final RuntimeAgentSkillBindingMapper skillBindingMapper;
    private final RuntimeAgentRemoteBindingMapper remoteAgentBindingMapper;
    private final RuntimeAgentMapper agentMapper;
    private final RuntimeWorkflowToolCatalogQuery workflowCatalog;
    private final ObjectMapper objectMapper;

    public List<AgentConfigVersionView> list(String agentId) {
        requireAgent(agentId);
        return configMapper.selectList(Wrappers.<RuntimeAgentConfigVersionEntity>lambdaQuery()
                        .eq(RuntimeAgentConfigVersionEntity::getAgentId, agentId)
                        .orderByDesc(RuntimeAgentConfigVersionEntity::getVersionNo))
                .stream()
                .map(this::toView)
                .toList();
    }

    public Optional<RuntimeAgentConfigVersionEntity> find(Long configVersionId) {
        return configVersionId == null
                ? Optional.empty()
                : Optional.ofNullable(configMapper.selectById(configVersionId));
    }

    public Optional<RuntimeAgentConfigVersionEntity> resolveActive(String agentId) {
        RuntimeAgentEntity agent = requireAgent(agentId);
        if (agent.getActiveConfigVersionId() != null) {
            RuntimeAgentConfigVersionEntity active = configMapper.selectById(agent.getActiveConfigVersionId());
            if (active != null && agentId.equals(active.getAgentId()) && "ACTIVE".equalsIgnoreCase(active.getStatus())) {
                return Optional.of(active);
            }
        }
        return Optional.ofNullable(configMapper.selectOne(Wrappers.<RuntimeAgentConfigVersionEntity>lambdaQuery()
                .eq(RuntimeAgentConfigVersionEntity::getAgentId, agentId)
                .eq(RuntimeAgentConfigVersionEntity::getStatus, "ACTIVE")
                .orderByDesc(RuntimeAgentConfigVersionEntity::getVersionNo)
                .last("LIMIT 1")));
    }

    public Optional<RuntimeAgentConfigVersionEntity> resolveDisplayConfig(String agentId) {
        RuntimeAgentConfigVersionEntity draft = configMapper.selectOne(
                Wrappers.<RuntimeAgentConfigVersionEntity>lambdaQuery()
                        .eq(RuntimeAgentConfigVersionEntity::getAgentId, agentId)
                        .eq(RuntimeAgentConfigVersionEntity::getStatus, "DRAFT")
                        .orderByDesc(RuntimeAgentConfigVersionEntity::getVersionNo)
                        .last("LIMIT 1"));
        return draft == null ? resolveActive(agentId) : Optional.of(draft);
    }

    @Transactional
    public AgentConfigVersionView saveDraft(String agentId, AgentConfigDraftRequest request) {
        requireLockedAgent(agentId);
        AgentConfigDraftRequest body = request == null ? emptyDraft() : request;
        RuntimeAgentConfigVersionEntity draft = configMapper.selectOne(
                Wrappers.<RuntimeAgentConfigVersionEntity>lambdaQuery()
                        .eq(RuntimeAgentConfigVersionEntity::getAgentId, agentId)
                        .eq(RuntimeAgentConfigVersionEntity::getStatus, "DRAFT")
                        .orderByDesc(RuntimeAgentConfigVersionEntity::getVersionNo)
                        .last("LIMIT 1"));
        boolean creating = draft == null;
        if (creating) {
            draft = new RuntimeAgentConfigVersionEntity();
            draft.setAgentId(agentId);
            draft.setVersionNo(nextVersionNo(agentId));
            draft.setStatus("DRAFT");
            RuntimeAgentConfigVersionEntity newDraft = draft;
            resolveActive(agentId).ifPresent(active -> copyConfiguration(active, newDraft));
            applyDefaults(draft);
            draft.setCreatedAt(LocalDateTime.now());
        }
        apply(body, draft);
        applyDefaults(draft);
        draft.setUpdatedAt(LocalDateTime.now());
        if (creating) {
            configMapper.insert(draft);
            copyActiveToolsIfNeeded(agentId, draft.getId(), body.tools());
            copyActiveSkillsIfNeeded(agentId, draft.getId(), body.skills());
            copyActiveRemoteAgentsIfNeeded(agentId, draft.getId(), body.remoteAgents());
        } else {
            configMapper.updateById(draft);
        }
        if (body.tools() != null) {
            replaceTools(agentId, draft, body.tools());
        }
        if (body.skills() != null) {
            replaceSkills(agentId, draft, body.skills());
        }
        if (body.remoteAgents() != null) {
            replaceRemoteAgents(agentId, draft, body.remoteAgents());
        }
        validateUniqueToolNames(draft);
        return toView(draft);
    }

    @Transactional
    public AgentConfigVersionView createInitialDraft(String agentId,
                                                     String systemPrompt,
                                                     String modelInstanceId,
                                                     String configJson) {
        return saveDraft(agentId, new AgentConfigDraftRequest(
                "AGENTSCOPE",
                systemPrompt,
                modelInstanceId,
                6,
                4,
                2,
                DEFAULT_TOTAL_TIMEOUT_MS,
                DEFAULT_WORKFLOW_TIMEOUT_MS,
                DEFAULT_PAGE_BRIDGE_TIMEOUT_MS,
                true,
                "DEV_ALLOW_ALL",
                "ALLOW_LIST",
                configJson,
                List.of()));
    }

    @Transactional
    public AgentConfigVersionView publish(String agentId, Long configVersionId, String publishedBy) {
        RuntimeAgentEntity agent = requireLockedAgent(agentId);
        RuntimeAgentConfigVersionEntity target = requireVersion(agentId, configVersionId);
        return publishPrepared(agent, target, publishedBy, true);
    }

    /** Publish one attachment from ACTIVE while leaving the editable DRAFT and unrelated pins intact. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public WorkflowAttachmentPublication publishWorkflowAttachment(
            String agentId, WorkflowToolRequest request, String replacedWorkflowId, String modelInstanceId,
            String publishedBy, boolean preserveToolConfiguration, AgentConfigDraftRequest initialDefaults) {
        RuntimeAgentEntity agent = requireLockedAgent(agentId);
        if (request == null || !StringUtils.hasText(request.workflowId())) {
            throw new IllegalArgumentException("workflowId is required for Agent Workflow Tool");
        }
        String workflowId = request.workflowId().trim();
        Entry entry = requireActivePublishedWorkflow(workflowId);
        if (!entry.version().executable()) {
            throw new IllegalArgumentException("Agent Workflow Tool requires an executable published Workflow version: " + workflowId);
        }
        RuntimeAgentConfigVersionEntity active = resolveActive(agentId).orElse(null);
        if (active == null && initialDefaults == null) {
            throw new IllegalArgumentException("Publish the initial Agent configuration before attaching a Workflow");
        }
        List<RuntimeAgentWorkflowToolEntity> activeTools = active == null ? List.of() : snapshotTools(agentId, active.getId());
        String replacedId = trimToNull(replacedWorkflowId);
        String targetId = replacedId == null ? workflowId : replacedId;
        RuntimeAgentWorkflowToolEntity current = activeTools.stream()
                .filter(tool -> targetId.equals(tool.getWorkflowId())).findFirst().orElse(null);
        if (replacedId != null && (replacedId.equals(workflowId) || current == null
                || activeTools.stream().anyMatch(tool -> workflowId.equals(tool.getWorkflowId())))) {
            throw new IllegalArgumentException("replaceWorkflowId must name an attached Workflow and replacement Workflow must be different and unattached");
        }
        if (active != null && replacedId == null && current != null && preserveToolConfiguration
                && java.util.Objects.equals(current.getWorkflowVersionId(), entry.version().id())
                && java.util.Objects.equals(active.getModelInstanceId(), modelInstanceId)) {
            return new WorkflowAttachmentPublication(toView(active), true);
        }
        RuntimeAgentConfigVersionEntity candidate = new RuntimeAgentConfigVersionEntity();
        candidate.setAgentId(agentId);
        candidate.setVersionNo(nextVersionNo(agentId));
        candidate.setStatus("DRAFT");
        if (active != null) copyConfiguration(active, candidate);
        else apply(initialDefaults, candidate);
        candidate.setModelInstanceId(modelInstanceId);
        applyDefaults(candidate);
        validatePublishable(candidate);
        candidate.setCreatedAt(LocalDateTime.now());
        candidate.setUpdatedAt(candidate.getCreatedAt());
        configMapper.insert(candidate);
        copyActiveToolsIfNeeded(agentId, candidate.getId(), null);
        copyActiveSkillsIfNeeded(agentId, candidate.getId(), null);
        copyActiveRemoteAgentsIfNeeded(agentId, candidate.getId(), null);
        RuntimeAgentWorkflowToolEntity copied = toolMapper.selectOne(Wrappers.<RuntimeAgentWorkflowToolEntity>lambdaQuery()
                .eq(RuntimeAgentWorkflowToolEntity::getAgentConfigVersionId, candidate.getId())
                .eq(RuntimeAgentWorkflowToolEntity::getWorkflowId, targetId));
        if (copied != null && replacedId == null && preserveToolConfiguration) {
            copied.setWorkflowVersionId(entry.version().id());
            copied.setUpdatedAt(LocalDateTime.now());
            toolMapper.updateById(copied);
        } else {
            int priority = request.priority() != null ? request.priority()
                    : current != null && current.getPriority() != null ? current.getPriority()
                    : activeTools.stream().map(RuntimeAgentWorkflowToolEntity::getPriority)
                            .filter(java.util.Objects::nonNull).max(Integer::compareTo).orElse(-1) + 1;
            if (copied != null) toolMapper.deleteById(copied.getId());
            requireToolName(request.toolName());
            insertWorkflowTool(agentId, candidate.getId(), request, entry, priority);
        }
        validateUniqueToolNames(candidate);
        return new WorkflowAttachmentPublication(publishPrepared(agent, candidate, publishedBy, false), false);
    }

    public record WorkflowAttachmentPublication(AgentConfigVersionView config, boolean reused) { }

    private AgentConfigVersionView publishPrepared(RuntimeAgentEntity agent, RuntimeAgentConfigVersionEntity target,
                                                   String publishedBy, boolean refreshAllWorkflowPins) {
        String agentId = agent.getId();
        if (!"DRAFT".equalsIgnoreCase(target.getStatus())) {
            throw new IllegalArgumentException("only DRAFT Agent config can be published");
        }
        validatePublishable(target);
        if (refreshAllWorkflowPins) pinWorkflowVersions(target);
        validateSkillBindings(target, agent.getProjectCode());
        validateRemoteAgentBindings(target);
        List<RuntimeAgentConfigVersionEntity> activeVersions = configMapper.selectList(
                Wrappers.<RuntimeAgentConfigVersionEntity>lambdaQuery()
                        .eq(RuntimeAgentConfigVersionEntity::getAgentId, agentId)
                        .eq(RuntimeAgentConfigVersionEntity::getStatus, "ACTIVE"));
        for (RuntimeAgentConfigVersionEntity active : activeVersions) {
            active.setStatus("ARCHIVED");
            active.setUpdatedAt(LocalDateTime.now());
            configMapper.updateById(active);
        }
        target.setStatus("ACTIVE");
        target.setPublishedBy(trimToNull(publishedBy));
        target.setPublishedAt(LocalDateTime.now());
        target.setUpdatedAt(LocalDateTime.now());
        configMapper.updateById(target);
        agent.setActiveConfigVersionId(target.getId());
        agent.setUpdatedAt(LocalDateTime.now());
        agentMapper.update(null, Wrappers.<RuntimeAgentEntity>lambdaUpdate()
                .eq(RuntimeAgentEntity::getId, agentId)
                .set(RuntimeAgentEntity::getActiveConfigVersionId, target.getId())
                .set(RuntimeAgentEntity::getUpdatedAt, agent.getUpdatedAt()));
        return toView(target);
    }

    /**
     * Copies an immutable ACTIVE/ARCHIVED snapshot into the single editable draft.
     * Published rows are never reactivated or mutated.
     */
    @Transactional
    public AgentConfigVersionView copyToDraft(String agentId, Long configVersionId) {
        requireLockedAgent(agentId);
        RuntimeAgentConfigVersionEntity source = requireVersion(agentId, configVersionId);
        if ("DRAFT".equalsIgnoreCase(source.getStatus())) {
            return toView(source);
        }

        RuntimeAgentConfigVersionEntity currentDraft = configMapper.selectOne(
                Wrappers.<RuntimeAgentConfigVersionEntity>lambdaQuery()
                        .eq(RuntimeAgentConfigVersionEntity::getAgentId, agentId)
                        .eq(RuntimeAgentConfigVersionEntity::getStatus, "DRAFT")
                        .orderByDesc(RuntimeAgentConfigVersionEntity::getVersionNo)
                        .last("LIMIT 1"));
        if (currentDraft != null) {
            toolMapper.delete(Wrappers.<RuntimeAgentWorkflowToolEntity>lambdaQuery()
                    .eq(RuntimeAgentWorkflowToolEntity::getAgentConfigVersionId, currentDraft.getId()));
            skillBindingMapper.delete(Wrappers.<RuntimeAgentSkillBindingEntity>lambdaQuery()
                    .eq(RuntimeAgentSkillBindingEntity::getAgentConfigVersionId, currentDraft.getId()));
            remoteAgentBindingMapper.delete(Wrappers.<RuntimeAgentRemoteBindingEntity>lambdaQuery()
                    .eq(RuntimeAgentRemoteBindingEntity::getAgentConfigVersionId,
                            currentDraft.getId()));
            configMapper.deleteById(currentDraft.getId());
        }

        List<WorkflowToolRequest> tools = listTools(agentId, source.getId()).stream()
                .map(this::toRequest)
                .toList();
        List<SkillBindingRequest> skills = listSkills(agentId, source.getId()).stream()
                .map(this::toRequest)
                .toList();
        List<RemoteAgentBindingRequest> remoteBindings = listRemoteAgentBindings(
                        agentId, source.getId()).stream()
                .map(this::toRequest)
                .toList();
        return saveDraft(agentId, new AgentConfigDraftRequest(
                source.getRuntimeType(),
                source.getSystemPrompt(),
                source.getModelInstanceId(),
                source.getMaxPlanSteps(),
                source.getMaxWorkflowCalls(),
                source.getMaxReplans(),
                source.getTotalTimeoutMs(),
                source.getWorkflowTimeoutMs(),
                source.getPageBridgeTimeoutMs(),
                source.getParallelReadOnly(),
                source.getPolicyProfile(),
                source.getToolCatalogMode(),
                source.getConfigJson(),
                tools,
                skills,
                remoteBindings));
    }

    public List<RuntimeAgentWorkflowToolEntity> resolveActiveTools(String agentId,
                                                                   RuntimeAgentConfigVersionEntity activeConfig) {
        return resolveTools(agentId, activeConfig);
    }

    public List<RuntimeAgentWorkflowToolEntity> resolveTools(String agentId,
                                                              RuntimeAgentConfigVersionEntity config) {
        if (config == null || config.getId() == null || !agentId.equals(config.getAgentId())) {
            return List.of();
        }
        return toolMapper.selectList(Wrappers.<RuntimeAgentWorkflowToolEntity>lambdaQuery()
                .eq(RuntimeAgentWorkflowToolEntity::getAgentId, agentId)
                .eq(RuntimeAgentWorkflowToolEntity::getAgentConfigVersionId, config.getId())
                .eq(RuntimeAgentWorkflowToolEntity::getEnabled, true)
                .orderByAsc(RuntimeAgentWorkflowToolEntity::getPriority)
                .orderByAsc(RuntimeAgentWorkflowToolEntity::getId));
    }

    public List<WorkflowToolView> listTools(String agentId, Long configVersionId) {
        requireVersion(agentId, configVersionId);
        List<RuntimeAgentWorkflowToolEntity> rows = toolMapper.selectList(
                Wrappers.<RuntimeAgentWorkflowToolEntity>lambdaQuery()
                        .eq(RuntimeAgentWorkflowToolEntity::getAgentId, agentId)
                        .eq(RuntimeAgentWorkflowToolEntity::getAgentConfigVersionId, configVersionId)
                        .orderByAsc(RuntimeAgentWorkflowToolEntity::getPriority)
                        .orderByAsc(RuntimeAgentWorkflowToolEntity::getId));
        return toToolViews(rows);
    }

    public List<RuntimeAgentSkillBindingEntity> resolveSkills(String agentId,
                                                               RuntimeAgentConfigVersionEntity config) {
        if (config == null || config.getId() == null || !agentId.equals(config.getAgentId())) {
            return List.of();
        }
        return skillBindingMapper.selectList(Wrappers.<RuntimeAgentSkillBindingEntity>lambdaQuery()
                .eq(RuntimeAgentSkillBindingEntity::getAgentId, agentId)
                .eq(RuntimeAgentSkillBindingEntity::getAgentConfigVersionId, config.getId())
                .eq(RuntimeAgentSkillBindingEntity::getEnabled, true)
                .orderByAsc(RuntimeAgentSkillBindingEntity::getPriority)
                .orderByAsc(RuntimeAgentSkillBindingEntity::getId));
    }

    public List<SkillBindingView> listSkills(String agentId, Long configVersionId) {
        requireVersion(agentId, configVersionId);
        return skillBindingMapper.selectList(Wrappers.<RuntimeAgentSkillBindingEntity>lambdaQuery()
                        .eq(RuntimeAgentSkillBindingEntity::getAgentId, agentId)
                        .eq(RuntimeAgentSkillBindingEntity::getAgentConfigVersionId, configVersionId)
                        .orderByAsc(RuntimeAgentSkillBindingEntity::getPriority)
                        .orderByAsc(RuntimeAgentSkillBindingEntity::getId))
                .stream()
                .map(this::toView)
                .toList();
    }

    public List<RuntimeAgentRemoteBindingEntity> resolveRemoteAgentBindings(
            String agentId, RuntimeAgentConfigVersionEntity config) {
        if (config == null || config.getId() == null || !agentId.equals(config.getAgentId())) {
            return List.of();
        }
        return remoteAgentBindingMapper.selectList(
                Wrappers.<RuntimeAgentRemoteBindingEntity>lambdaQuery()
                        .eq(RuntimeAgentRemoteBindingEntity::getAgentId, agentId)
                        .eq(RuntimeAgentRemoteBindingEntity::getAgentConfigVersionId, config.getId())
                        .eq(RuntimeAgentRemoteBindingEntity::getEnabled, true)
                        .orderByAsc(RuntimeAgentRemoteBindingEntity::getPriority)
                        .orderByAsc(RuntimeAgentRemoteBindingEntity::getId));
    }

    public List<RemoteAgentBindingView> listRemoteAgentBindings(
            String agentId, Long configVersionId) {
        requireVersion(agentId, configVersionId);
        return remoteAgentBindingMapper.selectList(
                        Wrappers.<RuntimeAgentRemoteBindingEntity>lambdaQuery()
                                .eq(RuntimeAgentRemoteBindingEntity::getAgentId, agentId)
                                .eq(RuntimeAgentRemoteBindingEntity::getAgentConfigVersionId,
                                        configVersionId)
                                .orderByAsc(RuntimeAgentRemoteBindingEntity::getPriority)
                                .orderByAsc(RuntimeAgentRemoteBindingEntity::getId))
                .stream().map(this::toView).toList();
    }

    @Transactional
    public AgentConfigVersionView ensureWorkflowToolInDraft(String agentId,
                                                             String workflowId,
                                                             boolean readOnly) {
        if (!StringUtils.hasText(workflowId)) {
            throw new IllegalArgumentException("workflowId is required for Agent Workflow Tool");
        }
        String normalizedWorkflowId = workflowId.trim();
        var workflow = requireActivePublishedWorkflow(normalizedWorkflowId).workflow();
        return upsertWorkflowToolInDraft(agentId, new WorkflowToolRequest(
                normalizedWorkflowId,
                workflowToolName(workflow.keySlug()),
                workflow.description(),
                null,
                null,
                readOnly ? "READ" : "PAGE_ACTION",
                "workflow:" + workflow.keySlug(),
                readOnly,
                true,
                null));
    }

    /** Adds or replaces one Workflow tool in a new Agent draft while preserving the rest of the catalog. */
    @Transactional
    public AgentConfigVersionView upsertWorkflowToolInDraft(String agentId,
                                                            WorkflowToolRequest requestedTool) {
        requireLockedAgent(agentId);
        if (requestedTool == null || !StringUtils.hasText(requestedTool.workflowId())) {
            throw new IllegalArgumentException("workflowId is required for Agent Workflow Tool");
        }
        String normalizedWorkflowId = requestedTool.workflowId().trim();
        requireActivePublishedWorkflow(normalizedWorkflowId);

        Optional<RuntimeAgentConfigVersionEntity> displayConfig = resolveDisplayConfig(agentId);
        List<WorkflowToolRequest> tools = new ArrayList<>();
        boolean replaced = false;
        if (displayConfig.isPresent()) {
            for (WorkflowToolView current : listTools(agentId, displayConfig.get().getId())) {
                if (normalizedWorkflowId.equals(current.workflowId())) {
                    tools.add(requestedTool);
                    replaced = true;
                } else {
                    tools.add(new WorkflowToolRequest(
                            current.workflowId(),
                            current.toolName(),
                            current.description(),
                            current.inputSchemaJson(),
                            current.outputSchemaJson(),
                            current.riskLevel(),
                            current.permissionKey(),
                            current.readOnly(),
                            current.enabled(),
                            current.priority()));
                }
            }
        }
        if (!replaced) {
            tools.add(requestedTool);
        }
        return saveDraft(agentId, new AgentConfigDraftRequest(
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, List.copyOf(tools)));
    }

    /**
     * Replaces one explicitly identified Workflow tool while preserving every
     * other entry in the current catalog. The caller must name the old
     * Workflow; this method never infers replacement from page bindings or
     * display names because one page may legitimately expose several
     * Workflows.
     */
    @Transactional
    public AgentConfigVersionView replaceWorkflowToolInDraft(
            String agentId,
            String replacedWorkflowId,
            WorkflowToolRequest requestedTool) {
        requireLockedAgent(agentId);
        if (!StringUtils.hasText(replacedWorkflowId)) {
            throw new IllegalArgumentException(
                    "replaceWorkflowId is required for Workflow Tool replacement");
        }
        if (requestedTool == null || !StringUtils.hasText(requestedTool.workflowId())) {
            throw new IllegalArgumentException(
                    "workflowId is required for Agent Workflow Tool");
        }
        String oldWorkflowId = replacedWorkflowId.trim();
        String newWorkflowId = requestedTool.workflowId().trim();
        if (oldWorkflowId.equals(newWorkflowId)) {
            throw new IllegalArgumentException(
                    "replaceWorkflowId must identify a different Workflow");
        }

        requireActivePublishedWorkflow(newWorkflowId);

        Optional<RuntimeAgentConfigVersionEntity> displayConfig = resolveDisplayConfig(agentId);
        if (displayConfig.isEmpty()) {
            throw new IllegalArgumentException(
                    "replaceWorkflowId is not attached to the Agent tool catalog: " + oldWorkflowId);
        }
        List<WorkflowToolRequest> tools = new ArrayList<>();
        boolean replaced = false;
        for (WorkflowToolView current : listTools(agentId, displayConfig.get().getId())) {
            if (oldWorkflowId.equals(current.workflowId())) {
                WorkflowToolRequest replacement = requestedTool.priority() == null
                        ? new WorkflowToolRequest(
                                newWorkflowId,
                                requestedTool.toolName(),
                                requestedTool.descriptionOverride(),
                                requestedTool.inputSchemaOverrideJson(),
                                requestedTool.outputSchemaOverrideJson(),
                                requestedTool.riskLevel(),
                                requestedTool.permissionKey(),
                                requestedTool.readOnly(),
                                requestedTool.enabled(),
                                current.priority())
                        : requestedTool;
                tools.add(replacement);
                replaced = true;
            } else {
                tools.add(toRequest(current));
            }
        }
        if (!replaced) {
            throw new IllegalArgumentException(
                    "replaceWorkflowId is not attached to the Agent tool catalog: " + oldWorkflowId);
        }
        if (tools.stream().filter(tool -> newWorkflowId.equals(tool.workflowId())).count() > 1) {
            throw new IllegalArgumentException(
                    "replacement Workflow is already attached to the Agent tool catalog: " + newWorkflowId);
        }
        return saveDraft(agentId, new AgentConfigDraftRequest(
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, List.copyOf(tools)));
    }

    @Transactional
    public void deleteAllForAgent(String agentId) {
        if (!StringUtils.hasText(agentId)) {
            return;
        }
        String normalizedAgentId = agentId.trim();
        agentMapper.lockById(normalizedAgentId);
        toolMapper.delete(Wrappers.<RuntimeAgentWorkflowToolEntity>lambdaQuery()
                .eq(RuntimeAgentWorkflowToolEntity::getAgentId, normalizedAgentId));
        skillBindingMapper.delete(Wrappers.<RuntimeAgentSkillBindingEntity>lambdaQuery()
                .eq(RuntimeAgentSkillBindingEntity::getAgentId, normalizedAgentId));
        remoteAgentBindingMapper.delete(Wrappers.<RuntimeAgentRemoteBindingEntity>lambdaQuery()
                .eq(RuntimeAgentRemoteBindingEntity::getAgentId, normalizedAgentId));
        configMapper.delete(Wrappers.<RuntimeAgentConfigVersionEntity>lambdaQuery()
                .eq(RuntimeAgentConfigVersionEntity::getAgentId, normalizedAgentId));
    }

    private void replaceTools(String agentId,
                              RuntimeAgentConfigVersionEntity draft,
                              List<WorkflowToolRequest> requests) {
        if (!"DRAFT".equalsIgnoreCase(draft.getStatus())) {
            throw new IllegalArgumentException("published Agent tool catalog is immutable");
        }
        toolMapper.delete(Wrappers.<RuntimeAgentWorkflowToolEntity>lambdaQuery()
                .eq(RuntimeAgentWorkflowToolEntity::getAgentConfigVersionId, draft.getId()));
        if (requests == null || requests.isEmpty()) {
            return;
        }
        List<String> requestedIds = new ArrayList<>();
        for (WorkflowToolRequest request : requests) {
            if (request == null || !StringUtils.hasText(request.workflowId())) {
                throw new IllegalArgumentException("workflowId is required for Agent Workflow Tool");
            }
            requestedIds.add(request.workflowId().trim());
        }
        Map<String, Entry> catalog = workflowCatalog.current(requestedIds);
        Map<String, Boolean> workflowIds = new LinkedHashMap<>();
        Map<String, Boolean> toolNames = new LinkedHashMap<>();
        int index = 0;
        for (WorkflowToolRequest request : requests) {
            String workflowId = request.workflowId().trim();
            Entry entry = catalog.get(workflowId);
            if (entry == null || !entry.activeWorkflow()) {
                throw new IllegalArgumentException("Agent Workflow Tool requires an ACTIVE workflow: " + workflowId);
            }
            if (entry.version() == null) {
                throw new IllegalArgumentException("Agent Workflow Tool requires a published workflow version: " + workflowId);
            }
            var workflow = entry.workflow();
            String toolName = firstText(request.toolName(), workflow.keySlug());
            requireToolName(toolName);
            if (workflowIds.putIfAbsent(workflowId, true) != null) {
                throw new IllegalArgumentException("duplicate workflow in Agent tool catalog: " + workflowId);
            }
            if (toolNames.putIfAbsent(toolName, true) != null) {
                throw new IllegalArgumentException("duplicate toolName in Agent tool catalog: " + toolName);
            }
            insertWorkflowTool(agentId, draft.getId(), request, entry, index);
            index++;
        }
    }

    private void insertWorkflowTool(String agentId, Long configVersionId,
                                    WorkflowToolRequest request, Entry entry, int priority) {
        String workflowId = request.workflowId().trim();
        var workflow = entry.workflow();
        String toolName = firstText(request.toolName(), workflow.keySlug());
        RuntimeAgentWorkflowToolEntity entity = new RuntimeAgentWorkflowToolEntity();
        entity.setAgentId(agentId);
        entity.setAgentConfigVersionId(configVersionId);
        entity.setWorkflowId(workflowId);
        entity.setWorkflowVersionId(entry.version().id());
        entity.setToolName(toolName);
        entity.setDescriptionOverride(trimToNull(request.descriptionOverride()));
        entity.setInputSchemaOverrideJson(trimToNull(request.inputSchemaOverrideJson()));
        entity.setOutputSchemaOverrideJson(trimToNull(request.outputSchemaOverrideJson()));
        entity.setRiskLevel(firstText(request.riskLevel(), Boolean.FALSE.equals(request.readOnly()) ? "WRITE" : "READ"));
        entity.setPermissionKey(firstText(request.permissionKey(), "workflow:" + workflow.keySlug()));
        entity.setReadOnly(request.readOnly() == null ? !"WRITE".equalsIgnoreCase(entity.getRiskLevel()) : request.readOnly());
        entity.setEnabled(request.enabled() == null || request.enabled());
        entity.setPriority(priority);
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        toolMapper.insert(entity);
    }

    private void replaceSkills(String agentId,
                               RuntimeAgentConfigVersionEntity draft,
                               List<SkillBindingRequest> requests) {
        if (!"DRAFT".equalsIgnoreCase(draft.getStatus())) {
            throw new IllegalArgumentException("published Agent Skill bindings are immutable");
        }
        skillBindingMapper.delete(Wrappers.<RuntimeAgentSkillBindingEntity>lambdaQuery()
                .eq(RuntimeAgentSkillBindingEntity::getAgentConfigVersionId, draft.getId()));
        if (requests == null || requests.isEmpty()) {
            return;
        }

        Map<String, Boolean> identities = new LinkedHashMap<>();
        Map<Long, Boolean> versionIds = new LinkedHashMap<>();
        int index = 0;
        for (SkillBindingRequest request : requests) {
            if (request == null || request.skillId() == null || request.skillVersionId() == null) {
                throw new IllegalArgumentException("skillId and skillVersionId are required for Agent Skill binding");
            }
            if (!"PUBLISHED".equalsIgnoreCase(request.catalogStatus())) {
                throw new IllegalArgumentException("Agent Skill binding requires a PUBLISHED catalog version");
            }
            String publisher = requiredText(request.publisher(), 64, "Skill publisher").toLowerCase(java.util.Locale.ROOT);
            String standardName = requiredText(request.name(), 64, "Skill name");
            String version = requiredText(request.version(), 64, "Skill version");
            String visibility = upperOrDefault(request.visibility(), "");
            if (!SKILL_VISIBILITIES.contains(visibility)) {
                throw new IllegalArgumentException(
                        "Skill visibility must be PRIVATE, PROJECT, SHARED, or PUBLIC");
            }
            String skillProjectCode = trimToNull(request.projectCode());
            if ("PROJECT".equals(visibility) && !StringUtils.hasText(skillProjectCode)) {
                throw new IllegalArgumentException("Project-scoped Agent Skill requires projectCode");
            }
            if (!"PROJECT".equals(visibility)) skillProjectCode = null;
            String sourceSha256 = requiredSha256(request.sourceSha256(), "Skill sourceSha256");
            String contentTreeSha256 = requiredSha256(request.contentTreeSha256(), "Skill contentTreeSha256");
            String sourceRoot = normalizeSourceRoot(request.sourceRoot(), standardName);
            validateSkillManifest(request.packageManifestJson(), standardName, sourceRoot,
                    sourceSha256, contentTreeSha256);
            validateOptionalJsonObject(request.riskReportJson(), "Skill riskReportJson");

            String identity = publisher + "/" + standardName;
            if (identities.putIfAbsent(identity, true) != null) {
                throw new IllegalArgumentException("duplicate Skill in Agent config: " + identity);
            }
            if (versionIds.putIfAbsent(request.skillVersionId(), true) != null) {
                throw new IllegalArgumentException("duplicate Skill version in Agent config: " + request.skillVersionId());
            }

            String activationMode = upperOrDefault(request.activationMode(), "MODEL_SELECTED");
            if (!SKILL_ACTIVATION_MODES.contains(activationMode)) {
                throw new IllegalArgumentException(
                        "Skill activationMode must be MODEL_SELECTED, ALWAYS, or EXPLICIT");
            }
            String scriptPolicy = upperOrDefault(request.scriptPolicy(), "DENY");
            if (!SKILL_SCRIPT_POLICIES.contains(scriptPolicy)) {
                throw new IllegalArgumentException("Skill scriptPolicy must be DENY or SANDBOX_REVIEWED");
            }

            RuntimeAgentSkillBindingEntity entity = new RuntimeAgentSkillBindingEntity();
            entity.setAgentId(agentId);
            entity.setAgentConfigVersionId(draft.getId());
            entity.setSkillId(request.skillId());
            entity.setSkillVersionId(request.skillVersionId());
            entity.setPublisher(publisher);
            entity.setStandardName(standardName);
            entity.setDisplayName(trimToNull(request.displayName()));
            entity.setVisibility(visibility);
            entity.setProjectCode(skillProjectCode);
            entity.setVersion(version);
            entity.setSourceSha256(sourceSha256);
            entity.setContentTreeSha256(contentTreeSha256);
            entity.setSourceRoot(sourceRoot);
            entity.setPackageManifestJson(request.packageManifestJson());
            entity.setRiskReportJson(trimToNull(request.riskReportJson()));
            entity.setHasScripts(Boolean.TRUE.equals(request.hasScripts()));
            entity.setActivationMode(activationMode);
            entity.setScriptPolicy(scriptPolicy);
            entity.setRequired(Boolean.TRUE.equals(request.required()));
            entity.setEnabled(request.enabled() == null || request.enabled());
            entity.setPriority(index);
            LocalDateTime now = LocalDateTime.now();
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            skillBindingMapper.insert(entity);
            index++;
        }
    }

    private void replaceRemoteAgents(
            String agentId,
            RuntimeAgentConfigVersionEntity draft,
            List<RemoteAgentBindingRequest> requests) {
        if (!"DRAFT".equalsIgnoreCase(draft.getStatus())) {
            throw new IllegalArgumentException("published Agent A2A bindings are immutable");
        }
        remoteAgentBindingMapper.delete(
                Wrappers.<RuntimeAgentRemoteBindingEntity>lambdaQuery()
                        .eq(RuntimeAgentRemoteBindingEntity::getAgentConfigVersionId,
                                draft.getId()));
        if (requests == null || requests.isEmpty()) return;
        Map<Long, Boolean> revisions = new LinkedHashMap<>();
        Map<String, Boolean> toolNames = new LinkedHashMap<>();
        int index = 0;
        for (RemoteAgentBindingRequest request : requests) {
            if (request == null || request.principalId() == null || request.principalId() <= 0
                    || request.remoteAgentId() == null || request.remoteAgentId() <= 0
                    || request.remoteAgentRevisionId() == null
                    || request.remoteAgentRevisionId() <= 0) {
                throw new IllegalArgumentException(
                        "principalId, remoteAgentId, and remoteAgentRevisionId are required for A2A binding");
            }
            String remoteAgentKey = requiredText(
                    request.remoteAgentKey(), 128, "A2A remoteAgentKey");
            String toolName = requiredText(request.toolName(), 128, "A2A toolName");
            requireToolName(toolName);
            String description = requiredText(request.description(), 2000, "A2A description");
            List<String> allowedSkills = normalizedStringList(
                    request.allowedSkillIds(), 200, 64, "A2A allowedSkillIds");
            List<String> inputModes = normalizedStringList(
                    request.inputModes(), 128, 32, "A2A inputModes");
            List<String> outputModes = normalizedStringList(
                    request.outputModes(), 128, 32, "A2A outputModes");
            if (allowedSkills.isEmpty() || inputModes.isEmpty() || outputModes.isEmpty()) {
                throw new IllegalArgumentException(
                        "A2A binding requires skill, input-mode, and output-mode allowlists");
            }
            if (revisions.putIfAbsent(request.remoteAgentRevisionId(), true) != null) {
                throw new IllegalArgumentException("duplicate remote Agent revision in Agent config");
            }
            if (toolNames.putIfAbsent(toolName, true) != null) {
                throw new IllegalArgumentException("duplicate A2A toolName in Agent config: " + toolName);
            }
            String risk = upperOrDefault(request.riskLevel(), "READ");
            if (!A2A_RISK_LEVELS.contains(risk)) {
                throw new IllegalArgumentException("A2A riskLevel must be READ, WRITE, or IRREVERSIBLE");
            }
            String permissionKey = requiredText(
                    request.permissionKey(), 160, "A2A permissionKey");
            long timeoutMs = request.timeoutMs() == null ? 60_000L : request.timeoutMs();
            if (timeoutMs < 1_000L || timeoutMs > 600_000L) {
                throw new IllegalArgumentException("A2A timeoutMs must be between 1000 and 600000");
            }
            RuntimeAgentRemoteBindingEntity entity = new RuntimeAgentRemoteBindingEntity();
            entity.setAgentId(agentId);
            entity.setAgentConfigVersionId(draft.getId());
            entity.setPrincipalId(request.principalId());
            entity.setRemoteAgentId(request.remoteAgentId());
            entity.setRemoteAgentRevisionId(request.remoteAgentRevisionId());
            entity.setRemoteAgentKeySnapshot(remoteAgentKey);
            entity.setToolName(toolName);
            entity.setDescriptionSnapshot(description);
            entity.setAllowedSkillIdsJson(jsonStringList(allowedSkills));
            entity.setInputModesJson(jsonStringList(inputModes));
            entity.setOutputModesJson(jsonStringList(outputModes));
            entity.setRiskLevel(risk);
            entity.setPermissionKey(permissionKey);
            entity.setTimeoutMs(timeoutMs);
            entity.setEnabled(request.enabled() == null || request.enabled());
            entity.setPriority(index);
            LocalDateTime now = LocalDateTime.now();
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            remoteAgentBindingMapper.insert(entity);
            index++;
        }
    }

    private void copyActiveToolsIfNeeded(String agentId, Long draftId, List<WorkflowToolRequest> requestedTools) {
        if (requestedTools != null || draftId == null) {
            return;
        }
        Optional<RuntimeAgentConfigVersionEntity> active = resolveActive(agentId);
        if (active.isEmpty()) {
            return;
        }
        for (RuntimeAgentWorkflowToolEntity source : snapshotTools(agentId, active.get().getId())) {
            RuntimeAgentWorkflowToolEntity copy = new RuntimeAgentWorkflowToolEntity();
            copy.setAgentId(agentId);
            copy.setAgentConfigVersionId(draftId);
            copy.setWorkflowId(source.getWorkflowId());
            copy.setWorkflowVersionId(source.getWorkflowVersionId());
            copy.setToolName(source.getToolName());
            copy.setDescriptionOverride(source.getDescriptionOverride());
            copy.setInputSchemaOverrideJson(source.getInputSchemaOverrideJson());
            copy.setOutputSchemaOverrideJson(source.getOutputSchemaOverrideJson());
            copy.setRiskLevel(source.getRiskLevel());
            copy.setPermissionKey(source.getPermissionKey());
            copy.setReadOnly(source.getReadOnly());
            copy.setEnabled(source.getEnabled());
            copy.setPriority(source.getPriority());
            LocalDateTime now = LocalDateTime.now();
            copy.setCreatedAt(now);
            copy.setUpdatedAt(now);
            toolMapper.insert(copy);
        }
    }

    private void copyActiveSkillsIfNeeded(String agentId,
                                          Long draftId,
                                          List<SkillBindingRequest> requestedSkills) {
        if (requestedSkills != null || draftId == null) {
            return;
        }
        Optional<RuntimeAgentConfigVersionEntity> active = resolveActive(agentId);
        if (active.isEmpty()) {
            return;
        }
        List<RuntimeAgentSkillBindingEntity> activeSnapshot = skillBindingMapper.selectList(
                Wrappers.<RuntimeAgentSkillBindingEntity>lambdaQuery()
                        .eq(RuntimeAgentSkillBindingEntity::getAgentId, agentId)
                        .eq(RuntimeAgentSkillBindingEntity::getAgentConfigVersionId, active.get().getId())
                        .orderByAsc(RuntimeAgentSkillBindingEntity::getPriority)
                        .orderByAsc(RuntimeAgentSkillBindingEntity::getId));
        for (RuntimeAgentSkillBindingEntity source : activeSnapshot) {
            RuntimeAgentSkillBindingEntity copy = new RuntimeAgentSkillBindingEntity();
            copy.setAgentId(agentId);
            copy.setAgentConfigVersionId(draftId);
            copy.setSkillId(source.getSkillId());
            copy.setSkillVersionId(source.getSkillVersionId());
            copy.setPublisher(source.getPublisher());
            copy.setStandardName(source.getStandardName());
            copy.setDisplayName(source.getDisplayName());
            copy.setVisibility(source.getVisibility());
            copy.setProjectCode(source.getProjectCode());
            copy.setVersion(source.getVersion());
            copy.setSourceSha256(source.getSourceSha256());
            copy.setContentTreeSha256(source.getContentTreeSha256());
            copy.setSourceRoot(source.getSourceRoot());
            copy.setPackageManifestJson(source.getPackageManifestJson());
            copy.setRiskReportJson(source.getRiskReportJson());
            copy.setHasScripts(source.getHasScripts());
            copy.setActivationMode(source.getActivationMode());
            copy.setScriptPolicy(source.getScriptPolicy());
            copy.setRequired(source.getRequired());
            copy.setEnabled(source.getEnabled());
            copy.setPriority(source.getPriority());
            LocalDateTime now = LocalDateTime.now();
            copy.setCreatedAt(now);
            copy.setUpdatedAt(now);
            skillBindingMapper.insert(copy);
        }
    }

    private void copyActiveRemoteAgentsIfNeeded(
            String agentId,
            Long draftId,
            List<RemoteAgentBindingRequest> requestedBindings) {
        if (requestedBindings != null || draftId == null) return;
        Optional<RuntimeAgentConfigVersionEntity> active = resolveActive(agentId);
        if (active.isEmpty()) return;
        for (RuntimeAgentRemoteBindingEntity source : remoteAgentBindingMapper.selectList(
                Wrappers.<RuntimeAgentRemoteBindingEntity>lambdaQuery()
                        .eq(RuntimeAgentRemoteBindingEntity::getAgentId, agentId)
                        .eq(RuntimeAgentRemoteBindingEntity::getAgentConfigVersionId, active.get().getId())
                        .orderByAsc(RuntimeAgentRemoteBindingEntity::getPriority)
                        .orderByAsc(RuntimeAgentRemoteBindingEntity::getId))) {
            RuntimeAgentRemoteBindingEntity copy = new RuntimeAgentRemoteBindingEntity();
            copy.setAgentId(agentId);
            copy.setAgentConfigVersionId(draftId);
            copy.setPrincipalId(source.getPrincipalId());
            copy.setRemoteAgentId(source.getRemoteAgentId());
            copy.setRemoteAgentRevisionId(source.getRemoteAgentRevisionId());
            copy.setRemoteAgentKeySnapshot(source.getRemoteAgentKeySnapshot());
            copy.setToolName(source.getToolName());
            copy.setDescriptionSnapshot(source.getDescriptionSnapshot());
            copy.setAllowedSkillIdsJson(source.getAllowedSkillIdsJson());
            copy.setInputModesJson(source.getInputModesJson());
            copy.setOutputModesJson(source.getOutputModesJson());
            copy.setRiskLevel(source.getRiskLevel());
            copy.setPermissionKey(source.getPermissionKey());
            copy.setTimeoutMs(source.getTimeoutMs());
            copy.setPriority(source.getPriority());
            copy.setEnabled(source.getEnabled());
            LocalDateTime now = LocalDateTime.now();
            copy.setCreatedAt(now);
            copy.setUpdatedAt(now);
            remoteAgentBindingMapper.insert(copy);
        }
    }

    private List<RuntimeAgentWorkflowToolEntity> snapshotTools(String agentId, Long configVersionId) {
        return toolMapper.selectList(Wrappers.<RuntimeAgentWorkflowToolEntity>lambdaQuery()
                .eq(RuntimeAgentWorkflowToolEntity::getAgentId, agentId)
                .eq(RuntimeAgentWorkflowToolEntity::getAgentConfigVersionId, configVersionId)
                .orderByAsc(RuntimeAgentWorkflowToolEntity::getPriority)
                .orderByAsc(RuntimeAgentWorkflowToolEntity::getId));
    }

    private List<WorkflowToolView> toToolViews(List<RuntimeAgentWorkflowToolEntity> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        Map<Reference, Entry> catalog = workflowCatalog.pinned(rows.stream()
                .map(row -> new Reference(row.getWorkflowId(), row.getWorkflowVersionId())).toList());
        List<WorkflowToolView> views = new ArrayList<>();
        for (RuntimeAgentWorkflowToolEntity row : rows) {
            Entry entry = catalog.get(new Reference(row.getWorkflowId(), row.getWorkflowVersionId()));
            var workflow = entry.workflow();
            var version = entry.version();
            views.add(new WorkflowToolView(
                    row.getId(),
                    row.getAgentId(),
                    row.getAgentConfigVersionId(),
                    row.getWorkflowId(),
                    workflow == null ? null : workflow.keySlug(),
                    workflow == null ? null : workflow.name(),
                    version == null ? null : version.version(),
                    version == null ? null : version.id(),
                    row.getToolName(),
                    row.getDescriptionOverride(),
                    firstText(row.getDescriptionOverride(), workflow == null ? null : workflow.description()),
                    row.getInputSchemaOverrideJson(),
                    firstText(
                            row.getInputSchemaOverrideJson(),
                            version == null ? null : version.inputSchemaJson()),
                    row.getOutputSchemaOverrideJson(),
                    firstText(
                            row.getOutputSchemaOverrideJson(),
                            version == null ? null : version.outputSchemaJson()),
                    row.getRiskLevel(),
                    row.getPermissionKey(),
                    row.getReadOnly(),
                    row.getEnabled(),
                    row.getPriority(),
                    row.getCreatedAt(),
                    row.getUpdatedAt()));
        }
        return List.copyOf(views);
    }

    private WorkflowToolRequest toRequest(WorkflowToolView tool) {
        return new WorkflowToolRequest(
                tool.workflowId(),
                tool.toolName(),
                tool.descriptionOverride(),
                tool.inputSchemaOverrideJson(),
                tool.outputSchemaOverrideJson(),
                tool.riskLevel(),
                tool.permissionKey(),
                tool.readOnly(),
                tool.enabled(),
                tool.priority());
    }

    private SkillBindingRequest toRequest(SkillBindingView skill) {
        return new SkillBindingRequest(
                skill.skillId(),
                skill.skillVersionId(),
                skill.publisher(),
                skill.name(),
                skill.displayName(),
                skill.version(),
                "PUBLISHED",
                skill.sourceSha256(),
                skill.contentTreeSha256(),
                skill.sourceRoot(),
                skill.packageManifestJson(),
                skill.riskReportJson(),
                skill.hasScripts(),
                skill.activationMode(),
                skill.scriptPolicy(),
                skill.required(),
                skill.enabled(),
                skill.priority(),
                skill.visibility(),
                skill.projectCode());
    }

    private RemoteAgentBindingRequest toRequest(RemoteAgentBindingView binding) {
        return new RemoteAgentBindingRequest(
                binding.principalId(), binding.remoteAgentId(), binding.remoteAgentRevisionId(),
                binding.remoteAgentKey(), binding.toolName(), binding.description(),
                binding.allowedSkillIds(), binding.inputModes(), binding.outputModes(),
                binding.riskLevel(), binding.permissionKey(), binding.timeoutMs(),
                binding.enabled(), binding.priority());
    }

    private RemoteAgentBindingView toView(RuntimeAgentRemoteBindingEntity binding) {
        return new RemoteAgentBindingView(
                binding.getId(), binding.getAgentId(), binding.getAgentConfigVersionId(),
                binding.getPrincipalId(), binding.getRemoteAgentId(),
                binding.getRemoteAgentRevisionId(), binding.getRemoteAgentKeySnapshot(),
                binding.getToolName(), binding.getDescriptionSnapshot(),
                parseStringList(binding.getAllowedSkillIdsJson(), "allowedSkillIdsJson"),
                parseStringList(binding.getInputModesJson(), "inputModesJson"),
                parseStringList(binding.getOutputModesJson(), "outputModesJson"),
                binding.getRiskLevel(), binding.getPermissionKey(), binding.getTimeoutMs(),
                binding.getEnabled(), binding.getPriority(), binding.getCreatedAt(),
                binding.getUpdatedAt());
    }

    private SkillBindingView toView(RuntimeAgentSkillBindingEntity skill) {
        return new SkillBindingView(
                skill.getId(),
                skill.getAgentId(),
                skill.getAgentConfigVersionId(),
                skill.getSkillId(),
                skill.getSkillVersionId(),
                skill.getPublisher(),
                skill.getStandardName(),
                skill.getDisplayName(),
                skill.getVersion(),
                skill.getSourceSha256(),
                skill.getContentTreeSha256(),
                skill.getSourceRoot(),
                skill.getPackageManifestJson(),
                skill.getRiskReportJson(),
                skill.getHasScripts(),
                skill.getActivationMode(),
                skill.getScriptPolicy(),
                skill.getRequired(),
                skill.getEnabled(),
                skill.getPriority(),
                skill.getCreatedAt(),
                skill.getUpdatedAt(),
                skill.getVisibility(),
                skill.getProjectCode());
    }

    private AgentConfigVersionView toView(RuntimeAgentConfigVersionEntity entity) {
        return new AgentConfigVersionView(
                entity.getId(),
                entity.getAgentId(),
                entity.getVersionNo(),
                entity.getStatus(),
                entity.getRuntimeType(),
                entity.getSystemPrompt(),
                entity.getModelInstanceId(),
                entity.getMaxPlanSteps(),
                entity.getMaxWorkflowCalls(),
                entity.getMaxReplans(),
                entity.getTotalTimeoutMs(),
                entity.getWorkflowTimeoutMs(),
                entity.getPageBridgeTimeoutMs(),
                entity.getParallelReadOnly(),
                entity.getPolicyProfile(),
                entity.getToolCatalogMode(),
                entity.getConfigJson(),
                entity.getPublishedBy(),
                entity.getPublishedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                listTools(entity.getAgentId(), entity.getId()),
                listSkills(entity.getAgentId(), entity.getId()),
                listRemoteAgentBindings(entity.getAgentId(), entity.getId()));
    }

    private RuntimeAgentEntity requireAgent(String agentId) {
        if (!StringUtils.hasText(agentId)) {
            throw new IllegalArgumentException("agentId is required");
        }
        RuntimeAgentEntity agent = agentMapper.selectById(agentId.trim());
        if (agent == null) {
            throw new IllegalArgumentException("agent not found: " + agentId);
        }
        return agent;
    }

    private RuntimeAgentEntity requireLockedAgent(String agentId) {
        if (!StringUtils.hasText(agentId)) throw new IllegalArgumentException("agentId is required");
        agentMapper.lockById(agentId.trim());
        return requireAgent(agentId);
    }

    private RuntimeAgentConfigVersionEntity requireVersion(String agentId, Long configVersionId) {
        if (configVersionId == null) {
            throw new IllegalArgumentException("configVersionId is required");
        }
        RuntimeAgentConfigVersionEntity version = configMapper.selectById(configVersionId);
        if (version == null || !agentId.equals(version.getAgentId())) {
            throw new IllegalArgumentException("Agent config version not found: " + configVersionId);
        }
        return version;
    }

    private void validatePublishable(RuntimeAgentConfigVersionEntity target) {
        if (!"AGENTSCOPE".equalsIgnoreCase(target.getRuntimeType())) {
            throw new IllegalArgumentException("Supervisor runtimeType must be AGENTSCOPE");
        }
        if (!StringUtils.hasText(target.getSystemPrompt())) {
            throw new IllegalArgumentException("Agent systemPrompt is required before publish");
        }
        if (!StringUtils.hasText(target.getModelInstanceId())) {
            throw new IllegalArgumentException("Agent modelInstanceId is required before publish");
        }
        if (!"ALLOW_LIST".equalsIgnoreCase(target.getToolCatalogMode())) {
            throw new IllegalArgumentException("Agent toolCatalogMode must be ALLOW_LIST");
        }
        validateConfigJson(target.getConfigJson());
    }

    private void validateConfigJson(String configJson) {
        if (!StringUtils.hasText(configJson)) return;
        try {
            JsonNode root = objectMapper.readTree(configJson);
            if (!root.isObject()) {
                throw new IllegalArgumentException("Agent configJson must be a JSON object");
            }
            validateManagedExecutorConfig(root.get("managedExecutor"));
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Agent configJson must be valid JSON", ex);
        }
    }

    private void validateManagedExecutorConfig(JsonNode config) {
        if (config == null || config.isMissingNode()) return;
        if (!config.isObject()) {
            throw new IllegalArgumentException("Agent managedExecutor config must be a JSON object");
        }
        config.fieldNames().forEachRemaining(field -> {
            if (!MANAGED_EXECUTOR_CONFIG_FIELDS.contains(field)) {
                throw new IllegalArgumentException("Agent managedExecutor config contains unsupported field: " + field);
            }
        });
        if (!config.path("enabled").isBoolean()) {
            throw new IllegalArgumentException("Agent managedExecutor.enabled must be boolean");
        }
        if (!config.path("enabled").asBoolean()) return;
        if (config.has("autoRouteEnabled") && !config.path("autoRouteEnabled").isBoolean()) {
            throw new IllegalArgumentException("Agent managedExecutor.autoRouteEnabled must be boolean");
        }
        JsonNode tools = config.path("allowedTools");
        if (!tools.isArray() || tools.isEmpty()) {
            throw new IllegalArgumentException("Agent managedExecutor.allowedTools must be a non-empty array");
        }
        Set<String> names = new java.util.LinkedHashSet<>();
        for (JsonNode tool : tools) {
            String name = tool.isTextual() ? tool.asText() : null;
            if (!MANAGED_EXECUTOR_TOOLS.contains(name) || !names.add(name)) {
                throw new IllegalArgumentException("Agent managedExecutor.allowedTools contains an invalid tool");
            }
        }
        String profile = requiredManagedIdentifier(config, "sandboxProfile", 128);
        if (!Set.of("ANALYZE_READONLY", "WORKSPACE_PATCH").contains(profile)) {
            throw new IllegalArgumentException("Agent managedExecutor.sandboxProfile is invalid");
        }
        requiredManagedIdentifier(config, "acceptanceProfile", 128);
        if (config.has("modelRef") && !config.path("modelRef").isNull()) {
            requiredManagedIdentifier(config, "modelRef", 128);
        }
        requiredManagedInteger(config, "maxWallTimeSeconds", 60, 14_400);
        requiredManagedInteger(config, "approvalTimeoutSeconds", 30, 1_800);
        optionalManagedInteger(config, "priority", -100, 100);
        if (config.has("maxDelegationsPerRun")) {
            requiredManagedInteger(config, "maxDelegationsPerRun", 1, 1);
        }
    }

    private String requiredManagedIdentifier(JsonNode config, String field, int maximum) {
        JsonNode value = config.path(field);
        if (!value.isTextual()) {
            throw new IllegalArgumentException("Agent managedExecutor." + field + " is required");
        }
        String text = value.asText().trim();
        if (text.isEmpty() || text.length() > maximum || !MANAGED_EXECUTOR_IDENTIFIER.matcher(text).matches()) {
            throw new IllegalArgumentException("Agent managedExecutor." + field + " is invalid");
        }
        return text;
    }

    private void requiredManagedInteger(JsonNode config, String field, int minimum, int maximum) {
        JsonNode value = config.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt()
                || value.intValue() < minimum || value.intValue() > maximum) {
            throw new IllegalArgumentException("Agent managedExecutor." + field + " is invalid");
        }
    }

    private void optionalManagedInteger(JsonNode config, String field, int minimum, int maximum) {
        if (config.has(field)) requiredManagedInteger(config, field, minimum, maximum);
    }

    private Entry requireActivePublishedWorkflow(String workflowId) {
        Entry entry = workflowCatalog.current(List.of(workflowId)).get(workflowId);
        if (entry == null || !entry.activePublishedWorkflow()) {
            throw new IllegalArgumentException(
                    "Agent Workflow Tool requires an ACTIVE published workflow: " + workflowId);
        }
        return entry;
    }

    private void pinWorkflowVersions(RuntimeAgentConfigVersionEntity target) {
        List<RuntimeAgentWorkflowToolEntity> tools = toolMapper.selectList(
                Wrappers.<RuntimeAgentWorkflowToolEntity>lambdaQuery()
                        .eq(RuntimeAgentWorkflowToolEntity::getAgentId, target.getAgentId())
                        .eq(RuntimeAgentWorkflowToolEntity::getAgentConfigVersionId, target.getId())
                        .eq(RuntimeAgentWorkflowToolEntity::getEnabled, true));
        Map<String, Entry> catalog = workflowCatalog.current(tools.stream()
                .map(RuntimeAgentWorkflowToolEntity::getWorkflowId).toList());
        for (RuntimeAgentWorkflowToolEntity tool : tools) {
            Entry entry = catalog.get(tool.getWorkflowId());
            if (entry == null || !entry.activePublishedWorkflow() || !entry.version().executable()) {
                throw new IllegalArgumentException(
                        "Agent Workflow Tool requires an executable published Workflow version: " + tool.getWorkflowId());
            }
            tool.setWorkflowVersionId(entry.version().id());
            tool.setUpdatedAt(LocalDateTime.now());
            toolMapper.updateById(tool);
        }
    }

    private void validateSkillBindings(RuntimeAgentConfigVersionEntity target, String agentProjectCode) {
        List<RuntimeAgentSkillBindingEntity> bindings = skillBindingMapper.selectList(
                Wrappers.<RuntimeAgentSkillBindingEntity>lambdaQuery()
                        .eq(RuntimeAgentSkillBindingEntity::getAgentId, target.getAgentId())
                        .eq(RuntimeAgentSkillBindingEntity::getAgentConfigVersionId, target.getId()));
        for (RuntimeAgentSkillBindingEntity binding : bindings) {
            String visibility = upperOrDefault(binding.getVisibility(), "");
            if (!SKILL_VISIBILITIES.contains(visibility)
                    || ("PROJECT".equals(visibility) && !StringUtils.hasText(binding.getProjectCode()))) {
                throw new IllegalArgumentException(
                        "Agent Skill binding has an invalid scope: " + binding.getPublisher() + "/"
                                + binding.getStandardName());
            }
            if ("PROJECT".equals(visibility)
                    && !java.util.Objects.equals(trimToNull(binding.getProjectCode()), trimToNull(agentProjectCode))) {
                throw new IllegalArgumentException(
                        "Project-scoped Agent Skill no longer matches the Agent project: "
                                + binding.getPublisher() + "/" + binding.getStandardName());
            }
            if (Boolean.TRUE.equals(binding.getRequired()) && !Boolean.TRUE.equals(binding.getEnabled())) {
                throw new IllegalArgumentException(
                        "Required Agent Skill must be enabled: " + binding.getPublisher() + "/"
                                + binding.getStandardName());
            }
            requiredSha256(binding.getSourceSha256(), "Skill sourceSha256");
            requiredSha256(binding.getContentTreeSha256(), "Skill contentTreeSha256");
            validateSkillManifest(binding.getPackageManifestJson(), binding.getStandardName(),
                    binding.getSourceRoot(), binding.getSourceSha256(), binding.getContentTreeSha256());
            if (Boolean.TRUE.equals(binding.getHasScripts())
                    && !SKILL_SCRIPT_POLICIES.contains(upperOrDefault(binding.getScriptPolicy(), "DENY"))) {
                throw new IllegalArgumentException(
                        "Agent Skill with scripts has an invalid script policy: " + binding.getStandardName());
            }
        }
    }

    private void validateRemoteAgentBindings(RuntimeAgentConfigVersionEntity target) {
        List<RuntimeAgentRemoteBindingEntity> bindings = remoteAgentBindingMapper.selectList(
                Wrappers.<RuntimeAgentRemoteBindingEntity>lambdaQuery()
                        .eq(RuntimeAgentRemoteBindingEntity::getAgentId, target.getAgentId())
                        .eq(RuntimeAgentRemoteBindingEntity::getAgentConfigVersionId,
                                target.getId()));
        for (RuntimeAgentRemoteBindingEntity binding : bindings) {
            if (binding.getPrincipalId() == null || binding.getPrincipalId() <= 0
                    || binding.getRemoteAgentId() == null || binding.getRemoteAgentId() <= 0
                    || binding.getRemoteAgentRevisionId() == null
                    || binding.getRemoteAgentRevisionId() <= 0) {
                throw new IllegalArgumentException("A2A binding contains an invalid fixed reference");
            }
            requireToolName(binding.getToolName());
            requiredText(binding.getRemoteAgentKeySnapshot(), 128, "A2A remoteAgentKey");
            requiredText(binding.getDescriptionSnapshot(), 2000, "A2A description");
            requiredText(binding.getPermissionKey(), 160, "A2A permissionKey");
            if (!A2A_RISK_LEVELS.contains(upperOrDefault(binding.getRiskLevel(), ""))) {
                throw new IllegalArgumentException("A2A binding riskLevel is invalid");
            }
            if (binding.getTimeoutMs() == null || binding.getTimeoutMs() < 1_000L
                    || binding.getTimeoutMs() > 600_000L) {
                throw new IllegalArgumentException("A2A binding timeout is invalid");
            }
            if (parseStringList(binding.getAllowedSkillIdsJson(), "allowedSkillIdsJson").isEmpty()
                    || parseStringList(binding.getInputModesJson(), "inputModesJson").isEmpty()
                    || parseStringList(binding.getOutputModesJson(), "outputModesJson").isEmpty()) {
                throw new IllegalArgumentException("A2A binding snapshots must not be empty");
            }
        }
        validateUniqueToolNames(target);
    }

    private void validateUniqueToolNames(RuntimeAgentConfigVersionEntity target) {
        if (target == null || target.getId() == null) return;
        Set<String> names = new java.util.HashSet<>();
        for (RuntimeAgentWorkflowToolEntity tool : toolMapper.selectList(
                Wrappers.<RuntimeAgentWorkflowToolEntity>lambdaQuery()
                        .eq(RuntimeAgentWorkflowToolEntity::getAgentConfigVersionId,
                                target.getId()))) {
            if (!names.add(tool.getToolName())) {
                throw new IllegalArgumentException(
                        "duplicate Supervisor toolName in Agent config: " + tool.getToolName());
            }
        }
        for (RuntimeAgentRemoteBindingEntity binding : remoteAgentBindingMapper.selectList(
                Wrappers.<RuntimeAgentRemoteBindingEntity>lambdaQuery()
                        .eq(RuntimeAgentRemoteBindingEntity::getAgentConfigVersionId,
                                target.getId()))) {
            if (!names.add(binding.getToolName())) {
                throw new IllegalArgumentException(
                        "A2A and Workflow toolName collision in Agent config: "
                                + binding.getToolName());
            }
        }
    }

    private int nextVersionNo(String agentId) {
        RuntimeAgentConfigVersionEntity latest = configMapper.selectOne(
                Wrappers.<RuntimeAgentConfigVersionEntity>lambdaQuery()
                        .eq(RuntimeAgentConfigVersionEntity::getAgentId, agentId)
                        .orderByDesc(RuntimeAgentConfigVersionEntity::getVersionNo)
                        .last("LIMIT 1"));
        return latest == null || latest.getVersionNo() == null ? 1 : latest.getVersionNo() + 1;
    }

    private void copyConfiguration(RuntimeAgentConfigVersionEntity source,
                                   RuntimeAgentConfigVersionEntity target) {
        target.setRuntimeType(source.getRuntimeType());
        target.setSystemPrompt(source.getSystemPrompt());
        target.setModelInstanceId(source.getModelInstanceId());
        target.setMaxPlanSteps(source.getMaxPlanSteps());
        target.setMaxWorkflowCalls(source.getMaxWorkflowCalls());
        target.setMaxReplans(source.getMaxReplans());
        target.setTotalTimeoutMs(source.getTotalTimeoutMs());
        target.setWorkflowTimeoutMs(source.getWorkflowTimeoutMs());
        target.setPageBridgeTimeoutMs(source.getPageBridgeTimeoutMs());
        target.setParallelReadOnly(source.getParallelReadOnly());
        target.setPolicyProfile(source.getPolicyProfile());
        target.setToolCatalogMode(source.getToolCatalogMode());
        target.setConfigJson(source.getConfigJson());
    }

    private void apply(AgentConfigDraftRequest request, RuntimeAgentConfigVersionEntity target) {
        if (StringUtils.hasText(request.runtimeType())) target.setRuntimeType(request.runtimeType().trim());
        if (request.systemPrompt() != null) target.setSystemPrompt(request.systemPrompt());
        if (request.modelInstanceId() != null) target.setModelInstanceId(trimToNull(request.modelInstanceId()));
        if (request.maxPlanSteps() != null) target.setMaxPlanSteps(positive(request.maxPlanSteps(), "maxPlanSteps"));
        if (request.maxWorkflowCalls() != null) target.setMaxWorkflowCalls(positive(request.maxWorkflowCalls(), "maxWorkflowCalls"));
        if (request.maxReplans() != null) target.setMaxReplans(nonNegative(request.maxReplans(), "maxReplans"));
        if (request.totalTimeoutMs() != null) target.setTotalTimeoutMs(positive(request.totalTimeoutMs(), "totalTimeoutMs"));
        if (request.workflowTimeoutMs() != null) target.setWorkflowTimeoutMs(positive(request.workflowTimeoutMs(), "workflowTimeoutMs"));
        if (request.pageBridgeTimeoutMs() != null) target.setPageBridgeTimeoutMs(positive(request.pageBridgeTimeoutMs(), "pageBridgeTimeoutMs"));
        if (request.parallelReadOnly() != null) target.setParallelReadOnly(request.parallelReadOnly());
        if (StringUtils.hasText(request.policyProfile())) target.setPolicyProfile(request.policyProfile().trim());
        if (StringUtils.hasText(request.toolCatalogMode())) target.setToolCatalogMode(request.toolCatalogMode().trim());
        if (request.configJson() != null) target.setConfigJson(request.configJson());
    }

    private void applyDefaults(RuntimeAgentConfigVersionEntity target) {
        if (!StringUtils.hasText(target.getRuntimeType())) target.setRuntimeType("AGENTSCOPE");
        if (target.getMaxPlanSteps() == null) target.setMaxPlanSteps(6);
        if (target.getMaxWorkflowCalls() == null) target.setMaxWorkflowCalls(4);
        if (target.getMaxReplans() == null) target.setMaxReplans(2);
        if (target.getTotalTimeoutMs() == null) target.setTotalTimeoutMs(DEFAULT_TOTAL_TIMEOUT_MS);
        if (target.getWorkflowTimeoutMs() == null) target.setWorkflowTimeoutMs(DEFAULT_WORKFLOW_TIMEOUT_MS);
        if (target.getPageBridgeTimeoutMs() == null) target.setPageBridgeTimeoutMs(DEFAULT_PAGE_BRIDGE_TIMEOUT_MS);
        if (target.getParallelReadOnly() == null) target.setParallelReadOnly(true);
        if (!StringUtils.hasText(target.getPolicyProfile())) target.setPolicyProfile("DEV_ALLOW_ALL");
        if (!StringUtils.hasText(target.getToolCatalogMode())) target.setToolCatalogMode("ALLOW_LIST");
    }

    private AgentConfigDraftRequest emptyDraft() {
        return new AgentConfigDraftRequest(null, null, null, null, null, null, null, null, null,
                null, null, null, null, null);
    }

    private int positive(int value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be greater than zero");
        return value;
    }

    private int nonNegative(int value, String name) {
        if (value < 0) throw new IllegalArgumentException(name + " must not be negative");
        return value;
    }

    private void requireToolName(String toolName) {
        if (!StringUtils.hasText(toolName) || !TOOL_NAME.matcher(toolName.trim()).matches()) {
            throw new IllegalArgumentException("invalid Agent Workflow Tool name: " + toolName);
        }
    }

    private String requiredText(String value, int maxLength, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " must not exceed " + maxLength + " characters");
        }
        return normalized;
    }

    private List<String> normalizedStringList(
            List<String> values, int maxLength, int maxItems, String field) {
        if (values == null) return List.of();
        if (values.size() > maxItems) {
            throw new IllegalArgumentException(field + " contains too many values");
        }
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        for (String value : values) {
            result.add(requiredText(value, maxLength, field));
        }
        return List.copyOf(result);
    }

    private String jsonStringList(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception failure) {
            throw new IllegalArgumentException("A2A binding snapshot could not be serialized", failure);
        }
    }

    private List<String> parseStringList(String json, String field) {
        if (!StringUtils.hasText(json)) return List.of();
        try {
            var type = objectMapper.getTypeFactory().constructCollectionType(List.class, String.class);
            List<String> values = objectMapper.readValue(json, type);
            return normalizedStringList(values, 200, 64, "A2A " + field);
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException("A2A " + field + " is invalid JSON", failure);
        }
    }

    private String requiredSha256(String value, String field) {
        String normalized = requiredText(value, 64, field).toLowerCase(java.util.Locale.ROOT);
        if (!SHA256.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256 digest");
        }
        return normalized;
    }

    private String normalizeSourceRoot(String value, String standardName) {
        String root = value == null ? "" : value.trim().replace('\\', '/');
        if (root.isEmpty()) {
            return root;
        }
        if (root.startsWith("/") || root.contains(":") || root.contains("../")
                || root.startsWith("./") || root.contains("//")) {
            throw new IllegalArgumentException("Skill sourceRoot must be a safe relative directory");
        }
        if (!root.endsWith("/")) {
            root = root + "/";
        }
        String withoutSlash = root.substring(0, root.length() - 1);
        String leaf = withoutSlash.substring(withoutSlash.lastIndexOf('/') + 1);
        if (!standardName.equals(leaf)) {
            throw new IllegalArgumentException("Skill sourceRoot must end with the standard Skill name");
        }
        return root;
    }

    private void validateSkillManifest(String manifestJson,
                                       String standardName,
                                       String sourceRoot,
                                       String sourceSha256,
                                       String contentTreeSha256) {
        String value = requiredText(manifestJson, 2_000_000, "Skill packageManifestJson");
        try {
            com.fasterxml.jackson.databind.JsonNode manifest = objectMapper.readTree(value);
            if (manifest == null || !manifest.isObject()) {
                throw new IllegalArgumentException("Skill packageManifestJson must be a JSON object");
            }
            requireManifestValue(manifest, "name", standardName);
            requireManifestValue(manifest, "sourceRoot", sourceRoot == null ? "" : sourceRoot);
            requireManifestValue(manifest, "sourceSha256", sourceSha256);
            requireManifestValue(manifest, "contentTreeSha256", contentTreeSha256);
            if (!manifest.path("files").isArray() || manifest.path("files").isEmpty()) {
                throw new IllegalArgumentException("Skill package manifest must contain files");
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Skill packageManifestJson must be valid JSON", exception);
        }
    }

    private void requireManifestValue(com.fasterxml.jackson.databind.JsonNode manifest,
                                      String field,
                                      String expected) {
        if (!expected.equals(manifest.path(field).asText(null))) {
            throw new IllegalArgumentException("Skill package manifest " + field + " does not match the binding snapshot");
        }
    }

    private void validateOptionalJsonObject(String value, String field) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        try {
            if (!objectMapper.readTree(value).isObject()) {
                throw new IllegalArgumentException(field + " must be a JSON object");
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException(field + " must be valid JSON", exception);
        }
    }

    private String upperOrDefault(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(java.util.Locale.ROOT) : fallback;
    }

    private String workflowToolName(String keySlug) {
        String source = StringUtils.hasText(keySlug) ? keySlug.trim() : "workflow_tool";
        String normalized = source.replaceAll("[^A-Za-z0-9_]", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
        if (normalized.isEmpty() || !Character.isLetter(normalized.charAt(0))) {
            normalized = "workflow_" + normalized;
        }
        if (normalized.length() < 2) normalized = "workflow_tool";
        return normalized.length() <= 128 ? normalized : normalized.substring(0, 128);
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String firstText(String first, String second) {
        return StringUtils.hasText(first) ? first.trim() : trimToNull(second);
    }
}
