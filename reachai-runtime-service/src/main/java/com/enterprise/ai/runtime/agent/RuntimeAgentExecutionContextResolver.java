package com.enterprise.ai.runtime.agent;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.runtime.a2a.RuntimeA2aRemoteAgentBindingEntity;
import com.enterprise.ai.runtime.a2a.RuntimeA2aRemoteAgentBindingMapper;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionMapper;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowVersionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Hot-path resolver for Runtime Agent execution. Never calls admin display queries
 * ({@code resolveDisplayConfig}, {@code listTools}).
 */
@Service
@RequiredArgsConstructor
public class RuntimeAgentExecutionContextResolver {

    private final RuntimeAgentMapper agentMapper;
    private final RuntimeAgentConfigVersionMapper configMapper;
    private final RuntimeAgentWorkflowToolMapper toolMapper;
    private final RuntimeAgentSkillBindingMapper skillBindingMapper;
    private final RuntimeWorkflowDefinitionMapper workflowMapper;
    private final RuntimeWorkflowVersionMapper workflowVersionMapper;
    private RuntimeA2aRemoteAgentBindingMapper a2aBindingMapper;

    @Autowired(required = false)
    void setA2aBindingMapper(RuntimeA2aRemoteAgentBindingMapper mapper) {
        this.a2aBindingMapper = mapper;
    }

    public Optional<RuntimeAgentExecutionContext> resolve(String idOrKeySlug) {
        long totalStart = System.nanoTime();
        long agentStart = totalStart;
        Optional<RuntimeAgentEntity> agentOpt = findAgentOnce(idOrKeySlug);
        long agentMs = elapsedMs(agentStart);
        if (agentOpt.isEmpty()) {
            return Optional.empty();
        }
        RuntimeAgentEntity agentEntity = agentOpt.get();
        RuntimeAgentExecutionView agent = RuntimeAgentExecutionView.fromEntity(agentEntity);

        long configStart = System.nanoTime();
        Optional<RuntimeAgentConfigVersionEntity> configOpt = loadActiveConfig(agentEntity);
        long configMs = elapsedMs(configStart);
        if (configOpt.isEmpty()) {
            return Optional.of(new RuntimeAgentExecutionContext(
                    agent, null, List.of(), List.of(), List.of(), List.of(),
                    new RuntimeAgentExecutionContext.ResolveTimings(agentMs, configMs, 0L, 0L, elapsedMs(totalStart))));
        }
        RuntimeAgentConfigVersionEntity config = configOpt.get();

        long toolStart = System.nanoTime();
        List<RuntimeAgentWorkflowToolEntity> tools = loadEnabledTools(agent.id(), config.getId());
        long toolMs = elapsedMs(toolStart);

        long skillStart = System.nanoTime();
        List<RuntimeAgentSkillBindingEntity> skills = loadEnabledSkills(agent.id(), config.getId());
        long skillMs = elapsedMs(skillStart);

        long a2aStart = System.nanoTime();
        List<RuntimeA2aRemoteAgentBindingEntity> remoteAgents =
                loadEnabledRemoteAgents(agent.id(), config.getId());
        long a2aMs = elapsedMs(a2aStart);

        long targetStart = System.nanoTime();
        List<RuntimeResolvedWorkflowTarget> targets = resolveTargetsBatch(tools);
        long targetMs = elapsedMs(targetStart);

        return Optional.of(new RuntimeAgentExecutionContext(
                agent,
                config,
                tools,
                skills,
                remoteAgents,
                targets,
                new RuntimeAgentExecutionContext.ResolveTimings(
                        agentMs, configMs, toolMs, skillMs, a2aMs, targetMs, elapsedMs(totalStart))));
    }

    /**
     * Loads a specific published/archived config for replay/continuation without display queries.
     */
    public Optional<RuntimeAgentExecutionContext> resolvePublished(String idOrKeySlug, Long configVersionId) {
        return resolveSpecificConfig(idOrKeySlug, configVersionId, false);
    }

    /**
     * Resolves one exact Agent config for Eval snapshotting. Unlike replay, DRAFT is allowed, but
     * the resolver still requires server-owned config/tool/workflow rows and pinned Workflow versions.
     */
    public Optional<RuntimeAgentExecutionContext> resolveForEvaluation(String idOrKeySlug,
                                                                       Long configVersionId) {
        return resolveSpecificConfig(idOrKeySlug, configVersionId, true);
    }

