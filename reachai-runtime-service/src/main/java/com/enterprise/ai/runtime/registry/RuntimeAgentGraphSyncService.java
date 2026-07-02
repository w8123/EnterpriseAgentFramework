package com.enterprise.ai.runtime.registry;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.registry.RuntimeAgentGraphSyncContracts.AgentGraphRegistration;
import com.enterprise.ai.runtime.registry.RuntimeAgentGraphSyncContracts.AgentGraphSyncItem;
import com.enterprise.ai.runtime.registry.RuntimeAgentGraphSyncContracts.AgentGraphSyncRequest;
import com.enterprise.ai.runtime.registry.RuntimeAgentGraphSyncContracts.AgentGraphSyncResponse;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionEntity;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDefinitionService;
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
import java.util.Locale;
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
            String graphCode = normalizeGraphCode(firstText(graph.code(), spec.getCode()));
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
        normalizeGraphSpec(registration, spec, graphCode);
        String modelInstanceId = firstText(
                registration == null ? null : registration.modelInstanceId(),
                modelInstanceIdFromGraph(spec));
        if (!StringUtils.hasText(modelInstanceId)) {
            throw new IllegalArgumentException("SDK Agent Graph 缺少 modelInstanceId: " + graphCode);
        }

        Map<String, Object> sdkGraph = new LinkedHashMap<>();
        sdkGraph.put("managedBy", "SDK");
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
        extra.put("managedBy", "SDK");
        extra.put("overwriteMode", "DRAFT_ONLY");
        extra.put("sdkGraph", sdkGraph);

        RuntimeWorkflowDefinitionEntity draft = new RuntimeWorkflowDefinitionEntity();
        draft.setKeySlug(keySlug);
        draft.setName(firstText(registration == null ? null : registration.name(), spec.getName(), graphCode));
        draft.setDescription(registration == null ? null : registration.description());
        draft.setProjectId(project.projectId());
        draft.setProjectCode(project.projectCode());
        draft.setWorkflowType("SDK_GRAPH");
        draft.setRuntimeType(firstText(registration == null ? null : registration.runtimeType(),
                spec.getRuntimeHint(), "LANGGRAPH4J"));
        draft.setGraphSpecJson(writeJson(spec));
        draft.setCanvasJson(canvasJsonFromGraphSpec(spec));
        draft.setInputSchemaJson(writeJson(spec.getInputSchema()));
        draft.setDefaultModelInstanceId(modelInstanceId);
        draft.setStatus(existing == null ? "DRAFT" : existing.getStatus());
        draft.setManagedBy("SDK");
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

    private void normalizeGraphSpec(AgentGraphRegistration registration, GraphSpec spec, String graphCode) {
        if (!StringUtils.hasText(spec.getCode())) {
            spec.setCode(graphCode);
        }
        if (!StringUtils.hasText(spec.getName())) {
            spec.setName(firstText(registration == null ? null : registration.name(), graphCode));
        }
        if (!StringUtils.hasText(spec.getMode())) {
            spec.setMode("WORKFLOW");
        }
        if (!StringUtils.hasText(spec.getRuntimeHint())) {
            spec.setRuntimeHint("LANGGRAPH4J");
        }
        if (!StringUtils.hasText(spec.getEntry())) {
            spec.setEntry(firstExecutableNodeId(spec));
        }
        if (spec.getFinish() == null || spec.getFinish().isEmpty()) {
            spec.setFinish(List.of(lastExecutableNodeId(spec)));
        }
    }

    private String canvasJsonFromGraphSpec(GraphSpec spec) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        nodes.add(canvasNode("start", "start", 60, 220, Map.of("label", "开始", "kind", "start")));
        int index = 0;
        for (GraphSpec.Node graphNode : spec.getNodes() == null ? List.<GraphSpec.Node>of() : spec.getNodes()) {
            String kind = canvasKind(graphNode);
            Map<String, Object> config = graphNode.getConfig() == null ? Map.of() : graphNode.getConfig();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("label", firstText(graphNode.getName(), graphNode.getId()));
            data.put("kind", kind);
            data.put("configVersion", 2);
            data.put("source", "SDK");
            data.put("category", canvasCategory(kind));
            data.put("collapsed", graphNode.getLayout() != null && Boolean.TRUE.equals(graphNode.getLayout().getCollapsed()));
            data.put("inputs", graphNode.getInputs() == null ? List.of() : graphNode.getInputs());
            data.put("outputs", graphNode.getOutputs() == null ? List.of() : graphNode.getOutputs());
            data.put("description", firstText(graphNode.getDescription(), stringValue(config.get("description")), ""));
            applyCanvasNodeConfig(kind, data, config);
            int x = canvasPosition(graphNode, config, "x", 260 + (index * 220));
            int y = canvasPosition(graphNode, config, "y", 220);
            nodes.add(canvasNode(graphNode.getId(), kind, x, y, data));
            index++;
        }
        nodes.add(canvasNode("end", "end", 260 + (Math.max(index, 1) * 220), 220, Map.of("label", "结束", "kind", "end")));
        int edgeIndex = 0;
        for (GraphSpec.Edge graphEdge : spec.getEdges() == null ? List.<GraphSpec.Edge>of() : spec.getEdges()) {
            String condition = firstText(graphEdge.getCondition(), "always");
            Map<String, Object> edge = new LinkedHashMap<>();
            edge.put("id", "sdk-e-" + edgeIndex++);
            edge.put("source", canvasEndpoint(graphEdge.getFrom()));
            edge.put("target", canvasEndpoint(graphEdge.getTo()));
            edge.put("condition", condition);
            edge.put("label", condition);
            edge.put("type", "smoothstep");
            edge.put("markerEnd", "arrowclosed");
            edge.put("interactionWidth", 18);
            edge.put("animated", !"always".equalsIgnoreCase(condition) && !"default".equalsIgnoreCase(condition));
            edges.add(edge);
        }
        return writeJson(Map.of("version", 2, "nodes", nodes, "edges", edges));
    }

    private void applyCanvasNodeConfig(String kind, Map<String, Object> data, Map<String, Object> config) {
        if ("llm".equals(kind)) {
            data.put("llmConfig", Map.of(
                    "modelInstanceId", defaultText(stringValue(config.get("modelInstanceId")), ""),
                    "systemPrompt", defaultText(stringValue(config.get("systemPrompt")), ""),
                    "userPrompt", defaultText(stringValue(config.get("userPrompt")), "{{ input }}"),
                    "contextVariables", defaultObject(config.get("contextVariables"), List.of()),
                    "modelParams", defaultObject(config.get("modelParams"), Map.of()),
                    "outputFormat", defaultText(stringValue(config.get("outputFormat")), "text"),
                    "outputSchema", defaultObject(config.get("outputSchema"), List.of())
            ));
        } else if ("tool".equals(kind) && config.get("inputMapping") != null) {
            data.put("toolConfig", Map.of("inputMapping", config.get("inputMapping")));
        }
    }

    private int canvasPosition(GraphSpec.Node node, Map<String, Object> config, String axis, int fallback) {
        GraphSpec.Layout.NodeLayout layout = node.getLayout();
        if (layout != null) {
            Double value = "x".equals(axis) ? layout.getX() : layout.getY();
            if (value != null) {
                return value.intValue();
            }
        }
        Object ui = config.get("ui");
        if (!(ui instanceof Map<?, ?> uiMap)) {
            return fallback;
        }
        Object position = uiMap.get("position");
        if (!(position instanceof Map<?, ?> positionMap)) {
            return fallback;
        }
        Object raw = positionMap.get(axis);
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw instanceof String text && StringUtils.hasText(text)) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
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

    private String firstExecutableNodeId(GraphSpec spec) {
        return spec.getNodes().stream()
                .map(GraphSpec.Node::getId)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("SDK Agent Graph 缺少可执行节点"));
    }

    private String lastExecutableNodeId(GraphSpec spec) {
        List<GraphSpec.Node> nodes = spec.getNodes();
        for (int i = nodes.size() - 1; i >= 0; i--) {
            String id = nodes.get(i).getId();
            if (StringUtils.hasText(id)) {
                return id;
            }
        }
        throw new IllegalArgumentException("SDK Agent Graph 缺少可执行节点");
    }

    private Map<String, Object> canvasNode(String id, String type, int x, int y, Map<String, Object> data) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("type", type);
        node.put("position", Map.of("x", x, "y", y));
        node.put("data", data);
        return node;
    }

    private String canvasKind(GraphSpec.Node node) {
        String type = node == null ? "" : stringValue(node.getType()).trim().toUpperCase(Locale.ROOT);
        return switch (type) {
            case "LLM" -> "llm";
            case "IF_ELSE" -> "condition";
            case "INTENT_CLASSIFIER" -> "classifier";
            case "VARIABLE_ASSIGN" -> "variable";
            case "TEMPLATE" -> "template";
            case "USER_INPUT" -> "userInput";
            case "HTTP_REQUEST" -> "http";
            case "KNOWLEDGE_RETRIEVAL" -> "knowledge";
            default -> "tool";
        };
    }

    private String canvasCategory(String kind) {
        return switch (kind) {
            case "condition", "classifier", "variable", "template", "userInput" -> "flow";
            case "knowledge" -> "knowledge";
            case "http" -> "integration";
            default -> "action";
        };
    }

    private String canvasEndpoint(String endpoint) {
        if ("START".equalsIgnoreCase(endpoint)) {
            return "start";
        }
        if ("END".equalsIgnoreCase(endpoint)) {
            return "end";
        }
        return endpoint;
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

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private Object defaultObject(Object value, Object fallback) {
        return value == null ? fallback : value;
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
