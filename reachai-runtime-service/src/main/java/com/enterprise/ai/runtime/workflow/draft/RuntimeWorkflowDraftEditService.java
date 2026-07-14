package com.enterprise.ai.runtime.workflow.draft;

import com.enterprise.ai.agent.graph.AgentGraphNodeType;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatRequest;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatRequest.ChatMessage;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatResult;
import com.enterprise.ai.runtime.workflow.layout.RuntimeWorkflowCanvasLayoutService;
import com.enterprise.ai.runtime.workflow.mutation.RuntimeWorkflowGraphMutationService;
import com.enterprise.ai.runtime.workflow.mutation.RuntimeWorkflowGraphMutationService.MutationOperation;
import com.enterprise.ai.runtime.workflow.mutation.RuntimeWorkflowGraphMutationService.MutationResult;
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
public class RuntimeWorkflowDraftEditService {

    private static final String PROVIDER = "LLM_PATCH";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final Set<String> NODE_DATA_PATCH_KEYS = Set.of(
            "label", "kind", "configVersion", "description", "source", "category", "collapsed",
            "inputs", "outputs", "inputSchema", "outputSchema", "inputMapping", "retry",
            "errorPolicy", "outputAlias", "needsConfiguration", "placeholderReason",
            "userInputConfig", "llmConfig", "knowledgeConfig", "httpConfig", "parameterConfig",
            "conditionConfig", "answerConfig", "codeConfig", "classifierConfig", "aggregateConfig",
            "approvalConfig", "loopConfig", "knowledgeWriteConfig", "documentExtractConfig", "mcpConfig",
            "toolConfig", "assignments", "template", "writeToAnswer");

    private final ObjectMapper objectMapper;
    private final RuntimeModelServiceClient modelServiceClient;
    private final RuntimeWorkflowCanvasLayoutService canvasLayoutService;
    private final RuntimeWorkflowGraphMutationService graphMutationService;
    private final RuntimeWorkflowDraftCandidateValidationService candidateValidationService;
    private final RuntimeWorkflowDraftRepairService repairService;

    public RuntimeWorkflowDraftEditView edit(RuntimeWorkflowDraftEditRequest request) {
        Map<String, Object> canvas = mutableCanvas(request == null ? null : request.currentCanvas());
        GraphSpec graphSpec = currentGraphSpec(request, canvas);
        List<String> validationErrors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<RuntimeWorkflowDraftEditOperationView> operations = List.of();
        String summary = "";

        if (request == null || !StringUtils.hasText(request.instruction())) {
            validationErrors.add("instruction is required");
            return result(summary, operations, canvas, graphSpec, warnings, validationErrors);
        }
        if (!StringUtils.hasText(request.modelInstanceId())) {
            validationErrors.add("modelInstanceId is required");
            return result(summary, operations, canvas, graphSpec, warnings, validationErrors);
        }

        String raw = callModel(request, canvas, graphSpec);
        try {
            JsonNode root = objectMapper.readTree(extractJsonObject(raw));
            summary = root.path("summary").asText("");
            operations = parseOperations(root.path("operations"));
        } catch (Exception ex) {
            validationErrors.add("AI did not return valid JSON patch: " + ex.getMessage());
            return result(summary, operations, canvas, graphSpec, warnings, validationErrors);
        }

        try {
            MutationResult mutation = graphMutationService.mutate(graphSpec, mutationOperations(graphSpec, operations));
            graphSpec = mutation.graphSpec();
            var releaseValidation = candidateValidationService.validate(
                    request.workflowId(),
                    request.projectCode(),
                    null,
                    request.modelInstanceId(),
                    graphSpec);
            RuntimeWorkflowDraftRepairService.RepairResult repair = repairService.repair(
                    new RuntimeWorkflowDraftRepairService.RepairRequest(
                            request.workflowId(),
                            request.projectCode(),
                            null,
                            request.modelInstanceId(),
                            request.instruction(),
                            request.selectedNodeIds(),
                            request.selectedEdgeIds(),
                            draftResources(request),
                            RuntimeWorkflowDraftRepairService.DEFAULT_MAX_REPAIR_ROUNDS),
                    graphSpec,
                    releaseValidation);
            graphSpec = repair.graphSpec();
            releaseValidation = repair.validation();
            warnings.addAll(repair.warnings());
            canvas = canvasLayoutService.projectAndLayout(
                    graphSpec,
                    canvas,
                    RuntimeWorkflowCanvasLayoutService.Options.defaults());
            releaseValidation.errors().stream()
                    .map(item -> item.code() + ": " + item.message())
                    .forEach(validationErrors::add);
            releaseValidation.warnings().stream()
                    .map(item -> item.code() + ": " + item.message())
                    .forEach(warnings::add);
        } catch (IllegalArgumentException ex) {
            validationErrors.add(ex.getMessage());
        }
        warnings.addAll(buildWarnings(canvas));
        return result(summary, operations, canvas, graphSpec, warnings, validationErrors);
    }

