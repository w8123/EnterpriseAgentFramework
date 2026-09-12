package com.enterprise.ai.runtime.internal;

import com.enterprise.ai.runtime.workflow.RuntimeWorkflowReferenceIndex;
import com.enterprise.ai.runtime.agent.RuntimeAgentWorkflowUsageQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Joins Workflow-owned reverse references with Agent-owned, frozen binding evidence. */
@Service
@RequiredArgsConstructor
public class RuntimeCapabilityReferenceService {
    private static final int LIMIT = 10_000;
    private final RuntimeWorkflowReferenceIndex index;
    private final RuntimeAgentWorkflowUsageQuery agentUsage;

    @Transactional(readOnly = true)
    public Evidence inspect(Query query) {
        if (query == null || query.projectCode() == null || query.capabilities() == null
                || query.capabilities().size() > 500 || query.projectCode().isBlank()
                || query.agentConfigVersionIds() != null && (query.agentConfigVersionIds().size() > LIMIT
                || query.agentConfigVersionIds().stream().anyMatch(id -> id == null || id < 1))) {
            throw new IllegalArgumentException("能力引用查询范围无效");
        }
        Map<String, String> aliases = new HashMap<>();
        for (CapabilityKey key : query.capabilities()) {
            if (key == null || key.qualifiedName() == null || key.qualifiedName().length() > 256
                    || !key.qualifiedName().startsWith(query.projectCode() + ":")) {
                throw new IllegalArgumentException("能力引用不属于当前项目");
            }
            alias(aliases, key.qualifiedName(), key.qualifiedName());
            if (key.storageName() != null && !key.storageName().isBlank()) alias(aliases, key.storageName(), key.qualifiedName());
        }
        var workflowEvidence = index.inspect(aliases.keySet());
        var agentEvidence = agentUsage.publishedBindings(query.agentConfigVersionIds());
        var warnings = new LinkedHashSet<>(workflowEvidence.warnings());
        warnings.addAll(agentEvidence.warnings());
        Set<Usage> result = new LinkedHashSet<>();
        for (var hit : workflowEvidence.hits()) {
            String name = aliases.get(hit.referenceKey());
            if (name == null) continue;
            if (!append(result, warnings, new Usage(name, "WORKFLOW", hit.workflowId(), hit.workflowName(), hit.versionId(), hit.version(),
                    hit.versionId() == null ? "DRAFT" : "ACTIVE".equals(hit.status()) ? "PUBLISHED" : "HISTORICAL",
                    hit.nodeId(), hit.workflowId(), null))) break;
        }
        var hitsByVersion = workflowEvidence.hits().stream().filter(hit -> hit.versionId() != null)
                .collect(Collectors.groupingBy(RuntimeWorkflowReferenceIndex.Hit::versionId));
        var versionIds = agentEvidence.bindings().stream().map(RuntimeAgentWorkflowUsageQuery.Binding::workflowVersionId)
                .filter(Objects::nonNull).distinct().toList();
        var ownerByVersion = index.versionOwners(versionIds);
        agentBindings:
        for (var binding : agentEvidence.bindings().stream().distinct().toList()) {
            if (!ownerByVersion.containsKey(binding.workflowVersionId())
                    || !Objects.equals(binding.workflowId(), ownerByVersion.get(binding.workflowVersionId()))) {
                warnings.add("AGENT_WORKFLOW_VERSION_MISSING"); continue;
            }
            for (var hit : hitsByVersion.getOrDefault(binding.workflowVersionId(), List.of())) {
                String name = aliases.get(hit.referenceKey());
                if (name != null && !append(result, warnings, new Usage(name, "AGENT", binding.agentId(), binding.agentName(),
                        hit.versionId(), hit.version(), binding.active() ? "PUBLISHED" : "EXTERNAL_PIN",
                        hit.nodeId(), hit.workflowId(), binding.configVersionId()))) break agentBindings;
            }
        }
        return new Evidence(warnings.isEmpty() ? "COMPLETE" : "PARTIAL", Instant.now().toString(),
                List.copyOf(result), List.copyOf(warnings));
    }

    private static boolean append(Set<Usage> result, Set<String> warnings, Usage usage) {
        if (result.contains(usage)) return true;
        if (result.size() >= LIMIT) {
            warnings.add("REFERENCE_SCAN_LIMIT");
            return false;
        }
        result.add(usage);
        return true;
    }

    private static void alias(Map<String, String> aliases, String alias, String qualifiedName) {
        String previous = aliases.putIfAbsent(alias, qualifiedName);
        if (previous != null && !previous.equals(qualifiedName)) throw new IllegalArgumentException("能力引用别名存在冲突");
    }

    public record Query(String projectCode, List<CapabilityKey> capabilities, List<Long> agentConfigVersionIds) { }
    public record CapabilityKey(String qualifiedName, String storageName) { }
    public record Evidence(String state, String checkedAt, List<Usage> usages, List<String> warnings) { }
    public record Usage(String qualifiedName, String kind, String id, String name, Long versionId,
                        String version, String stage, String nodeId, String workflowId, Long agentConfigVersionId) { }
}
