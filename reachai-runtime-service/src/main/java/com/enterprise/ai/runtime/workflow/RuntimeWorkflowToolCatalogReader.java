package com.enterprise.ai.runtime.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

@Component
@RequiredArgsConstructor
public class RuntimeWorkflowToolCatalogReader implements RuntimeWorkflowToolCatalogQuery {
    private static final int BATCH_SIZE = 500;
    private final RuntimeWorkflowDefinitionMapper workflows;
    private final RuntimeWorkflowVersionMapper versions;
    private final ObjectMapper json;

    @Override
    @Transactional(readOnly = true)
    public Map<String, Entry> current(List<String> workflowIds) {
        if (workflowIds == null || workflowIds.isEmpty()) return Map.of();
        var ids = workflowIds.stream().filter(StringUtils::hasText).distinct().toList();
        var definitions = batches(ids, workflows::selectBatchIds, RuntimeWorkflowDefinitionEntity::getId);
        // The mapper retains the established rollout-percent / version-id preference.
        var active = batches(ids, versions::listActiveByWorkflowIds, RuntimeWorkflowVersionEntity::getWorkflowId);
        var result = new LinkedHashMap<String, Entry>();
        for (String id : ids) {
            var definition = definitions.get(id);
            var version = active.get(id);
            result.put(id, entry(definition, isPublished(version) && "ACTIVE".equalsIgnoreCase(version.getStatus())
                    ? version : null));
        }
        return Collections.unmodifiableMap(result);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Reference, Entry> pinned(List<Reference> references) {
        if (references == null || references.isEmpty()) return Map.of();
        if (references.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("Workflow reference is required");
        var ids = references.stream().map(Reference::workflowId).filter(StringUtils::hasText).distinct().toList();
        var versionIds = references.stream().map(Reference::versionId).filter(Objects::nonNull).distinct().toList();
        var definitions = batches(ids, workflows::selectBatchIds, RuntimeWorkflowDefinitionEntity::getId);
        var published = batches(versionIds, versions::selectBatchIds, RuntimeWorkflowVersionEntity::getId);
        var result = new LinkedHashMap<Reference, Entry>();
        for (Reference reference : references) {
            var definition = definitions.get(reference.workflowId());
            var version = published.get(reference.versionId());
            boolean belongs = definition != null && version != null && definition.getId().equals(version.getWorkflowId());
            result.put(reference, entry(definition, belongs && isPublished(version) ? version : null));
        }
        return Collections.unmodifiableMap(result);
    }

    private Entry entry(RuntimeWorkflowDefinitionEntity workflow, RuntimeWorkflowVersionEntity version) {
        if (workflow == null) return new Entry(null, null);
        var metadata = new Workflow(workflow.getId(), workflow.getKeySlug(), workflow.getName(), workflow.getDescription(),
                "ACTIVE".equalsIgnoreCase(workflow.getStatus()));
        var contract = version == null ? null : new Version(version.getId(), version.getVersion(),
                StringUtils.hasText(version.getGraphSpecSnapshotJson()),
                RuntimeWorkflowSchemaResolver.inputSchemaJson(json, null, version),
                RuntimeWorkflowSchemaResolver.outputSchemaJson(json, null, version));
        return new Entry(metadata, contract);
    }

    private boolean isPublished(RuntimeWorkflowVersionEntity version) {
        return version != null && ("ACTIVE".equalsIgnoreCase(version.getStatus())
                || "RETIRED".equalsIgnoreCase(version.getStatus()));
    }

    private <K, T> Map<K, T> batches(List<K> ids, Function<List<K>, List<T>> query, Function<T, K> key) {
        var result = new LinkedHashMap<K, T>();
        for (int start = 0; start < ids.size(); start += BATCH_SIZE) {
            for (T value : query.apply(ids.subList(start, Math.min(start + BATCH_SIZE, ids.size())))) {
                result.putIfAbsent(key.apply(value), value);
            }
        }
        return result;
    }
}
