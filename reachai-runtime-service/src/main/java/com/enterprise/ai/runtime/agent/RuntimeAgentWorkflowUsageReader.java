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
    public Evidence publishedBindings(Collection<Long> externallyPinnedConfigs, java.util.Map<Long, String> workflowVersions) {
        var external = new LinkedHashSet<>(externallyPinnedConfigs == null ? List.<Long>of() : externallyPinnedConfigs);
        Set<String> warnings = new LinkedHashSet<>();
        // A missing active configuration makes coverage partial, even when this scope has no hits.
        // Read at most one identity instead of materializing every enabled Agent and its JSON config.
        var brokenActive = agents.selectList(Wrappers.<RuntimeAgentEntity>lambdaQuery()
                .select(RuntimeAgentEntity::getId)
                .eq(RuntimeAgentEntity::getEnabled, true).isNotNull(RuntimeAgentEntity::getActiveConfigVersionId)
                .apply("NOT EXISTS (SELECT 1 FROM runtime_agent_config_version c WHERE c.id = runtime_agent.active_config_version_id AND c.agent_id = runtime_agent.id)")
                .last("limit 1"));
        if (!brokenActive.isEmpty()) warnings.add("AGENT_CONFIG_VERSION_MISSING");
        var brokenWorkflow = Wrappers.<RuntimeAgentWorkflowToolEntity>lambdaQuery()
                .select(RuntimeAgentWorkflowToolEntity::getId)
                .eq(RuntimeAgentWorkflowToolEntity::getEnabled, true)
                .apply("NOT EXISTS (SELECT 1 FROM runtime_workflow_version v WHERE v.id = runtime_agent_workflow_tool.workflow_version_id AND v.workflow_id = runtime_agent_workflow_tool.workflow_id)");
        reachable(brokenWorkflow, external);
        if (!bindings.selectList(brokenWorkflow.last("limit 1")).isEmpty()) warnings.add("AGENT_WORKFLOW_VERSION_MISSING");
        var matching = workflowVersions == null ? java.util.Map.<Long, String>of() : workflowVersions;
        List<RuntimeAgentWorkflowToolEntity> rows = List.of();
        if (!matching.isEmpty()) {
            var query = Wrappers.<RuntimeAgentWorkflowToolEntity>lambdaQuery()
                    .select(RuntimeAgentWorkflowToolEntity::getId, RuntimeAgentWorkflowToolEntity::getAgentId,
                            RuntimeAgentWorkflowToolEntity::getAgentConfigVersionId, RuntimeAgentWorkflowToolEntity::getWorkflowId,
                            RuntimeAgentWorkflowToolEntity::getWorkflowVersionId)
                    .eq(RuntimeAgentWorkflowToolEntity::getEnabled, true)
                    .in(RuntimeAgentWorkflowToolEntity::getWorkflowId, new LinkedHashSet<>(matching.values()))
                    .in(RuntimeAgentWorkflowToolEntity::getWorkflowVersionId, matching.keySet());
            reachable(query, external);
            rows = bindings.selectList(query.orderByAsc(RuntimeAgentWorkflowToolEntity::getId).last("limit " + (LIMIT + 1)));
            if (rows.size() > LIMIT) warnings.add("REFERENCE_SCAN_LIMIT");
        }
        // Unrelated external pins still affect completeness, but only one aggregate row is needed.
        if (!external.isEmpty()) {
            var coverage = configs.inspectPinnedCoverage(external);
            if (coverage == null || coverage.getExistingCount() != external.size()) warnings.add("AGENT_CONFIG_VERSION_MISSING");
            if (coverage == null || coverage.getMissingOwnerCount() > 0) warnings.add("AGENT_CONFIG_OWNER_MISSING");
        }
        Set<Long> expected = new LinkedHashSet<>();
        rows.stream().limit(LIMIT).map(RuntimeAgentWorkflowToolEntity::getAgentConfigVersionId).filter(Objects::nonNull).forEach(expected::add);
        if (expected.isEmpty()) return new Evidence(List.of(), warnings);
        if (expected.size() > LIMIT) warnings.add("REFERENCE_SCAN_LIMIT");
        List<Long> selectedIds = expected.stream().limit(LIMIT).toList();
        var selectedConfigs = configs.selectList(Wrappers.<RuntimeAgentConfigVersionEntity>lambdaQuery()
                .select(RuntimeAgentConfigVersionEntity::getId, RuntimeAgentConfigVersionEntity::getAgentId)
                .in(RuntimeAgentConfigVersionEntity::getId, selectedIds));
        var found = selectedConfigs.stream().map(RuntimeAgentConfigVersionEntity::getId).collect(Collectors.toSet());
        if (!found.containsAll(expected)) warnings.add("AGENT_CONFIG_VERSION_MISSING");
        var byAgent = new HashMap<String, RuntimeAgentEntity>();
        var owners = selectedConfigs.stream().map(RuntimeAgentConfigVersionEntity::getAgentId).filter(Objects::nonNull).distinct().toList();
        if (!owners.isEmpty()) agents.selectList(Wrappers.<RuntimeAgentEntity>lambdaQuery()
                .select(RuntimeAgentEntity::getId, RuntimeAgentEntity::getName, RuntimeAgentEntity::getEnabled, RuntimeAgentEntity::getActiveConfigVersionId)
                .in(RuntimeAgentEntity::getId, owners)).forEach(agent -> byAgent.put(agent.getId(), agent));
        var ownerByConfig = new HashMap<Long, String>();
        for (var config : selectedConfigs) {
            if (!byAgent.containsKey(config.getAgentId())) warnings.add("AGENT_CONFIG_OWNER_MISSING");
            ownerByConfig.put(config.getId(), config.getAgentId());
        }
        // Historical configurations are reachable only through an explicit publication pin.
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

    private static void reachable(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RuntimeAgentWorkflowToolEntity> query,
                                  Set<Long> external) {
        query.and(candidate -> {
            candidate.apply("EXISTS (SELECT 1 FROM runtime_agent a WHERE a.id = runtime_agent_workflow_tool.agent_id AND a.enabled = 1 AND a.active_config_version_id = runtime_agent_workflow_tool.agent_config_version_id)");
            if (!external.isEmpty()) candidate.or().in(RuntimeAgentWorkflowToolEntity::getAgentConfigVersionId, external);
        });
    }
}
