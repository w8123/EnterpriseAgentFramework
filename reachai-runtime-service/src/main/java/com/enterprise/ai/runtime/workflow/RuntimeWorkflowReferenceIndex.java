package com.enterprise.ai.runtime.workflow;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.agent.graph.GraphSpecToolContract;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Transactional reverse index owned by Workflow; incomplete coverage never proves zero references. */
@Service
@RequiredArgsConstructor
public class RuntimeWorkflowReferenceIndex {
    private static final int LIMIT = 10_000;
    private final RuntimeWorkflowReferenceMapper references;
    private final RuntimeWorkflowDefinitionMapper workflows;
    private final RuntimeWorkflowVersionMapper versions;
    private final ObjectMapper json;

    /** Resolves version ownership for reference evidence, including retired pinned releases. */
    public Map<Long, String> versionOwners(Collection<Long> versionIds) {
        Map<Long, String> ownerByVersion = new HashMap<>();
        if (!versionIds.isEmpty()) {
            versions.selectList(Wrappers.<RuntimeWorkflowVersionEntity>lambdaQuery()
                    .select(RuntimeWorkflowVersionEntity::getId, RuntimeWorkflowVersionEntity::getWorkflowId)
                    .in(RuntimeWorkflowVersionEntity::getId, versionIds)).forEach(version ->
                    ownerByVersion.put(version.getId(), version.getWorkflowId()));
        }
        return Collections.unmodifiableMap(ownerByVersion);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void indexDraft(RuntimeWorkflowDefinitionEntity workflow) {
        replace(workflow.getId(), 0L, workflow.getGraphSpecJson(), workflow.getUpdatedAt(), false);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void indexVersion(RuntimeWorkflowVersionEntity version) {
        String graph = version.getGraphSpecSnapshotJson();
        if (graph == null || graph.isBlank()) {
            try {
                var snapshot = json.readTree(version.getSnapshotJson());
                var value = snapshot == null ? null : snapshot.get("graphSpec");
                graph = value == null ? null : value.isTextual() ? value.asText() : value.toString();
            } catch (Exception invalid) { graph = null; }
        }
        replace(version.getWorkflowId(), version.getId(), graph, null, true);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void delete(String workflowId) {
        references.delete(Wrappers.<RuntimeWorkflowReferenceEntity>lambdaQuery()
                .eq(RuntimeWorkflowReferenceEntity::getWorkflowId, workflowId));
    }

    @Transactional
    public void rebuildDraft(String id) {
        var workflow = workflows.selectForRelease(id);
        if (workflow != null) indexDraft(workflow);
    }

    @Transactional
    public void rebuildVersion(Long id) {
        var version = versions.selectOne(Wrappers.<RuntimeWorkflowVersionEntity>lambdaQuery()
                .eq(RuntimeWorkflowVersionEntity::getId, id).last("for update"));
        if (version != null) indexVersion(version);
    }

    @Transactional(readOnly = true)
    public Evidence inspect(Collection<String> keys) {
        Set<String> warnings = new LinkedHashSet<>();
        if (!references.missingDraftIds(1).isEmpty() || !references.missingVersionIds(1).isEmpty()) {
            warnings.add("REFERENCE_INDEX_INCOMPLETE");
        }
        var incomplete = references.incompleteWarnings(LIMIT + 1);
        if (incomplete.size() > LIMIT) warnings.add("REFERENCE_SCAN_LIMIT");
        for (String raw : incomplete.stream().limit(LIMIT).toList()) {
            try { warnings.addAll(json.readValue(raw, new TypeReference<List<String>>() { })); }
            catch (Exception invalid) { warnings.add("REFERENCE_INDEX_UNREADABLE"); }
        }
        var rows = keys.isEmpty() ? List.<RuntimeWorkflowReferenceMapper.UsageRow>of() : references.findUsages(keys, LIMIT + 1);
        if (rows.size() > LIMIT) warnings.add("REFERENCE_SCAN_LIMIT");
        var hits = rows.stream().limit(LIMIT).map(row -> new Hit(row.getReferenceKey(), row.getWorkflowId(),
                row.getWorkflowName(), row.getVersionId(), row.getVersion(), row.getStatus(), row.getNodeId())).toList();
        return new Evidence(hits, warnings);
    }

    private void replace(String workflowId, Long versionId, String graphJson, LocalDateTime revision, boolean published) {
        List<RuntimeWorkflowReferenceEntity> rows = new ArrayList<>();
        Set<String> warnings = new LinkedHashSet<>();
        if (graphJson == null || graphJson.isBlank()) {
            if (published) warnings.add("PUBLISHED_GRAPH_MISSING");
        } else {
            try {
                var graph = json.readValue(graphJson, GraphSpec.class);
                if (graph == null || graph.getNodes() == null) warnings.add("GRAPH_UNREADABLE");
                else for (int ordinal = 0; ordinal < graph.getNodes().size(); ordinal++) {
                    var node = graph.getNodes().get(ordinal);
                    if (node == null) { warnings.add("GRAPH_UNREADABLE"); continue; }
                    if (!"TOOL".equals(node.getType())) continue;
                    String ref = GraphSpecToolContract.resolve(node);
                    if (ref == null || ref.contains("${") || ref.contains("{{") || ref.length() > 256) {
                        warnings.add("UNRESOLVED_TOOL_REFERENCE"); continue;
                    }
                    var row = row(workflowId, versionId, ordinal, revision);
                    row.setNodeId(node.getId()); row.setReferenceKey(ref); rows.add(row);
                }
            } catch (Exception invalid) { warnings.add("GRAPH_UNREADABLE"); }
        }
        var header = row(workflowId, versionId, -1, revision);
        header.setState(warnings.isEmpty() ? "READY" : "PARTIAL");
        try { header.setWarningsJson(json.writeValueAsString(warnings)); }
        catch (Exception invalid) { throw new IllegalStateException("引用索引状态无法序列化", invalid); }
        rows.add(header);
        references.delete(Wrappers.<RuntimeWorkflowReferenceEntity>lambdaQuery()
                .eq(RuntimeWorkflowReferenceEntity::getWorkflowId, workflowId)
                .eq(RuntimeWorkflowReferenceEntity::getWorkflowVersionId, versionId));
        for (int offset = 0; offset < rows.size(); offset += 250) {
            references.insertBatch(rows.subList(offset, Math.min(offset + 250, rows.size())));
        }
    }

    private RuntimeWorkflowReferenceEntity row(String workflowId, Long versionId, int ordinal, LocalDateTime revision) {
        var row = new RuntimeWorkflowReferenceEntity(); row.setWorkflowId(workflowId); row.setWorkflowVersionId(versionId);
        row.setNodeOrdinal(ordinal); row.setIndexedRevision(revision); row.setState("READY"); return row;
    }

    public record Hit(String referenceKey, String workflowId, String workflowName, Long versionId,
                      String version, String status, String nodeId) { }
    public record Evidence(List<Hit> hits, Set<String> warnings) {
        public Evidence { hits = List.copyOf(hits); warnings = Set.copyOf(warnings); }
    }
}
