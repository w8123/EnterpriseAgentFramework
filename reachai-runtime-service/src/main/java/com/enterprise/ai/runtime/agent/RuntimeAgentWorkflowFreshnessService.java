package com.enterprise.ai.runtime.agent;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowFreshnessViews.AgentWorkflowFreshnessView;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowFreshnessViews.WorkflowAgentReferenceView;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowFreshnessViews.WorkflowAgentReferencesView;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowFreshnessViews.WorkflowPinFreshness;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowToolCatalogQuery;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowToolCatalogQuery.Entry;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowToolCatalogQuery.Reference;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Shows which Workflow versions the ACTIVE Agent configurations actually run,
 * compared with each Workflow's current published version. All reads stay
 * inside Runtime-owned Agent and Workflow tables.
 */
@Service
@RequiredArgsConstructor
public class RuntimeAgentWorkflowFreshnessService {

    private static final int REFERENCE_LIMIT = 500;

    private final RuntimeAgentMapper agentMapper;
    private final RuntimeAgentConfigVersionMapper configMapper;
    private final RuntimeAgentWorkflowToolMapper toolMapper;
    private final RuntimeAgentConfigService configService;
    private final RuntimeWorkflowToolCatalogQuery workflowCatalog;

    @Transactional(readOnly = true)
    public AgentWorkflowFreshnessView agentFreshness(String agentId) {
        if (!StringUtils.hasText(agentId)) throw new IllegalArgumentException("agentId is required");
        RuntimeAgentEntity agent = agentMapper.selectById(agentId.trim());
        if (agent == null) throw new IllegalArgumentException("agent not found: " + agentId);
        RuntimeAgentConfigVersionEntity active = configService.resolveActive(agent.getId()).orElse(null);
        List<RuntimeAgentWorkflowToolEntity> tools = active == null
                ? List.of() : configService.resolveActiveTools(agent.getId(), active);
        Map<String, Entry> current = workflowCatalog.current(tools.stream()
                .map(RuntimeAgentWorkflowToolEntity::getWorkflowId).toList());
        Map<Reference, Entry> pinned = workflowCatalog.pinned(tools.stream().map(this::reference).toList());
        List<WorkflowPinFreshness> rows = tools.stream()
                .map(tool -> freshness(tool, pinned.get(reference(tool)), current.get(tool.getWorkflowId())))
                .toList();
        return new AgentWorkflowFreshnessView(
                agent.getId(),
                agent.getName(),
                agent.getKeySlug(),
                agent.getProjectId(),
                agent.getProjectCode(),
                agent.getEnabled(),
                active == null ? null : active.getId(),
                active == null ? null : active.getVersionNo(),
                (int) rows.stream().filter(WorkflowPinFreshness::stale).count(),
                rows);
    }

    /** Agents whose ACTIVE configuration has an enabled tool pinned to this Workflow. */
    @Transactional(readOnly = true)
    public WorkflowAgentReferencesView workflowReferences(String workflowId) {
        if (!StringUtils.hasText(workflowId)) throw new IllegalArgumentException("workflowId is required");
        String id = workflowId.trim();
        Entry latest = workflowCatalog.current(List.of(id)).get(id);
        Long latestId = latest == null || latest.version() == null ? null : latest.version().id();
        String latestVersion = latest == null || latest.version() == null ? null : latest.version().version();

        List<RuntimeAgentWorkflowToolEntity> tools = toolMapper.selectList(
                Wrappers.<RuntimeAgentWorkflowToolEntity>lambdaQuery()
                        .eq(RuntimeAgentWorkflowToolEntity::getWorkflowId, id)
                        .eq(RuntimeAgentWorkflowToolEntity::getEnabled, true)
                        .apply("EXISTS (SELECT 1 FROM runtime_agent a WHERE a.id = runtime_agent_workflow_tool.agent_id"
                                + " AND a.active_config_version_id = runtime_agent_workflow_tool.agent_config_version_id)")
                        .orderByAsc(RuntimeAgentWorkflowToolEntity::getId)
                        .last("LIMIT " + REFERENCE_LIMIT));
        if (tools.isEmpty()) {
            return new WorkflowAgentReferencesView(id, latestId, latestVersion, 0, List.of());
        }
        Map<String, RuntimeAgentEntity> agents = byKey(agentMapper.selectBatchIds(tools.stream()
                .map(RuntimeAgentWorkflowToolEntity::getAgentId).distinct().toList()), RuntimeAgentEntity::getId);
        Map<Long, RuntimeAgentConfigVersionEntity> configs = byKey(configMapper.selectBatchIds(tools.stream()
                .map(RuntimeAgentWorkflowToolEntity::getAgentConfigVersionId).distinct().toList()),
                RuntimeAgentConfigVersionEntity::getId);
        Map<Reference, Entry> pinned = workflowCatalog.pinned(tools.stream().map(this::reference).toList());

        List<WorkflowAgentReferenceView> rows = new ArrayList<>();
        for (RuntimeAgentWorkflowToolEntity tool : tools) {
            RuntimeAgentEntity agent = agents.get(tool.getAgentId());
            RuntimeAgentConfigVersionEntity config = configs.get(tool.getAgentConfigVersionId());
            if (agent == null || config == null || !Objects.equals(agent.getActiveConfigVersionId(), config.getId())) {
                continue;
            }
            WorkflowPinFreshness pin = freshness(tool, pinned.get(reference(tool)), latest);
            rows.add(new WorkflowAgentReferenceView(
                    agent.getId(),
                    agent.getName(),
                    agent.getKeySlug(),
                    agent.getProjectId(),
                    agent.getProjectCode(),
                    agent.getEnabled(),
                    config.getId(),
                    config.getVersionNo(),
                    pin.toolName(),
                    pin.pinnedWorkflowVersionId(),
                    pin.pinnedWorkflowVersion(),
                    pin.latestWorkflowVersionId(),
                    pin.latestWorkflowVersion(),
                    pin.stale()));
        }
        rows.sort(Comparator.comparing(WorkflowAgentReferenceView::stale).reversed()
                .thenComparing(row -> row.agentName() == null ? "" : row.agentName()));
        return new WorkflowAgentReferencesView(id, latestId, latestVersion,
                (int) rows.stream().filter(WorkflowAgentReferenceView::stale).count(), List.copyOf(rows));
    }

