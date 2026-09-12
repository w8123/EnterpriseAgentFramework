package com.enterprise.ai.runtime.agent;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDeletionReferences;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class RuntimeAgentWorkflowUsageReader implements RuntimeAgentWorkflowUsageQuery, RuntimeWorkflowDeletionReferences {
    private static final int LIMIT = 10_000;
    private final RuntimeAgentMapper agents;
    private final RuntimeAgentWorkflowToolMapper bindings;
    private final RuntimeAgentConfigVersionMapper configs;

    @Override
    public Set<String> referencedWorkflowIds(Collection<String> workflowIds) {
        if (workflowIds == null || workflowIds.isEmpty()) return Set.of();
        return bindings.selectList(Wrappers.<RuntimeAgentWorkflowToolEntity>lambdaQuery()
                .select(RuntimeAgentWorkflowToolEntity::getWorkflowId)
                .in(RuntimeAgentWorkflowToolEntity::getWorkflowId, workflowIds)
                .groupBy(RuntimeAgentWorkflowToolEntity::getWorkflowId)).stream()
                .map(RuntimeAgentWorkflowToolEntity::getWorkflowId).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    @Transactional(readOnly = true)
    public Evidence publishedBindings(Collection<Long> externallyPinnedConfigs) {
        var external = new LinkedHashSet<>(externallyPinnedConfigs == null ? List.<Long>of() : externallyPinnedConfigs);
        Set<String> warnings = new LinkedHashSet<>();
        var liveAgents = agents.selectList(Wrappers.<RuntimeAgentEntity>lambdaQuery()
                .eq(RuntimeAgentEntity::getEnabled, true).isNotNull(RuntimeAgentEntity::getActiveConfigVersionId)
                .orderByAsc(RuntimeAgentEntity::getId).last("limit " + (LIMIT + 1)));
        if (liveAgents.size() > LIMIT) warnings.add("REFERENCE_SCAN_LIMIT");
        var byAgent = new HashMap<String, RuntimeAgentEntity>();
        liveAgents.stream().limit(LIMIT).forEach(agent -> byAgent.put(agent.getId(), agent));
        Set<Long> expected = new LinkedHashSet<>(external);
        byAgent.values().stream().map(RuntimeAgentEntity::getActiveConfigVersionId).filter(Objects::nonNull).forEach(expected::add);
        if (expected.isEmpty()) return new Evidence(List.of(), warnings);
        if (expected.size() > LIMIT) warnings.add("REFERENCE_SCAN_LIMIT");
        List<Long> selectedIds = expected.stream().limit(LIMIT).toList();
        var selectedConfigs = configs.selectBatchIds(selectedIds);
        var found = selectedConfigs.stream().map(RuntimeAgentConfigVersionEntity::getId).collect(Collectors.toSet());
        if (!found.containsAll(expected)) warnings.add("AGENT_CONFIG_VERSION_MISSING");
        var missingOwners = selectedConfigs.stream().map(RuntimeAgentConfigVersionEntity::getAgentId)
                .filter(Objects::nonNull).filter(id -> !byAgent.containsKey(id)).distinct().toList();
        if (!missingOwners.isEmpty()) agents.selectBatchIds(missingOwners).forEach(agent -> byAgent.put(agent.getId(), agent));
        var ownerByConfig = new HashMap<Long, String>();
        for (var config : selectedConfigs) {
            if (!byAgent.containsKey(config.getAgentId())) warnings.add("AGENT_CONFIG_OWNER_MISSING");
            ownerByConfig.put(config.getId(), config.getAgentId());
        }
        // Historical configs are queried only when an external publication pins them.
        var rows = bindings.selectList(Wrappers.<RuntimeAgentWorkflowToolEntity>lambdaQuery()
                .eq(RuntimeAgentWorkflowToolEntity::getEnabled, true)
                .in(RuntimeAgentWorkflowToolEntity::getAgentConfigVersionId, selectedIds)
                .orderByAsc(RuntimeAgentWorkflowToolEntity::getId).last("limit " + (LIMIT + 1)));
        if (rows.size() > LIMIT) warnings.add("REFERENCE_SCAN_LIMIT");
        List<Binding> result = new ArrayList<>();
        for (var row : rows.stream().limit(LIMIT).toList()) {
            var agent = byAgent.get(row.getAgentId());
            if (agent == null || !Objects.equals(row.getAgentId(), ownerByConfig.get(row.getAgentConfigVersionId()))) {
                warnings.add("AGENT_CONFIG_OWNER_MISSING"); continue;
            }
            boolean active = Boolean.TRUE.equals(agent.getEnabled())
                    && Objects.equals(agent.getActiveConfigVersionId(), row.getAgentConfigVersionId());
            if (active || external.contains(row.getAgentConfigVersionId())) {
                result.add(new Binding(agent.getId(), agent.getName(), row.getAgentConfigVersionId(),
                        row.getWorkflowId(), row.getWorkflowVersionId(), active));
            }
        }
        return new Evidence(result, warnings);
    }
}
