package com.enterprise.ai.runtime.workflow.draft;

import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatData;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatRequest;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatResult;
import com.enterprise.ai.runtime.workflow.layout.RuntimeWorkflowCanvasLayoutService;
import com.enterprise.ai.runtime.workflow.RuntimeWorkflowReleaseValidationResult;
import com.enterprise.ai.runtime.workflow.mutation.RuntimeWorkflowGraphMutationService;
import com.enterprise.ai.runtime.workflow.node.RuntimeWorkflowNodeCapabilityDescriptor;
import com.enterprise.ai.runtime.workflow.node.RuntimeWorkflowNodeCapabilityRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void draftPromptUsesOnlyAiAuthoringCatalogAndRejectsClosedNodes() throws Exception {
        CapturingModelClient modelClient = new CapturingModelClient("""
                {"nodes":[{"id":"ask","kind":"interaction","label":"Ask"},{"id":"answer","kind":"answer","label":"Answer"}],"edges":[{"from":"START","to":"ask"},{"from":"ask","to":"answer"},{"from":"answer","to":"END"}]}
                """);
        ObjectMapper objectMapper = new ObjectMapper();
        RuntimeWorkflowDraftCandidateValidationService validationService = validationService();
        RuntimeWorkflowGraphMutationService mutationService = new RuntimeWorkflowGraphMutationService(objectMapper);
        RuntimeWorkflowNodeCapabilityRegistry registry = new RuntimeWorkflowNodeCapabilityRegistry();
        RuntimeWorkflowDraftGenerationService service = new RuntimeWorkflowDraftGenerationService(
                objectMapper,
                modelClient,
                new RuntimeWorkflowCanvasLayoutService(objectMapper),
                validationService,
                new RuntimeWorkflowDraftRepairService(objectMapper, modelClient, mutationService, validationService),
                registry);

        RuntimeWorkflowDraftGenerationView result = service.generate(new RuntimeWorkflowDraftGenerationRequest(
                "agent-1",
                "Order Agent",
                "Collect confirmation before answering",
                "orders",
                "model-1",
                "WORKFLOW",
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()));

        assertFalse(result.validationErrors().isEmpty());
        assertTrue(result.validationErrors().stream().anyMatch(item -> item.contains("WORKFLOW_NODE_NOT_AUTHORABLE")));

        String userPrompt = modelClient.requests.get(0).getMessages().stream()
                .filter(message -> "user".equals(message.getRole()))
                .map(ModelChatRequest.ChatMessage::getContent)
                .findFirst()
                .orElseThrow();
        String systemPrompt = modelClient.requests.get(0).getMessages().stream()
                .filter(message -> "system".equals(message.getRole()))
                .map(ModelChatRequest.ChatMessage::getContent)
                .findFirst()
                .orElseThrow();
        JsonNode nodeTypes = objectMapper.readTree(userPrompt).get("nodeTypes");
        Set<String> promptTypes = new HashSet<>();
        nodeTypes.forEach(item -> promptTypes.add(item.get("type").asText()));
        Set<String> authorable = registry.aiAuthoringCatalog().stream()
                .map(RuntimeWorkflowNodeCapabilityDescriptor::type)
                .collect(Collectors.toSet());
        assertEquals(authorable, promptTypes);
        assertTrue(promptTypes.contains("KNOWLEDGE_RETRIEVAL"));
        assertTrue(promptTypes.contains("HTTP_REQUEST"));
        assertTrue(promptTypes.contains("LOOP"));
        assertFalse(promptTypes.contains("INTERACTION"));
        assertFalse(promptTypes.contains("HUMAN_APPROVAL"));
        assertFalse(promptTypes.contains("MCP_CALL"));
        assertTrue(systemPrompt.contains("Authorable nodeTypes"));
        assertTrue(systemPrompt.contains("Closed / non-authorable types are forbidden"));
        assertFalse(systemPrompt.contains("INTERACTION(confirm_action)"));
        assertFalse(systemPrompt.contains("|approval|"));
    }

    @Test
    void confirmRequiredPageActionKeepsPreExecutionConfirmSemantics() throws Exception {
        CapturingModelClient modelClient = new CapturingModelClient("""
                {"nodes":[
                  {"id":"input","kind":"userInput","label":"Input"},
                  {"id":"open","kind":"pageAction","label":"Open","config":{"ref":"openRowAction"}},
                  {"id":"answer","kind":"answer","label":"Answer","config":{"template":"已处理"}}
                ],"edges":[
                  {"from":"START","to":"input"},
                  {"from":"input","to":"open"},
                  {"from":"open","to":"answer"},
                  {"from":"answer","to":"END"}
                ]}
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

        RuntimeWorkflowDraftResourceView pageAction = new RuntimeWorkflowDraftResourceView(
                "PAGE_ACTION",
                "openRowAction",
                "demo.orders.openRowAction",
                null,
                "orders",
                "Open row",
                Map.of(
                        "projectCode", "orders",
                        "pageKey", "orders",
                        "actionKey", "openRowAction",
                        "confirmRequired", true));

        RuntimeWorkflowDraftGenerationView result = service.generate(new RuntimeWorkflowDraftGenerationRequest(
                "agent-1",
                "Page Agent",
                "Open a table row with confirmation",
                "orders",
                "model-1",
                "PAGE_ASSISTANT",
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(pageAction)));

        assertTrue(result.validationErrors().isEmpty(), String.valueOf(result.validationErrors()));
        Object confirm = result.graphSpec().getNodes().stream()
                .filter(node -> "PAGE_ACTION".equals(node.getType()))
                .findFirst()
                .orElseThrow()
                .getConfig()
                .get("confirm");
        assertEquals(true, confirm);

        String systemPrompt = modelClient.requests.get(0).getMessages().stream()
                .filter(message -> "system".equals(message.getRole()))
                .map(ModelChatRequest.ChatMessage::getContent)
                .findFirst()
                .orElseThrow();
        assertTrue(systemPrompt.contains("Page Bridge performs pre-execution confirmation")
                || systemPrompt.contains("Page Bridge confirms before execution"));
        assertFalse(systemPrompt.contains("ask the user to reconfirm"));
        assertFalse(systemPrompt.contains("动作后再要求确认"));
        assertFalse(systemPrompt.contains("let ANSWER state the action result or ask the user to reconfirm"));
        assertTrue(systemPrompt.contains("Do not emit INTERACTION/HUMAN_APPROVAL"));
        assertFalse(systemPrompt.contains("INTERACTION(confirm_action)"));
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

    @Test
    void finalKnowledgeConfigSerializationOmitsDirectReturnFields() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        RuntimeWorkflowDraftGenerationService service = new RuntimeWorkflowDraftGenerationService(
                objectMapper,
                new CapturingModelClient("{}"),
                new RuntimeWorkflowCanvasLayoutService(objectMapper),
                validationService(),
                mock(RuntimeWorkflowDraftRepairService.class));
        var method = RuntimeWorkflowDraftGenerationService.class.getDeclaredMethod("knowledgeConfig", Map.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) method.invoke(service, Map.of(
                "knowledgeBaseCodes", List.of("kb1"),
                "query", "input",
                "directReturnEnabled", true,
                "directReturnThreshold", 0.9D));
        String json = objectMapper.writeValueAsString(config);
        assertFalse(json.contains("directReturnEnabled"));
        assertFalse(json.contains("directReturnThreshold"));
        assertFalse(config.containsKey("directReturnEnabled"));
        assertFalse(config.containsKey("directReturnThreshold"));
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