    /**
     * Batched stale-tool counts for an Agent list: one tool query plus one
     * Workflow catalog lookup, regardless of how many Agents are listed.
     */
    @Transactional(readOnly = true)
    public Map<String, Integer> staleWorkflowToolCounts(Collection<RuntimeAgentEntity> agents) {
        if (agents == null || agents.isEmpty()) return Map.of();
        List<Long> activeConfigIds = agents.stream()
                .map(RuntimeAgentEntity::getActiveConfigVersionId).filter(Objects::nonNull).distinct().toList();
        if (activeConfigIds.isEmpty()) return Map.of();
        List<RuntimeAgentWorkflowToolEntity> tools = toolMapper.selectList(
                Wrappers.<RuntimeAgentWorkflowToolEntity>lambdaQuery()
                        .select(RuntimeAgentWorkflowToolEntity::getAgentId,
                                RuntimeAgentWorkflowToolEntity::getAgentConfigVersionId,
                                RuntimeAgentWorkflowToolEntity::getWorkflowId,
                                RuntimeAgentWorkflowToolEntity::getWorkflowVersionId)
                        .in(RuntimeAgentWorkflowToolEntity::getAgentConfigVersionId, activeConfigIds)
                        .eq(RuntimeAgentWorkflowToolEntity::getEnabled, true));
        if (tools == null || tools.isEmpty()) return Map.of();
        Map<String, Long> activeByAgent = new HashMap<>();
        agents.forEach(agent -> {
            if (agent.getActiveConfigVersionId() != null) activeByAgent.put(agent.getId(), agent.getActiveConfigVersionId());
        });
        Map<String, Entry> current = workflowCatalog.current(tools.stream()
                .map(RuntimeAgentWorkflowToolEntity::getWorkflowId).distinct().toList());
        Map<String, Integer> counts = new HashMap<>();
        for (RuntimeAgentWorkflowToolEntity tool : tools) {
            if (!Objects.equals(activeByAgent.get(tool.getAgentId()), tool.getAgentConfigVersionId())) continue;
            if (RuntimeAgentWorkflowFreshnessViews.isStale(tool.getWorkflowVersionId(), current.get(tool.getWorkflowId()))) {
                counts.merge(tool.getAgentId(), 1, Integer::sum);
            }
        }
        return Map.copyOf(counts);
    }

    private WorkflowPinFreshness freshness(RuntimeAgentWorkflowToolEntity tool, Entry pinned, Entry latest) {
        var workflow = latest != null && latest.workflow() != null ? latest.workflow()
                : pinned == null ? null : pinned.workflow();
        boolean latestExecutable = latest != null && latest.activePublishedWorkflow() && latest.version().executable();
        return new WorkflowPinFreshness(
                tool.getWorkflowId(),
                workflow == null ? null : workflow.keySlug(),
                workflow == null ? null : workflow.name(),
                tool.getToolName(),
                tool.getWorkflowVersionId(),
                pinned == null || pinned.version() == null ? null : pinned.version().version(),
                latestExecutable ? latest.version().id() : null,
                latestExecutable ? latest.version().version() : null,
                RuntimeAgentWorkflowFreshnessViews.isStale(tool.getWorkflowVersionId(), latest));
    }

    private Reference reference(RuntimeAgentWorkflowToolEntity tool) {
        return new Reference(tool.getWorkflowId(), tool.getWorkflowVersionId());
    }

    private static <K, T> Map<K, T> byKey(List<T> values, Function<T, K> key) {
        if (values == null) return Map.of();
        return values.stream().filter(Objects::nonNull)
                .collect(Collectors.toMap(key, Function.identity(), (left, right) -> left));
    }
}
