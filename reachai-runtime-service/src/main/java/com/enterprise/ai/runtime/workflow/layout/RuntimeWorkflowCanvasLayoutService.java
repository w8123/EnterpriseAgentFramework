package com.enterprise.ai.runtime.workflow.layout;

import com.enterprise.ai.agent.graph.AgentGraphNodeType;
import com.enterprise.ai.agent.graph.GraphSpec;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Projects GraphSpec into the visual canvas and applies deterministic, cycle-safe layout coordinates.
 * GraphSpec remains the semantic source of truth; this service only mutates canvas data.
 */
@Service
@RequiredArgsConstructor
public class RuntimeWorkflowCanvasLayoutService {

    public static final String ENGINE = "layered-v2";
    public static final int DEFAULT_COLUMN_GAP = 88;
    public static final int DEFAULT_ROW_GAP = 56;
    public static final int DEFAULT_COMPONENT_GAP = 96;
    public static final int DEFAULT_MARGIN = 80;

    private static final int DEFAULT_NODE_WIDTH = 240;
    private static final int DEFAULT_NODE_HEIGHT = 156;
    private static final int WIDE_NODE_WIDTH = 300;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public Map<String, Object> projectAndLayout(GraphSpec graph,
                                                Map<String, Object> currentCanvas,
                                                Options options) {
        Map<String, Object> canvas = mutableCanvas(currentCanvas);
        reconcileNodes(graph, canvas);
        reconcileEdges(graph, canvas);
        return layoutCanvas(canvas, options);
    }

    public Map<String, Object> layoutCanvas(Map<String, Object> currentCanvas, Options options) {
        Map<String, Object> canvas = mutableCanvas(currentCanvas);
        Options actual = options == null ? Options.defaults() : options.normalized();
        Set<String> positionedNodeIds = new HashSet<>();
        for (Map<String, Object> node : nodes(canvas)) {
            if (hasPosition(node)) {
                positionedNodeIds.add(text(node.get("id")));
            }
        }

        Map<String, Point> positions = calculatePositions(
                nodes(canvas),
                edges(canvas),
                actual.columnGap(),
                actual.rowGap());
        for (Map<String, Object> node : nodes(canvas)) {
            String nodeId = text(node.get("id"));
            Point point = positions.get(nodeId);
            if (point == null || (!actual.autoLayout() && positionedNodeIds.contains(nodeId))) {
                continue;
            }
            node.put("position", Map.of("x", point.x(), "y", point.y()));
        }

        canvas.put("version", 2);
        canvas.put("layoutVersion", 2);
        canvas.put("layout", Map.of(
                "engine", ENGINE,
                "direction", "LR",
                "columnGap", actual.columnGap(),
                "rowGap", actual.rowGap(),
                "autoLayout", actual.autoLayout()));
        return canvas;
    }

    private void reconcileNodes(GraphSpec graph, Map<String, Object> canvas) {
        Map<String, Map<String, Object>> existing = new LinkedHashMap<>();
        for (Map<String, Object> node : nodes(canvas)) {
            String id = text(node.get("id"));
            if (StringUtils.hasText(id)) {
                existing.put(id, node);
            }
        }

        List<Map<String, Object>> projected = new ArrayList<>();
        projected.add(boundaryNode(existing.get("start"), "start", "开始"));
        if (graph != null && graph.getNodes() != null) {
            for (GraphSpec.Node graphNode : graph.getNodes()) {
                if (graphNode == null || !StringUtils.hasText(graphNode.getId())) {
                    continue;
                }
                projected.add(graphNode(existing.get(graphNode.getId()), graphNode));
            }
        }
        projected.add(boundaryNode(existing.get("end"), "end", "结束"));
        canvas.put("nodes", projected);
    }

    private Map<String, Object> boundaryNode(Map<String, Object> existing, String id, String label) {
        Map<String, Object> node = existing == null ? new LinkedHashMap<>() : mutableMap(existing);
        Map<String, Object> data = mutableMap(node.get("data"));
        data.put("label", label);
        data.put("kind", id);
        data.put("configVersion", 2);
        node.put("id", id);
        node.put("type", id);
        node.put("data", data);
        return node;
    }

