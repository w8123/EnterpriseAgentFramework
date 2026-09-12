package com.enterprise.ai.runtime.agent;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowExecutionQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Hot-path resolver for Runtime Agent execution. Never calls admin display queries
 * ({@code resolveDisplayConfig}, {@code listTools}).
 * Each entry opens a short committed snapshot, including when Eval already owns a write transaction.
 * Only local owner queries run inside it; execution and package/remote calls happen after it closes.
 */
@Service
@RequiredArgsConstructor
public class RuntimeAgentExecutionContextResolver {

    private final RuntimeAgentIdentityQuery agentIdentities;
    private final RuntimeAgentConfigVersionMapper configMapper;
    private final RuntimeAgentWorkflowToolMapper toolMapper;
    private final RuntimeAgentSkillBindingMapper skillBindingMapper;
    private final RuntimeWorkflowExecutionQuery workflowQuery;
    private final RuntimeAgentRemoteBindingQuery a2aBindings;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public Optional<RuntimeAgentExecutionContext> resolve(String idOrKeySlug) {
        long totalStart = System.nanoTime();
        long agentStart = totalStart;
        Optional<RuntimeAgentExecutionView> agentOpt = agentIdentities.find(idOrKeySlug);
        long agentMs = elapsedMs(agentStart);
        if (agentOpt.isEmpty()) {
            return Optional.empty();
        }
        RuntimeAgentExecutionView agent = agentOpt.get();

        long configStart = System.nanoTime();
        Optional<RuntimeAgentConfigVersionEntity> configOpt = loadActiveConfig(agent);
        long configMs = elapsedMs(configStart);
        if (configOpt.isEmpty()) {
            return Optional.of(new RuntimeAgentExecutionContext(
                    agent, null, List.of(), List.of(), List.of(), List.of(),
                    new RuntimeAgentExecutionContext.ResolveTimings(agentMs, configMs, 0L, 0L, elapsedMs(totalStart))));
        }
        RuntimeAgentConfigVersionEntity config = configOpt.get();

        long toolStart = System.nanoTime();
        List<RuntimeAgentWorkflowToolSnapshot> tools = loadEnabledTools(agent.id(), config.getId());
        long toolMs = elapsedMs(toolStart);

        long skillStart = System.nanoTime();
        List<RuntimeAgentSkillBindingSnapshot> skills = loadEnabledSkills(agent.id(), config.getId());
        long skillMs = elapsedMs(skillStart);

        long a2aStart = System.nanoTime();
        List<RuntimeAgentRemoteBindingView> remoteAgents =
                loadEnabledRemoteAgents(agent.id(), config.getId());
        long a2aMs = elapsedMs(a2aStart);

        long targetStart = System.nanoTime();
        List<RuntimeResolvedWorkflowTarget> targets = resolveTargetsBatch(tools);
        long targetMs = elapsedMs(targetStart);

        return Optional.of(new RuntimeAgentExecutionContext(
                agent,
                RuntimeAgentConfigSnapshot.fromEntity(config),
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
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public Optional<RuntimeAgentExecutionContext> resolvePublished(String idOrKeySlug, Long configVersionId) {
        return resolveSpecificConfig(idOrKeySlug, configVersionId, false);
    }

    /**
     * Resolves one exact Agent config for Eval snapshotting. Unlike replay, DRAFT is allowed, but
     * the resolver still requires server-owned config/tool/workflow rows and pinned Workflow versions.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public Optional<RuntimeAgentExecutionContext> resolveForEvaluation(String idOrKeySlug,
                                                                       Long configVersionId) {
        return resolveSpecificConfig(idOrKeySlug, configVersionId, true);
    }

    private Optional<RuntimeAgentExecutionContext> resolveSpecificConfig(String idOrKeySlug,
                                                                         Long configVersionId,
                                                                         boolean allowDraft) {
        long totalStart = System.nanoTime();
        long agentStart = totalStart;
        Optional<RuntimeAgentExecutionView> agentOpt = agentIdentities.find(idOrKeySlug);
        long agentMs = elapsedMs(agentStart);
        if (agentOpt.isEmpty() || configVersionId == null) {
            return Optional.empty();
        }
        RuntimeAgentExecutionView agent = agentOpt.get();

        long configStart = System.nanoTime();
        RuntimeAgentConfigVersionEntity config = configMapper.selectById(configVersionId);
        long configMs = elapsedMs(configStart);
        boolean allowedStatus = config != null && (allowDraft
                ? RuntimeAgentConfigStatus.isKnown(config.getStatus())
                : RuntimeAgentConfigStatus.isPublished(config.getStatus()));
        if (config == null
                || !agent.id().equals(config.getAgentId())
                || !allowedStatus) {
            return Optional.of(new RuntimeAgentExecutionContext(
                    agent, null, List.of(), List.of(), List.of(), List.of(),
                    new RuntimeAgentExecutionContext.ResolveTimings(agentMs, configMs, 0L, 0L, elapsedMs(totalStart))));
        }

        long toolStart = System.nanoTime();
        List<RuntimeAgentWorkflowToolSnapshot> tools = loadEnabledTools(agent.id(), config.getId());
        long toolMs = elapsedMs(toolStart);

        long skillStart = System.nanoTime();
        List<RuntimeAgentSkillBindingSnapshot> skills = loadEnabledSkills(agent.id(), config.getId());
        long skillMs = elapsedMs(skillStart);

        long a2aStart = System.nanoTime();
        List<RuntimeAgentRemoteBindingView> remoteAgents =
                loadEnabledRemoteAgents(agent.id(), config.getId());
        long a2aMs = elapsedMs(a2aStart);

        long targetStart = System.nanoTime();
        List<RuntimeResolvedWorkflowTarget> targets = resolveTargetsBatch(tools);
        long targetMs = elapsedMs(targetStart);

        return Optional.of(new RuntimeAgentExecutionContext(
                agent,
                RuntimeAgentConfigSnapshot.fromEntity(config),
                tools,
                skills,
                remoteAgents,
                targets,
                new RuntimeAgentExecutionContext.ResolveTimings(
                        agentMs, configMs, toolMs, skillMs, a2aMs, targetMs, elapsedMs(totalStart))));
    }

    private Optional<RuntimeAgentConfigVersionEntity> loadActiveConfig(RuntimeAgentExecutionView agent) {
        if (agent.activeConfigVersionId() != null) {
            RuntimeAgentConfigVersionEntity active = configMapper.selectById(agent.activeConfigVersionId());
            if (active != null
                    && agent.id().equals(active.getAgentId())
                    && "ACTIVE".equalsIgnoreCase(active.getStatus())) {
                return Optional.of(active);
            }
        }
        return Optional.ofNullable(configMapper.selectOne(Wrappers.<RuntimeAgentConfigVersionEntity>lambdaQuery()
                .eq(RuntimeAgentConfigVersionEntity::getAgentId, agent.id())
                .eq(RuntimeAgentConfigVersionEntity::getStatus, "ACTIVE")
                .orderByDesc(RuntimeAgentConfigVersionEntity::getVersionNo)
                .last("LIMIT 1")));
    }

    private List<RuntimeAgentWorkflowToolSnapshot> loadEnabledTools(String agentId, Long configVersionId) {
        if (!StringUtils.hasText(agentId) || configVersionId == null) {
            return List.of();
        }
        return toolMapper.selectList(Wrappers.<RuntimeAgentWorkflowToolEntity>lambdaQuery()
                .eq(RuntimeAgentWorkflowToolEntity::getAgentId, agentId)
                .eq(RuntimeAgentWorkflowToolEntity::getAgentConfigVersionId, configVersionId)
                .eq(RuntimeAgentWorkflowToolEntity::getEnabled, true)
                .orderByAsc(RuntimeAgentWorkflowToolEntity::getPriority)
                .orderByAsc(RuntimeAgentWorkflowToolEntity::getId)).stream()
                .map(RuntimeAgentWorkflowToolSnapshot::fromEntity).toList();
    }

    private List<RuntimeAgentSkillBindingSnapshot> loadEnabledSkills(String agentId, Long configVersionId) {
        if (!StringUtils.hasText(agentId) || configVersionId == null) {
            return List.of();
        }
        return skillBindingMapper.selectList(Wrappers.<RuntimeAgentSkillBindingEntity>lambdaQuery()
                .eq(RuntimeAgentSkillBindingEntity::getAgentId, agentId)
                .eq(RuntimeAgentSkillBindingEntity::getAgentConfigVersionId, configVersionId)
                .eq(RuntimeAgentSkillBindingEntity::getEnabled, true)
                .orderByAsc(RuntimeAgentSkillBindingEntity::getPriority)
                .orderByAsc(RuntimeAgentSkillBindingEntity::getId)).stream()
                .map(RuntimeAgentSkillBindingSnapshot::fromEntity).toList();
    }

    private List<RuntimeAgentRemoteBindingView> loadEnabledRemoteAgents(String agentId, Long configVersionId) {
        return a2aBindings.enabledBindings(agentId, configVersionId);
    }

    List<RuntimeResolvedWorkflowTarget> resolveTargetsBatch(List<RuntimeAgentWorkflowToolSnapshot> tools) {
        if (tools == null || tools.isEmpty()) return List.of();
        var targets = workflowQuery.resolve(tools.stream().map(tool ->
                new RuntimeWorkflowExecutionQuery.Reference(tool.getWorkflowId(), tool.getWorkflowVersionId())).toList());
        List<RuntimeResolvedWorkflowTarget> result = new ArrayList<>();
        for (int index = 0; index < tools.size(); index++) {
            var target = targets.get(index);
            result.add(new RuntimeResolvedWorkflowTarget(tools.get(index), target.workflow(), target.version()));
        }
        return List.copyOf(result);
    }

    private static long elapsedMs(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
    }
}
