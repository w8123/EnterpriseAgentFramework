package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.agent.graph.AgentGraphNodeType;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Enforces the persisted Workflow document boundary: GraphSpec owns executable
 * semantics while canvasJson owns presentation and layout only.
 */
@Component
@RequiredArgsConstructor
public class RuntimeWorkflowDocumentCanonicalizer {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final Set<String> GRAPH_SPEC_FIELDS = Set.of(
            "schemaVersion", "inputSchema", "stateSchema", "nodes", "edges", "entryNodeId", "exitNodeIds");
    private static final Set<String> GRAPH_NODE_FIELDS = Set.of(
            "id", "type", "name", "description", "ref", "inputs", "outputs",
            "inputSchema", "outputSchema", "retry", "errorPolicy", "config");
    private static final Set<String> GRAPH_EDGE_FIELDS = Set.of(
            "id", "from", "to", "condition", "sourceHandle", "targetHandle", "priority");
    private static final Set<String> CANVAS_FIELDS = Set.of(
            "schemaVersion", "layoutVersion", "viewport", "layout", "nodes", "edges");
    private static final Set<String> CANVAS_NODE_FIELDS = Set.of(
            "id", "position", "width", "height", "collapsed");
    private static final Set<String> CANVAS_EDGE_FIELDS = Set.of("id", "label", "style");

    private final ObjectMapper objectMapper;

    public String canonicalizeGraphSpecJson(String graphSpecJson) {
        if (!StringUtils.hasText(graphSpecJson)) {
            return graphSpecJson;
        }
        try {
            Map<String, Object> document = objectMapper.readValue(graphSpecJson, MAP_TYPE);
            rejectRemovedGraphSpecFields(document);
            rejectUnknownFields(document, GRAPH_SPEC_FIELDS, "GraphSpec");
            requireVersion(document, "schemaVersion", 2, "GraphSpec");
            requireList(document, "nodes", "GraphSpec");
            requireList(document, "edges", "GraphSpec");
            if (!(document.get("entryNodeId") instanceof String)) {
                throw new IllegalArgumentException("GraphSpec entryNodeId must be a string");
            }
            requireList(document, "exitNodeIds", "GraphSpec");
            requireStringItems(document.get("exitNodeIds"), "GraphSpec exitNodeIds");
            GraphSpec graphSpec = objectMapper.readValue(graphSpecJson, GraphSpec.class);
            if (graphSpec == null) {
                throw new IllegalArgumentException("graphSpecJson must be a JSON object");
            }
            return objectMapper.writeValueAsString(canonicalizeGraphSpec(graphSpec));
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("graphSpecJson is not valid GraphSpec JSON", ex);
        }
    }

    public GraphSpec canonicalizeGraphSpec(GraphSpec graphSpec) {
        if (graphSpec == null) {
            throw new IllegalArgumentException("graphSpec is required");
        }
        if (!Integer.valueOf(2).equals(graphSpec.getSchemaVersion())) {
            throw new IllegalArgumentException("GraphSpec schemaVersion must be 2");
        }

        List<GraphSpec.Node> sourceNodes = graphSpec.getNodes() == null ? List.of() : graphSpec.getNodes();
        List<GraphSpec.Edge> sourceEdges = graphSpec.getEdges() == null ? List.of() : graphSpec.getEdges();
        if (sourceNodes.stream().anyMatch(node -> node != null && isBoundaryNode(node))
                || sourceEdges.stream().anyMatch(edge -> edge != null
                && (isBoundaryEndpoint(edge.getFrom()) || isBoundaryEndpoint(edge.getTo())))) {
            throw new IllegalArgumentException("GraphSpec must use entryNodeId/exitNodeIds instead of START/END boundaries");
        }
        for (GraphSpec.Node node : sourceNodes) {
            if (node == null) {
                throw new IllegalArgumentException("GraphSpec nodes must not contain null items");
            }
            AgentGraphNodeType nodeType = requireCanonicalNodeType(node.getType());
            requireCanonicalToolRef(nodeType, node.getRef());
        }
        if (sourceEdges.stream().anyMatch(edge -> edge == null)) {
            throw new IllegalArgumentException("GraphSpec edges must not contain null items");
        }
        List<GraphSpec.Node> nodes = sourceNodes.stream().filter(node -> node != null).toList();

        List<GraphSpec.Edge> edges = sourceEdges.stream()
                .filter(edge -> edge != null)
                .toList();

        LinkedHashSet<String> exitNodeIds = new LinkedHashSet<>();
        for (String nodeId : graphSpec.getExitNodeIds()) {
            String normalized = trimToNull(nodeId);
            if (StringUtils.hasText(normalized)) {
                exitNodeIds.add(normalized);
            }
        }
        graphSpec.setSchemaVersion(2);
        graphSpec.setNodes(nodes);
        graphSpec.setEdges(edges);
        graphSpec.setEntryNodeId(trimToEmpty(graphSpec.getEntryNodeId()));
        graphSpec.setExitNodeIds(List.copyOf(exitNodeIds));
        return graphSpec;
    }

