package com.enterprise.ai.agent.workflow.aicoding;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowGraphPatchServiceTest {

    private final WorkflowGraphPatchService service = new WorkflowGraphPatchService(new ObjectMapper());

    @Test
    void dryRunPatchAddsNodeAndEdgeWithoutMutatingInputGraph() {
        GraphSpec base = GraphSpec.builder()
                .entry("start")
                .node(GraphSpec.Node.builder().id("start").type("USER_INPUT").name("Start").build())
                .build();
        Map<String, Object> canvas = new LinkedHashMap<>();
        canvas.put("version", 2);
        canvas.put("nodes", new ArrayList<>(List.of(Map.of(
                "id", "start",
                "type", "userInput",
                "position", Map.of("x", 100, "y", 80),
                "data", Map.of("label", "Start", "kind", "userInput")
        ))));
        canvas.put("edges", new ArrayList<>());

        WorkflowGraphPatchService.PatchResult result = service.apply(base, canvas, List.of(
                WorkflowGraphPatchOperation.builder()
                        .op(WorkflowGraphPatchOperation.OperationOp.ADD_NODE)
                        .node(GraphSpec.Node.builder()
                                .id("answer")
                                .type("ANSWER")
                                .name("Answer")
                                .config(Map.of("answerConfig", Map.of("template", "ok")))
                                .build())
                        .build(),
                WorkflowGraphPatchOperation.builder()
                        .op(WorkflowGraphPatchOperation.OperationOp.ADD_EDGE)
                        .edge(GraphSpec.Edge.builder().id("e1").from("start").to("answer").condition("always").build())
                        .build()
        ), true);

        assertEquals(1, base.getNodes().size());
        assertEquals(2, result.getGraphSpec().getNodes().size());
        assertEquals(1, result.getChangedNodes().size());
        assertTrue(result.getChangedNodes().contains("answer"));
        assertEquals(1, result.getChangedEdges().size());
        assertTrue(result.getErrors().isEmpty());
        assertNotNull(result.getCanvas().get("nodes"));
    }

    @Test
    void deleteNodeRemovesRelatedEdges() {
        GraphSpec base = GraphSpec.builder()
                .entry("start")
                .node(GraphSpec.Node.builder().id("start").type("USER_INPUT").build())
                .node(GraphSpec.Node.builder().id("answer").type("ANSWER").build())
                .edge(GraphSpec.Edge.builder().id("e1").from("start").to("answer").build())
                .edge(GraphSpec.Edge.builder().id("e2").from("answer").to("END").build())
                .build();

        WorkflowGraphPatchService.PatchResult result = service.apply(base, Map.of(), List.of(
                WorkflowGraphPatchOperation.builder()
                        .op(WorkflowGraphPatchOperation.OperationOp.DELETE_NODE)
                        .nodeId("answer")
                        .build()
        ), true);

        assertEquals(1, result.getGraphSpec().getNodes().size());
        assertTrue(result.getGraphSpec().getEdges().isEmpty());
        assertTrue(result.getChangedEdges().contains("e1"));
        assertTrue(result.getChangedEdges().contains("e2"));
    }

    @Test
    void addEdgeRejectsInvalidEndpoints() {
        GraphSpec base = GraphSpec.builder()
                .entry("start")
                .node(GraphSpec.Node.builder().id("start").type("USER_INPUT").build())
                .build();

        WorkflowGraphPatchService.PatchResult toStart = service.apply(base, Map.of(), List.of(
                WorkflowGraphPatchOperation.builder()
                        .op(WorkflowGraphPatchOperation.OperationOp.ADD_EDGE)
                        .edge(GraphSpec.Edge.builder().id("bad").from("start").to("START").build())
                        .build()
        ), true);
        assertFalse(toStart.getErrors().isEmpty());

        WorkflowGraphPatchService.PatchResult missingTarget = service.apply(base, Map.of(), List.of(
                WorkflowGraphPatchOperation.builder()
                        .op(WorkflowGraphPatchOperation.OperationOp.ADD_EDGE)
                        .edge(GraphSpec.Edge.builder().id("bad2").from("start").to("missing").build())
                        .build()
        ), true);
        assertFalse(missingTarget.getErrors().isEmpty());
    }

    @Test
    void updateNodePreservesUnpatchedConfigFields() {
        GraphSpec base = GraphSpec.builder()
                .entry("llm")
                .node(GraphSpec.Node.builder()
                        .id("llm")
                        .type("LLM")
                        .config(new LinkedHashMap<>(Map.of(
                                "prompt", "hello",
                                "temperature", 0.2,
                                "modelInstanceId", "llm-1"
                        )))
                        .build())
                .build();

        WorkflowGraphPatchService.PatchResult result = service.apply(base, Map.of(), List.of(
                WorkflowGraphPatchOperation.builder()
                        .op(WorkflowGraphPatchOperation.OperationOp.UPDATE_NODE)
                        .nodeId("llm")
                        .patch(Map.of("config", Map.of("prompt", "updated")))
                        .build()
        ), true);

        GraphSpec.Node updated = result.getGraphSpec().getNodes().get(0);
        assertEquals("updated", updated.getConfig().get("prompt"));
        assertEquals(0.2, updated.getConfig().get("temperature"));
        assertEquals("llm-1", updated.getConfig().get("modelInstanceId"));
    }

    @Test
    void updateNodeIgnoresIdPatch() {
        GraphSpec base = GraphSpec.builder()
                .entry("llm")
                .node(GraphSpec.Node.builder()
                        .id("llm")
                        .type("LLM")
                        .config(Map.of("prompt", "hello"))
                        .build())
                .build();

        WorkflowGraphPatchService.PatchResult result = service.apply(base, Map.of(), List.of(
                WorkflowGraphPatchOperation.builder()
                        .op(WorkflowGraphPatchOperation.OperationOp.UPDATE_NODE)
                        .nodeId("llm")
                        .patch(Map.of("id", "renamed", "config", Map.of("prompt", "updated")))
                        .build()
        ), true);

        GraphSpec.Node updated = result.getGraphSpec().getNodes().get(0);
        assertEquals("llm", updated.getId());
        assertEquals("updated", updated.getConfig().get("prompt"));
    }

    @Test
    void setEntryFailsWhenNodeDoesNotExist() {
        GraphSpec base = GraphSpec.builder()
                .node(GraphSpec.Node.builder().id("start").type("USER_INPUT").build())
                .build();

        WorkflowGraphPatchService.PatchResult result = service.apply(base, Map.of(), List.of(
                WorkflowGraphPatchOperation.builder()
                        .op(WorkflowGraphPatchOperation.OperationOp.SET_ENTRY)
                        .entry("missing")
                        .build()
        ), true);

        assertFalse(result.getErrors().isEmpty());
        assertTrue(result.getErrors().get(0).contains("entry node does not exist"));
    }

    @Test
    void addNodeRejectsUnsupportedNodeType() {
        GraphSpec base = GraphSpec.builder()
                .node(GraphSpec.Node.builder().id("start").type("USER_INPUT").build())
                .build();

        WorkflowGraphPatchService.PatchResult result = service.apply(base, Map.of(), List.of(
                WorkflowGraphPatchOperation.builder()
                        .op(WorkflowGraphPatchOperation.OperationOp.ADD_NODE)
                        .node(GraphSpec.Node.builder().id("bad").type("NOT_A_REAL_TYPE").build())
                        .build()
        ), true);

        assertFalse(result.getErrors().isEmpty());
        assertTrue(result.getErrors().get(0).contains("unsupported node type"));
    }

    @Test
    void setEntryUpdatesGraphEntryWhenNodeExists() {
        GraphSpec base = GraphSpec.builder()
                .entry("start")
                .node(GraphSpec.Node.builder().id("start").type("USER_INPUT").build())
                .node(GraphSpec.Node.builder().id("llm").type("LLM").config(Map.of("modelInstanceId", "llm-1")).build())
                .build();

        WorkflowGraphPatchService.PatchResult result = service.apply(base, Map.of(), List.of(
                WorkflowGraphPatchOperation.builder()
                        .op(WorkflowGraphPatchOperation.OperationOp.SET_ENTRY)
                        .entry("llm")
                        .build()
        ), true);

        assertEquals("llm", result.getGraphSpec().getEntry());
        assertTrue(result.getErrors().isEmpty());
    }

    @Test
    void addNodeRejectsDuplicateNodeId() {
        GraphSpec base = GraphSpec.builder()
                .node(GraphSpec.Node.builder().id("start").type("USER_INPUT").build())
                .build();

        WorkflowGraphPatchService.PatchResult result = service.apply(base, Map.of(), List.of(
                WorkflowGraphPatchOperation.builder()
                        .op(WorkflowGraphPatchOperation.OperationOp.ADD_NODE)
                        .node(GraphSpec.Node.builder().id("start").type("ANSWER").build())
                        .build()
        ), true);

        assertFalse(result.getErrors().isEmpty());
        assertTrue(result.getErrors().get(0).contains("duplicate node id"));
    }

    @Test
    void autoLayoutAssignsIncreasingXForLinearChain() {
        GraphSpec base = GraphSpec.builder()
                .entry("start")
                .node(GraphSpec.Node.builder().id("start").type("USER_INPUT").name("Start").build())
                .build();

        WorkflowGraphPatchService.PatchResult result = service.apply(base, Map.of(), List.of(
                WorkflowGraphPatchOperation.builder()
                        .op(WorkflowGraphPatchOperation.OperationOp.ADD_NODE)
                        .node(GraphSpec.Node.builder().id("llm").type("LLM").name("LLM").build())
                        .build(),
                WorkflowGraphPatchOperation.builder()
                        .op(WorkflowGraphPatchOperation.OperationOp.ADD_NODE)
                        .node(GraphSpec.Node.builder().id("answer").type("ANSWER").name("Answer").build())
                        .build(),
                WorkflowGraphPatchOperation.builder()
                        .op(WorkflowGraphPatchOperation.OperationOp.ADD_EDGE)
                        .edge(GraphSpec.Edge.builder().id("e1").from("start").to("llm").build())
                        .build(),
                WorkflowGraphPatchOperation.builder()
                        .op(WorkflowGraphPatchOperation.OperationOp.ADD_EDGE)
                        .edge(GraphSpec.Edge.builder().id("e2").from("llm").to("answer").build())
                        .build()
        ), true);

        assertTrue(result.getErrors().isEmpty());
        assertEquals(80.0, positionX(result, "start"));
        assertEquals(340.0, positionX(result, "llm"));
        assertEquals(600.0, positionX(result, "answer"));
    }

    @Test
    void autoLayoutSeparatesBranchNodesVertically() {
        GraphSpec base = GraphSpec.builder()
                .entry("router")
                .node(GraphSpec.Node.builder().id("router").type("INTENT_CLASSIFIER").name("Router").build())
                .build();

        WorkflowGraphPatchService.PatchResult result = service.apply(base, Map.of(), List.of(
                WorkflowGraphPatchOperation.builder()
                        .op(WorkflowGraphPatchOperation.OperationOp.ADD_NODE)
                        .node(GraphSpec.Node.builder().id("branchA").type("PAGE_ACTION").name("Branch A").build())
                        .build(),
                WorkflowGraphPatchOperation.builder()
                        .op(WorkflowGraphPatchOperation.OperationOp.ADD_NODE)
                        .node(GraphSpec.Node.builder().id("branchB").type("PAGE_ACTION").name("Branch B").build())
                        .build(),
                WorkflowGraphPatchOperation.builder()
                        .op(WorkflowGraphPatchOperation.OperationOp.ADD_EDGE)
                        .edge(GraphSpec.Edge.builder().id("e1").from("router").to("branchA").condition("a").build())
                        .build(),
                WorkflowGraphPatchOperation.builder()
                        .op(WorkflowGraphPatchOperation.OperationOp.ADD_EDGE)
                        .edge(GraphSpec.Edge.builder().id("e2").from("router").to("branchB").condition("b").build())
                        .build()
        ), true);

        assertTrue(result.getErrors().isEmpty());
        assertEquals(340.0, positionX(result, "branchA"));
        assertEquals(340.0, positionX(result, "branchB"));
        assertEquals(120.0, positionY(result, "branchA"));
        assertEquals(270.0, positionY(result, "branchB"));
        assertTrue(positionY(result, "branchA") != positionY(result, "branchB"));
    }

    @Test
    void autoLayoutBackfillsMissingCanvasNodes() {
        GraphSpec base = GraphSpec.builder()
                .entry("start")
                .node(GraphSpec.Node.builder().id("start").type("USER_INPUT").build())
                .node(GraphSpec.Node.builder().id("answer").type("ANSWER").build())
                .edge(GraphSpec.Edge.builder().id("e1").from("start").to("answer").build())
                .build();

        WorkflowGraphPatchService.PatchResult result = service.apply(base, Map.of(), List.of(), true);

        assertTrue(result.getErrors().isEmpty());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) result.getCanvas().get("nodes");
        assertEquals(2, nodes.size());
        assertEquals(80.0, positionX(result, "start"));
        assertEquals(340.0, positionX(result, "answer"));
    }

    @Test
    void autoLayoutHandlesCyclesWithoutHanging() {
        GraphSpec base = GraphSpec.builder()
                .entry("router")
                .node(GraphSpec.Node.builder().id("router").type("INTENT_CLASSIFIER").build())
                .node(GraphSpec.Node.builder().id("retry").type("LLM").build())
                .edge(GraphSpec.Edge.builder().id("e1").from("router").to("retry").build())
                .edge(GraphSpec.Edge.builder().id("e2").from("retry").to("router").build())
                .build();

        WorkflowGraphPatchService.PatchResult result = assertTimeoutPreemptively(
                Duration.ofSeconds(2),
                () -> service.apply(base, Map.of(), List.of(), true));

        assertTrue(result.getErrors().isEmpty());
        assertEquals(80.0, positionX(result, "router"));
        assertEquals(340.0, positionX(result, "retry"));
    }

    @Test
    void autoLayoutDisabledKeepsIncrementalPositions() {
        GraphSpec base = GraphSpec.builder()
                .entry("start")
                .node(GraphSpec.Node.builder().id("start").type("USER_INPUT").build())
                .build();
        Map<String, Object> canvas = new LinkedHashMap<>();
        canvas.put("version", 2);
        canvas.put("nodes", new ArrayList<>(List.of(Map.of(
                "id", "start",
                "type", "userInput",
                "position", Map.of("x", 100, "y", 80),
                "data", Map.of("label", "Start", "kind", "userInput")
        ))));
        canvas.put("edges", new ArrayList<>());

        WorkflowGraphPatchService.PatchResult result = service.apply(base, canvas, List.of(
                WorkflowGraphPatchOperation.builder()
                        .op(WorkflowGraphPatchOperation.OperationOp.ADD_NODE)
                        .node(GraphSpec.Node.builder().id("answer").type("ANSWER").build())
                        .build(),
                WorkflowGraphPatchOperation.builder()
                        .op(WorkflowGraphPatchOperation.OperationOp.ADD_EDGE)
                        .edge(GraphSpec.Edge.builder().id("e1").from("start").to("answer").build())
                        .build()
        ), false);

        assertTrue(result.getErrors().isEmpty());
        assertEquals(100.0, positionX(result, "start"));
        assertEquals(80.0, positionY(result, "start"));
        assertEquals(0.0, positionX(result, "answer"));
        assertEquals(0.0, positionY(result, "answer"));
    }

    @SuppressWarnings("unchecked")
    private double positionX(WorkflowGraphPatchService.PatchResult result, String nodeId) {
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) result.getCanvas().get("nodes");
        for (Map<String, Object> node : nodes) {
            if (nodeId.equals(node.get("id"))) {
                return ((Number) ((Map<String, Object>) node.get("position")).get("x")).doubleValue();
            }
        }
        throw new AssertionError("node not found: " + nodeId);
    }

    @SuppressWarnings("unchecked")
    private double positionY(WorkflowGraphPatchService.PatchResult result, String nodeId) {
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) result.getCanvas().get("nodes");
        for (Map<String, Object> node : nodes) {
            if (nodeId.equals(node.get("id"))) {
                return ((Number) ((Map<String, Object>) node.get("position")).get("y")).doubleValue();
            }
        }
        throw new AssertionError("node not found: " + nodeId);
    }
}