    private Optional<RuntimeAgentExecutionContext> resolveSpecificConfig(String idOrKeySlug,
                                                                         Long configVersionId,
                                                                         boolean allowDraft) {
        long totalStart = System.nanoTime();
        long agentStart = totalStart;
        Optional<RuntimeAgentEntity> agentOpt = findAgentOnce(idOrKeySlug);
        long agentMs = elapsedMs(agentStart);
        if (agentOpt.isEmpty() || configVersionId == null) {
            return Optional.empty();
        }
        RuntimeAgentExecutionView agent = RuntimeAgentExecutionView.fromEntity(agentOpt.get());

        long configStart = System.nanoTime();
        RuntimeAgentConfigVersionEntity config = configMapper.selectById(configVersionId);
        long configMs = elapsedMs(configStart);
        boolean draft = config != null && "DRAFT".equalsIgnoreCase(config.getStatus());
        boolean knownEvalStatus = config != null && Set.of("DRAFT", "ACTIVE", "ARCHIVED")
                .contains(String.valueOf(config.getStatus()).toUpperCase(java.util.Locale.ROOT));
        if (config == null
                || !agent.id().equals(config.getAgentId())
                || (!allowDraft && draft)
                || (allowDraft && !knownEvalStatus)) {
            return Optional.of(new RuntimeAgentExecutionContext(
                    agent, null, List.of(), List.of(), List.of(), List.of(),
                    new RuntimeAgentExecutionContext.ResolveTimings(agentMs, configMs, 0L, 0L, elapsedMs(totalStart))));
        }

        long toolStart = System.nanoTime();
        List<RuntimeAgentWorkflowToolEntity> tools = loadEnabledTools(agent.id(), config.getId());
        long toolMs = elapsedMs(toolStart);

        long skillStart = System.nanoTime();
        List<RuntimeAgentSkillBindingEntity> skills = loadEnabledSkills(agent.id(), config.getId());
        long skillMs = elapsedMs(skillStart);

        long a2aStart = System.nanoTime();
        List<RuntimeA2aRemoteAgentBindingEntity> remoteAgents =
                loadEnabledRemoteAgents(agent.id(), config.getId());
        long a2aMs = elapsedMs(a2aStart);

        long targetStart = System.nanoTime();
        List<RuntimeResolvedWorkflowTarget> targets = resolveTargetsBatch(tools);
        long targetMs = elapsedMs(targetStart);

        return Optional.of(new RuntimeAgentExecutionContext(
                agent,
                config,
                tools,
                skills,
                remoteAgents,
                targets,
                new RuntimeAgentExecutionContext.ResolveTimings(
                        agentMs, configMs, toolMs, skillMs, a2aMs, targetMs, elapsedMs(totalStart))));
    }

    /** Single SQL: {@code WHERE id = ? OR key_slug = ? LIMIT 1}. */
    Optional<RuntimeAgentEntity> findAgentOnce(String idOrKeySlug) {
        if (!StringUtils.hasText(idOrKeySlug)) {
            return Optional.empty();
        }
        String lookup = idOrKeySlug.trim();
        return Optional.ofNullable(agentMapper.selectOne(Wrappers.<RuntimeAgentEntity>lambdaQuery()
                .and(w -> w.eq(RuntimeAgentEntity::getId, lookup)
                        .or()
                        .eq(RuntimeAgentEntity::getKeySlug, lookup))
                .last("LIMIT 1")));
    }

    private Optional<RuntimeAgentConfigVersionEntity> loadActiveConfig(RuntimeAgentEntity agent) {
        if (agent.getActiveConfigVersionId() != null) {
            RuntimeAgentConfigVersionEntity active = configMapper.selectById(agent.getActiveConfigVersionId());
            if (active != null
                    && agent.getId().equals(active.getAgentId())
                    && "ACTIVE".equalsIgnoreCase(active.getStatus())) {
                return Optional.of(active);
            }
        }
        return Optional.ofNullable(configMapper.selectOne(Wrappers.<RuntimeAgentConfigVersionEntity>lambdaQuery()
                .eq(RuntimeAgentConfigVersionEntity::getAgentId, agent.getId())
                .eq(RuntimeAgentConfigVersionEntity::getStatus, "ACTIVE")
                .orderByDesc(RuntimeAgentConfigVersionEntity::getVersionNo)
                .last("LIMIT 1")));
    }