    public String canonicalizeCanvasJson(String canvasJson) {
        if (!StringUtils.hasText(canvasJson)) {
            return canvasJson;
        }
        try {
            Map<String, Object> canvas = objectMapper.readValue(canvasJson, MAP_TYPE);
            if (canvas == null) {
                throw new IllegalArgumentException("canvasJson must be a JSON object");
            }
            rejectRemovedCanvasFields(canvas);
            rejectUnknownFields(canvas, CANVAS_FIELDS, "canvasJson");
            requireVersion(canvas, "schemaVersion", 1, "canvasJson");
            requireVersion(canvas, "layoutVersion", 1, "canvasJson");
            requireList(canvas, "nodes", "canvasJson");
            requireList(canvas, "edges", "canvasJson");
            return objectMapper.writeValueAsString(toLayoutDocument(canvas));
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("canvasJson is not valid JSON", ex);
        }
    }

    private Map<String, Object> toLayoutDocument(Map<String, Object> canvas) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("schemaVersion", 1);
        document.put("layoutVersion", 1);
        copyMapIfPresent(canvas, document, "viewport");
        copyMapIfPresent(canvas, document, "layout");

        List<Map<String, Object>> layoutNodes = new ArrayList<>();
        for (Map<String, Object> node : maps(canvas.get("nodes"))) {
            String id = trimToNull(text(node.get("id")));
            if (!StringUtils.hasText(id)) {
                continue;
            }
            Map<String, Object> layoutNode = new LinkedHashMap<>();
            layoutNode.put("id", id);
            Map<String, Object> position = map(node.get("position"));
            if (hasCoordinate(position)) {
                layoutNode.put("position", Map.of("x", position.get("x"), "y", position.get("y")));
            }
            copyNumberIfPresent(node, layoutNode, "width");
            copyNumberIfPresent(node, layoutNode, "height");
            Object collapsed = node.get("collapsed");
            if (collapsed instanceof Boolean) {
                layoutNode.put("collapsed", collapsed);
            }
            layoutNodes.add(layoutNode);
        }
        document.put("nodes", layoutNodes);

