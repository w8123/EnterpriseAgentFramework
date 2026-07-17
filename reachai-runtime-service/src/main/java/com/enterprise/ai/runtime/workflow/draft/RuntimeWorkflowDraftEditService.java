package com.enterprise.ai.runtime.workflow.draft;

import com.enterprise.ai.agent.graph.AgentGraphNodeType;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.workflow.authoring.WorkflowAuthoringAgentAdapter;
import com.enterprise.ai.runtime.workflow.authoring.WorkflowAuthoringRequest;
import com.enterprise.ai.runtime.workflow.authoring.WorkflowAuthoringResult;
import com.enterprise.ai.runtime.workflow.layout.RuntimeWorkflowCanvasLayoutService;
import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.UUID;

/**
 * Workflow Studio /edit-draft application entry.
 *
 * <p>Parses the current draft context, delegates reasoning/tool-use to
 * {@link WorkflowAuthoringAgentAdapter}, then projects a canvas snapshot for preview.</p>
 */
@Service
@RequiredArgsConstructor
public class RuntimeWorkflowDraftEditService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final RuntimeWorkflowCanvasLayoutService canvasLayoutService;
    private final WorkflowAuthoringAgentAdapter authoringAgentAdapter;

    public RuntimeWorkflowDraftEditView edit(RuntimeWorkflowDraftEditRequest request) {
        Map<String, Object> canvas = mutableCanvas(request == null ? null : request.currentCanvas());
        GraphSpec originalGraphSpec = currentGraphSpec(request, canvas);
        String originalFingerprint = fingerprint(originalGraphSpec);

        if (request == null || !StringUtils.hasText(request.instruction())) {
            return failedView(
                    "instruction is required",
                    "INSTRUCTION_REQUIRED",
                    canvas,
                    originalGraphSpec,
                    List.of("instruction is required"),
                    0,
                    UUID.randomUUID().toString());
        }
        if (!StringUtils.hasText(request.modelInstanceId())) {
            return failedView(
                    "modelInstanceId is required",
                    "MODEL_INSTANCE_REQUIRED",
                    canvas,
                    originalGraphSpec,
                    List.of("modelInstanceId is required"),
                    0,
                    UUID.randomUUID().toString());
        }

        WorkflowAuthoringResult authored = authoringAgentAdapter.author(new WorkflowAuthoringRequest(
                request.workflowId(),
                firstText(request.agentName(), "Workflow"),
                request.projectCode(),
                null,
                request.instruction(),
                request.modelInstanceId(),
                deepCopy(originalGraphSpec),
                request.selectedNodeIds(),
                request.selectedEdgeIds(),
                draftResources(request)));

        if (!originalFingerprint.equals(fingerprint(originalGraphSpec))) {
            return failedView(
                    "原始 GraphSpec 被意外修改，已拒绝返回预览",
                    "ORIGINAL_GRAPH_TAMPERED",
                    canvas,
                    originalGraphSpec,
                    List.of("ORIGINAL_GRAPH_TAMPERED: original GraphSpec was modified in place"),
                    authored == null ? 0 : authored.attempts(),
                    authored == null ? null : authored.authoringId());
        }

        GraphSpec candidate = authored.graphSpec() == null ? originalGraphSpec : authored.graphSpec();
        Map<String, Object> projected = canvasLayoutService.projectAndLayout(
                candidate,
                canvas,
                RuntimeWorkflowCanvasLayoutService.Options.defaults());
        List<String> warnings = new ArrayList<>(authored.warnings());
        warnings.addAll(buildWarnings(projected));

        String status = authored.succeeded() && authored.validationErrors().isEmpty()
                ? "SUCCEEDED"
                : "FAILED";
        return new RuntimeWorkflowDraftEditView(
                status,
                authored.provider(),
                authored.summary(),
                authored.operations(),
                projected,
                candidate,
                warnings,
                placeholderNodes(projected),
                authored.validationErrors(),
                authored.attempts(),
                authored.failureCode(),
                authored.authoringId());
    }

    private RuntimeWorkflowDraftEditView failedView(String summary,
                                                    String failureCode,
                                                    Map<String, Object> canvas,
                                                    GraphSpec graphSpec,
                                                    List<String> validationErrors,
                                                    int attempts,
                                                    String authoringId) {
        Map<String, Object> projected = canvasLayoutService.projectAndLayout(
                graphSpec,
                canvas,
                RuntimeWorkflowCanvasLayoutService.Options.defaults());
        return new RuntimeWorkflowDraftEditView(
                "FAILED",
                WorkflowAuthoringResult.PROVIDER,
                summary,
                List.of(),
                projected,
                graphSpec,
                buildWarnings(projected),
                placeholderNodes(projected),
                validationErrors,
                attempts,
                failureCode,
                authoringId);
    }

    private Map<String, Object> draftResources(RuntimeWorkflowDraftEditRequest request) {
        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("tools", request.tools() == null ? List.of() : request.tools());
        resources.put("capabilities", request.capabilities() == null ? List.of() : request.capabilities());
        resources.put("knowledgeBases", request.knowledgeBases() == null ? List.of() : request.knowledgeBases());
        return resources;
    }

    private GraphSpec currentGraphSpec(RuntimeWorkflowDraftEditRequest request,
                                       Map<String, Object> canvas) {
        if (request != null && request.currentGraphSpec() != null) {
            return deepCopy(request.currentGraphSpec());
        }
        Object embedded = canvas.get("graphSpec");
        if (embedded instanceof Map<?, ?> map && !map.isEmpty()) {
            return objectMapper.convertValue(map, GraphSpec.class);
        }
        return toGraphSpec(canvas);
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
            for (Object input : inputs) {
                builder.input(toPort(input));
            }
        }
        if (data.get("outputs") instanceof List<?> outputs) {
            for (Object output : outputs) {
                builder.output(toPort(output));
            }
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
        if (!StringUtils.hasText(id)) {
            return null;
        }
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
            if (findNode(canvas, text(edge.get("source"))) == null
                    || findNode(canvas, text(edge.get("target"))) == null) {
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

    private GraphSpec deepCopy(GraphSpec source) {
        try {
            return objectMapper.treeToValue(objectMapper.valueToTree(source), GraphSpec.class);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to copy GraphSpec", ex);
        }
    }

    private String fingerprint(GraphSpec graphSpec) {
        try {
            return objectMapper.writeValueAsString(graphSpec);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to fingerprint GraphSpec", ex);
        }
    }

    private Map<String, Object> mutableMap(Object value) {
        if (value == null) {
            return new LinkedHashMap<>();
        }
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
        if ("start".equalsIgnoreCase(id)) {
            return "START";
        }
        if ("end".equalsIgnoreCase(id)) {
            return "END";
        }
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
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(text(value).toLowerCase(Locale.ROOT));
    }

    private Integer integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (!StringUtils.hasText(text(value))) {
            return null;
        }
        return Integer.parseInt(text(value));
    }

    private Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (!StringUtils.hasText(text(value))) {
            return null;
        }
        return Long.parseLong(text(value));
    }

    private Double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (!StringUtils.hasText(text(value))) {
            return null;
        }
        return Double.parseDouble(text(value));
    }
}
