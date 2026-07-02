package com.enterprise.ai.runtime.workflow.draft;

import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatData;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatRequest;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeWorkflowDraftGenerationServiceTest {

    @Test
    void generatesCanvasAndGraphSpecFromModelDraft() {
        CapturingModelClient modelClient = new CapturingModelClient("""
                {"nodes":[{"id":"collect_order","kind":"userInput","label":"Collect order","config":{"fields":[{"name":"orderNo","type":"string"}]}},{"id":"answer","kind":"answer","label":"Answer","config":{"template":"Order {{collect_order.orderNo}} received"}}],"edges":[{"from":"START","to":"collect_order"},{"from":"collect_order","to":"answer"},{"from":"answer","to":"END"}]}
                """);
        RuntimeWorkflowDraftGenerationService service = new RuntimeWorkflowDraftGenerationService(
                new ObjectMapper(),
                modelClient);
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
        assertEquals("model-1", modelClient.requests.get(0).getModelInstanceId());
    }

    @Test
    void returnsValidationErrorWithoutCallingModelWhenRequirementIsBlank() {
        CapturingModelClient modelClient = new CapturingModelClient("{}");
        RuntimeWorkflowDraftGenerationService service = new RuntimeWorkflowDraftGenerationService(
                new ObjectMapper(),
                modelClient);

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