        List<Map<String, Object>> layoutEdges = new ArrayList<>();
        for (Map<String, Object> edge : maps(canvas.get("edges"))) {
            String id = trimToNull(text(edge.get("id")));
            if (!StringUtils.hasText(id)) {
                continue;
            }
            Map<String, Object> layoutEdge = new LinkedHashMap<>();
            layoutEdge.put("id", id);
            String label = trimToNull(text(edge.get("label")));
            String style = trimToNull(text(edge.get("style")));
            if (StringUtils.hasText(label)) {
                layoutEdge.put("label", label);
            }
            if (StringUtils.hasText(style)) {
                layoutEdge.put("style", style);
            }
            layoutEdges.add(layoutEdge);
        }
        document.put("edges", layoutEdges);
        return document;
    }

    private boolean isBoundaryNode(GraphSpec.Node node) {
        return isBoundaryEndpoint(node.getId())
                || isBoundaryEndpoint(node.getType());
    }

    private boolean isBoundaryEndpoint(String value) {
        return isStartBoundary(value) || isEndBoundary(value);
    }

    private boolean isStartBoundary(String value) {
        return "START".equalsIgnoreCase(trimToNull(value));
    }

    private boolean isEndBoundary(String value) {
        return "END".equalsIgnoreCase(trimToNull(value));
    }

    private void copyMapIfPresent(Map<String, Object> source,
                                  Map<String, Object> target,
                                  String key) {
        Map<String, Object> value = map(source.get(key));
        if (!value.isEmpty()) {
            target.put(key, value);
        }
    }

    private void copyNumberIfPresent(Map<String, Object> source,
                                     Map<String, Object> target,
                                     String key) {
        Object value = source.get(key);
        if (value instanceof Number) {
            target.put(key, value);
        }
    }

    private void rejectRemovedGraphSpecFields(Map<String, Object> document) {
        if (document == null) {
            throw new IllegalArgumentException("graphSpecJson must be a JSON object");
        }
        for (String field : List.of("entry", "finish", "code", "name", "mode", "runtimeHint", "layout")) {
            if (document.containsKey(field)) {
                throw new IllegalArgumentException("GraphSpec field has been removed: " + field);
            }
        }
        for (Map<String, Object> node : maps(document.get("nodes"))) {
            rejectUnknownFields(node, GRAPH_NODE_FIELDS, "GraphSpec node");
            if (node.containsKey("layout")) {
                throw new IllegalArgumentException("GraphSpec node.layout has been removed; use canvasJson");
            }
            Map<String, Object> config = map(node.get("config"));
            for (String field : List.of("ui", "collapsed", "category")) {
                if (config.containsKey(field)) {
                    throw new IllegalArgumentException(
                            "GraphSpec node.config." + field + " has been removed; use canvasJson");
                }
            }
            requireCanonicalNodeType(text(node.get("type")));
        }
        for (Map<String, Object> edge : maps(document.get("edges"))) {
            rejectUnknownFields(edge, GRAPH_EDGE_FIELDS, "GraphSpec edge");
            if (edge.containsKey("layout")) {
                throw new IllegalArgumentException("GraphSpec edge.layout has been removed; use canvasJson");
            }
        }
    }

    private void rejectRemovedCanvasFields(Map<String, Object> canvas) {
        if (canvas.containsKey("version")) {
            throw new IllegalArgumentException("canvasJson field has been removed: version");
        }
        for (Map<String, Object> node : maps(canvas.get("nodes"))) {
            rejectUnknownFields(node, CANVAS_NODE_FIELDS, "canvasJson node");
            for (String field : List.of("type", "data", "layout")) {
                if (node.containsKey(field)) {
                    throw new IllegalArgumentException(
                            "canvasJson node." + field + " has been removed from the layout contract");
                }
            }
        }
        for (Map<String, Object> edge : maps(canvas.get("edges"))) {
            rejectUnknownFields(edge, CANVAS_EDGE_FIELDS, "canvasJson edge");
            for (String field : List.of("source", "target", "condition", "type")) {
                if (edge.containsKey(field)) {
                    throw new IllegalArgumentException(
                            "canvasJson edge." + field + " has been removed from the layout contract");
                }
            }
        }
    }

    private void requireVersion(Map<String, Object> document,
                                String field,
                                int expected,
                                String label) {
        Object value = document.get(field);
        if (!(value instanceof Number number) || number.intValue() != expected) {
            throw new IllegalArgumentException(label + " " + field + " must be " + expected);
        }
    }

    private void requireList(Map<String, Object> document,
                             String field,
                             String label) {
        if (!(document.get(field) instanceof List<?>)) {
            throw new IllegalArgumentException(label + " " + field + " must be an array");
        }
    }

    private void requireStringItems(Object value, String label) {
        if (!(value instanceof List<?> list) || list.stream().anyMatch(item -> !(item instanceof String))) {
            throw new IllegalArgumentException(label + " must contain only strings");
        }
    }

    private AgentGraphNodeType requireCanonicalNodeType(String rawType) {
        AgentGraphNodeType type = AgentGraphNodeType.find(rawType).orElse(null);
        if (type == null || !type.type().equals(rawType)) {
            throw new IllegalArgumentException("GraphSpec node.type must use a canonical value: " + rawType);
        }
        return type;
    }

    private void requireCanonicalToolRef(AgentGraphNodeType nodeType, GraphSpec.CapabilityRef ref) {
        if (nodeType != AgentGraphNodeType.TOOL || ref == null || !StringUtils.hasText(ref.getKind())) {
            return;
        }
        if (!"TOOL".equals(ref.getKind())) {
            throw new IllegalArgumentException("GraphSpec TOOL node ref.kind must be TOOL");
        }
    }

    private void rejectUnknownFields(Map<String, Object> document,
                                     Set<String> allowed,
                                     String label) {
        for (String field : document.keySet()) {
            if (!allowed.contains(field)) {
                throw new IllegalArgumentException(label + " contains unsupported field: " + field);
            }
        }
    }

    private boolean hasCoordinate(Map<String, Object> position) {
        return position.get("x") instanceof Number && position.get("y") instanceof Number;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?>) {
                result.add(objectMapper.convertValue(item, MAP_TYPE));
            }
        }
        return result;
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?>)) {
            return new LinkedHashMap<>();
        }
        return objectMapper.convertValue(value, MAP_TYPE);
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String trimToEmpty(String value) {
        return StringUtils.hasText(value) ? value.trim() : "";
    }
}