    private Map<String, Object> draftResources(RuntimeWorkflowDraftEditRequest request) {
        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("tools", request.tools() == null ? List.of() : request.tools());
        resources.put("capabilities", request.capabilities() == null ? List.of() : request.capabilities());
        resources.put("knowledgeBases", request.knowledgeBases() == null ? List.of() : request.knowledgeBases());
        return resources;
    }

    private RuntimeWorkflowDraftEditView result(String summary,
                                                List<RuntimeWorkflowDraftEditOperationView> operations,
                                                Map<String, Object> canvas,
                                                GraphSpec graphSpec,
                                                List<String> warnings,
                                                List<String> validationErrors) {
        Map<String, Object> layoutCanvas = canvasLayoutService.projectAndLayout(
                graphSpec,
                canvas,
                RuntimeWorkflowCanvasLayoutService.Options.defaults());
        return new RuntimeWorkflowDraftEditView(
                PROVIDER,
                summary == null ? "" : summary,
                operations,
                layoutCanvas,
                graphSpec,
                warnings,
                placeholderNodes(layoutCanvas),
                validationErrors);
    }

    private String callModel(RuntimeWorkflowDraftEditRequest request,
                             Map<String, Object> canvas,
                             GraphSpec graphSpec) {
        ModelChatRequest modelRequest = ModelChatRequest.builder()
                .modelInstanceId(request.modelInstanceId().trim())
                .messages(List.of(
                        ChatMessage.builder().role("system").content(systemPrompt()).build(),
                        ChatMessage.builder().role("user").content(userPrompt(request, canvas, graphSpec)).build()))
                .build();
        ModelChatResult result = modelServiceClient.chat(modelRequest);
        if (result == null || result.getData() == null || result.getData().getContent() == null) {
            return "";
        }
        return result.getData().getContent();
    }

    private List<RuntimeWorkflowDraftEditOperationView> parseOperations(JsonNode operationsNode)
            throws JsonProcessingException {
        if (!operationsNode.isArray()) {
            return List.of();
        }
        List<RuntimeWorkflowDraftEditOperationView> operations = new ArrayList<>();
        for (JsonNode item : operationsNode) {
            operations.add(objectMapper.treeToValue(item, RuntimeWorkflowDraftEditOperationView.class));
        }
        return operations;
    }

    private GraphSpec currentGraphSpec(RuntimeWorkflowDraftEditRequest request,
                                       Map<String, Object> canvas) {
        if (request != null && request.currentGraphSpec() != null) {
            return objectMapper.convertValue(request.currentGraphSpec(), GraphSpec.class);
        }
        Object embedded = canvas.get("graphSpec");
        if (embedded instanceof Map<?, ?> map && !map.isEmpty()) {
            return objectMapper.convertValue(map, GraphSpec.class);
        }
        return toGraphSpec(canvas);
    }

    private List<MutationOperation> mutationOperations(
            GraphSpec graphSpec,
            List<RuntimeWorkflowDraftEditOperationView> operations) {
        List<MutationOperation> result = new ArrayList<>();
        for (RuntimeWorkflowDraftEditOperationView operation : operations == null ? List.<RuntimeWorkflowDraftEditOperationView>of() : operations) {
            if (operation == null || operation.getType() == null) {
                result.add(null);
                continue;
            }
            result.add(switch (operation.getType()) {
                case ADD_NODE -> new MutationOperation(
                        MutationOperation.Op.ADD_NODE,
                        graphNode(operation.getNode()),
                        null, null, null, null, null, null);
                case UPDATE_NODE -> new MutationOperation(
                        MutationOperation.Op.UPDATE_NODE,
                        null,
                        operation.getNodeId(),
                        graphNodePatch(graphSpec, operation),
                        null, null, null, null);
                case DELETE_NODE -> new MutationOperation(
                        MutationOperation.Op.DELETE_NODE,
                        null, operation.getNodeId(), null, null, null, null, null);
                case ADD_EDGE -> new MutationOperation(
                        MutationOperation.Op.ADD_EDGE,
                        null, null, null, graphEdge(operation.getEdge()), null, null, null);
                case UPDATE_EDGE -> new MutationOperation(
                        MutationOperation.Op.UPDATE_EDGE,
                        null, null, graphEdgePatch(operation.getPatch()), null, operation.getEdgeId(), null, null);
                case DELETE_EDGE -> new MutationOperation(
                        MutationOperation.Op.DELETE_EDGE,
                        null, null, null, null, operation.getEdgeId(), null, null);
            });
        }
        return result;
    }

