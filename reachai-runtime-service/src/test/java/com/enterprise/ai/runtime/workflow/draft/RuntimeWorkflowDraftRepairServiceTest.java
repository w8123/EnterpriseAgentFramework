package com.enterprise.ai.runtime.workflow.draft;

import com.enterprise.ai.agent.graph.GraphSpec;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatData;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatRequest;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatResult;
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

class RuntimeWorkflowDraftRepairServiceTest {

    @Test
    void repairsCandidateAndRevalidatesWithinBoundedLoop() {
        ObjectMapper objectMapper = new ObjectMapper();
        CapturingModelClient modelClient = new CapturingModelClient("""
                {"summary":"set entry","operations":[{"op":"SET_ENTRY","entry":"answer"}]}
                """);
        RuntimeWorkflowDraftCandidateValidationService validationService =
                mock(RuntimeWorkflowDraftCandidateValidationService.class);
        when(validationService.validate(
                nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class), any(GraphSpec.class)))
                .thenReturn(RuntimeWorkflowReleaseValidationResult.builder().build());
        RuntimeWorkflowDraftRepairService service = new RuntimeWorkflowDraftRepairService(
                objectMapper,
                modelClient,
                new RuntimeWorkflowGraphMutationService(objectMapper),
                validationService);
        GraphSpec candidate = GraphSpec.builder()
                .code("repair")
                .name("Repair")
                .node(GraphSpec.Node.builder().id("answer").type("ANSWER").name("Answer").build())
                .build();
        RuntimeWorkflowReleaseValidationResult invalid = RuntimeWorkflowReleaseValidationResult.builder()
                .error("GRAPH_ENTRY_MISSING", null, "GraphSpec entry is required")
                .build();

        RuntimeWorkflowDraftRepairService.RepairResult result = service.repair(
                new RuntimeWorkflowDraftRepairService.RepairRequest(
                        "wf-1", "orders", "WORKFLOW", "model-1", "create an answer workflow",
                        List.of(), List.of(), Map.of(), 2),
                candidate,
                invalid);

        assertEquals("answer", result.graphSpec().getEntry());
        assertTrue(result.validation().valid());
        assertEquals(1, result.completedRounds());
        assertEquals(1, modelClient.requests.size());
        assertTrue(result.warnings().get(0).contains("revalidated"));
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
