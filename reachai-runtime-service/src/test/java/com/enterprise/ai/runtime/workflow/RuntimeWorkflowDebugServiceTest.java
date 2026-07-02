package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeWorkflowDebugServiceTest {

    private final RuntimeWorkflowDefinitionService workflowService = mock(RuntimeWorkflowDefinitionService.class);
    private final RuntimeWorkflowDebugService service = new RuntimeWorkflowDebugService(
            workflowService,
            new RuntimeGraphSpecExecutor(new ObjectMapper(), new NoopModelClient(), new NoopCapabilityClient()),
            new ObjectMapper());

    @Test
    void debugRunExecutesWorkflowGraphSpecLocally() {
        RuntimeWorkflowDebugService.DebugRunRequest request = new RuntimeWorkflowDebugService.DebugRunRequest(
                null,
                "wf-orders",
                "Orders",
                "CHAT",
                "orders",
                "LANGGRAPH4J",
                null,
                """
                        {
                          "entry":"input",
                          "nodes":[
                            {"id":"input","type":"USER_INPUT","name":"Input"},
                            {"id":"answer","type":"ANSWER","name":"Answer","config":{"template":"收到：{{ input }}"}}
                          ],
                          "edges":[{"from":"input","to":"answer"}]
                        }
                        """,
                null,
                "A-1",
                Map.of("channel", "studio"),
                Map.of("traceId", "trace-debug"));

        RuntimeWorkflowDebugService.DebugRunResult result = service.debugRun(request);

        assertEquals(true, result.success());
        assertEquals("SUCCESS", result.status());
        assertEquals("收到：A-1", result.answer());
        assertEquals("trace-debug", result.traceId());
        assertEquals(2, result.steps().size());
        assertEquals("input", result.steps().get(0).nodeId());
        assertEquals("answer", result.steps().get(1).nodeId());
        assertEquals("收到：A-1", result.finalState().get("lastOutput"));
    }

    @Test
    void debugNodeExecutesFromRequestedNodeAndCanLoadGraphSpecByWorkflowId() {
        RuntimeWorkflowDefinitionEntity workflow = new RuntimeWorkflowDefinitionEntity();
        workflow.setId("wf-1");
        workflow.setKeySlug("wf-orders");
        workflow.setName("Orders");
        workflow.setWorkflowType("CHAT");
        workflow.setRuntimeType("LANGGRAPH4J");
        workflow.setGraphSpecJson("""
                {
                  "entry":"input",
                  "nodes":[
                    {"id":"input","type":"USER_INPUT"},
                    {"id":"answer","type":"ANSWER","config":{"template":"节点：{{ input }}"}}
                  ],
                  "edges":[{"from":"input","to":"answer"}]
                }
                """);
        when(workflowService.findById("wf-1")).thenReturn(Optional.of(workflow));

        RuntimeWorkflowDebugService.NodeDebugRequest request = new RuntimeWorkflowDebugService.NodeDebugRequest(
                "wf-1",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "answer",
                "hello",
                Map.of("input", "state-input"));

        RuntimeWorkflowDebugService.NodeDebugResult result = service.debugNode(request);

        assertEquals(true, result.success());
        assertEquals("answer", result.nodeId());
        assertEquals("ANSWER", result.nodeType());
        assertEquals("节点：state-input", result.nodeOutput());
        assertEquals("节点：state-input", result.outputState().get("lastOutput"));
    }

    private static final class NoopModelClient implements RuntimeModelServiceClient {
        @Override
        public ModelChatResult chat(ModelChatRequest request) {
            return new ModelChatResult(0, "ok",
                    new ModelChatData("model answer", "model-1", "test", null, null, null, "stop"));
        }
    }

    private static final class NoopCapabilityClient implements RuntimeCapabilityCatalogClient {
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
            return Map.of();
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