    private GraphSpec.Node graphNode(Map<String, Object> rawNode) {
        Map<String, Object> node = mutableMap(rawNode);
        Map<String, Object> data = mutableMap(node.get("data"));
        if (!data.isEmpty()) {
            String id = text(node.get("id"));
            String kind = firstText(text(data.get("kind")), text(node.get("type")));
            return toGraphNode(id, kind, node, data);
        }
        GraphSpec.Node graphNode = objectMapper.convertValue(node, GraphSpec.Node.class);
        if (StringUtils.hasText(graphNode.getType())) {
            graphNode.setType(AgentGraphNodeType.normalize(graphNode.getType()));
        }
        return graphNode;
    }

    private Map<String, Object> graphNodePatch(GraphSpec graphSpec,
                                               RuntimeWorkflowDraftEditOperationView operation) {
        Map<String, Object> raw = mutableMap(operation.getPatch());
        if (raw.isEmpty()) {
            raw = mutableMap(operation.getNode());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of("name", "description", "ref", "inputs", "outputs", "inputSchema",
                "outputSchema", "retry", "errorPolicy", "layout")) {
            if (raw.containsKey(key)) {
                result.put(key, raw.get(key));
            }
        }
        if (raw.containsKey("type")) {
            result.put("type", AgentGraphNodeType.normalize(text(raw.get("type"))));
        }
        Map<String, Object> data = mutableMap(raw.get("data"));
        for (String key : NODE_DATA_PATCH_KEYS) {
            if (raw.containsKey(key)) {
                data.putIfAbsent(key, raw.get(key));
            }
        }
        if (raw.get("config") instanceof Map<?, ?> config) {
            deepMerge(data, mutableMap(config));
        }
        GraphSpec.Node current = findGraphNode(graphSpec, operation.getNodeId());
        String currentType = current == null ? "" : current.getType();
        String kind = firstText(text(data.remove("kind")), text(raw.get("type")), currentType);
        if (StringUtils.hasText(kind)) {
            result.put("type", AgentGraphNodeType.normalize(kind));
        }
        String label = text(data.remove("label"));
        if (StringUtils.hasText(label)) {
            result.put("name", label);
        }
        putIfPresent(result, "description", data.remove("description"));
        putIfPresent(result, "inputs", data.remove("inputs"));
        putIfPresent(result, "outputs", data.remove("outputs"));
        putIfPresent(result, "inputSchema", data.remove("inputSchema"));
        putIfPresent(result, "outputSchema", data.remove("outputSchema"));
        putIfPresent(result, "retry", data.remove("retry"));
        putIfPresent(result, "errorPolicy", data.remove("errorPolicy"));
        Object collapsed = data.remove("collapsed");
        if (collapsed != null) {
            Map<String, Object> layout = mutableMap(result.get("layout"));
            layout.put("collapsed", collapsed);
            result.put("layout", layout);
        }
        data.remove("configVersion");
        data.remove("category");
        if ("ANSWER".equalsIgnoreCase(AgentGraphNodeType.normalize(kind))) {
            normalizeAnswerPatch(data);
        }
        if (!data.isEmpty()) {
            result.put("config", data);
        }
        return result;
    }

    private GraphSpec.Edge graphEdge(Map<String, Object> rawEdge) {
        Map<String, Object> edge = mutableMap(rawEdge);
        String from = boundaryEndpoint(firstText(text(edge.get("from")), text(edge.get("source"))));
        String to = boundaryEndpoint(firstText(text(edge.get("to")), text(edge.get("target"))));
        return GraphSpec.Edge.builder()
                .id(text(edge.get("id")))
                .from(from)
                .to(to)
                .condition(firstText(text(edge.get("condition")), text(edge.get("label")), "always"))
                .sourceHandle(text(edge.get("sourceHandle")))
                .targetHandle(text(edge.get("targetHandle")))
                .build();
    }

    private Map<String, Object> graphEdgePatch(Map<String, Object> rawPatch) {
        Map<String, Object> patch = mutableMap(rawPatch);
        if (patch.containsKey("source")) {
            patch.put("from", boundaryEndpoint(text(patch.remove("source"))));
        }
        if (patch.containsKey("target")) {
            patch.put("to", boundaryEndpoint(text(patch.remove("target"))));
        }
        return patch;
    }

