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
                new MutationOperation(MutationOperation.Op.SET_ENTRY, null, null, null, null, null, "missing", null))));

        assertEquals(List.of("answer"), source.getNodes().stream().map(GraphSpec.Node::getId).toList());
        assertEquals("answer", source.getEntry());
    }

    @Test
    void updateEdgeAcceptsCanvasEndpointAliasesButKeepsEdgeIdStable() {
        GraphSpec source = graph();
        GraphSpec.Node tool = GraphSpec.Node.builder().id("tool").type("TOOL").name("Tool").build();
        source.setNodes(List.of(source.getNodes().get(0), tool));

        MutationResult result = service.mutate(source, List.of(new MutationOperation(
                MutationOperation.Op.UPDATE_EDGE,
                null,
                null,
                Map.of("target", "tool", "condition", "ok"),
                null,
                "e-start-answer",
                null,
                null)));

        GraphSpec.Edge edge = result.graphSpec().getEdges().get(0);
        assertEquals("e-start-answer", edge.getId());
        assertEquals("tool", edge.getTo());
        assertEquals("ok", edge.getCondition());
    }

    private GraphSpec graph() {
        GraphSpec.Node answer = GraphSpec.Node.builder()
                .id("answer")
                .type("ANSWER")
                .name("Answer")
                .config(Map.of("answerConfig", Map.of("template", "old", "format", "keep")))
                .build();
        GraphSpec.Edge edge = GraphSpec.Edge.builder()
                .id("e-start-answer")
                .from("START")
                .to("answer")
                .condition("always")
                .build();
        return GraphSpec.builder()
                .code("test")
                .name("Test")
                .node(answer)
                .edge(edge)
                .entry("answer")
                .finishNode("answer")
                .build();
    }

    private Object answerTemplate(GraphSpec graph) {
        return ((Map<?, ?>) graph.getNodes().get(0).getConfig().get("answerConfig")).get("template");
    }
}
