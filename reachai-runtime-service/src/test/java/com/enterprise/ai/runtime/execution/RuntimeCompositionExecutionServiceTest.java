package com.enterprise.ai.runtime.execution;

import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuntimeCompositionExecutionServiceTest {

    private final CapturingCapabilityClient capabilityClient = new CapturingCapabilityClient();
    private final RuntimeGraphSpecExecutor graphSpecExecutor = new RuntimeGraphSpecExecutor(
            new ObjectMapper(),
            request -> new RuntimeModelServiceClient.ModelChatResult(
                    0,
                    "ok",
                    new RuntimeModelServiceClient.ModelChatData("model answer", "model-1", "openai", null, null, null,
                            "stop")),
            capabilityClient);
    private final RuntimeCompositionExecutionService service =
            new RuntimeCompositionExecutionService(capabilityClient, graphSpecExecutor);

    @Test
    void executesCompositionGraphSpecLoadedFromCapabilityService() {
        capabilityClient.composition = Map.of(
                "qualifiedName", "orders.queryOrderFlow",
                "capabilityCode", "orders",
                "compositionCode", "queryOrderFlow",
                "enabled", true,
                "graphSpecJson", """
                        {"entry":"answer","nodes":[{"id":"answer","type":"ANSWER","config":{"template":"订单：{{ orderNo }}"}}]}
                        """);

        Map<String, Object> result = service.execute("orders.queryOrderFlow",
                Map.of("params", Map.of("orderNo", "A001")));

        assertEquals(true, result.get("success"));
        assertEquals("RUNTIME_GRAPH_EXECUTED", result.get("code"));
        assertEquals("订单：A001", result.get("answer"));
        assertEquals(List.of("orders.queryOrderFlow"), capabilityClient.compositionLookups);
    }

    @Test
    void rejectsDisabledCompositionWithoutExecutingGraphSpec() {
        capabilityClient.composition = Map.of(
                "qualifiedName", "orders.disabled",
                "enabled", false,
                "graphSpecJson", """
                        {"entry":"answer","nodes":[{"id":"answer","type":"ANSWER","config":{"template":"ok"}}]}
                        """);

        Map<String, Object> result = service.execute("orders.disabled", Map.of("message", "hello"));

        assertEquals(false, result.get("success"));
        assertEquals("RUNTIME_COMPOSITION_DISABLED", result.get("code"));
        assertEquals("Composition definition is disabled: orders.disabled", result.get("answer"));
    }

    private static final class CapturingCapabilityClient implements RuntimeCapabilityCatalogClient {
        private final List<String> compositionLookups = new ArrayList<>();
        private Map<String, Object> composition = Map.of();

        @Override
        public Map<String, Object> getToolDefinition(String qualifiedName) {
            return Map.of();
        }

        @Override
        public Map<String, Object> executeTool(String qualifiedName, Map<String, Object> request) {
            return Map.of("success", true, "data", Map.of());
        }

        @Override
        public Map<String, Object> getCompositionDefinition(String qualifiedName) {
            compositionLookups.add(qualifiedName);
            return composition;
        }

        @Override
        public Map<String, Object> getProject(String projectCode) {
            return Map.of();
        }

        @Override
        public Map<String, Object> getProjectById(Long projectId) {
            return Map.of();
        }

        @Override
        public List<Map<String, Object>> listRuntimeInstances() {
            return List.of();
        }
    }
}
