package com.enterprise.ai.runtime.workflow.mutation;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.workflow.mutation.RuntimeWorkflowGraphMutationService.MutationOperation;
import com.enterprise.ai.runtime.workflow.mutation.RuntimeWorkflowGraphMutationService.MutationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeWorkflowGraphMutationServiceTest {

    private final RuntimeWorkflowGraphMutationService service =
            new RuntimeWorkflowGraphMutationService(new ObjectMapper());

    @Test
    void appliesDeepGraphSpecPatchWithoutMutatingSource() {
        GraphSpec source = graph();

        MutationResult result = service.mutate(source, List.of(new MutationOperation(
                MutationOperation.Op.UPDATE_NODE,
                null,
                "answer",
                Map.of("config", Map.of("answerConfig", Map.of("template", "new"))),
                null,
                null,
                null,
                null)));

        assertNotSame(source, result.graphSpec());
        assertEquals("old", answerTemplate(source));
        assertEquals("new", answerTemplate(result.graphSpec()));
        assertEquals("keep", ((Map<?, ?>) result.graphSpec().getNodes().get(0).getConfig().get("answerConfig"))
                .get("format"));
        assertEquals(List.of("answer"), result.changedNodes());
    }

    @Test
    void setsWorkflowInputSchemaWithoutMutatingSource() {
        GraphSpec source = graph();

        MutationResult result = service.mutate(source, List.of(new MutationOperation(
                MutationOperation.Op.SET_INPUT_SCHEMA,
                null,
                null,
                Map.of(
                        "type", "object",
                        "required", List.of("message"),
                        "properties", Map.of("message", Map.of("type", "string"))),
                null,
                null,
                null,
                null)));

        assertEquals(null, source.getInputSchema());
        assertEquals(List.of("message"), result.graphSpec().getInputSchema().get("required"));
    }

    @Test
    void replacesToolInputMappingInsteadOfRetainingRemovedArguments() {
        GraphSpec source = graph();
        GraphSpec.Node tool = GraphSpec.Node.builder()
                .id("tool")
                .type("TOOL")
                .name("Tool")
                .config(Map.of("inputMapping", Map.of("legacy", "nodeOutput.extract.legacy")))
                .build();
        source.setNodes(List.of(source.getNodes().get(0), tool));

        MutationResult result = service.mutate(source, List.of(new MutationOperation(
                MutationOperation.Op.UPDATE_NODE,
                null,
                "tool",
                Map.of("config", Map.of("inputMapping", Map.of("teamName", "nodeOutput.extract.teamName"))),
                null,
                null,
                null,
                null)));

        assertEquals(
                Map.of("teamName", "nodeOutput.extract.teamName"),
                result.graphSpec().getNodes().get(1).getConfig().get("inputMapping"));
    }

    @Test
    void rejectsNodeIdMutationAndLeavesSourceUnchanged() {
        GraphSpec source = graph();

        assertThrows(IllegalArgumentException.class, () -> service.mutate(source, List.of(new MutationOperation(
                MutationOperation.Op.UPDATE_NODE,
                null,
                "answer",
                Map.of("id", "renamed"),
                null,
                null,
                null,
                null))));

        assertEquals("answer", source.getNodes().get(0).getId());
    }

    @Test
    void operationBatchIsAtomicWhenLaterOperationFails() {
        GraphSpec source = graph();
        GraphSpec.Node tool = GraphSpec.Node.builder().id("tool").type("TOOL").name("Tool").build();

        assertThrows(IllegalArgumentException.class, () -> service.mutate(source, List.of(
                new MutationOperation(MutationOperation.Op.ADD_NODE, tool, null, null, null, null, null, null),
                new MutationOperation(MutationOperation.Op.SET_ENTRY_NODE, null, null, null, null, null, "missing", null))));

        assertEquals(List.of("answer"), source.getNodes().stream().map(GraphSpec.Node::getId).toList());
        assertEquals("answer", source.getEntryNodeId());
    }

    @Test
    void updateEdgeUsesCanonicalEndpointsAndKeepsEdgeIdStable() {
        GraphSpec source = graph();
        GraphSpec.Node tool = GraphSpec.Node.builder().id("tool").type("TOOL").name("Tool").build();
        source.setNodes(List.of(source.getNodes().get(0), tool));

        MutationResult result = service.mutate(source, List.of(new MutationOperation(
                MutationOperation.Op.UPDATE_EDGE,
                null,
                null,
                Map.of("to", "tool", "condition", "ok"),
                null,
                "e-answer-answer",
                null,
                null)));

        GraphSpec.Edge edge = result.graphSpec().getEdges().get(0);
        assertEquals("e-answer-answer", edge.getId());
        assertEquals("tool", edge.getTo());
        assertEquals("ok", edge.getCondition());
    }

    @Test
    void rejectsAddAndUpdateForNonAuthorableNodesButAllowsDelete() {
        GraphSpec.Node interaction = GraphSpec.Node.builder()
                .id("ask")
                .type("INTERACTION")
                .name("Ask")
                .build();
        GraphSpec source = GraphSpec.builder()
                .node(interaction)
                .entryNodeId("ask")
                .exitNodeIds(List.of("ask"))
                .build();

        IllegalArgumentException addRejected = assertThrows(IllegalArgumentException.class, () ->
                service.mutate(graph(), List.of(new MutationOperation(
                        MutationOperation.Op.ADD_NODE, interaction, null, null, null, null, null, null))));
        assertTrue(addRejected.getMessage().contains("WORKFLOW_NODE_NOT_AUTHORABLE"));

        IllegalArgumentException updateRejected = assertThrows(IllegalArgumentException.class, () ->
                service.mutate(source, List.of(new MutationOperation(
                        MutationOperation.Op.UPDATE_NODE,
                        null,
                        "ask",
                        Map.of("name", "changed"),
                        null,
                        null,
                        null,
                        null))));
        assertTrue(updateRejected.getMessage().contains("WORKFLOW_NODE_NOT_AUTHORABLE"));

        IllegalArgumentException typeRejected = assertThrows(IllegalArgumentException.class, () ->
                service.mutate(graph(), List.of(new MutationOperation(
                        MutationOperation.Op.UPDATE_NODE,
                        null,
                        "answer",
                        Map.of("type", "CODE"),
                        null,
                        null,
                        null,
                        null))));
        assertTrue(typeRejected.getMessage().contains("WORKFLOW_NODE_NOT_AUTHORABLE"));

        MutationResult deleted = service.mutate(source, List.of(new MutationOperation(
                MutationOperation.Op.DELETE_NODE, null, "ask", null, null, null, null, null)));
        assertEquals(List.of(), deleted.graphSpec().getNodes());
        assertEquals(List.of("ask"), deleted.changedNodes());
    }

    @Test
    void rejectsCanvasKindsBoundaryEndpointsAndEmptyExitNodes() {
        GraphSpec.Node canvasKindNode = GraphSpec.Node.builder()
                .id("tool")
                .type("tool")
                .build();
        assertThrows(IllegalArgumentException.class, () -> service.mutate(graph(), List.of(new MutationOperation(
                MutationOperation.Op.ADD_NODE, canvasKindNode, null, null, null, null, null, null))));

        GraphSpec.Edge boundaryEdge = GraphSpec.Edge.builder()
                .id("e-start-answer")
                .from("START")
                .to("answer")
                .build();
        assertThrows(IllegalArgumentException.class, () -> service.mutate(graph(), List.of(new MutationOperation(
                MutationOperation.Op.ADD_EDGE, null, null, null, boundaryEdge, null, null, null))));

        assertThrows(IllegalArgumentException.class, () -> service.mutate(graph(), List.of(new MutationOperation(
                MutationOperation.Op.SET_EXIT_NODES, null, null, null, null, null, null, List.of()))));
    }

    private GraphSpec graph() {
        GraphSpec.Node answer = GraphSpec.Node.builder()
                .id("answer")
                .type("ANSWER")
                .name("Answer")
                .config(Map.of("answerConfig", Map.of("template", "old", "format", "keep")))
                .build();
        GraphSpec.Edge edge = GraphSpec.Edge.builder()
                .id("e-answer-answer")
                .from("answer")
                .to("answer")
                .condition("always")
                .build();
        return GraphSpec.builder()
                .node(answer)
                .edge(edge)
                .entryNodeId("answer")
                .exitNodeIds(List.of("answer"))
                .build();
    }

    private Object answerTemplate(GraphSpec graph) {
        return ((Map<?, ?>) graph.getNodes().get(0).getConfig().get("answerConfig")).get("template");
    }
}