    private Map<String, Object> graphNode(Map<String, Object> existing, GraphSpec.Node graphNode) {
        Map<String, Object> node = existing == null ? new LinkedHashMap<>() : mutableMap(existing);
        Map<String, Object> data = mutableMap(node.get("data"));
        if (graphNode.getConfig() != null) {
            data.putAll(mutableMap(graphNode.getConfig()));
        }
        String kind = AgentGraphNodeType.find(graphNode.getType())
                .map(AgentGraphNodeType::canvasKind)
                .orElseGet(() -> defaultKind(graphNode.getType()));
        data.put("label", firstText(graphNode.getName(), graphNode.getId()));
        data.put("kind", kind);
        data.put("configVersion", 2);
        if (StringUtils.hasText(graphNode.getDescription())) {
            data.put("description", graphNode.getDescription());
        }
        AgentGraphNodeType.find(graphNode.getType())
                .map(AgentGraphNodeType::canvasCategory)
                .ifPresent(category -> data.put("category", category));
        if (graphNode.getLayout() != null && graphNode.getLayout().getCollapsed() != null) {
            data.put("collapsed", graphNode.getLayout().getCollapsed());
        }
        if (graphNode.getLayout() != null) {
            putPositiveDimension(node, "width", graphNode.getLayout().getWidth());
            putPositiveDimension(node, "height", graphNode.getLayout().getHeight());
        }
        node.put("id", graphNode.getId());
        node.put("type", kind);
        node.put("data", data);
        return node;
    }

    private void reconcileEdges(GraphSpec graph, Map<String, Object> canvas) {
        Set<String> validNodeIds = new LinkedHashSet<>();
        for (Map<String, Object> node : nodes(canvas)) {
            String nodeId = text(node.get("id"));
            if (StringUtils.hasText(nodeId)) {
                validNodeIds.add(nodeId);
            }
        }

        Map<String, Map<String, Object>> existingById = new LinkedHashMap<>();
        for (Map<String, Object> edge : edges(canvas)) {
            String id = text(edge.get("id"));
            if (StringUtils.hasText(id)) {
                existingById.put(id, edge);
            }
        }

        List<EdgeProjection> desired = new ArrayList<>();
        if (graph != null && graph.getEdges() != null) {
            int index = 1;
            for (GraphSpec.Edge graphEdge : graph.getEdges()) {
                if (graphEdge == null) continue;
                String source = canvasEndpoint(graphEdge.getFrom());
                String target = canvasEndpoint(graphEdge.getTo());
                if (!StringUtils.hasText(source)
                        || !StringUtils.hasText(target)
                        || !validNodeIds.contains(source)
                        || !validNodeIds.contains(target)) continue;
                String id = firstText(graphEdge.getId(), "e-" + index++ + "-" + source + "-" + target);
                String label = graphEdge.getLayout() == null ? null : graphEdge.getLayout().getLabel();
                desired.add(new EdgeProjection(
                        id,
                        source,
                        target,
                        firstText(graphEdge.getCondition(), "always"),
                        blankToNull(graphEdge.getSourceHandle()),
                        blankToNull(graphEdge.getTargetHandle()),
                        blankToNull(label)));
            }
        }

        Set<String> existingPairs = new HashSet<>();
        for (EdgeProjection edge : desired) {
            existingPairs.add(edge.source() + "->" + edge.target());
        }
        if (graph != null && StringUtils.hasText(graph.getEntry())) {
            String target = canvasEndpoint(graph.getEntry());
            if (validNodeIds.contains(target) && !existingPairs.contains("start->" + target)) {
                desired.add(0, new EdgeProjection(
                        "e-start-" + target,
                        "start",
                        target,
                        "always",
                        null,
                        null,
                        null));
            }
        }

        Set<String> finish = new LinkedHashSet<>();
        if (graph != null && graph.getFinish() != null) {
            graph.getFinish().stream()
                    .filter(StringUtils::hasText)
                    .map(this::canvasEndpoint)
                    .filter(validNodeIds::contains)
                    .forEach(finish::add);
        }
        for (String source : finish) {
            if (!existingPairs.contains(source + "->end")) {
                desired.add(new EdgeProjection(
                        "e-" + source + "-end",
                        source,
                        "end",
                        "always",
                        null,
                        null,
                        null));
            }
        }

        List<Map<String, Object>> projected = new ArrayList<>();
        for (EdgeProjection edge : desired) {
            Map<String, Object> item = existingById.containsKey(edge.id())
                    ? mutableMap(existingById.get(edge.id()))
                    : new LinkedHashMap<>();
            item.put("id", edge.id());
            item.put("source", edge.source());
            item.put("target", edge.target());
            item.put("condition", edge.condition());
            item.put("type", "smoothstep");
            putOrRemove(item, "sourceHandle", edge.sourceHandle());
            putOrRemove(item, "targetHandle", edge.targetHandle());
            putOrRemove(item, "label", edge.label());
            projected.add(item);
        }
        canvas.put("edges", projected);
    }

