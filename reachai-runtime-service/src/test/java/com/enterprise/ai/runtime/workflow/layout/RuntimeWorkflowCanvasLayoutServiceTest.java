package com.enterprise.ai.runtime.workflow.layout;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeWorkflowCanvasLayoutServiceTest {

    private RuntimeWorkflowCanvasLayoutService service;

    @BeforeEach
    void setUp() {
        service = new RuntimeWorkflowCanvasLayoutService(new ObjectMapper());
    }

    @Test
    void alignsVariableSizeLinearChainWithEqualBoundaryGaps() {
        GraphSpec graph = graph(
                List.of(
                        node("input", "USER_INPUT", 200, 100),
                        node("answer", "ANSWER", 320, 220)),
                List.of(edge("input-answer", "input", "answer")),
                "input",
                List.of("answer"));

        Map<String, Object> canvas = service.projectAndLayout(
                graph,
                Map.of("nodes", List.of(
                        Map.of("id", "input", "width", 200, "height", 100),
                        Map.of("id", "answer", "width", 320, "height", 220))),
                RuntimeWorkflowCanvasLayoutService.Options.defaults());

        List<String> chain = List.of("start", "input", "answer", "end");
        double expectedCenter = centerY(node(canvas, "start"));
        for (String nodeId : chain) {
            assertEquals(expectedCenter, centerY(node(canvas, nodeId)), 0.001, nodeId + " must share the center line");
        }
        for (int index = 1; index < chain.size(); index++) {
            Map<String, Object> left = node(canvas, chain.get(index - 1));
            Map<String, Object> right = node(canvas, chain.get(index));
            assertEquals(
                    RuntimeWorkflowCanvasLayoutService.DEFAULT_COLUMN_GAP,
                    x(right) - x(left) - width(left),
                    0.001,
                    "linear boundary gaps must be equal");
        }
    }

    @Test
    void centersBranchAndMergeWithoutOverlap() {
        GraphSpec graph = graph(
                List.of(
                        node("router", "INTENT_CLASSIFIER", 300, 180),
                        node("yes", "TOOL", 240, 150),
                        node("no", "TOOL", 240, 190),
                        node("merge", "VARIABLE_AGGREGATOR", 240, 160)),
                List.of(
                        edge("router-yes", "router", "yes"),
                        edge("router-no", "router", "no"),
                        edge("yes-merge", "yes", "merge"),
                        edge("no-merge", "no", "merge")),
                "router",
                List.of("merge"));

        Map<String, Object> canvas = service.projectAndLayout(
                graph,
                Map.of(),
                RuntimeWorkflowCanvasLayoutService.Options.defaults());
        Map<String, Object> yes = node(canvas, "yes");
        Map<String, Object> no = node(canvas, "no");
        assertFalse(overlaps(yes, no));
        double branchMidpoint = (Math.min(centerY(yes), centerY(no)) + Math.max(centerY(yes), centerY(no))) / 2;
        assertEquals(branchMidpoint, centerY(node(canvas, "router")), 0.001);
        assertEquals(branchMidpoint, centerY(node(canvas, "merge")), 0.001);
    }

    @Test
    void reachableCycleTerminatesAndKeepsMainDirection() {
        GraphSpec graph = graph(
                List.of(node("a", "TOOL", 240, 156), node("b", "TOOL", 240, 156)),
                List.of(edge("a-b", "a", "b"), edge("b-a", "b", "a")),
                "a",
                List.of("b"));

        Map<String, Object> canvas = assertTimeoutPreemptively(
                Duration.ofSeconds(2),
                () -> service.projectAndLayout(
                        graph,
                        Map.of(),
                        RuntimeWorkflowCanvasLayoutService.Options.defaults()));

        assertTrue(x(node(canvas, "start")) < x(node(canvas, "a")));
        assertTrue(x(node(canvas, "a")) < x(node(canvas, "b")));
        assertTrue(x(node(canvas, "b")) < x(node(canvas, "end")));
    }

    @Test
    void packsDisconnectedNodesBelowMainGraphAndIsIdempotent() {
        GraphSpec graph = graph(
                List.of(node("main", "TOOL", 240, 156), node("orphan", "TOOL", 240, 156)),
                List.of(),
                "main",
                List.of("main"));
        Map<String, Object> first = service.projectAndLayout(
                graph,
                Map.of(),
                RuntimeWorkflowCanvasLayoutService.Options.defaults());
        Map<String, Object> second = service.layoutCanvas(
                first,
                RuntimeWorkflowCanvasLayoutService.Options.defaults());

        double mainBottom = nodes(first).stream()
                .filter(item -> !"orphan".equals(item.get("id")))
                .mapToDouble(item -> y(item) + height(item))
                .max()
                .orElseThrow();
        assertTrue(y(node(first, "orphan")) > mainBottom);
        assertEquals(positions(first), positions(second));
    }

    @Test
    void disabledLayoutPreservesExistingCoordinatesButPlacesNewNodes() {
        Map<String, Object> canvas = new LinkedHashMap<>();
        canvas.put("nodes", new ArrayList<>(List.of(
                canvasNode("a", 999, 777),
                canvasNodeWithoutPosition("b"))));
        canvas.put("edges", List.of(Map.of("id", "a-b", "source", "a", "target", "b")));

        Map<String, Object> result = service.layoutCanvas(
                canvas,
                new RuntimeWorkflowCanvasLayoutService.Options(false, "LR", 88, 56));

        assertEquals(999, x(node(result, "a")), 0.001);
        assertEquals(777, y(node(result, "a")), 0.001);
        assertTrue(node(result, "b").get("position") instanceof Map<?, ?>);
    }

    @Test
    void projectionRemovesStaleCanvasItemsAndBackfillsGraphItems() {
        GraphSpec graph = graph(
                List.of(node("kept", "ANSWER", 240, 156), node("added", "TOOL", 240, 156)),
                List.of(edge("kept-added", "kept", "added")),
                "kept",
                List.of("added"));
        Map<String, Object> existing = Map.of(
                "nodes", List.of(canvasNode("stale", 1, 1), canvasNode("kept", 2, 2)),
                "edges", List.of(Map.of("id", "stale-edge", "source", "stale", "target", "kept")));

        Map<String, Object> result = service.projectAndLayout(
                graph,
                existing,
                RuntimeWorkflowCanvasLayoutService.Options.defaults());

        assertEquals(Set.of("start", "kept", "added", "end"), nodes(result).stream()
                .map(item -> String.valueOf(item.get("id")))
                .collect(Collectors.toSet()));
        assertEquals(Set.of("e-start-kept", "kept-added", "e-added-end"), edges(result).stream()
                .map(item -> String.valueOf(item.get("id")))
                .collect(Collectors.toSet()));
        assertEquals(1, result.get("schemaVersion"));
        assertTrue(nodes(result).stream().allMatch(item -> !item.containsKey("type") && !item.containsKey("data")));
        assertTrue(edges(result).stream().allMatch(item -> !item.containsKey("source")
                && !item.containsKey("target")
                && !item.containsKey("condition")));
    }

    @Test
    void projectionIgnoresFinishNodesThatDoNotExistInGraph() {
        GraphSpec graph = graph(
                List.of(node("kept", "ANSWER", 240, 156)),
                List.of(),
                "kept",
                List.of("deleted"));

        Map<String, Object> result = service.projectAndLayout(
                graph,
                Map.of(),
                RuntimeWorkflowCanvasLayoutService.Options.defaults());

        assertEquals(Set.of("e-start-kept"), edges(result).stream()
                .map(item -> String.valueOf(item.get("id")))
                .collect(Collectors.toSet()));
        assertFalse(edges(result).stream()
                .anyMatch(edge -> String.valueOf(edge.get("id")).contains("deleted")));
    }

    private GraphSpec graph(List<GraphSpec.Node> nodes,
                            List<GraphSpec.Edge> edges,
                            String entry,
                            List<String> finish) {
        return GraphSpec.builder()
                .nodes(nodes)
                .edges(edges)
                .entryNodeId(entry)
                .exitNodeIds(finish)
                .build();
    }

    private GraphSpec.Node node(String id, String type, double width, double height) {
        return GraphSpec.Node.builder()
                .id(id)
                .type(type)
                .name(id)
                .build();
    }

    private GraphSpec.Edge edge(String id, String source, String target) {
        return GraphSpec.Edge.builder().id(id).from(source).to(target).condition("always").build();
    }

    private Map<String, Object> canvasNode(String id, int x, int y) {
        Map<String, Object> node = canvasNodeWithoutPosition(id);
        node.put("position", Map.of("x", x, "y", y));
        return node;
    }

    private Map<String, Object> canvasNodeWithoutPosition(String id) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("type", "tool");
        node.put("data", Map.of("kind", "tool", "label", id, "configVersion", 2));
        return node;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> nodes(Map<String, Object> canvas) {
        return (List<Map<String, Object>>) canvas.get("nodes");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> edges(Map<String, Object> canvas) {
        return (List<Map<String, Object>>) canvas.get("edges");
    }

    private Map<String, Object> node(Map<String, Object> canvas, String id) {
        return nodes(canvas).stream().filter(item -> id.equals(item.get("id"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> position(Map<String, Object> node) {
        return (Map<String, Object>) node.get("position");
    }

    private Map<String, Map<String, Object>> positions(Map<String, Object> canvas) {
        return nodes(canvas).stream().collect(Collectors.toMap(
                item -> String.valueOf(item.get("id")),
                this::position));
    }

    private double x(Map<String, Object> node) {
        return ((Number) position(node).get("x")).doubleValue();
    }

    private double y(Map<String, Object> node) {
        return ((Number) position(node).get("y")).doubleValue();
    }

    private double width(Map<String, Object> node) {
        Object width = node.get("width");
        if (width instanceof Number number) return number.doubleValue();
        return 240;
    }

    private double height(Map<String, Object> node) {
        Object height = node.get("height");
        return height instanceof Number number ? number.doubleValue() : 156;
    }

    private double centerY(Map<String, Object> node) {
        return y(node) + height(node) / 2;
    }

    private boolean overlaps(Map<String, Object> left, Map<String, Object> right) {
        return x(left) < x(right) + width(right)
                && x(left) + width(left) > x(right)
                && y(left) < y(right) + height(right)
                && y(left) + height(left) > y(right);
    }
}