    private List<RuntimeAgentWorkflowToolEntity> loadEnabledTools(String agentId, Long configVersionId) {
        if (!StringUtils.hasText(agentId) || configVersionId == null) {
            return List.of();
        }
        return toolMapper.selectList(Wrappers.<RuntimeAgentWorkflowToolEntity>lambdaQuery()
                .eq(RuntimeAgentWorkflowToolEntity::getAgentId, agentId)
                .eq(RuntimeAgentWorkflowToolEntity::getAgentConfigVersionId, configVersionId)
                .eq(RuntimeAgentWorkflowToolEntity::getEnabled, true)
                .orderByAsc(RuntimeAgentWorkflowToolEntity::getPriority)
                .orderByAsc(RuntimeAgentWorkflowToolEntity::getId));
    }

    private List<RuntimeAgentSkillBindingEntity> loadEnabledSkills(String agentId, Long configVersionId) {
        if (!StringUtils.hasText(agentId) || configVersionId == null) {
            return List.of();
        }
        return skillBindingMapper.selectList(Wrappers.<RuntimeAgentSkillBindingEntity>lambdaQuery()
                .eq(RuntimeAgentSkillBindingEntity::getAgentId, agentId)
                .eq(RuntimeAgentSkillBindingEntity::getAgentConfigVersionId, configVersionId)
                .eq(RuntimeAgentSkillBindingEntity::getEnabled, true)
                .orderByAsc(RuntimeAgentSkillBindingEntity::getPriority)
                .orderByAsc(RuntimeAgentSkillBindingEntity::getId));
    }

    private List<RuntimeA2aRemoteAgentBindingEntity> loadEnabledRemoteAgents(
            String agentId, Long configVersionId) {
        if (a2aBindingMapper == null || !StringUtils.hasText(agentId) || configVersionId == null) {
            return List.of();
        }
        return a2aBindingMapper.selectList(
                Wrappers.<RuntimeA2aRemoteAgentBindingEntity>lambdaQuery()
                        .eq(RuntimeA2aRemoteAgentBindingEntity::getAgentId, agentId)
                        .eq(RuntimeA2aRemoteAgentBindingEntity::getAgentConfigVersionId, configVersionId)
                        .eq(RuntimeA2aRemoteAgentBindingEntity::getEnabled, true)
                        .orderByAsc(RuntimeA2aRemoteAgentBindingEntity::getPriority)
                        .orderByAsc(RuntimeA2aRemoteAgentBindingEntity::getId));
    }

    List<RuntimeResolvedWorkflowTarget> resolveTargetsBatch(List<RuntimeAgentWorkflowToolEntity> tools) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        List<String> workflowIds = tools.stream()
                .map(RuntimeAgentWorkflowToolEntity::getWorkflowId)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        Map<String, RuntimeWorkflowDefinitionEntity> workflows = workflowIds.isEmpty()
                ? Map.of()
                : workflowMapper.selectBatchIds(workflowIds).stream()
                .collect(Collectors.toMap(RuntimeWorkflowDefinitionEntity::getId, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));

        List<Long> versionIds = tools.stream()
                .map(RuntimeAgentWorkflowToolEntity::getWorkflowVersionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, RuntimeWorkflowVersionEntity> versions = versionIds.isEmpty()
                ? Map.of()
                : workflowVersionMapper.selectBatchIds(versionIds).stream()
                .collect(Collectors.toMap(RuntimeWorkflowVersionEntity::getId, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));

        List<RuntimeResolvedWorkflowTarget> resolved = new ArrayList<>(tools.size());
        for (RuntimeAgentWorkflowToolEntity tool : tools) {
            RuntimeWorkflowDefinitionEntity workflow = workflows.get(tool.getWorkflowId());
            if (workflow == null || !"ACTIVE".equalsIgnoreCase(workflow.getStatus())) {
                throw new IllegalStateException("Configured Workflow tool is not ACTIVE: " + tool.getWorkflowId());
            }
            RuntimeWorkflowVersionEntity pinned = versions.get(tool.getWorkflowVersionId());
            if (pinned == null
                    || !workflow.getId().equals(pinned.getWorkflowId())
                    || !StringUtils.hasText(pinned.getGraphSpecSnapshotJson())) {
                throw new IllegalStateException("Configured Workflow tool has no executable pinned version: "
                        + workflow.getId() + "#" + tool.getWorkflowVersionId());
            }
            resolved.add(new RuntimeResolvedWorkflowTarget(tool, workflow, pinned));
        }
        return List.copyOf(resolved);
    }

    private static long elapsedMs(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
    }
}
