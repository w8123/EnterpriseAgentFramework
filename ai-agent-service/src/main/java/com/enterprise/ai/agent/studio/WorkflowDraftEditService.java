package com.enterprise.ai.agent.studio;

import com.enterprise.ai.agent.graph.AgentGraphNodeType;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.agent.llm.LlmService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class WorkflowDraftEditService {

    private static final String PROVIDER = "LLM_PATCH";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final Set<String> NODE_DATA_PATCH_KEYS = Set.of(
            "label",
            "kind",
            "configVersion",
            "description",
            "source",
            "category",
            "collapsed",
            "inputs",
            "outputs",
            "inputSchema",
            "outputSchema",
            "inputMapping",
            "retry",
            "errorPolicy",
            "outputAlias",
            "needsConfiguration",
            "placeholderReason",
            "userInputConfig",
            "llmConfig",
            "knowledgeConfig",
            "httpConfig",
            "parameterConfig",
            "conditionConfig",
            "answerConfig",
            "codeConfig",
            "classifierConfig",
            "aggregateConfig",
            "approvalConfig",
            "loopConfig",
            "knowledgeWriteConfig",
            "documentExtractConfig",
            "mcpConfig",
            "toolConfig",
            "assignments",
            "template",
            "writeToAnswer"
    );

    private final ObjectMapper objectMapper;
    private final LlmService llmService;

    public WorkflowDraftEditResult edit(WorkflowDraftEditRequest request) {
        Map<String, Object> canvas = mutableCanvas(request == null ? null : request.getCurrentCanvas());
        List<String> validationErrors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<WorkflowDraftEditOperation> operations = List.of();
        String summary = "";

        if (request == null || !StringUtils.hasText(request.getInstruction())) {
            validationErrors.add("instruction is required");
            return result(summary, operations, canvas, warnings, validationErrors);
        }
        if (!StringUtils.hasText(request.getModelInstanceId())) {
            validationErrors.add("modelInstanceId is required");
            return result(summary, operations, canvas, warnings, validationErrors);
        }

        String raw = llmService.chat(systemPrompt(), userPrompt(request, canvas), request.getModelInstanceId());
        try {
            JsonNode root = objectMapper.readTree(extractJsonObject(raw));
            summary = root.path("summary").asText("");
            operations = parseOperations(root.path("operations"));
        } catch (Exception ex) {
            validationErrors.add("AI did not return valid JSON patch: " + ex.getMessage());
            return result(summary, operations, canvas, warnings, validationErrors);
        }

        applyOperations(canvas, operations, validationErrors);
        warnings.addAll(buildWarnings(canvas));
        return result(summary, operations, canvas, warnings, validationErrors);
    }

    private WorkflowDraftEditResult result(String summary,
                                           List<WorkflowDraftEditOperation> operations,
                                           Map<String, Object> canvas,
                                           List<String> warnings,
                                           List<String> validationErrors) {
        return new WorkflowDraftEditResult(
                PROVIDER,
                summary == null ? "" : summary,
                operations,
                canvas,
                toGraphSpec(canvas),
                warnings,
                placeholderNodes(canvas),
                validationErrors);
    }

    private List<WorkflowDraftEditOperation> parseOperations(JsonNode operationsNode) throws JsonProcessingException {
        if (!operationsNode.isArray()) {
            return List.of();
        }
        List<WorkflowDraftEditOperation> operations = new ArrayList<>();
        for (JsonNode item : operationsNode) {
            operations.add(objectMapper.treeToValue(item, WorkflowDraftEditOperation.class));
        }
        return operations;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mutableCanvas(Map<String, Object> rawCanvas) {
        Map<String, Object> copied = rawCanvas == null
                ? new LinkedHashMap<>()
                : objectMapper.convertValue(rawCanvas, MAP_TYPE);
        copied.putIfAbsent("version", 2);
        copied.putIfAbsent("nodes", new ArrayList<Map<String, Object>>());
        copied.putIfAbsent("edges", new ArrayList<Map<String, Object>>());
        copied.computeIfPresent("nodes", (key, value) -> mutableList(value));
        copied.computeIfPresent("edges", (key, value) -> mutableList(value));
        return copied;
    }

    private List<Object> mutableList(Object value) {
        if (!(value instanceof List<?> items)) {
            return new ArrayList<>();
        }
        List<Object> copied = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Map<?, ?> map) {
                copied.add(objectMapper.convertValue(map, MAP_TYPE));
            } else {
                copied.add(item);
            }
        }
        return copied;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> nodes(Map<String, Object> canvas) {
        return (List<Map<String, Object>>) (List<?>) canvas.computeIfAbsent("nodes", key -> new ArrayList<>());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> edges(Map<String, Object> canvas) {
        return (List<Map<String, Object>>) (List<?>) canvas.computeIfAbsent("edges", key -> new ArrayList<>());
    }

    private void applyOperations(Map<String, Object> canvas,
                                 List<WorkflowDraftEditOperation> operations,
                                 List<String> errors) {
        for (WorkflowDraftEditOperation operation : operations) {
            if (operation == null || operation.getType() == null) {
                errors.add("operation type is required");
                continue;
            }
            switch (operation.getType()) {
                case ADD_NODE -> addNode(canvas, operation, errors);
                case UPDATE_NODE -> updateNode(canvas, operation, errors);
                case DELETE_NODE -> deleteNode(canvas, operation, errors);
                case ADD_EDGE -> addEdge(canvas, operation, errors);
                case UPDATE_EDGE -> updateEdge(canvas, operation, errors);
                case DELETE_EDGE -> deleteEdge(canvas, operation, errors);
            }
        }
    }

    private void addNode(Map<String, Object> canvas, WorkflowDraftEditOperation operation, List<String> errors) {
        Map<String, Object> node = mutableMap(operation.getNode());
        String id = text(node.get("id"));
        Map<String, Object> data = mutableMap(node.get("data"));
        String kind = firstText(text(data.get("kind")), text(node.get("type")));
        if (!StringUtils.hasText(id) || !StringUtils.hasText(kind)) {
            errors.add("ADD_NODE requires node.id and node.data.kind/type");
            return;
        }
        if (findNode(canvas, id) != null) {
            errors.add("ADD_NODE references duplicate node id: " + id);
            return;
        }
        data.put("kind", kind);
        data.putIfAbsent("configVersion", 2);
        data.putIfAbsent("label", id);
        node.put("type", firstText(text(node.get("type")), kind));
        node.put("data", data);
        node.putIfAbsent("position", nextPosition(canvas));
        nodes(canvas).add(node);
    }

    private void updateNode(Map<String, Object> canvas, WorkflowDraftEditOperation operation, List<String> errors) {
        String id = firstText(operation.getNodeId(), text(operation.getPatch() == null ? null : operation.getPatch().get("id")));
        Map<String, Object> node = findNode(canvas, id);
        if (node == null) {
            errors.add("UPDATE_NODE references missing node id: " + id);
            return;
        }
        Map<String, Object> rawPatch = mutableMap(operation.getPatch());
        if (rawPatch.isEmpty() && operation.getNode() != null) {
            rawPatch = mutableMap(operation.getNode());
        }
        String patchId = text(rawPatch.get("id"));
        if (StringUtils.hasText(patchId) && !Objects.equals(id, patchId)) {
            errors.add("UPDATE_NODE cannot change node id from " + id + " to " + patchId);
            return;
        }
        rawPatch.remove("id");
        deepMerge(node, normalizeNodePatch(node, rawPatch));
    }

    private void deleteNode(Map<String, Object> canvas, WorkflowDraftEditOperation operation, List<String> errors) {
        String id = operation.getNodeId();
        if (isBoundaryNode(id)) {
            errors.add("DELETE_NODE cannot remove start/end boundary nodes: " + id);
            return;
        }
        Map<String, Object> node = findNode(canvas, id);
        if (node == null) {
            errors.add("DELETE_NODE references missing node id: " + id);
            return;
        }
        nodes(canvas).removeIf(item -> Objects.equals(id, text(item.get("id"))));
        edges(canvas).removeIf(edge -> Objects.equals(id, text(edge.get("source")))
                || Objects.equals(id, text(edge.get("target"))));
    }

    private void addEdge(Map<String, Object> canvas, WorkflowDraftEditOperation operation, List<String> errors) {
        Map<String, Object> edge = mutableMap(operation.getEdge());
        String id = text(edge.get("id"));
        String source = text(edge.get("source"));
        String target = text(edge.get("target"));
        if (!StringUtils.hasText(id) || !StringUtils.hasText(source) || !StringUtils.hasText(target)) {
            errors.add("ADD_EDGE requires edge.id, edge.source and edge.target");
            return;
        }
        if (findEdge(canvas, id) != null) {
            errors.add("ADD_EDGE references duplicate edge id: " + id);
            return;
        }
        if (findNode(canvas, source) == null || findNode(canvas, target) == null) {
            errors.add("ADD_EDGE references missing source/target node: " + id);
            return;
        }
        edge.putIfAbsent("condition", "always");
        edges(canvas).add(edge);
    }

    private void updateEdge(Map<String, Object> canvas, WorkflowDraftEditOperation operation, List<String> errors) {
        String id = operation.getEdgeId();
        Map<String, Object> edge = findEdge(canvas, id);
        if (edge == null) {
            errors.add("UPDATE_EDGE references missing edge id: " + id);
            return;
        }
        Map<String, Object> patch = mutableMap(operation.getPatch());
        String source = firstText(text(patch.get("source")), text(edge.get("source")));
        String target = firstText(text(patch.get("target")), text(edge.get("target")));
        if (findNode(canvas, source) == null || findNode(canvas, target) == null) {
            errors.add("UPDATE_EDGE references missing source/target node: " + id);
            return;
        }
        deepMerge(edge, patch);
    }

    private void deleteEdge(Map<String, Object> canvas, WorkflowDraftEditOperation operation, List<String> errors) {
        String id = operation.getEdgeId();
        if (findEdge(canvas, id) == null) {
            errors.add("DELETE_EDGE references missing edge id: " + id);
            return;
        }
        edges(canvas).removeIf(edge -> Objects.equals(id, text(edge.get("id"))));
    }

    private Map<String, Object> findNode(Map<String, Object> canvas, String id) {
        if (!StringUtils.hasText(id)) return null;
        return nodes(canvas).stream()
                .filter(node -> Objects.equals(id, text(node.get("id"))))
                .findFirst()
                .orElse(null);
    }

    private Map<String, Object> findEdge(Map<String, Object> canvas, String id) {
        if (!StringUtils.hasText(id)) return null;
        return edges(canvas).stream()
                .filter(edge -> Objects.equals(id, text(edge.get("id"))))
                .findFirst()
                .orElse(null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mutableMap(Object value) {
        if (value == null) {
            return new LinkedHashMap<>();
        }
        if (value instanceof Map<?, ?> map) {
            return objectMapper.convertValue(map, MAP_TYPE);
        }
        return objectMapper.convertValue(value, MAP_TYPE);
    }

    private Map<String, Object> normalizeNodePatch(Map<String, Object> node, Map<String, Object> rawPatch) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        Map<String, Object> dataPatch = new LinkedHashMap<>();

        Object data = rawPatch.get("data");
        if (data instanceof Map<?, ?>) {
            deepMerge(dataPatch, mutableMap(data));
        }
        Object config = rawPatch.get("config");
        if (config instanceof Map<?, ?>) {
            deepMerge(dataPatch, mutableMap(config));
        }

        for (Map.Entry<String, Object> entry : rawPatch.entrySet()) {
            String key = entry.getKey();
            if ("data".equals(key) || "config".equals(key)) {
                continue;
            }
            if (NODE_DATA_PATCH_KEYS.contains(key)) {
                dataPatch.put(key, entry.getValue());
            } else {
                normalized.put(key, entry.getValue());
            }
        }

        Map<String, Object> currentData = mutableMap(node.get("data"));
        String kind = firstText(text(dataPatch.get("kind")), text(currentData.get("kind")), text(node.get("type")));
        if ("answer".equalsIgnoreCase(kind)) {
            normalizeAnswerPatch(dataPatch);
        }
        if (!dataPatch.isEmpty()) {
            normalized.put("data", dataPatch);
        }
        return normalized;
    }

    private void normalizeAnswerPatch(Map<String, Object> dataPatch) {
        Map<String, Object> answerConfig = mutableMap(dataPatch.get("answerConfig"));
        String template = firstText(text(dataPatch.get("template")), text(answerConfig.get("template")));
        if (StringUtils.hasText(template)) {
            answerConfig.put("template", template);
            dataPatch.put("answerConfig", answerConfig);
            dataPatch.put("template", template);
            dataPatch.put("writeToAnswer", true);
        }
    }

    private void deepMerge(Map<String, Object> target, Map<String, Object> patch) {
        for (Map.Entry<String, Object> entry : patch.entrySet()) {
            if (target.get(entry.getKey()) instanceof Map<?, ?> targetData
                    && entry.getValue() instanceof Map<?, ?> patchData) {
                Map<String, Object> merged = mutableMap(targetData);
                deepMerge(merged, mutableMap(patchData));
                target.put(entry.getKey(), merged);
            } else {
                target.put(entry.getKey(), entry.getValue());
            }
        }
    }

    private Map<String, Object> nextPosition(Map<String, Object> canvas) {
        int index = nodes(canvas).size();
        return Map.of("x", 240 + index * 80, "y", 200 + (index % 3) * 40);
    }

    private List<String> buildWarnings(Map<String, Object> canvas) {
        List<String> warnings = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (Map<String, Object> node : nodes(canvas)) {
            String id = text(node.get("id"));
            if (!StringUtils.hasText(id)) {
                warnings.add("Canvas contains a node without id");
            } else if (!ids.add(id)) {
                warnings.add("Canvas contains duplicate node id: " + id);
            }
        }
        for (Map<String, Object> edge : edges(canvas)) {
            String source = text(edge.get("source"));
            String target = text(edge.get("target"));
            if (findNode(canvas, source) == null || findNode(canvas, target) == null) {
                warnings.add("Canvas contains edge with missing source/target: " + text(edge.get("id")));
            }
        }
        return warnings;
    }

    private List<WorkflowDraftPlaceholder> placeholderNodes(Map<String, Object> canvas) {
        List<WorkflowDraftPlaceholder> placeholders = new ArrayList<>();
        for (Map<String, Object> node : nodes(canvas)) {
            Map<String, Object> data = mutableMap(node.get("data"));
            if (Boolean.TRUE.equals(data.get("needsConfiguration"))) {
                placeholders.add(new WorkflowDraftPlaceholder(
                        text(node.get("id")),
                        firstText(text(data.get("kind")), text(node.get("type"))),
                        firstText(text(data.get("label")), text(node.get("id"))),
                        firstText(text(data.get("placeholderReason")), "AI generated placeholder needs configuration")));
            }
        }
        return placeholders;
    }

    private GraphSpec toGraphSpec(Map<String, Object> canvas) {
        GraphSpec.GraphSpecBuilder builder = GraphSpec.builder()
                .code(firstText(text(canvas.get("graphCode")), "ai_edited_graph"))
                .name(firstText(text(canvas.get("graphName")), "AI edited workflow"))
                .mode("WORKFLOW")
                .runtimeHint("LANGGRAPH4J")
                .layout(GraphSpec.Layout.builder().engine("vue-flow").direction("LR").build());

        for (Map<String, Object> node : nodes(canvas)) {
            String id = text(node.get("id"));
            Map<String, Object> data = mutableMap(node.get("data"));
            String kind = firstText(text(data.get("kind")), text(node.get("type")));
            if (!StringUtils.hasText(id) || isBoundaryNode(id) || isBoundaryKind(kind)) {
                continue;
            }
            builder.node(toGraphNode(id, kind, node, data));
        }

        List<String> finish = new ArrayList<>();
        String entry = null;
        for (Map<String, Object> edge : edges(canvas)) {
            String from = boundaryEndpoint(text(edge.get("source")));
            String to = boundaryEndpoint(text(edge.get("target")));
            if (!StringUtils.hasText(from) || !StringUtils.hasText(to)) {
                continue;
            }
            builder.edge(GraphSpec.Edge.builder()
                    .id(text(edge.get("id")))
                    .from(from)
                    .to(to)
                    .condition(firstText(text(edge.get("condition")), text(edge.get("label")), "always"))
                    .sourceHandle(text(edge.get("sourceHandle")))
                    .targetHandle(text(edge.get("targetHandle")))
                    .layout(GraphSpec.Layout.EdgeLayout.builder()
                            .label(firstText(text(edge.get("label")), text(edge.get("condition"))))
                            .build())
                    .build());
            if ("START".equals(from) && entry == null) {
                entry = to;
            }
            if ("END".equals(to) && !"START".equals(from)) {
                finish.add(from);
            }
        }
        builder.entry(firstText(entry, firstWorkflowNodeId(canvas)));
        for (String item : finish.stream().distinct().toList()) {
            builder.finishNode(item);
        }
        return builder.build();
    }

    private GraphSpec.Node toGraphNode(String id, String kind, Map<String, Object> node, Map<String, Object> data) {
        Map<String, Object> config = new LinkedHashMap<>(data);
        config.remove("label");
        config.remove("kind");
        config.remove("description");
        config.remove("inputs");
        config.remove("outputs");
        config.remove("retry");
        config.remove("errorPolicy");

        GraphSpec.Node.NodeBuilder builder = GraphSpec.Node.builder()
                .id(id)
                .type(AgentGraphNodeType.normalize(kind))
                .name(firstText(text(data.get("label")), id))
                .description(text(data.get("description")))
                .config(config)
                .layout(toNodeLayout(node, data));

        if (data.get("inputs") instanceof List<?> inputs) {
            for (Object input : inputs) builder.input(toPort(input));
        }
        if (data.get("outputs") instanceof List<?> outputs) {
            for (Object output : outputs) builder.output(toPort(output));
        }
        if (data.get("retry") instanceof Map<?, ?> retry) {
            Map<String, Object> map = mutableMap(retry);
            builder.retry(GraphSpec.RetryPolicy.builder()
                    .enabled(bool(map.get("enabled")))
                    .maxAttempts(integer(map.get("maxAttempts")))
                    .backoffMs(longValue(map.get("backoffMs")))
                    .build());
        }
        if (data.get("errorPolicy") instanceof Map<?, ?> errorPolicy) {
            Map<String, Object> map = mutableMap(errorPolicy);
            builder.errorPolicy(GraphSpec.ErrorPolicy.builder()
                    .strategy(text(map.get("strategy")))
                    .fallbackNodeId(text(map.get("fallbackNodeId")))
                    .defaultOutput(mutableMap(map.get("defaultOutput")))
                    .build());
        }
        GraphSpec.CapabilityRef ref = capabilityRef(kind, data);
        if (ref != null) {
            builder.ref(ref);
        }
        return builder.build();
    }

    private GraphSpec.Layout.NodeLayout toNodeLayout(Map<String, Object> node, Map<String, Object> data) {
        Map<String, Object> position = mutableMap(node.get("position"));
        return GraphSpec.Layout.NodeLayout.builder()
                .x(doubleValue(position.get("x")))
                .y(doubleValue(position.get("y")))
                .collapsed(bool(data.get("collapsed")))
                .build();
    }

    private GraphSpec.Port toPort(Object value) {
        Map<String, Object> map = mutableMap(value);
        return GraphSpec.Port.builder()
                .id(text(map.get("id")))
                .name(text(map.get("name")))
                .type(text(map.get("type")))
                .required(bool(map.get("required")))
                .schema(text(map.get("schema")))
                .source(text(map.get("source")))
                .build();
    }

    private GraphSpec.CapabilityRef capabilityRef(String kind, Map<String, Object> data) {
        Map<String, Object> toolConfig = mutableMap(data.get("toolConfig"));
        if (!StringUtils.hasText(text(toolConfig.get("ref")))) {
            return null;
        }
        String graphType = AgentGraphNodeType.normalize(kind);
        String refKind = "CAPABILITY".equals(graphType) ? "CAPABILITY" : "TOOL";
        return GraphSpec.CapabilityRef.builder()
                .kind(refKind)
                .name(text(toolConfig.get("ref")))
                .qualifiedName(text(toolConfig.get("qualifiedName")))
                .definitionId(longValue(toolConfig.get("definitionId")))
                .projectCode(text(toolConfig.get("projectCode")))
                .build();
    }

    private String firstWorkflowNodeId(Map<String, Object> canvas) {
        return nodes(canvas).stream()
                .map(node -> text(node.get("id")))
                .filter(id -> StringUtils.hasText(id) && !isBoundaryNode(id))
                .findFirst()
                .orElse(null);
    }

    private String boundaryEndpoint(String id) {
        if ("start".equalsIgnoreCase(id)) return "START";
        if ("end".equalsIgnoreCase(id)) return "END";
        return id;
    }

    private boolean isBoundaryNode(String id) {
        return "start".equalsIgnoreCase(id) || "end".equalsIgnoreCase(id);
    }

    private boolean isBoundaryKind(String kind) {
        return "start".equalsIgnoreCase(kind) || "end".equalsIgnoreCase(kind);
    }

    private String extractJsonObject(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("empty model response");
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("missing JSON object");
        }
        return text.substring(start, end + 1);
    }

    private String systemPrompt() {
        return """
                You are an Agent Studio workflow editor. Return only strict JSON.
                Schema:
                {"summary":"short summary","operations":[{"type":"ADD_NODE|UPDATE_NODE|DELETE_NODE|ADD_EDGE|UPDATE_EDGE|DELETE_EDGE","nodeId":"optional","edgeId":"optional","node":{},"edge":{},"patch":{},"reason":"why"}]}
                Use the current canvas ids. Do not delete start or end. Keep edits local to selected nodes or edges when selections exist.
                Canvas node data must include kind and configVersion=2.
                For UPDATE_NODE, put VueFlow canvas data changes under patch.data. For answer nodes, update both patch.data.answerConfig.template and patch.data.template.
                """;
    }

    private String userPrompt(WorkflowDraftEditRequest request, Map<String, Object> canvas) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("agentId", request.getAgentId());
        payload.put("agentName", request.getAgentName());
        payload.put("projectCode", request.getProjectCode());
        payload.put("instruction", request.getInstruction());
        payload.put("selectedNodeIds", request.getSelectedNodeIds() == null ? List.of() : request.getSelectedNodeIds());
        payload.put("selectedEdgeIds", request.getSelectedEdgeIds() == null ? List.of() : request.getSelectedEdgeIds());
        payload.put("currentCanvas", canvas);
        payload.put("tools", request.getTools() == null ? List.of() : request.getTools());
        payload.put("capabilities", request.getCapabilities() == null ? List.of() : request.getCapabilities());
        payload.put("knowledgeBases", request.getKnowledgeBases() == null ? List.of() : request.getKnowledgeBases());
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize edit prompt", e);
        }
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Boolean bool(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean b) return b;
        return Boolean.parseBoolean(text(value).toLowerCase(Locale.ROOT));
    }

    private Integer integer(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (!StringUtils.hasText(text(value))) return null;
        return Integer.parseInt(text(value));
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (!StringUtils.hasText(text(value))) return null;
        return Long.parseLong(text(value));
    }

    private Double doubleValue(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        if (!StringUtils.hasText(text(value))) return null;
        return Double.parseDouble(text(value));
    }
}
