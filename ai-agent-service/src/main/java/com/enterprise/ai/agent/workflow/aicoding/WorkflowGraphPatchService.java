package com.enterprise.ai.agent.workflow.aicoding;

import com.enterprise.ai.agent.graph.AgentGraphNodeType;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Getter;
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
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WorkflowGraphPatchService {

    private static final String END = "END";
    private static final double LAYOUT_ORIGIN_X = 80;
    private static final double LAYOUT_ORIGIN_Y = 120;
    private static final double LAYOUT_LEVEL_GAP = 260;
    private static final double LAYOUT_LANE_GAP = 150;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public PatchResult apply(GraphSpec baseGraph,
                             Map<String, Object> baseCanvas,
                             List<WorkflowGraphPatchOperation> operations,
                             boolean autoLayout) {
        if (baseGraph == null) {
            throw new IllegalArgumentException("base GraphSpec is required");
        }
        MutableGraph graph = MutableGraph.from(baseGraph);
        Map<String, Object> canvas = mutableCanvas(baseCanvas);
        List<String> errors = new ArrayList<>();
        Set<String> changedNodes = new LinkedHashSet<>();
        Set<String> changedEdges = new LinkedHashSet<>();

        List<WorkflowGraphPatchOperation> safeOperations = operations == null ? List.of() : operations;
        for (int index = 0; index < safeOperations.size(); index++) {
            WorkflowGraphPatchOperation operation = safeOperations.get(index);
            if (operation == null || operation.getOp() == null) {
                errors.add("operation[" + index + "] requires op");
                continue;
            }
            try {
                switch (operation.getOp()) {
                    case ADD_NODE -> applyAddNode(graph, canvas, operation, autoLayout, changedNodes);
                    case UPDATE_NODE -> applyUpdateNode(graph, canvas, operation, changedNodes);
                    case DELETE_NODE -> applyDeleteNode(graph, canvas, operation, changedNodes, changedEdges);
                    case ADD_EDGE -> applyAddEdge(graph, canvas, operation, changedEdges);
                    case DELETE_EDGE -> applyDeleteEdge(graph, canvas, operation, changedEdges);
                    case SET_ENTRY -> applySetEntry(graph, operation);
                    default -> errors.add("unsupported op: " + operation.getOp());
                }
            } catch (IllegalArgumentException ex) {
                errors.add("operation[" + index + "] " + operation.getOp() + ": " + ex.getMessage());
            }
        }

        GraphSpec patched = graph.toGraphSpec();
        if (autoLayout) {
            applyRankBasedLayout(canvas, patched);
        }
        return PatchResult.builder()
                .graphSpec(patched)
                .canvas(canvas)
                .changedNodes(List.copyOf(changedNodes))
                .changedEdges(List.copyOf(changedEdges))
                .errors(errors)
                .build();
    }

    /**
     * Applies rank-based canvas layout aligned with Workflow Studio auto-layout.
     * When {@code autoLayout} is false, returns a mutable copy without repositioning nodes.
     */
    public Map<String, Object> layoutCanvas(GraphSpec graphSpec, Map<String, Object> canvas, boolean autoLayout) {
        Map<String, Object> mutable = mutableCanvas(canvas);
        if (autoLayout && graphSpec != null) {
            applyRankBasedLayout(mutable, graphSpec);
        }
        return mutable;
    }

    private void applyRankBasedLayout(Map<String, Object> canvas, GraphSpec graphSpec) {
        syncCanvasNodesFromGraph(canvas, graphSpec);
        List<Map<String, Object>> nodes = canvasNodes(canvas);
        if (nodes.isEmpty()) {
            return;
        }

        Map<String, Integer> ranks = computeNodeRanks(graphSpec, nodes);
        int maxRank = ranks.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<String> orderedNodeIds = buildStableNodeOrder(nodes, graphSpec);
        Map<Integer, Integer> lanes = new LinkedHashMap<>();

        for (String nodeId : orderedNodeIds) {
            Map<String, Object> canvasNode = findCanvasNode(nodes, nodeId);
            if (canvasNode == null) {
                continue;
            }
            int level = ranks.getOrDefault(nodeId, maxRank + 1);
            int lane = lanes.getOrDefault(level, 0);
            lanes.put(level, lane + 1);

            Map<String, Object> position = new LinkedHashMap<>();
            position.put("x", LAYOUT_ORIGIN_X + level * LAYOUT_LEVEL_GAP);
            position.put("y", LAYOUT_ORIGIN_Y + lane * LAYOUT_LANE_GAP);
            canvasNode.put("position", position);
        }
        canvas.put("nodes", nodes);
    }

    private void syncCanvasNodesFromGraph(Map<String, Object> canvas, GraphSpec graphSpec) {
        if (graphSpec.getNodes() == null || graphSpec.getNodes().isEmpty()) {
            return;
        }
        List<Map<String, Object>> nodes = canvasNodes(canvas);
        Set<String> existingIds = nodes.stream()
                .map(node -> text(node.get("id")))
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (GraphSpec.Node node : graphSpec.getNodes()) {
            if (node == null || !StringUtils.hasText(node.getId())) {
                continue;
            }
            String nodeId = node.getId().trim();
            if (existingIds.contains(nodeId)) {
                continue;
            }
            syncCanvasNodeAdd(canvas, node, false);
            existingIds.add(nodeId);
        }
    }

    private Map<String, Integer> computeNodeRanks(GraphSpec graphSpec, List<Map<String, Object>> canvasNodes) {
        Map<String, List<String>> outgoing = buildOutgoingAdjacency(graphSpec);
        Set<String> graphNodeIds = collectGraphNodeIds(graphSpec);
        Set<String> incomingTargets = collectIncomingTargets(graphSpec);

        List<String> seeds = new ArrayList<>();
        if (StringUtils.hasText(graphSpec.getEntry())) {
            seeds.add(graphSpec.getEntry().trim());
        }
        for (String nodeId : graphNodeIds) {
            if (!incomingTargets.contains(nodeId) && !seeds.contains(nodeId)) {
                seeds.add(nodeId);
            }
        }
        if (seeds.isEmpty() && !canvasNodes.isEmpty()) {
            String fallback = text(canvasNodes.get(0).get("id"));
            if (StringUtils.hasText(fallback)) {
                seeds.add(fallback);
            }
        }

        Map<String, Integer> ranks = new LinkedHashMap<>();
        List<String> queue = new ArrayList<>();
        int maxReachableRank = Math.max(0, Math.max(graphNodeIds.size(), canvasNodes.size()) - 1);
        for (String seed : seeds) {
            if (!graphNodeIds.contains(seed) && canvasNodes.stream().noneMatch(node -> seed.equals(text(node.get("id"))))) {
                continue;
            }
            ranks.putIfAbsent(seed, 0);
            if (!queue.contains(seed)) {
                queue.add(seed);
            }
        }

        for (int index = 0; index < queue.size(); index++) {
            String current = queue.get(index);
            int currentRank = ranks.getOrDefault(current, 0);
            for (String target : outgoing.getOrDefault(current, List.of())) {
                if (END.equalsIgnoreCase(target) || !graphNodeIds.contains(target)) {
                    continue;
                }
                int nextRank = currentRank + 1;
                if (nextRank > maxReachableRank) {
                    continue;
                }
                Integer existing = ranks.get(target);
                if (existing == null || existing < nextRank) {
                    ranks.put(target, nextRank);
                    queue.add(target);
                }
            }
        }

        int maxRank = ranks.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        for (String nodeId : graphNodeIds) {
            ranks.putIfAbsent(nodeId, maxRank + 1);
        }
        for (Map<String, Object> node : canvasNodes) {
            String nodeId = text(node.get("id"));
            if (StringUtils.hasText(nodeId)) {
                ranks.putIfAbsent(nodeId, maxRank + 1);
            }
        }
        return ranks;
    }

    private Map<String, List<String>> buildOutgoingAdjacency(GraphSpec graphSpec) {
        Map<String, List<String>> outgoing = new LinkedHashMap<>();
        if (graphSpec.getEdges() == null) {
            return outgoing;
        }
        for (GraphSpec.Edge edge : graphSpec.getEdges()) {
            if (edge == null || !StringUtils.hasText(edge.getFrom()) || !StringUtils.hasText(edge.getTo())) {
                continue;
            }
            String from = edge.getFrom().trim();
            String to = edge.getTo().trim();
            outgoing.computeIfAbsent(from, key -> new ArrayList<>()).add(to);
        }
        return outgoing;
    }

    private Set<String> collectGraphNodeIds(GraphSpec graphSpec) {
        Set<String> ids = new LinkedHashSet<>();
        if (graphSpec.getNodes() == null) {
            return ids;
        }
        for (GraphSpec.Node node : graphSpec.getNodes()) {
            if (node != null && StringUtils.hasText(node.getId())) {
                ids.add(node.getId().trim());
            }
        }
        return ids;
    }

    private Set<String> collectIncomingTargets(GraphSpec graphSpec) {
        Set<String> incoming = new LinkedHashSet<>();
        if (graphSpec.getEdges() == null) {
            return incoming;
        }
        for (GraphSpec.Edge edge : graphSpec.getEdges()) {
            if (edge == null || !StringUtils.hasText(edge.getTo())) {
                continue;
            }
            String to = edge.getTo().trim();
            if (!END.equalsIgnoreCase(to)) {
                incoming.add(to);
            }
        }
        return incoming;
    }

    private List<String> buildStableNodeOrder(List<Map<String, Object>> canvasNodes, GraphSpec graphSpec) {
        List<String> ordered = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> node : canvasNodes) {
            String nodeId = text(node.get("id"));
            if (StringUtils.hasText(nodeId) && seen.add(nodeId)) {
                ordered.add(nodeId);
            }
        }
        if (graphSpec.getNodes() != null) {
            for (GraphSpec.Node node : graphSpec.getNodes()) {
                if (node != null && StringUtils.hasText(node.getId())) {
                    String nodeId = node.getId().trim();
                    if (seen.add(nodeId)) {
                        ordered.add(nodeId);
                    }
                }
            }
        }
        return ordered;
    }

    private Map<String, Object> findCanvasNode(List<Map<String, Object>> nodes, String nodeId) {
        for (Map<String, Object> node : nodes) {
            if (Objects.equals(text(node.get("id")), nodeId)) {
                return node;
            }
        }
        return null;
    }

    private void applyAddNode(MutableGraph graph,
                              Map<String, Object> canvas,
                              WorkflowGraphPatchOperation operation,
                              boolean autoLayout,
                              Set<String> changedNodes) {
        GraphSpec.Node node = operation.getNode();
        if (node == null || !StringUtils.hasText(node.getId())) {
            throw new IllegalArgumentException("ADD_NODE requires node.id");
        }
        String nodeId = node.getId().trim();
        if (graph.containsNode(nodeId)) {
            throw new IllegalArgumentException("duplicate node id: " + nodeId);
        }
        String type = AgentGraphNodeType.normalize(node.getType());
        if (!AgentGraphNodeType.supports(type)) {
            throw new IllegalArgumentException("unsupported node type: " + node.getType());
        }
        GraphSpec.Node normalized = normalizeNode(node, type);
        graph.addNode(normalized);
        syncCanvasNodeAdd(canvas, normalized, autoLayout);
        changedNodes.add(nodeId);
    }

    private void applyUpdateNode(MutableGraph graph,
                                 Map<String, Object> canvas,
                                 WorkflowGraphPatchOperation operation,
                                 Set<String> changedNodes) {
        String nodeId = requireText(operation.getNodeId(), "UPDATE_NODE requires nodeId");
        GraphSpec.Node existing = graph.requireNode(nodeId);
        Map<String, Object> patch = operation.getPatch();
        if (patch == null || patch.isEmpty()) {
            if (operation.getNode() != null) {
                patch = new LinkedHashMap<>(objectMapper.convertValue(operation.getNode(), MAP_TYPE));
                patch.remove("id");
            }
        }
        if (patch == null || patch.isEmpty()) {
            throw new IllegalArgumentException("UPDATE_NODE requires patch or node fields");
        }
        patch = new LinkedHashMap<>(patch);
        patch.remove("id");
        if (patch.containsKey("type")) {
            String type = AgentGraphNodeType.normalize(String.valueOf(patch.get("type")));
            if (!AgentGraphNodeType.supports(type)) {
                throw new IllegalArgumentException("unsupported node type: " + patch.get("type"));
            }
            patch.put("type", type);
        }
        GraphSpec.Node merged = mergeNode(existing, patch);
        graph.replaceNode(nodeId, merged);
        syncCanvasNodeUpdate(canvas, merged);
        changedNodes.add(nodeId);
    }

    private void applyDeleteNode(MutableGraph graph,
                                 Map<String, Object> canvas,
                                 WorkflowGraphPatchOperation operation,
                                 Set<String> changedNodes,
                                 Set<String> changedEdges) {
        String nodeId = requireText(operation.getNodeId(), "DELETE_NODE requires nodeId");
        if (!graph.containsNode(nodeId)) {
            throw new IllegalArgumentException("node not found: " + nodeId);
        }
        List<String> removedEdges = graph.removeNodeAndEdges(nodeId);
        removeCanvasNode(canvas, nodeId);
        for (String edgeId : removedEdges) {
            removeCanvasEdge(canvas, edgeId);
            changedEdges.add(edgeId);
        }
        changedNodes.add(nodeId);
    }

    private void applyAddEdge(MutableGraph graph,
                              Map<String, Object> canvas,
                              WorkflowGraphPatchOperation operation,
                              Set<String> changedEdges) {
        GraphSpec.Edge edge = operation.getEdge();
        if (edge == null) {
            throw new IllegalArgumentException("ADD_EDGE requires edge");
        }
        String from = requireText(edge.getFrom(), "ADD_EDGE requires edge.from");
        String to = requireText(edge.getTo(), "ADD_EDGE requires edge.to");
        if ("START".equalsIgnoreCase(to)) {
            throw new IllegalArgumentException("edge.to cannot be START");
        }
        if (END.equalsIgnoreCase(from)) {
            throw new IllegalArgumentException("edge.from cannot be END");
        }
        validateEndpoint(graph, from, "edge.from");
        validateEndpoint(graph, to, "edge.to");
        if (graph.hasDuplicateEdge(from, to, edge.getCondition())) {
            throw new IllegalArgumentException("duplicate edge already exists: " + from + " -> " + to);
        }
        String edgeId = StringUtils.hasText(edge.getId())
                ? edge.getId().trim()
                : from + "->" + to + "-" + System.nanoTime();
        if (graph.containsEdge(edgeId)) {
            throw new IllegalArgumentException("duplicate edge id: " + edgeId);
        }
        GraphSpec.Edge normalized = GraphSpec.Edge.builder()
                .id(edgeId)
                .from(from)
                .to(to)
                .condition(edge.getCondition())
                .sourceHandle(edge.getSourceHandle())
                .targetHandle(edge.getTargetHandle())
                .priority(edge.getPriority())
                .layout(edge.getLayout())
                .build();
        graph.addEdge(normalized);
        syncCanvasEdgeAdd(canvas, normalized);
        changedEdges.add(edgeId);
    }

    private void applyDeleteEdge(MutableGraph graph,
                                 Map<String, Object> canvas,
                                 WorkflowGraphPatchOperation operation,
                                 Set<String> changedEdges) {
        String edgeId = requireText(operation.getEdgeId(), "DELETE_EDGE requires edgeId");
        if (!graph.removeEdge(edgeId)) {
            throw new IllegalArgumentException("edge not found: " + edgeId);
        }
        removeCanvasEdge(canvas, edgeId);
        changedEdges.add(edgeId);
    }

    private void applySetEntry(MutableGraph graph, WorkflowGraphPatchOperation operation) {
        String entry = requireText(operation.getEntry(), "SET_ENTRY requires entry");
        if (!graph.containsNode(entry)) {
            throw new IllegalArgumentException("entry node does not exist: " + entry);
        }
        graph.setEntry(entry);
    }

    private GraphSpec.Node normalizeNode(GraphSpec.Node node, String type) {
        GraphSpec.Node.NodeBuilder builder = GraphSpec.Node.builder()
                .id(node.getId().trim())
                .type(type)
                .name(node.getName())
                .description(node.getDescription())
                .ref(node.getRef())
                .inputSchema(node.getInputSchema())
                .outputSchema(node.getOutputSchema())
                .retry(node.getRetry())
                .errorPolicy(node.getErrorPolicy())
                .layout(node.getLayout())
                .config(node.getConfig() == null ? null : new LinkedHashMap<>(node.getConfig()));
        if (node.getInputs() != null) {
            builder.inputs(List.copyOf(node.getInputs()));
        }
        if (node.getOutputs() != null) {
            builder.outputs(List.copyOf(node.getOutputs()));
        }
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private GraphSpec.Node mergeNode(GraphSpec.Node existing, Map<String, Object> patch) {
        Map<String, Object> base = objectMapper.convertValue(existing, MAP_TYPE);
        for (Map.Entry<String, Object> entry : patch.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if ("config".equals(key) && value instanceof Map<?, ?> patchConfig) {
                Map<String, Object> config = base.get("config") instanceof Map<?, ?> existingConfig
                        ? new LinkedHashMap<>((Map<String, Object>) existingConfig)
                        : new LinkedHashMap<>();
                for (Map.Entry<?, ?> configEntry : patchConfig.entrySet()) {
                    config.put(String.valueOf(configEntry.getKey()), configEntry.getValue());
                }
                base.put("config", config);
            } else if (value != null) {
                base.put(key, value);
            }
        }
        return objectMapper.convertValue(base, GraphSpec.Node.class);
    }

    private void validateEndpoint(MutableGraph graph, String endpoint, String field) {
        if ("START".equalsIgnoreCase(endpoint) || END.equalsIgnoreCase(endpoint)) {
            return;
        }
        if (!graph.containsNode(endpoint)) {
            throw new IllegalArgumentException(field + " node does not exist: " + endpoint);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mutableCanvas(Map<String, Object> rawCanvas) {
        Map<String, Object> canvas = rawCanvas == null || rawCanvas.isEmpty()
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(objectMapper.convertValue(rawCanvas, MAP_TYPE));
        canvas.putIfAbsent("version", 2);
        canvas.putIfAbsent("nodes", new ArrayList<Map<String, Object>>());
        canvas.putIfAbsent("edges", new ArrayList<Map<String, Object>>());
        canvas.computeIfPresent("nodes", (key, value) -> mutableList(value));
        canvas.computeIfPresent("edges", (key, value) -> mutableList(value));
        return canvas;
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
    private List<Map<String, Object>> canvasNodes(Map<String, Object> canvas) {
        Object nodes = canvas.get("nodes");
        if (!(nodes instanceof List<?> list)) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add(objectMapper.convertValue(map, MAP_TYPE));
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> canvasEdges(Map<String, Object> canvas) {
        Object edges = canvas.get("edges");
        if (!(edges instanceof List<?> list)) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add(objectMapper.convertValue(map, MAP_TYPE));
            }
        }
        return result;
    }

    private void syncCanvasNodeAdd(Map<String, Object> canvas, GraphSpec.Node node, boolean autoLayout) {
        List<Map<String, Object>> nodes = canvasNodes(canvas);
        for (Map<String, Object> existing : nodes) {
            if (Objects.equals(text(existing.get("id")), node.getId())) {
                return;
            }
        }
        Map<String, Object> position = new LinkedHashMap<>();
        if (autoLayout) {
            position.put("x", nextCanvasX(nodes));
            position.put("y", nextCanvasY(nodes));
        } else {
            position.put("x", 0);
            position.put("y", 0);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        if (node.getConfig() != null) {
            data.putAll(node.getConfig());
        }
        data.put("label", firstText(node.getName(), node.getId()));
        data.put("kind", canvasKind(node.getType()));
        Map<String, Object> canvasNode = new LinkedHashMap<>();
        canvasNode.put("id", node.getId());
        canvasNode.put("type", canvasKind(node.getType()));
        canvasNode.put("position", position);
        canvasNode.put("data", data);
        nodes.add(canvasNode);
        canvas.put("nodes", nodes);
    }

    private void syncCanvasNodeUpdate(Map<String, Object> canvas, GraphSpec.Node node) {
        List<Map<String, Object>> nodes = canvasNodes(canvas);
        for (Map<String, Object> canvasNode : nodes) {
            if (!Objects.equals(text(canvasNode.get("id")), node.getId())) {
                continue;
            }
            Map<String, Object> data = canvasNode.get("data") instanceof Map<?, ?> raw
                    ? objectMapper.convertValue(raw, MAP_TYPE)
                    : new LinkedHashMap<>();
            if (node.getConfig() != null) {
                data.putAll(node.getConfig());
            }
            if (StringUtils.hasText(node.getName())) {
                data.put("label", node.getName());
            }
            if (StringUtils.hasText(node.getType())) {
                data.put("kind", canvasKind(node.getType()));
                canvasNode.put("type", canvasKind(node.getType()));
            }
            canvasNode.put("data", data);
            canvas.put("nodes", nodes);
            return;
        }
        syncCanvasNodeAdd(canvas, node, true);
    }

    private void syncCanvasEdgeAdd(Map<String, Object> canvas, GraphSpec.Edge edge) {
        List<Map<String, Object>> edges = canvasEdges(canvas);
        for (Map<String, Object> existing : edges) {
            if (Objects.equals(text(existing.get("id")), edge.getId())) {
                return;
            }
        }
        Map<String, Object> canvasEdge = new LinkedHashMap<>();
        canvasEdge.put("id", edge.getId());
        canvasEdge.put("source", edge.getFrom());
        canvasEdge.put("target", edge.getTo());
        if (StringUtils.hasText(edge.getCondition())) {
            canvasEdge.put("condition", edge.getCondition());
            canvasEdge.put("label", edge.getCondition());
        }
        if (StringUtils.hasText(edge.getSourceHandle())) {
            canvasEdge.put("sourceHandle", edge.getSourceHandle());
        }
        if (StringUtils.hasText(edge.getTargetHandle())) {
            canvasEdge.put("targetHandle", edge.getTargetHandle());
        }
        edges.add(canvasEdge);
        canvas.put("edges", edges);
    }

    private void removeCanvasNode(Map<String, Object> canvas, String nodeId) {
        List<Map<String, Object>> nodes = canvasNodes(canvas).stream()
                .filter(node -> !Objects.equals(text(node.get("id")), nodeId))
                .collect(Collectors.toCollection(ArrayList::new));
        canvas.put("nodes", nodes);
    }

    private void removeCanvasEdge(Map<String, Object> canvas, String edgeId) {
        List<Map<String, Object>> edges = canvasEdges(canvas).stream()
                .filter(edge -> !Objects.equals(text(edge.get("id")), edgeId))
                .collect(Collectors.toCollection(ArrayList::new));
        canvas.put("edges", edges);
    }

    private double nextCanvasX(List<Map<String, Object>> nodes) {
        double max = 0;
        for (Map<String, Object> node : nodes) {
            max = Math.max(max, positionAxis(node, "x"));
        }
        return max + 220;
    }

    private double nextCanvasY(List<Map<String, Object>> nodes) {
        if (nodes.isEmpty()) {
            return 80;
        }
        return positionAxis(nodes.get(nodes.size() - 1), "y");
    }

    @SuppressWarnings("unchecked")
    private double positionAxis(Map<String, Object> node, String axis) {
        Object position = node.get("position");
        if (!(position instanceof Map<?, ?> map)) {
            return 0;
        }
        Object value = map.get(axis);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private String canvasKind(String type) {
        return AgentGraphNodeType.find(type)
                .map(AgentGraphNodeType::canvasKind)
                .orElse(type == null ? "" : type.toLowerCase(Locale.ROOT));
    }

    private String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String firstText(String first, String fallback) {
        return StringUtils.hasText(first) ? first.trim() : fallback;
    }

    @Getter
    @Builder
    public static class PatchResult {
        private final GraphSpec graphSpec;
        private final Map<String, Object> canvas;
        private final List<String> changedNodes;
        private final List<String> changedEdges;
        private final List<String> errors;
    }

    private static final class MutableGraph {
        private String code;
        private String name;
        private String mode;
        private String runtimeHint;
        private Map<String, Object> inputSchema;
        private Map<String, Object> stateSchema;
        private GraphSpec.Layout layout;
        private final LinkedHashMap<String, GraphSpec.Node> nodes = new LinkedHashMap<>();
        private final LinkedHashMap<String, GraphSpec.Edge> edges = new LinkedHashMap<>();
        private String entry;
        private final List<String> finish = new ArrayList<>();

        static MutableGraph from(GraphSpec graph) {
            MutableGraph mutable = new MutableGraph();
            mutable.code = graph.getCode();
            mutable.name = graph.getName();
            mutable.mode = graph.getMode();
            mutable.runtimeHint = graph.getRuntimeHint();
            mutable.inputSchema = graph.getInputSchema();
            mutable.stateSchema = graph.getStateSchema();
            mutable.layout = graph.getLayout();
            mutable.entry = graph.getEntry();
            if (graph.getFinish() != null) {
                mutable.finish.addAll(graph.getFinish());
            }
            if (graph.getNodes() != null) {
                for (GraphSpec.Node node : graph.getNodes()) {
                    if (node != null && StringUtils.hasText(node.getId())) {
                        mutable.nodes.put(node.getId().trim(), node);
                    }
                }
            }
            if (graph.getEdges() != null) {
                for (GraphSpec.Edge edge : graph.getEdges()) {
                    if (edge != null && StringUtils.hasText(edge.getId())) {
                        mutable.edges.put(edge.getId().trim(), edge);
                    }
                }
            }
            return mutable;
        }

        GraphSpec toGraphSpec() {
            GraphSpec.GraphSpecBuilder builder = GraphSpec.builder()
                    .code(code)
                    .name(name)
                    .mode(mode)
                    .runtimeHint(runtimeHint)
                    .inputSchema(inputSchema)
                    .stateSchema(stateSchema)
                    .layout(layout)
                    .entry(entry);
            for (String finishNode : finish) {
                builder.finishNode(finishNode);
            }
            for (GraphSpec.Node node : nodes.values()) {
                builder.node(node);
            }
            for (GraphSpec.Edge edge : edges.values()) {
                builder.edge(edge);
            }
            return builder.build();
        }

        boolean containsNode(String nodeId) {
            return nodes.containsKey(nodeId);
        }

        GraphSpec.Node requireNode(String nodeId) {
            GraphSpec.Node node = nodes.get(nodeId);
            if (node == null) {
                throw new IllegalArgumentException("node not found: " + nodeId);
            }
            return node;
        }

        void addNode(GraphSpec.Node node) {
            nodes.put(node.getId(), node);
        }

        void replaceNode(String nodeId, GraphSpec.Node node) {
            nodes.put(nodeId, node);
        }

        List<String> removeNodeAndEdges(String nodeId) {
            nodes.remove(nodeId);
            finish.removeIf(item -> Objects.equals(item, nodeId));
            if (Objects.equals(entry, nodeId)) {
                entry = null;
            }
            List<String> removed = new ArrayList<>();
            edges.entrySet().removeIf(item -> {
                GraphSpec.Edge edge = item.getValue();
                if (Objects.equals(edge.getFrom(), nodeId) || Objects.equals(edge.getTo(), nodeId)) {
                    removed.add(item.getKey());
                    return true;
                }
                return false;
            });
            return removed;
        }

        boolean containsEdge(String edgeId) {
            return edges.containsKey(edgeId);
        }

        void addEdge(GraphSpec.Edge edge) {
            edges.put(edge.getId(), edge);
        }

        boolean removeEdge(String edgeId) {
            return edges.remove(edgeId) != null;
        }

        boolean hasDuplicateEdge(String from, String to, String condition) {
            String normalizedCondition = condition == null ? "" : condition.trim();
            for (GraphSpec.Edge edge : edges.values()) {
                if (!Objects.equals(edge.getFrom(), from) || !Objects.equals(edge.getTo(), to)) {
                    continue;
                }
                String existingCondition = edge.getCondition() == null ? "" : edge.getCondition().trim();
                if (existingCondition.equals(normalizedCondition)) {
                    return true;
                }
            }
            return false;
        }

        void setEntry(String entry) {
            this.entry = entry;
        }
    }
}
