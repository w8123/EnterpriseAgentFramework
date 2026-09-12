package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Workflow owns SDK source identity, draft replacement and its transactional reference index. */
@Service
@RequiredArgsConstructor
public class RuntimeSdkWorkflowSyncService {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private final RuntimeWorkflowDefinitionService workflows;
    private final RuntimeWorkflowDocumentCanonicalizer documents;
    private final ObjectMapper json;

    /** Serialized documents keep mutable protocol GraphSpec and metadata objects outside this boundary. */
    public record SyncGraph(String code, String name, String description, String executionEngine,
                            String modelInstanceId, String graphSpecJson, String metadataJson) { }

    /** In preview mode newWorkflow describes the proposed creation, with no persisted ID. */
    public record GraphReceipt(String graphCode, String workflowId, String keySlug, boolean newWorkflow) { }

    @Transactional
    public List<GraphReceipt> sync(Long projectId, String projectCode, String syncId,
                                   boolean apply, List<SyncGraph> graphs) {
        if (projectId == null || projectId <= 0 || !StringUtils.hasText(projectCode) || !StringUtils.hasText(syncId)) {
            throw new IllegalArgumentException("SDK Workflow sync requires a resolved project and syncId");
        }
        String code = projectCode.trim();
        var prepared = new LinkedHashMap<String, Prepared>();
        for (SyncGraph graph : graphs == null ? List.<SyncGraph>of() : List.copyOf(graphs)) {
            Prepared item = prepare(code, graph);
            if (prepared.putIfAbsent(item.keySlug(), item) != null) {
                throw new IllegalArgumentException("SDK 图编码映射到重复的 Workflow keySlug: " + item.keySlug());
            }
        }
        // Stable lock order avoids taking existing Workflow locks in opposite batch orders.
        var results = new LinkedHashMap<String, GraphReceipt>();
        for (Prepared item : prepared.values().stream().sorted(Comparator.comparing(Prepared::keySlug)).toList()) {
            var existing = workflows.findByKeySlug(item.keySlug()).orElse(null);
            if (existing != null && apply) existing = workflows.lockForWrite(existing.getId());
            if (existing != null) requireSource(existing, projectId, code, item);
            if (!apply) {
                results.put(item.keySlug(), new GraphReceipt(item.graphCode(),
                        existing == null ? null : existing.getId(), item.keySlug(), existing == null));
                continue;
            }
            var draft = draft(projectId, code, syncId.trim(), item, existing);
            RuntimeWorkflowDefinitionEntity saved;
            try {
                saved = existing == null ? workflows.create(draft)
                        : workflows.update(existing.getId(), draft, existing.getUpdatedAt() == null
                                ? null : existing.getUpdatedAt().toString());
            } catch (RuntimeWorkflowKeySlugConflictException conflict) {
                throw new IllegalArgumentException("SDK Workflow keySlug 已被并发同步或其他来源占用: " + item.keySlug(), conflict);
            }
            results.put(item.keySlug(), new GraphReceipt(item.graphCode(), saved.getId(), saved.getKeySlug(), existing == null));
        }
        return prepared.keySet().stream().map(results::get).toList();
    }

    private Prepared prepare(String projectCode, SyncGraph registration) {
        if (registration == null || !StringUtils.hasText(registration.graphSpecJson())) {
            throw new IllegalArgumentException("SDK Agent Graph 缺少 graphSpec");
        }
        String graphCode = text(registration.code());
        if (graphCode == null) throw new IllegalArgumentException("SDK Agent Graph 缺少 code");
        String keySlug = (projectCode + "_" + graphCode).replaceAll("[^A-Za-z0-9_-]", "_").replaceAll("_+", "_");
        workflows.requireValidKeySlug(keySlug);
        String canonical = documents.canonicalizeGraphSpecJson(registration.graphSpecJson());
        GraphSpec spec;
        try { spec = json.readValue(canonical, GraphSpec.class); }
        catch (Exception invalid) { throw new IllegalArgumentException("SDK Agent Graph 文档无效: " + graphCode, invalid); }
        if (spec.getNodes() == null || spec.getNodes().isEmpty()
                || spec.getNodes().stream().noneMatch(node -> "LLM".equals(node.getType()))) {
            throw new IllegalArgumentException("SDK Agent Graph 必须包含 LLM 节点");
        }
        if (spec.getNodes().stream().anyMatch(node -> !StringUtils.hasText(node.getId()))) {
            throw new IllegalArgumentException("SDK Agent Graph 节点缺少 id");
        }
        if (!StringUtils.hasText(spec.getEntryNodeId()) || spec.getExitNodeIds().isEmpty()) {
            throw new IllegalArgumentException("SDK Agent Graph 缺少 entryNodeId 或 exitNodeIds");
        }
        String model = firstText(registration.modelInstanceId(), modelInstanceId(spec));
        if (model == null) throw new IllegalArgumentException("SDK Agent Graph 缺少 modelInstanceId: " + graphCode);
        return new Prepared(graphCode, keySlug, firstText(registration.name(), graphCode), registration.description(),
                WorkflowSemanticValues.normalizeExecutionEngine(firstText(registration.executionEngine(), WorkflowSemanticValues.ENGINE_GRAPH_SPEC)),
                model, canonical, layout(spec), write(spec.getInputSchema()), readMap(registration.metadataJson(), "SDK metadata"));
    }

