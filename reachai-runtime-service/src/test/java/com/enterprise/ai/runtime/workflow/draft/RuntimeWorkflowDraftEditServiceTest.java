package com.enterprise.ai.runtime.workflow.draft;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.workflow.authoring.WorkflowAuthoringAgentAdapter;
import com.enterprise.ai.runtime.workflow.authoring.WorkflowAuthoringRequest;
import com.enterprise.ai.runtime.workflow.authoring.WorkflowAuthoringResult;
import com.enterprise.ai.runtime.workflow.layout.RuntimeWorkflowCanvasLayoutService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeWorkflowDraftEditServiceTest {

    @Test
    void projectsSucceededAuthoringResultToCanvasPreview() {
        ObjectMapper objectMapper = new ObjectMapper();
        WorkflowAuthoringAgentAdapter adapter = mock(WorkflowAuthoringAgentAdapter.class);
        GraphSpec candidate = GraphSpec.builder()
                .code("order_graph")
                .name("Order Agent")
                .entry("answer")
                .node(GraphSpec.Node.builder()
                        .id("answer")
                        .type("ANSWER")
                        .name("回答")
                        .config(Map.of("answerConfig", Map.of("template", "处理完成"), "template", "处理完成"))
                        .build())
                .finishNode("answer")
                .build();
        when(adapter.author(any())).thenReturn(new WorkflowAuthoringResult(
                WorkflowAuthoringResult.Status.SUCCEEDED,
                WorkflowAuthoringResult.PROVIDER,
                "updated answer",
                List.of(RuntimeWorkflowDraftEditOperationView.builder()
                        .type(RuntimeWorkflowDraftEditOperationType.UPDATE_NODE)
                        .nodeId("answer")
                        .reason("match instruction")
                        .build()),
                candidate,
                List.of(),
                List.of(),
                1,
                null,
                List.of(Map.of("tool", "finalize_preview", "success", true)),
                "authoring-success-1"));

        RuntimeWorkflowDraftEditService service = new RuntimeWorkflowDraftEditService(
                objectMapper,
                new RuntimeWorkflowCanvasLayoutService(objectMapper),
                adapter);

        RuntimeWorkflowDraftEditView result = service.edit(new RuntimeWorkflowDraftEditRequest(
                "agent-1",
                "Order Agent",
                "把回答节点文案改成处理完成",
                "orders",
                "model-1",
                canvas(),
                List.of("answer"),
                List.of(),
                List.of(),
                List.of(),
                List.of()));

        assertEquals("SUCCEEDED", result.status());
        assertEquals(WorkflowAuthoringResult.PROVIDER, result.provider());
        assertEquals("updated answer", result.summary());
        assertEquals("authoring-success-1", result.authoringId());
        assertTrue(result.validationErrors().isEmpty());
        assertEquals("处理完成", ((Map<?, ?>) ((Map<?, ?>) node(result.canvasSnapshot(), "answer")
                .get("data")).get("answerConfig")).get("template"));
        assertEquals("answer", result.graphSpec().getEntry());
        verify(adapter).author(any());
    }

    @Test
    void returnsFailedStatusWithoutCallingAdapterWhenInstructionBlank() {
        WorkflowAuthoringAgentAdapter adapter = mock(WorkflowAuthoringAgentAdapter.class);
        ObjectMapper objectMapper = new ObjectMapper();
        RuntimeWorkflowDraftEditService service = new RuntimeWorkflowDraftEditService(
                objectMapper,
                new RuntimeWorkflowCanvasLayoutService(objectMapper),
                adapter);

        RuntimeWorkflowDraftEditView result = service.edit(new RuntimeWorkflowDraftEditRequest(
                "agent-1",
                "Order Agent",
                " ",
                "orders",
                "model-1",
                canvas(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()));

        assertEquals("FAILED", result.status());
        assertEquals(List.of("instruction is required"), result.validationErrors());
        assertEquals("INSTRUCTION_REQUIRED", result.failureCode());
    }

    @Test
    void canvasFallbackPreservesRetryAndErrorPolicyWhenCurrentGraphSpecMissing() {
        ObjectMapper objectMapper = new ObjectMapper();
        WorkflowAuthoringAgentAdapter adapter = mock(WorkflowAuthoringAgentAdapter.class);
        ArgumentCaptor<WorkflowAuthoringRequest> captor = ArgumentCaptor.forClass(WorkflowAuthoringRequest.class);
        when(adapter.author(captor.capture())).thenAnswer(invocation -> {
            WorkflowAuthoringRequest request = invocation.getArgument(0);
            return new WorkflowAuthoringResult(
                    WorkflowAuthoringResult.Status.FAILED,
                    WorkflowAuthoringResult.PROVIDER,
                    "captured",
                    List.of(),
                    request.currentGraphSpec(),
                    List.of(),
                    List.of("captured-only"),
                    0,
                    "CAPTURED",
                    List.of(),
                    "authoring-capture-1");
        });
        RuntimeWorkflowDraftEditService service = new RuntimeWorkflowDraftEditService(
                objectMapper,
                new RuntimeWorkflowCanvasLayoutService(objectMapper),
                adapter);

        Map<String, Object> canvasOnly = Map.of(
                "graphCode", "order_graph",
                "graphName", "Order Agent",
                "nodes", List.of(
                        Map.of("id", "start", "type", "start", "data", Map.of("kind", "start")),
                        Map.of("id", "answer", "type", "answer", "data", Map.of(
                                "kind", "answer",
                                "label", "回答",
                                "answerConfig", Map.of("template", "旧文案"),
                                "retry", Map.of("enabled", true, "maxAttempts", 3, "backoffMs", 250),
                                "errorPolicy", Map.of(
                                        "strategy", "FALLBACK",
                                        "fallbackNodeId", "answer",
                                        "defaultOutput", Map.of("ok", false)))),
                        Map.of("id", "end", "type", "end", "data", Map.of("kind", "end"))),
                "edges", List.of(
                        Map.of("id", "e-start-answer", "source", "start", "target", "answer"),
                        Map.of("id", "e-answer-end", "source", "answer", "target", "end")));

        service.edit(new RuntimeWorkflowDraftEditRequest(
                null,
                "agent-1",
                "Order Agent",
                "保留 retry",
                "orders",
                "model-1",
                canvasOnly,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()));

        GraphSpec graphSpec = captor.getValue().currentGraphSpec();
        assertNotNull(graphSpec);
        GraphSpec.Node answer = graphSpec.getNodes().stream()
                .filter(node -> "answer".equals(node.getId()))
                .findFirst()
                .orElseThrow();
        assertNotNull(answer.getRetry());
        assertEquals(Boolean.TRUE, answer.getRetry().getEnabled());
        assertEquals(3, answer.getRetry().getMaxAttempts());
        assertEquals(250L, answer.getRetry().getBackoffMs());
        assertNotNull(answer.getErrorPolicy());
        assertEquals("FALLBACK", answer.getErrorPolicy().getStrategy());
        assertEquals("answer", answer.getErrorPolicy().getFallbackNodeId());
        assertEquals(false, answer.getErrorPolicy().getDefaultOutput().get("ok"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> node(Map<String, Object> canvas, String nodeId) {
        return ((List<Map<String, Object>>) canvas.get("nodes")).stream()
                .filter(item -> nodeId.equals(item.get("id")))
                .findFirst()
                .orElseThrow();
    }

    private Map<String, Object> canvas() {
        return Map.of(
                "graphCode", "order_graph",
                "graphName", "Order Agent",
                "nodes", List.of(
                        Map.of("id", "start", "type", "start", "data", Map.of("kind", "start")),
                        Map.of("id", "answer", "type", "answer", "data", Map.of(
                                "kind", "answer",
                                "label", "回答",
                                "answerConfig", Map.of("template", "旧文案"))),
                        Map.of("id", "end", "type", "end", "data", Map.of("kind", "end"))),
                "edges", List.of(
                        Map.of("id", "e-start-answer", "source", "start", "target", "answer"),
                        Map.of("id", "e-answer-end", "source", "answer", "target", "end")));
    }
}
