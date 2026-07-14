package com.enterprise.ai.runtime.workflow.draft;

import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatData;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatRequest;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatResult;
import com.enterprise.ai.runtime.workflow.layout.RuntimeWorkflowCanvasLayoutService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowReleaseValidationResult;
import com.enterprise.ai.runtime.workflow.mutation.RuntimeWorkflowGraphMutationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeWorkflowDraftGenerationServiceTest {

    @Test
    void generatesCanvasAndGraphSpecFromModelDraft() {
        CapturingModelClient modelClient = new CapturingModelClient("""
                {"nodes":[{"id":"collect_order","kind":"userInput","label":"Collect order","config":{"fields":[{"name":"orderNo","type":"string"}]}},{"id":"answer","kind":"answer","label":"Answer","config":{"template":"Order {{collect_order.orderNo}} received"}}],"edges":[{"from":"START","to":"collect_order"},{"from":"collect_order","to":"answer"},{"from":"answer","to":"END"}]}
                """);
        ObjectMapper objectMapper = new ObjectMapper();
        RuntimeWorkflowDraftCandidateValidationService validationService = validationService();
        RuntimeWorkflowGraphMutationService mutationService = new RuntimeWorkflowGraphMutationService(objectMapper);
        RuntimeWorkflowDraftGenerationService service = new RuntimeWorkflowDraftGenerationService(
                objectMapper,
                modelClient,
                new RuntimeWorkflowCanvasLayoutService(objectMapper),
                validationService,
                new RuntimeWorkflowDraftRepairService(objectMapper, modelClient, mutationService, validationService));
        RuntimeWorkflowDraftGenerationRequest request = new RuntimeWorkflowDraftGenerationRequest(
                "agent-1",
                "Order Agent",
                "Generate an order intake workflow",
                "orders",
                "model-1",
                "WORKFLOW",
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());

        RuntimeWorkflowDraftGenerationView result = service.generate(request);

        assertEquals("LLM_DRAFT", result.provider());
        assertTrue(result.validationErrors().isEmpty());
        assertEquals("collect_order", result.graphSpec().getEntry());
        assertEquals("Order Agent", result.graphSpec().getName());
        assertEquals(2, result.graphSpec().getNodes().size());
        assertEquals("agent_1", result.canvasSnapshot().get("graphCode"));
        assertEquals(2, result.canvasSnapshot().get("layoutVersion"));
        assertEquals(88.0, x(node(result.canvasSnapshot(), "collect_order"))
                - x(node(result.canvasSnapshot(), "start")) - 240.0);
        assertEquals(centerY(node(result.canvasSnapshot(), "start")), centerY(node(result.canvasSnapshot(), "answer")));
        assertEquals("model-1", modelClient.requests.get(0).getModelInstanceId());
    }

    @Test
    void returnsValidationErrorWithoutCallingModelWhenRequirementIsBlank() {
        CapturingModelClient modelClient = new CapturingModelClient("{}");
        ObjectMapper objectMapper = new ObjectMapper();
        RuntimeWorkflowDraftCandidateValidationService validationService = validationService();
        RuntimeWorkflowGraphMutationService mutationService = new RuntimeWorkflowGraphMutationService(objectMapper);
        RuntimeWorkflowDraftGenerationService service = new RuntimeWorkflowDraftGenerationService(
                objectMapper,
                modelClient,
                new RuntimeWorkflowCanvasLayoutService(objectMapper),
                validationService,
                new RuntimeWorkflowDraftRepairService(objectMapper, modelClient, mutationService, validationService));

        RuntimeWorkflowDraftGenerationView result = service.generate(new RuntimeWorkflowDraftGenerationRequest(
                "agent-1",
                "Order Agent",
                " ",
                "orders",
                "model-1",
                "WORKFLOW",
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()));

        assertEquals(List.of("requirement and modelInstanceId are required"), result.validationErrors());
        assertTrue(modelClient.requests.isEmpty());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> node(Map<String, Object> canvas, String nodeId) {
        return ((List<Map<String, Object>>) canvas.get("nodes")).stream()
                .filter(item -> nodeId.equals(item.get("id")))
                .findFirst()
                .orElseThrow();
    }

    private RuntimeWorkflowDraftCandidateValidationService validationService() {
        RuntimeWorkflowDraftCandidateValidationService service = mock(RuntimeWorkflowDraftCandidateValidationService.class);
        when(service.validate(
                nullable(String.class),
                nullable(String.class),
                nullable(String.class),
                nullable(String.class),
                any(com.enterprise.ai.agent.graph.GraphSpec.class)))
                .thenReturn(RuntimeWorkflowReleaseValidationResult.builder().build());
        return service;
    }

    @SuppressWarnings("unchecked")
    private double x(Map<String, Object> node) {
        return ((Number) ((Map<String, Object>) node.get("position")).get("x")).doubleValue();
    }

    @SuppressWarnings("unchecked")
    private double centerY(Map<String, Object> node) {
        double y = ((Number) ((Map<String, Object>) node.get("position")).get("y")).doubleValue();
        return y + 78.0;
    }

    private static final class CapturingModelClient implements RuntimeModelServiceClient {
        private final String answer;
        private final List<ModelChatRequest> requests = new ArrayList<>();

        private CapturingModelClient(String answer) {
            this.answer = answer;
        }

        @Override
        public ModelChatResult chat(ModelChatRequest request) {
            requests.add(request);
            return new ModelChatResult(0, "ok",
                    new ModelChatData(answer, "gpt-test", "openai", null, null, null, "stop"));
        }
    }
}
