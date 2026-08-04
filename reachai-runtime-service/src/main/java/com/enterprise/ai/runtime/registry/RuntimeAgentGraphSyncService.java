package com.enterprise.ai.runtime.registry;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.registry.RuntimeAgentGraphSyncContracts.AgentGraphRegistration;
import com.enterprise.ai.runtime.registry.RuntimeAgentGraphSyncContracts.AgentGraphSyncItem;
import com.enterprise.ai.runtime.registry.RuntimeAgentGraphSyncContracts.AgentGraphSyncRequest;
import com.enterprise.ai.runtime.registry.RuntimeAgentGraphSyncContracts.AgentGraphSyncResponse;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionService;
import com.enterprise.ai.runtime.workflow.WorkflowSemanticValues;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RuntimeAgentGraphSyncService {

    private final RuntimeCapabilityCatalogClient capabilityClient;
    private final RuntimeWorkflowDefinitionService workflowDefinitionService;
    private final ObjectMapper objectMapper;

    @Transactional
    public AgentGraphSyncResponse sync(String projectCode, AgentGraphSyncRequest request) {
        ProjectRef project = getProject(projectCode);
        List<AgentGraphRegistration> graphs = request == null || request.graphs() == null
                ? List.of()
                : request.graphs();
        boolean apply = request == null || request.apply() == null || Boolean.TRUE.equals(request.apply());
        String syncId = StringUtils.hasText(request == null ? null : request.syncId())
                ? request.syncId().trim()
                : UUID.randomUUID().toString();

        int created = 0;
        int updated = 0;
        List<AgentGraphSyncItem> items = new ArrayList<>();
        for (AgentGraphRegistration graph : graphs) {
            GraphSpec spec = requireGraphSpec(graph);
            String graphCode = normalizeGraphCode(graph.code());
            String keySlug = agentKeySlug(project.projectCode(), graphCode);
            RuntimeWorkflowDefinitionEntity existing =
                    workflowDefinitionService.findByKeySlug(keySlug).orElse(null);
            String changeType = existing == null ? "CREATED" : "UPDATED";
            if (apply) {
                RuntimeWorkflowDefinitionEntity saved =
                        upsertSdkWorkflowGraph(project, graph, spec, graphCode, keySlug, syncId, existing);
                if (existing == null) {
                    created++;
                } else {
                    updated++;
                }
                items.add(new AgentGraphSyncItem(graphCode, saved.getId(), saved.getKeySlug(), changeType, "applied"));
            } else {
                items.add(new AgentGraphSyncItem(graphCode, existing == null ? null : existing.getId(),
                        keySlug, "CREATED".equals(changeType) ? "WOULD_CREATE" : "WOULD_UPDATE", "diff only"));
            }
        }
        return new AgentGraphSyncResponse(syncId, project.projectId(), project.projectCode(),
                graphs.size(), created, updated, items);
    }

    private RuntimeWorkflowDefinitionEntity upsertSdkWorkflowGraph(ProjectRef project,
                                                                   AgentGraphRegistration registration,
                                                                   GraphSpec spec,
                                                                   String graphCode,
                                                                   String keySlug,
                                                                   String syncId,
                                                                   RuntimeWorkflowDefinitionEntity existing) {
        validateGraphSpec(spec);
        String modelInstanceId = firstText(
                registration == null ? null : registration.modelInstanceId(),
                modelInstanceIdFromGraph(spec));
        if (!StringUtils.hasText(modelInstanceId)) {
            throw new IllegalArgumentException("SDK Agent Graph 缺少 modelInstanceId: " + graphCode);
        }

        Map<String, Object> sdkGraph = new LinkedHashMap<>();
        sdkGraph.put("source", "SDK");
        sdkGraph.put("projectCode", project.projectCode());
        sdkGraph.put("graphCode", graphCode);
        sdkGraph.put("syncId", syncId);
        sdkGraph.put("lastSyncedAt", LocalDateTime.now().toString());
        sdkGraph.put("overwriteMode", "DRAFT_ONLY");
        sdkGraph.put("sourceHash", Integer.toHexString(Objects.hash(writeJson(spec))));
        if (registration != null && registration.metadata() != null && !registration.metadata().isEmpty()) {
            sdkGraph.put("metadata", registration.metadata());
        }

        Map<String, Object> extra = new LinkedHashMap<>();
        if (existing != null && StringUtils.hasText(existing.getExtraJson())) {
            extra.put("previousExtraJson", existing.getExtraJson());
        }
        extra.put("overwriteMode", "DRAFT_ONLY");
        extra.put("sdkGraph", sdkGraph);

        RuntimeWorkflowDefinitionEntity draft = new RuntimeWorkflowDefinitionEntity();
        draft.setKeySlug(keySlug);
        draft.setName(firstText(registration == null ? null : registration.name(), graphCode));
        draft.setDescription(registration == null ? null : registration.description());
        draft.setProjectId(project.projectId());
        draft.setProjectCode(project.projectCode());
        draft.setWorkflowKind(WorkflowSemanticValues.KIND_GENERAL);
        draft.setExecutionEngine(WorkflowSemanticValues.normalizeExecutionEngine(firstText(
                registration == null ? null : registration.executionEngine(),
                WorkflowSemanticValues.ENGINE_GRAPH_SPEC)));
        draft.setGraphSpecJson(writeJson(spec));
        draft.setCanvasJson(canvasJsonFromGraphSpec(spec));
        draft.setInputSchemaJson(writeJson(spec.getInputSchema()));
        draft.setDefaultModelInstanceId(modelInstanceId);
        draft.setStatus(existing == null ? "DRAFT" : existing.getStatus());
        draft.setDefinitionAuthority(WorkflowSemanticValues.AUTHORITY_SDK);
        draft.setCreationChannel(WorkflowSemanticValues.CHANNEL_SDK_SYNC);
        draft.setExtraJson(writeJson(extra));
        if (existing == null) {
            return workflowDefinitionService.create(draft);
        }
        return workflowDefinitionService.update(existing.getId(), draft);
    }

    private ProjectRef getProject(String projectCode) {
        if (!StringUtils.hasText(projectCode)) {
            throw new IllegalArgumentException("projectCode is required");
        }
        Map<String, Object> body = capabilityClient.getProject(projectCode.trim());
        Long projectId = longValue(body.get("projectId"));
        String resolvedCode = firstText(stringValue(body.get("projectCode")), projectCode.trim());
        if (projectId == null || !StringUtils.hasText(resolvedCode)) {
            throw new IllegalArgumentException("Capability project lookup response is incomplete: " + projectCode);
        }
        return new ProjectRef(projectId, resolvedCode);
    }

    private GraphSpec requireGraphSpec(AgentGraphRegistration graph) {
        if (graph == null || graph.graphSpec() == null) {
            throw new IllegalArgumentException("SDK Agent Graph 缺少 graphSpec");
        }
        GraphSpec spec = graph.graphSpec();
        if (spec.getNodes() == null || spec.getNodes().isEmpty()) {
            throw new IllegalArgumentException("SDK Agent Graph 缺少 nodes");
        }
        boolean hasLlm = spec.getNodes().stream()
                .anyMatch(node -> "LLM".equalsIgnoreCase(node.getType()));
        if (!hasLlm) {
            throw new IllegalArgumentException("SDK Agent Graph 必须包含 LLM 节点");
        }
        return spec;
    }

    private void validateGraphSpec(GraphSpec spec) {
        if (!Integer.valueOf(2).equals(spec.getSchemaVersion())) {
            throw new IllegalArgumentException("SDK Agent Graph schemaVersion 必须为 2");
        }
        if (!StringUtils.hasText(spec.getEntryNodeId())) {
            throw new IllegalArgumentException("SDK Agent Graph 缺少 entryNodeId");
        }
        if (spec.getExitNodeIds().isEmpty()) {
            throw new IllegalArgumentException("SDK Agent Graph 缺少 exitNodeIds");
        }
        if (spec.getNodes().stream().anyMatch(node -> node != null && isBoundaryNode(node))
                || (spec.getEdges() != null && spec.getEdges().stream().anyMatch(edge -> edge != null
                && (isBoundaryEndpoint(edge.getFrom()) || isBoundaryEndpoint(edge.getTo()))))) {
            throw new IllegalArgumentException("SDK Agent Graph 不允许 START/END 边界节点");
        }
    }

    private String canvasJsonFromGraphSpec(GraphSpec spec) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        nodes.add(layoutNode("start", 60, 220, false));
        int index = 0;
        for (GraphSpec.Node graphNode : spec.getNodes() == null ? List.<GraphSpec.Node>of() : spec.getNodes()) {
            int x = 260 + (index * 220);
            int y = 220;
            nodes.add(layoutNode(
                    graphNode.getId(),
                    x,
                    y,
                    false));
            index++;
        }
        nodes.add(layoutNode("end", 260 + (Math.max(index, 1) * 220), 220, false));
        int edgeIndex = 0;
        for (GraphSpec.Edge graphEdge : spec.getEdges() == null ? List.<GraphSpec.Edge>of() : spec.getEdges()) {
            String condition = firstText(graphEdge.getCondition(), "always");
            Map<String, Object> edge = new LinkedHashMap<>();
            edge.put("id", firstText(graphEdge.getId(), "sdk-e-" + edgeIndex++));
            edge.put("label", condition);
            edge.put("style", "smoothstep");
            edges.add(edge);
        }
        return writeJson(Map.of(
                "schemaVersion", 1,
                "layoutVersion", 1,
                "nodes", nodes,
                "edges", edges));
    }

    private Map<String, Object> layoutNode(String id, int x, int y, boolean collapsed) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("position", Map.of("x", x, "y", y));
        if (collapsed) node.put("collapsed", true);
        return node;
    }

    private String modelInstanceIdFromGraph(GraphSpec spec) {
        for (GraphSpec.Node node : spec.getNodes() == null ? List.<GraphSpec.Node>of() : spec.getNodes()) {
            if (!"LLM".equalsIgnoreCase(node.getType()) || node.getConfig() == null) {
                continue;
            }
            Object model = node.getConfig().get("modelInstanceId");
            if (model != null && StringUtils.hasText(String.valueOf(model))) {
                return String.valueOf(model);
            }
        }
        return null;
    }

    private boolean isBoundaryNode(GraphSpec.Node node) {
        return isBoundaryEndpoint(node.getId())
                || isStartBoundary(node.getType())
                || isEndBoundary(node.getType());
    }

    private boolean isBoundaryEndpoint(String value) {
        return isStartBoundary(value) || isEndBoundary(value);
    }

    private boolean isStartBoundary(String value) {
        return "START".equalsIgnoreCase(value);
    }

    private boolean isEndBoundary(String value) {
        return "END".equalsIgnoreCase(value);
    }

    private String normalizeGraphCode(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("SDK Agent Graph 缺少 code");
        }
        return value.trim();
    }

    private String agentKeySlug(String projectCode, String graphCode) {
        return (projectCode + "_" + graphCode)
                .replaceAll("[^A-Za-z0-9_-]", "_")
                .replaceAll("_+", "_");
    }

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            return Long.parseLong(text.trim());
        }
        return null;
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("JSON 序列化失败: " + ex.getMessage(), ex);
        }
    }

    private record ProjectRef(Long projectId, String projectCode) {
    }
}