    private void requireSource(RuntimeWorkflowDefinitionEntity current, Long projectId, String projectCode, Prepared item) {
        Map<String, Object> extra = readMap(current.getExtraJson(), "Workflow 来源元数据");
        Map<?, ?> source = extra.get("sdkGraph") instanceof Map<?, ?> value ? value : Map.of();
        if (!Objects.equals(current.getProjectId(), projectId) || !Objects.equals(current.getProjectCode(), projectCode)
                || !item.keySlug().equals(current.getKeySlug())
                || !WorkflowSemanticValues.AUTHORITY_SDK.equals(current.getDefinitionAuthority())
                || !WorkflowSemanticValues.CHANNEL_SDK_SYNC.equals(current.getCreationChannel())
                || !"SDK".equals(source.get("source")) || !projectCode.equals(source.get("projectCode"))
                || !item.graphCode().equals(source.get("graphCode"))) {
            throw new IllegalArgumentException("SDK Workflow 来源与现有定义不一致: " + item.keySlug());
        }
    }

    private RuntimeWorkflowDefinitionEntity draft(Long projectId, String projectCode, String syncId,
                                                   Prepared item, RuntimeWorkflowDefinitionEntity existing) {
        var sdk = new LinkedHashMap<String, Object>();
        sdk.put("source", "SDK"); sdk.put("projectCode", projectCode); sdk.put("graphCode", item.graphCode());
        sdk.put("syncId", syncId); sdk.put("lastSyncedAt", LocalDateTime.now().toString());
        sdk.put("overwriteMode", "DRAFT_ONLY");
        sdk.put("sourceHash", Integer.toHexString(Objects.hash(item.graphSpecJson())));
        if (!item.metadata().isEmpty()) sdk.put("metadata", item.metadata());
        Map<String, Object> extra = existing == null ? new LinkedHashMap<>() : readMap(existing.getExtraJson(), "Workflow 元数据");
        // This former recursive copy is not release history; published snapshots remain in the version table.
        extra.remove("previousExtraJson");
        extra.put("overwriteMode", "DRAFT_ONLY"); extra.put("sdkGraph", sdk);
        var draft = new RuntimeWorkflowDefinitionEntity();
        draft.setKeySlug(item.keySlug()); draft.setName(item.name()); draft.setDescription(item.description());
        draft.setProjectId(projectId); draft.setProjectCode(projectCode);
        draft.setWorkflowKind(WorkflowSemanticValues.KIND_GENERAL); draft.setExecutionEngine(item.executionEngine());
        draft.setGraphSpecJson(item.graphSpecJson()); draft.setCanvasJson(item.canvasJson());
        draft.setInputSchemaJson(item.inputSchemaJson()); draft.setDefaultModelInstanceId(item.modelInstanceId());
        if (existing == null) draft.setStatus("DRAFT");
        draft.setDefinitionAuthority(WorkflowSemanticValues.AUTHORITY_SDK);
        draft.setCreationChannel(WorkflowSemanticValues.CHANNEL_SDK_SYNC); draft.setExtraJson(write(extra));
        return draft;
    }

    private String layout(GraphSpec spec) {
        var nodes = new ArrayList<Map<String, Object>>();
        nodes.add(layoutNode("start", 60));
        int index = 0;
        for (var node : spec.getNodes()) nodes.add(layoutNode(node.getId(), 260 + index++ * 220));
        nodes.add(layoutNode("end", 260 + Math.max(index, 1) * 220));
        var edges = new ArrayList<Map<String, Object>>();
        int edgeIndex = 0;
        for (var edge : spec.getEdges()) {
            edges.add(Map.of("id", firstText(edge.getId(), "sdk-e-" + edgeIndex++),
                    "label", firstText(edge.getCondition(), "always"), "style", "smoothstep"));
        }
        return write(Map.of("schemaVersion", 1, "layoutVersion", 1, "nodes", nodes, "edges", edges));
    }

    private Map<String, Object> layoutNode(String id, int x) {
        return Map.of("id", id, "position", Map.of("x", x, "y", 220));
    }

    private String modelInstanceId(GraphSpec spec) {
        for (var node : spec.getNodes()) {
            if (!"LLM".equals(node.getType()) || node.getConfig() == null) continue;
            Object model = node.getConfig().get("modelInstanceId");
            String value = model == null ? null : text(String.valueOf(model));
            if (value != null) return value;
        }
        return null;
    }

    private Map<String, Object> readMap(String value, String name) {
        if (!StringUtils.hasText(value)) return new LinkedHashMap<>();
        try {
            Map<String, Object> result = json.readValue(value, MAP);
            if (result == null) throw new IllegalArgumentException(name + " 必须为 JSON object");
            return new LinkedHashMap<>(result);
        } catch (Exception invalid) { throw new IllegalArgumentException(name + " 无法解析", invalid); }
    }

    private String write(Object value) {
        if (value == null) return null;
        try { return json.writeValueAsString(value); }
        catch (Exception invalid) { throw new IllegalArgumentException("SDK Workflow JSON 序列化失败", invalid); }
    }

    private String text(String value) { return StringUtils.hasText(value) ? value.trim() : null; }
    private String firstText(String a, String b) { return text(a) == null ? text(b) : text(a); }

    private record Prepared(String graphCode, String keySlug, String name, String description, String executionEngine,
                            String modelInstanceId, String graphSpecJson, String canvasJson, String inputSchemaJson,
                            Map<String, Object> metadata) { }
}
