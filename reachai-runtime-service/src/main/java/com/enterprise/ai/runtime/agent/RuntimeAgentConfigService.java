package com.enterprise.ai.runtime.agent;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.AgentConfigDraftRequest;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.AgentConfigVersionView;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.WorkflowToolRequest;
import com.enterprise.ai.runtime.agent.RuntimeAgentConfigViews.WorkflowToolView;
import com.enterprise.ai.runtime.agent.RuntimeAgentEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentMapper;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionMapper;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RuntimeAgentConfigService {

    private static final Pattern TOOL_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{1,127}");
    private static final int DEFAULT_TOTAL_TIMEOUT_MS = 300_000;
    private static final int DEFAULT_WORKFLOW_TIMEOUT_MS = 180_000;
    private static final int DEFAULT_PAGE_BRIDGE_TIMEOUT_MS = 30_000;

    private final RuntimeAgentConfigVersionMapper configMapper;
    private final RuntimeAgentWorkflowToolMapper toolMapper;
    private final RuntimeAgentMapper agentMapper;
    private final RuntimeWorkflowDefinitionMapper workflowMapper;
    private final RuntimeWorkflowVersionMapper workflowVersionMapper;

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
        requireAgent(agentId);
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
        } else {
            configMapper.updateById(draft);
        }
        if (body.tools() != null) {
            replaceTools(agentId, draft, body.tools());
        }
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
        RuntimeAgentEntity agent = requireAgent(agentId);
        RuntimeAgentConfigVersionEntity target = requireVersion(agentId, configVersionId);
        if (!"DRAFT".equalsIgnoreCase(target.getStatus())) {
            throw new IllegalArgumentException("only DRAFT Agent config can be published");
        }
        validatePublishable(target);
        pinWorkflowVersions(target);
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
        agentMapper.updateById(agent);
        return toView(target);
    }

    /**
     * Copies an immutable ACTIVE/ARCHIVED snapshot into the single editable draft.
     * Published rows are never reactivated or mutated.
     */
    @Transactional
    public AgentConfigVersionView copyToDraft(String agentId, Long configVersionId) {
        requireAgent(agentId);
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
            configMapper.deleteById(currentDraft.getId());
        }

        List<WorkflowToolRequest> tools = listTools(agentId, source.getId()).stream()
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
                tools));
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

    @Transactional
    public AgentConfigVersionView ensureWorkflowToolInDraft(String agentId,
                                                            String workflowId,
                                                            boolean readOnly) {
        requireAgent(agentId);
        if (!StringUtils.hasText(workflowId)) {
            throw new IllegalArgumentException("workflowId is required for Agent Workflow Tool");
        }
        String normalizedWorkflowId = workflowId.trim();
        RuntimeWorkflowDefinitionEntity workflow = workflowMapper.selectById(normalizedWorkflowId);
        if (workflow == null || !"ACTIVE".equalsIgnoreCase(workflow.getStatus())
                || workflowVersionMapper.listActive(normalizedWorkflowId).isEmpty()) {
            throw new IllegalArgumentException(
                    "Agent Workflow Tool requires an ACTIVE published workflow: " + normalizedWorkflowId);
        }

        Optional<RuntimeAgentConfigVersionEntity> displayConfig = resolveDisplayConfig(agentId);
        List<WorkflowToolRequest> tools = new ArrayList<>();
        if (displayConfig.isPresent()) {
            for (WorkflowToolView current : listTools(agentId, displayConfig.get().getId())) {
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
        if (tools.stream().noneMatch(tool -> normalizedWorkflowId.equals(tool.workflowId()))) {
            tools.add(new WorkflowToolRequest(
                    normalizedWorkflowId,
                    workflowToolName(workflow.getKeySlug()),
                    workflow.getDescription(),
                    null,
                    null,
                    readOnly ? "READ" : "PAGE_ACTION",
                    "workflow:" + workflow.getKeySlug(),
                    readOnly,
                    true,
                    tools.size()));
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
        toolMapper.delete(Wrappers.<RuntimeAgentWorkflowToolEntity>lambdaQuery()
                .eq(RuntimeAgentWorkflowToolEntity::getAgentId, normalizedAgentId));
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
        Map<String, Boolean> workflowIds = new LinkedHashMap<>();
        Map<String, Boolean> toolNames = new LinkedHashMap<>();
        int index = 0;
        for (WorkflowToolRequest request : requests) {
            if (request == null || !StringUtils.hasText(request.workflowId())) {
                throw new IllegalArgumentException("workflowId is required for Agent Workflow Tool");
            }
            String workflowId = request.workflowId().trim();
            RuntimeWorkflowDefinitionEntity workflow = workflowMapper.selectById(workflowId);
            if (workflow == null || !"ACTIVE".equalsIgnoreCase(workflow.getStatus())) {
                throw new IllegalArgumentException("Agent Workflow Tool requires an ACTIVE workflow: " + workflowId);
            }
            List<RuntimeWorkflowVersionEntity> activeVersions = workflowVersionMapper.listActive(workflowId);
            if (activeVersions.isEmpty()) {
                throw new IllegalArgumentException("Agent Workflow Tool requires a published workflow version: " + workflowId);
            }
            String toolName = firstText(request.toolName(), workflow.getKeySlug());
            requireToolName(toolName);
            if (workflowIds.putIfAbsent(workflowId, true) != null) {
                throw new IllegalArgumentException("duplicate workflow in Agent tool catalog: " + workflowId);
            }
            if (toolNames.putIfAbsent(toolName, true) != null) {
                throw new IllegalArgumentException("duplicate toolName in Agent tool catalog: " + toolName);
            }
            RuntimeAgentWorkflowToolEntity entity = new RuntimeAgentWorkflowToolEntity();
            entity.setAgentId(agentId);
            entity.setAgentConfigVersionId(draft.getId());
            entity.setWorkflowId(workflowId);
            entity.setWorkflowVersionId(activeVersions.get(0).getId());
            entity.setToolName(toolName);
            entity.setDescriptionOverride(trimToNull(request.descriptionOverride()));
            entity.setInputSchemaOverrideJson(trimToNull(request.inputSchemaOverrideJson()));
            entity.setOutputSchemaOverrideJson(trimToNull(request.outputSchemaOverrideJson()));
            entity.setRiskLevel(firstText(request.riskLevel(), Boolean.FALSE.equals(request.readOnly()) ? "WRITE" : "READ"));
            entity.setPermissionKey(firstText(request.permissionKey(), "workflow:" + workflow.getKeySlug()));
            entity.setReadOnly(request.readOnly() == null ? !"WRITE".equalsIgnoreCase(entity.getRiskLevel()) : request.readOnly());
            entity.setEnabled(request.enabled() == null || request.enabled());
            entity.setPriority(request.priority() == null ? index : request.priority());
            LocalDateTime now = LocalDateTime.now();
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            toolMapper.insert(entity);
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
        for (RuntimeAgentWorkflowToolEntity source : resolveActiveTools(agentId, active.get())) {
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

    private List<WorkflowToolView> toToolViews(List<RuntimeAgentWorkflowToolEntity> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        Map<String, RuntimeWorkflowDefinitionEntity> workflows = workflowMapper.selectBatchIds(
                        rows.stream().map(RuntimeAgentWorkflowToolEntity::getWorkflowId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(RuntimeWorkflowDefinitionEntity::getId, Function.identity()));
        Map<Long, RuntimeWorkflowVersionEntity> pinnedVersions = rows.stream()
                .map(RuntimeAgentWorkflowToolEntity::getWorkflowVersionId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .map(workflowVersionMapper::selectById)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toMap(RuntimeWorkflowVersionEntity::getId, Function.identity()));
        List<WorkflowToolView> views = new ArrayList<>();
        for (RuntimeAgentWorkflowToolEntity row : rows) {
            RuntimeWorkflowDefinitionEntity workflow = workflows.get(row.getWorkflowId());
            RuntimeWorkflowVersionEntity version = pinnedVersions.get(row.getWorkflowVersionId());
            views.add(new WorkflowToolView(
                    row.getId(),
                    row.getAgentId(),
                    row.getAgentConfigVersionId(),
                    row.getWorkflowId(),
                    workflow == null ? null : workflow.getKeySlug(),
                    workflow == null ? null : workflow.getName(),
                    version == null ? null : version.getVersion(),
                    version == null ? null : version.getId(),
                    row.getToolName(),
                    row.getDescriptionOverride(),
                    firstText(row.getDescriptionOverride(), workflow == null ? null : workflow.getDescription()),
                    row.getInputSchemaOverrideJson(),
                    firstText(row.getInputSchemaOverrideJson(), workflow == null ? null : workflow.getInputSchemaJson()),
                    row.getOutputSchemaOverrideJson(),
                    firstText(row.getOutputSchemaOverrideJson(), workflow == null ? null : workflow.getOutputSchemaJson()),
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
                listTools(entity.getAgentId(), entity.getId()));
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
    }

    private void pinWorkflowVersions(RuntimeAgentConfigVersionEntity target) {
        List<RuntimeAgentWorkflowToolEntity> tools = toolMapper.selectList(
                Wrappers.<RuntimeAgentWorkflowToolEntity>lambdaQuery()
                        .eq(RuntimeAgentWorkflowToolEntity::getAgentId, target.getAgentId())
                        .eq(RuntimeAgentWorkflowToolEntity::getAgentConfigVersionId, target.getId())
                        .eq(RuntimeAgentWorkflowToolEntity::getEnabled, true));
        for (RuntimeAgentWorkflowToolEntity tool : tools) {
            List<RuntimeWorkflowVersionEntity> activeVersions = workflowVersionMapper.listActive(tool.getWorkflowId());
            if (activeVersions.isEmpty() || !StringUtils.hasText(activeVersions.get(0).getGraphSpecSnapshotJson())) {
                throw new IllegalArgumentException(
                        "Agent Workflow Tool requires an executable published Workflow version: " + tool.getWorkflowId());
            }
            tool.setWorkflowVersionId(activeVersions.get(0).getId());
            tool.setUpdatedAt(LocalDateTime.now());
            toolMapper.updateById(tool);
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