    private Map<String, Point> calculatePositions(List<Map<String, Object>> canvasNodes,
                                                  List<Map<String, Object>> canvasEdges,
                                                  int columnGap,
                                                  int rowGap) {
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> node : canvasNodes) {
            String id = text(node.get("id"));
            if (StringUtils.hasText(id)) byId.put(id, node);
        }
        List<CanvasLink> links = new ArrayList<>();
        int edgeIndex = 0;
        for (Map<String, Object> edge : canvasEdges) {
            String source = text(edge.get("source"));
            String target = text(edge.get("target"));
            if (byId.containsKey(source) && byId.containsKey(target)) {
                links.add(new CanvasLink(edgeIndex++, text(edge.get("id")), source, target));
            }
        }

        List<List<String>> components = weakComponents(byId.keySet(), links);
        Map<String, Point> positions = new LinkedHashMap<>();
        int nextComponentY = DEFAULT_MARGIN;
        for (List<String> component : components) {
            ComponentLayout layout = layoutComponent(component, links, byId, columnGap, rowGap);
            for (Map.Entry<String, Point> entry : layout.positions().entrySet()) {
                Point point = entry.getValue();
                positions.put(entry.getKey(), new Point(
                        DEFAULT_MARGIN + point.x(),
                        nextComponentY + point.y()));
            }
            nextComponentY += layout.height() + DEFAULT_COMPONENT_GAP;
        }
        return positions;
    }

    private List<List<String>> weakComponents(Set<String> nodeIds, List<CanvasLink> links) {
        Map<String, Set<String>> neighbors = new HashMap<>();
        for (String nodeId : nodeIds) neighbors.put(nodeId, new LinkedHashSet<>());
        for (CanvasLink link : links) {
            neighbors.get(link.source()).add(link.target());
            neighbors.get(link.target()).add(link.source());
        }
        List<String> roots = nodeIds.stream().sorted(this::compareNodeIds).toList();
        List<List<String>> result = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        for (String root : roots) {
            if (!visited.add(root)) continue;
            List<String> component = new ArrayList<>();
            ArrayDeque<String> queue = new ArrayDeque<>();
            queue.add(root);
            while (!queue.isEmpty()) {
                String current = queue.removeFirst();
                component.add(current);
                neighbors.get(current).stream()
                        .sorted(this::compareNodeIds)
                        .filter(visited::add)
                        .forEach(queue::addLast);
            }
            component.sort(this::compareNodeIds);
            result.add(component);
        }
        return result;
    }

    private ComponentLayout layoutComponent(List<String> component,
                                            List<CanvasLink> allLinks,
                                            Map<String, Map<String, Object>> byId,
                                            int columnGap,
                                            int rowGap) {
        Set<String> ids = new LinkedHashSet<>(component);
        List<CanvasLink> links = allLinks.stream()
                .filter(link -> ids.contains(link.source()) && ids.contains(link.target()))
                .sorted(Comparator.comparing(CanvasLink::source, this::compareNodeIds)
                        .thenComparing(CanvasLink::target, this::compareNodeIds)
                        .thenComparing(CanvasLink::id))
                .toList();
        Map<String, List<CanvasLink>> outgoing = new HashMap<>();
        for (String id : component) outgoing.put(id, new ArrayList<>());
        for (CanvasLink link : links) outgoing.get(link.source()).add(link);

        Set<Integer> backEdges = new HashSet<>();
        Map<String, Integer> state = new HashMap<>();
        for (String id : component) {
            if (state.getOrDefault(id, 0) == 0) {
                markBackEdges(id, outgoing, state, backEdges);
            }
        }

        Map<String, Integer> indegree = new HashMap<>();
        Map<String, Integer> rank = new HashMap<>();
        for (String id : component) {
            indegree.put(id, 0);
            rank.put(id, 0);
        }
        for (CanvasLink link : links) {
            if (!backEdges.contains(link.index())) {
                indegree.put(link.target(), indegree.get(link.target()) + 1);
            }
        }
        PriorityQueue<String> queue = new PriorityQueue<>(this::compareNodeIds);
        indegree.forEach((id, degree) -> {
            if (degree == 0) queue.add(id);
        });
        Set<String> processed = new HashSet<>();
        while (!queue.isEmpty()) {
            String current = queue.remove();
            processed.add(current);
            for (CanvasLink link : outgoing.getOrDefault(current, List.of())) {
                if (backEdges.contains(link.index())) continue;
                rank.put(link.target(), Math.max(rank.get(link.target()), rank.get(current) + 1));
                int remaining = indegree.compute(link.target(), (id, value) -> value == null ? 0 : value - 1);
                if (remaining == 0) queue.add(link.target());
            }
        }
        int fallbackRank = rank.values().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
        for (String id : component) {
            if (!processed.contains(id)) rank.put(id, fallbackRank++);
        }

        Map<Integer, List<String>> rankNodes = new LinkedHashMap<>();
        component.stream()
                .sorted(Comparator.<String>comparingInt(rank::get).thenComparing(this::compareNodeIds))
                .forEach(id -> rankNodes.computeIfAbsent(rank.get(id), ignored -> new ArrayList<>()).add(id));
        Map<Integer, Integer> rankWidth = new LinkedHashMap<>();
        Map<Integer, Integer> rankHeight = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<String>> entry : rankNodes.entrySet()) {
            int width = entry.getValue().stream().mapToInt(id -> nodeSize(byId.get(id)).width()).max().orElse(DEFAULT_NODE_WIDTH);
            int height = entry.getValue().stream().mapToInt(id -> nodeSize(byId.get(id)).height()).sum()
                    + Math.max(0, entry.getValue().size() - 1) * rowGap;
            rankWidth.put(entry.getKey(), width);
            rankHeight.put(entry.getKey(), height);
        }
        int maxHeight = rankHeight.values().stream().mapToInt(Integer::intValue).max().orElse(DEFAULT_NODE_HEIGHT);
        Map<Integer, Integer> rankX = new LinkedHashMap<>();
        int x = 0;
        for (Integer rankValue : rankNodes.keySet().stream().sorted().toList()) {
            rankX.put(rankValue, x);
            x += rankWidth.get(rankValue) + columnGap;
        }

        Map<String, Point> positions = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<String>> entry : rankNodes.entrySet()) {
            int y = (maxHeight - rankHeight.get(entry.getKey())) / 2;
            List<String> orderedRankNodes = entry.getValue();
            String firstNodeId = orderedRankNodes.get(0);
            String lastNodeId = orderedRankNodes.get(orderedRankNodes.size() - 1);
            double firstCenter = y + nodeSize(byId.get(firstNodeId)).height() / 2.0;
            double lastCenter = y + rankHeight.get(entry.getKey())
                    - nodeSize(byId.get(lastNodeId)).height() / 2.0;
            int centerOffset = (int) Math.round(maxHeight / 2.0 - (firstCenter + lastCenter) / 2.0);
            y += centerOffset;
            for (String nodeId : entry.getValue()) {
                positions.put(nodeId, new Point(rankX.get(entry.getKey()), y));
                y += nodeSize(byId.get(nodeId)).height() + rowGap;
            }
        }
        int minY = positions.values().stream().mapToInt(Point::y).min().orElse(0);
        int maxBottom = positions.entrySet().stream()
                .mapToInt(entry -> entry.getValue().y() + nodeSize(byId.get(entry.getKey())).height())
                .max()
                .orElse(maxHeight);
        if (minY != 0) {
            positions.replaceAll((id, point) -> new Point(point.x(), point.y() - minY));
        }
        return new ComponentLayout(positions, maxBottom - minY);
    }

    private void markBackEdges(String nodeId,
                               Map<String, List<CanvasLink>> outgoing,
                               Map<String, Integer> state,
                               Set<Integer> backEdges) {
        state.put(nodeId, 1);
        for (CanvasLink link : outgoing.getOrDefault(nodeId, List.of())) {
            int targetState = state.getOrDefault(link.target(), 0);
            if (targetState == 1) {
                backEdges.add(link.index());
            } else if (targetState == 0) {
                markBackEdges(link.target(), outgoing, state, backEdges);
            }
        }
        state.put(nodeId, 2);
    }

    private NodeSize nodeSize(Map<String, Object> node) {
        Map<String, Object> data = mutableMap(node.get("data"));
        if (Boolean.TRUE.equals(data.get("collapsed"))) {
            return new NodeSize(190, 64);
        }
        String kind = firstText(text(data.get("kind")), text(node.get("type")));
        boolean wide = List.of("classifier", "condition", "approval", "loop").contains(kind);
        int routeCount = listSize(mutableMap(data.get("classifierConfig")).get("classes"));
        if (routeCount == 0) {
            routeCount = listSize(mutableMap(data.get("conditionConfig")).get("groups"));
        }
        int height = DEFAULT_NODE_HEIGHT + Math.max(0, routeCount - 2) * 28;
        return new NodeSize(
                positiveInt(node.get("width"), wide ? WIDE_NODE_WIDTH : DEFAULT_NODE_WIDTH),
                positiveInt(node.get("height"), height));
    }

    private int listSize(Object value) {
        return value instanceof List<?> list ? list.size() : 0;
    }

    private boolean hasPosition(Map<String, Object> node) {
        Map<String, Object> position = mutableMap(node.get("position"));
        return position.get("x") instanceof Number && position.get("y") instanceof Number;
    }

    private int compareNodeIds(String left, String right) {
        if ("start".equals(left) && !"start".equals(right)) return -1;
        if (!"start".equals(left) && "start".equals(right)) return 1;
        if ("end".equals(left) && !"end".equals(right)) return 1;
        if (!"end".equals(left) && "end".equals(right)) return -1;
        return left.compareTo(right);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mutableCanvas(Map<String, Object> raw) {
        Map<String, Object> canvas = raw == null ? new LinkedHashMap<>() : mutableMap(raw);
        canvas.putIfAbsent("version", 2);
        Object rawNodes = canvas.get("nodes");
        Object rawEdges = canvas.get("edges");
        canvas.put("nodes", rawNodes instanceof List<?> list ? mutableList(list) : new ArrayList<>());
        canvas.put("edges", rawEdges instanceof List<?> list ? mutableList(list) : new ArrayList<>());
        return canvas;
    }

    private List<Map<String, Object>> mutableList(List<?> source) {
        List<Map<String, Object>> copy = new ArrayList<>();
        for (Object item : source) {
            if (item instanceof Map<?, ?>) copy.add(mutableMap(item));
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> nodes(Map<String, Object> canvas) {
        return (List<Map<String, Object>>) canvas.get("nodes");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> edges(Map<String, Object> canvas) {
        return (List<Map<String, Object>>) canvas.get("edges");
    }

    private Map<String, Object> mutableMap(Object value) {
        if (value == null) return new LinkedHashMap<>();
        return objectMapper.convertValue(value, MAP_TYPE);
    }

    private String defaultKind(String type) {
        return StringUtils.hasText(type) ? type.trim().toLowerCase().replace("_", "") : "tool";
    }

    private String canvasEndpoint(String endpoint) {
        if ("START".equalsIgnoreCase(endpoint)) return "start";
        if ("END".equalsIgnoreCase(endpoint)) return "end";
        return blankToNull(endpoint);
    }

    private void putOrRemove(Map<String, Object> target, String key, Object value) {
        if (value == null) target.remove(key);
        else target.put(key, value);
    }

    private void putPositiveDimension(Map<String, Object> target, String key, Double value) {
        if (value != null && value > 0) target.put(key, value);
    }

    private int positiveInt(Object value, int fallback) {
        if (value instanceof Number number && number.doubleValue() > 0) {
            return (int) Math.round(number.doubleValue());
        }
        return fallback;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) return value.trim();
        }
        return "";
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    public record Options(Boolean requestedAutoLayout,
                          String direction,
                          Integer requestedColumnGap,
                          Integer requestedRowGap) {
        public static Options defaults() {
            return new Options(true, "LR", DEFAULT_COLUMN_GAP, DEFAULT_ROW_GAP);
        }

        private Options normalized() {
            return new Options(
                    requestedAutoLayout == null || requestedAutoLayout,
                    "LR",
                    positiveOrDefault(requestedColumnGap, DEFAULT_COLUMN_GAP),
                    positiveOrDefault(requestedRowGap, DEFAULT_ROW_GAP));
        }

        public boolean autoLayout() {
            return requestedAutoLayout == null || requestedAutoLayout;
        }

        public int columnGap() {
            return positiveOrDefault(requestedColumnGap, DEFAULT_COLUMN_GAP);
        }

        public int rowGap() {
            return positiveOrDefault(requestedRowGap, DEFAULT_ROW_GAP);
        }

        private static int positiveOrDefault(Integer value, int fallback) {
            return value == null || value < 1 ? fallback : value;
        }
    }

    private record EdgeProjection(String id,
                                  String source,
                                  String target,
                                  String condition,
                                  String sourceHandle,
                                  String targetHandle,
                                  String label) {
    }

    private record CanvasLink(int index, String id, String source, String target) {
    }

    private record NodeSize(int width, int height) {
    }

    private record Point(int x, int y) {
    }

    private record ComponentLayout(Map<String, Point> positions, int height) {
    }
}