    private GraphSpec.Node findGraphNode(GraphSpec graphSpec, String nodeId) {
        if (graphSpec == null || graphSpec.getNodes() == null || !StringUtils.hasText(nodeId)) {
            return null;
        }
        return graphSpec.getNodes().stream()
                .filter(node -> node != null && nodeId.equals(node.getId()))
                .findFirst()
                .orElse(null);
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
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
        finish.stream().distinct().forEach(builder::finishNode);
        return builder.build();
    }

    private GraphSpec.Node toGraphNode(String id, String kind, Map<String, Object> node, Map<String, Object> data) {
        Map<String, Object> config = new LinkedHashMap<>(data);
        for (String key : List.of("label", "kind", "description", "inputs", "outputs", "retry", "errorPolicy")) {
            config.remove(key);
        }
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

    private String systemPrompt() {
        return """
                You are a Workflow Studio GraphSpec editor. Return only strict JSON.
                Schema:
                {"summary":"short summary","operations":[{"type":"ADD_NODE|UPDATE_NODE|DELETE_NODE|ADD_EDGE|UPDATE_EDGE|DELETE_EDGE","nodeId":"optional","edgeId":"optional","node":{},"edge":{},"patch":{},"reason":"why"}]}
                GraphSpec is the semantic source of truth. Canvas is layout context only.
                Use existing GraphSpec node and edge ids. START and END are boundary endpoints, not editable nodes.
                Keep edits local to selected nodes or edges when selections exist.
                ADD_NODE.node and ADD_EDGE.edge use GraphSpec shapes. UPDATE_NODE.patch updates GraphSpec node fields.
                Put runtime node configuration under patch.config. Never change a node or edge id in an update.
                """;
    }

    private String userPrompt(RuntimeWorkflowDraftEditRequest request,
                              Map<String, Object> canvas,
                              GraphSpec graphSpec) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("agentId", request.agentId());
        payload.put("agentName", request.agentName());
        payload.put("projectCode", request.projectCode());
        payload.put("instruction", request.instruction());
        payload.put("selectedNodeIds", request.selectedNodeIds() == null ? List.of() : request.selectedNodeIds());
        payload.put("selectedEdgeIds", request.selectedEdgeIds() == null ? List.of() : request.selectedEdgeIds());
        payload.put("currentGraphSpec", graphSpec);
        payload.put("currentCanvasLayout", canvas);
        payload.put("tools", request.tools() == null ? List.of() : request.tools());
        payload.put("capabilities", request.capabilities() == null ? List.of() : request.capabilities());
        payload.put("knowledgeBases", request.knowledgeBases() == null ? List.of() : request.knowledgeBases());
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize edit prompt", e);
        }
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
            copied.add(item instanceof Map<?, ?> ? objectMapper.convertValue(item, MAP_TYPE) : item);
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

    private Map<String, Object> findNode(Map<String, Object> canvas, String id) {
        if (!StringUtils.hasText(id)) return null;
        return nodes(canvas).stream()
                .filter(node -> Objects.equals(id, text(node.get("id"))))
                .findFirst()
                .orElse(null);
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
            if (findNode(canvas, text(edge.get("source"))) == null || findNode(canvas, text(edge.get("target"))) == null) {
                warnings.add("Canvas contains edge with missing source/target: " + text(edge.get("id")));
            }
        }
        return warnings;
    }

    private List<RuntimeWorkflowDraftPlaceholderView> placeholderNodes(Map<String, Object> canvas) {
        List<RuntimeWorkflowDraftPlaceholderView> placeholders = new ArrayList<>();
        for (Map<String, Object> node : nodes(canvas)) {
            Map<String, Object> data = mutableMap(node.get("data"));
            if (Boolean.TRUE.equals(data.get("needsConfiguration"))) {
                placeholders.add(new RuntimeWorkflowDraftPlaceholderView(
                        text(node.get("id")),
                        firstText(text(data.get("kind")), text(node.get("type"))),
                        firstText(text(data.get("label")), text(node.get("id"))),
                        firstText(text(data.get("placeholderReason")), "AI generated placeholder needs configuration")));
            }
        }
        return placeholders;
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

    private Map<String, Object> mutableMap(Object value) {
        if (value == null) return new LinkedHashMap<>();
        if (value instanceof Map<?, ?>) return objectMapper.convertValue(value, MAP_TYPE);
        return objectMapper.convertValue(value, MAP_TYPE);
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
