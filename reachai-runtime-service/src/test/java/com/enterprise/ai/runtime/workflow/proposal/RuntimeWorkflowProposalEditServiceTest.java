package com.enterprise.ai.runtime.workflow.proposal;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowDocumentCanonicalizer;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeWorkflowProposalEditServiceTest {

    @Test
    void projectsSucceededAuthoringResultToCanvasPreview() {
        ObjectMapper objectMapper = new ObjectMapper();
        WorkflowAuthoringAgentAdapter adapter = mock(WorkflowAuthoringAgentAdapter.class);
        GraphSpec candidate = GraphSpec.builder()
                .entryNodeId("answer")
                .node(GraphSpec.Node.builder()
                        .id("answer")
                        .type("ANSWER")
                        .name("回答")
                        .config(Map.of("answerConfig", Map.of("template", "处理完成"), "template", "处理完成"))
                        .build())
                .exitNodeIds(List.of("answer"))
                .build();
        when(adapter.author(any())).thenReturn(new WorkflowAuthoringResult(
                WorkflowAuthoringResult.Status.SUCCEEDED,
                WorkflowAuthoringResult.PROVIDER,
                "updated answer",
                List.of(RuntimeWorkflowProposalEditOperationView.builder()
                        .type(RuntimeWorkflowProposalEditOperationType.UPDATE_NODE)
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

        RuntimeWorkflowProposalEditService service = new RuntimeWorkflowProposalEditService(
                objectMapper,
                new RuntimeWorkflowCanvasLayoutService(objectMapper),
                adapter,
                new RuntimeWorkflowDocumentCanonicalizer(objectMapper));

        RuntimeWorkflowProposalEditView result = service.edit(new RuntimeWorkflowProposalEditRequest(
                "workflow-1",
                "Order Workflow",
                "把回答节点文案改成处理完成",
                "orders",
                "GENERAL",
                "model-1",
                canvas(),
                candidate,
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
        GraphSpec.Node answer = result.graphSpec().getNodes().stream()
                .filter(item -> "answer".equals(item.getId()))
                .findFirst()
                .orElseThrow();
        assertEquals("处理完成", ((Map<?, ?>) answer.getConfig().get("answerConfig")).get("template"));
        assertFalse(node(result.canvasSnapshot(), "answer").containsKey("data"));
        assertEquals("answer", result.graphSpec().getEntryNodeId());
        verify(adapter).author(any());
    }

    @Test
    void returnsFailedStatusWithoutCallingAdapterWhenInstructionBlank() {
        WorkflowAuthoringAgentAdapter adapter = mock(WorkflowAuthoringAgentAdapter.class);
        ObjectMapper objectMapper = new ObjectMapper();
        RuntimeWorkflowProposalEditService service = new RuntimeWorkflowProposalEditService(
                objectMapper,
                new RuntimeWorkflowCanvasLayoutService(objectMapper),
                adapter,
                new RuntimeWorkflowDocumentCanonicalizer(objectMapper));

        RuntimeWorkflowProposalEditView result = service.edit(new RuntimeWorkflowProposalEditRequest(
                "workflow-1",
                "Order Workflow",
                " ",
                "orders",
                "GENERAL",
                "model-1",
                canvas(),
                minimalGraphSpec(),
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
    void currentGraphSpecPreservesRetryAndErrorPolicy() {
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
        RuntimeWorkflowProposalEditService service = new RuntimeWorkflowProposalEditService(
                objectMapper,
                new RuntimeWorkflowCanvasLayoutService(objectMapper),
                adapter,
                new RuntimeWorkflowDocumentCanonicalizer(objectMapper));

        GraphSpec currentGraphSpec = GraphSpec.builder()
                .entryNodeId("answer")
                .node(GraphSpec.Node.builder()
                        .id("answer")
                        .type("ANSWER")
                        .name("Answer")
                        .retry(GraphSpec.RetryPolicy.builder()
                                .enabled(true)
                                .maxAttempts(3)
                                .backoffMs(250L)
                                .build())
                        .errorPolicy(GraphSpec.ErrorPolicy.builder()
                                .strategy("FALLBACK")
                                .fallbackNodeId("answer")
                                .defaultOutput(Map.of("ok", false))
                                .build())
                        .config(Map.of("template", "existing answer"))
                        .build())
                .exitNodeIds(List.of("answer"))
                .build();

        service.edit(new RuntimeWorkflowProposalEditRequest(
                "workflow-1",
                "Order Workflow",
                "保留 retry",
                "orders",
                "GENERAL",
                "model-1",
                canvas(),
                currentGraphSpec,
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

    @Test
    void rejectsMissingCurrentGraphSpecWithoutReadingSemanticsFromCanvas() {
        ObjectMapper objectMapper = new ObjectMapper();
        WorkflowAuthoringAgentAdapter adapter = mock(WorkflowAuthoringAgentAdapter.class);
        RuntimeWorkflowProposalEditService service = new RuntimeWorkflowProposalEditService(
                objectMapper,
                new RuntimeWorkflowCanvasLayoutService(objectMapper),
                adapter,
                new RuntimeWorkflowDocumentCanonicalizer(objectMapper));

        RuntimeWorkflowProposalEditView result = service.edit(new RuntimeWorkflowProposalEditRequest(
                "workflow-1",
                "Order Workflow",
                "update answer",
                "orders",
                "GENERAL",
                "model-1",
                canvas(),
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()));

        assertEquals("FAILED", result.status());
        assertEquals("CURRENT_GRAPH_SPEC_REQUIRED", result.failureCode());
        assertEquals(List.of("currentGraphSpec is required"), result.validationErrors());
        verify(adapter, never()).author(any());
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
                "schemaVersion", 1,
                "layoutVersion", 1,
                "nodes", List.of(
                        Map.of("id", "start", "position", Map.of("x", 0, "y", 0)),
                        Map.of("id", "answer", "position", Map.of("x", 320, "y", 0)),
                        Map.of("id", "end", "position", Map.of("x", 640, "y", 0))),
                "edges", List.of(
                        Map.of("id", "e-start-answer"),
                        Map.of("id", "e-answer-end")));
    }

    private GraphSpec minimalGraphSpec() {
        return GraphSpec.builder()
                .entryNodeId("answer")
                .node(GraphSpec.Node.builder()
                        .id("answer")
                        .type("ANSWER")
                        .name("Answer")
                        .config(Map.of("template", "existing answer"))
                        .build())
                .exitNodeIds(List.of("answer"))
                .build();
    }
}
