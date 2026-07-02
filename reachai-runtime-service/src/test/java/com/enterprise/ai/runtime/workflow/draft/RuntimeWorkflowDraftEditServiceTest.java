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

class RuntimeWorkflowDraftEditServiceTest {

    @Test
    void appliesModelPatchAndBuildsGraphSpec() {
        CapturingModelClient modelClient = new CapturingModelClient("""
                {"summary":"updated answer","operations":[{"type":"UPDATE_NODE","nodeId":"answer","patch":{"data":{"answerConfig":{"template":"处理完成"},"template":"处理完成"}},"reason":"match instruction"}]}
                """);
        RuntimeWorkflowDraftEditService service = new RuntimeWorkflowDraftEditService(
                new ObjectMapper(),
                modelClient);
        RuntimeWorkflowDraftEditRequest request = new RuntimeWorkflowDraftEditRequest(
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
                List.of());

        RuntimeWorkflowDraftEditView result = service.edit(request);

        assertEquals("LLM_PATCH", result.provider());
        assertEquals("updated answer", result.summary());
        assertTrue(result.validationErrors().isEmpty());
        assertEquals("处理完成", ((Map<?, ?>) ((Map<?, ?>) node(result.canvasSnapshot(), "answer")
                .get("data")).get("answerConfig")).get("template"));
        assertEquals("answer", result.graphSpec().getEntry());
        assertEquals("Order Agent", result.graphSpec().getName());
        assertEquals("model-1", modelClient.requests.get(0).getModelInstanceId());
    }

    @Test
    void returnsValidationErrorWithoutCallingModelWhenInstructionIsBlank() {
        CapturingModelClient modelClient = new CapturingModelClient("{}");
        RuntimeWorkflowDraftEditService service = new RuntimeWorkflowDraftEditService(
                new ObjectMapper(),
                modelClient);

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

        assertEquals(List.of("instruction is required"), result.validationErrors());
        assertTrue(modelClient.requests.isEmpty());
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> node(Map<String, Object> canvas, String nodeId) {
        return ((List<Map<String, Object>>) canvas.get("nodes")).stream()
                .filter(item -> nodeId.equals(item.get("id")))
                .findFirst()
                .orElseThrow();
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
