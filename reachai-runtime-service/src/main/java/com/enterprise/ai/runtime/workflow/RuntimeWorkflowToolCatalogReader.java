package com.enterprise.ai.runtime.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.enterprise.ai.agent.graph.AgentGraphNodeType;
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
                RuntimeWorkflowSchemaResolver.outputSchemaJson(json, null, version), httpApiRiskFloor(version),
                businessMethodRiskFloor(version));
        return new Entry(metadata, contract);
    }

    private String httpApiRiskFloor(RuntimeWorkflowVersionEntity version) {
        if (version == null || !StringUtils.hasText(version.getSnapshotJson())) return null;
        try {
            return "WRITE".equals(json.readTree(version.getSnapshotJson()).path("httpApiRiskFloor").asText()) ? "WRITE" : null;
        } catch (Exception invalid) { throw new IllegalArgumentException("WORKFLOW_PUBLISHED_RISK_INVALID", invalid); }
    }

    private String businessMethodRiskFloor(RuntimeWorkflowVersionEntity version) {
        try {
            var snapshot = StringUtils.hasText(version.getSnapshotJson())
                    ? json.readTree(version.getSnapshotJson()) : json.createObjectNode();
            var floor = snapshot.path("businessMethodRiskFloor");
            if (!floor.isMissingNode() && !floor.isNull()) {
                String value = floor.asText();
                if (!"READ".equals(value) && !"WRITE".equals(value) && !"IRREVERSIBLE".equals(value))
                    throw new IllegalArgumentException("WORKFLOW_PUBLISHED_RISK_INVALID");
                return value;
            }
            // Legacy versions never recorded a trusted business-method floor. Do not silently
            // allow a read-only Agent binding until the Workflow is republished with one.
            if (!StringUtils.hasText(version.getGraphSpecSnapshotJson())) return "WRITE";
            var nodes = json.readTree(version.getGraphSpecSnapshotJson()).path("nodes");
            if (!nodes.isArray()) return "WRITE";
            for (var node : nodes) {
                if (!"TOOL".equals(AgentGraphNodeType.normalize(node.path("type").asText()))) continue;
                String ref = node.path("ref").path("qualifiedName").asText();
                if (!ref.startsWith("http-api:") && !node.path("config").has("httpApiAssetId")) return "WRITE";
            }
            return null;
        } catch (Exception invalid) {
            throw new IllegalArgumentException("WORKFLOW_PUBLISHED_RISK_INVALID", invalid);
        }
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
