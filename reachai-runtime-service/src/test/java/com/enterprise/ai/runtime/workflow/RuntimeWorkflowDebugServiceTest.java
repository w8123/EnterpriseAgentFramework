package com.enterprise.ai.runtime.workflow;

import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutionResult;
import com.enterprise.ai.runtime.execution.RuntimeGraphSpecExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeWorkflowDebugServiceTest {

    private final RuntimeWorkflowDefinitionService workflowService = mock(RuntimeWorkflowDefinitionService.class);
    private final RuntimeWorkflowDebugService service = new RuntimeWorkflowDebugService(
                workflowService,
                new RuntimeGraphSpecExecutor(new ObjectMapper(), new NoopModelClient(), new NoopCapabilityClient(),
                    mock(com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient.class)),
                mock(com.enterprise.ai.runtime.runops.RuntimeRunLifecycleService.class),
                new com.enterprise.ai.runtime.trace.RuntimeTraceEvidenceWriter(mock(com.enterprise.ai.runtime.trace.RuntimeTraceSpanMapper.class), org.mockito.Mockito.mock(com.enterprise.ai.runtime.trace.RuntimeToolCallLogMapper.class)),
                new ObjectMapper(),
                new RuntimeWorkflowDocumentCanonicalizer(new ObjectMapper()),
                new com.enterprise.ai.runtime.trace.RuntimeTraceRootService(mock(com.enterprise.ai.runtime.trace.RuntimeTraceSpanMapper.class), new ObjectMapper()),
                mock(com.enterprise.ai.runtime.trace.RuntimeTraceSpanTerminationService.class));

    @Test
    void debugContractsUseCanonicalNamesOnly() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RuntimeWorkflowDebugService.DebugRunRequest request = mapper.readValue("""
                {
                  "workflowKind":"GENERAL",
                  "executionEngine":"GRAPH_SPEC",
                  "inputParams":{},
                  "debugOptions":{}
                }
                """, RuntimeWorkflowDebugService.DebugRunRequest.class);

        assertEquals("GENERAL", request.workflowKind());
        assertEquals("GRAPH_SPEC", request.executionEngine());
        JsonNode requestJson = mapper.readTree(mapper.writeValueAsString(request));
        assertEquals("GENERAL", requestJson.path("workflowKind").asText());
        assertEquals("GRAPH_SPEC", requestJson.path("executionEngine").asText());
        assertFalse(requestJson.has("workflowType"));
        assertFalse(requestJson.has("runtimeType"));

        RuntimeWorkflowDebugService.DebugRunResult result = new RuntimeWorkflowDebugService.DebugRunResult(
                "run-1", "trace-1", null, "WORKFLOW", true, "COMPLETED", "ok", "answer",
                List.of(), null, List.of(), Map.of("lastOutput", "ok"), null, null);
        JsonNode resultJson = mapper.readTree(mapper.writeValueAsString(result));
        assertTrue(resultJson.has("stateSnapshot"));
        assertFalse(resultJson.has("finalState"));
    }

    @Test
    void mapExecutionStatusMapsCancelledCodeToCancelledNotError() {
        assertEquals("CANCELLED", RuntimeWorkflowDebugService.mapExecutionStatus(
                new RuntimeGraphSpecExecutionResult(false, "RUNTIME_GRAPH_CANCELLED",
                        "Workflow execution cancelled", "n1", "LLM", List.of(), Map.of())));
        assertEquals("FAILED", RuntimeWorkflowDebugService.mapExecutionStatus(
                new RuntimeGraphSpecExecutionResult(false, "RUNTIME_GRAPH_LLM_FAILED",
                        "boom", "n1", "LLM", List.of(), Map.of())));
        assertEquals("SUSPENDED", RuntimeWorkflowDebugService.mapExecutionStatus(
                new RuntimeGraphSpecExecutionResult(false, "RUNTIME_GRAPH_INTERACTION_WAITING",
                        "wait", "n1", "INTERACTION", List.of(), Map.of())));
        assertEquals("COMPLETED", RuntimeWorkflowDebugService.mapExecutionStatus(
                new RuntimeGraphSpecExecutionResult(true, "RUNTIME_GRAPH_EXECUTED",
                        "ok", "n1", "ANSWER", List.of(), Map.of())));
    }

    @Test
    void debugRunExecutesWorkflowGraphSpecLocally() {
        RuntimeWorkflowDebugService.DebugRunRequest request = new RuntimeWorkflowDebugService.DebugRunRequest(
                null,
                "wf-orders",
                "Orders",
                "GENERAL",
                "orders",
                "GRAPH_SPEC",
                null,
                """
                        {
                          "schemaVersion":2,
                          "entryNodeId":"input",
                          "exitNodeIds":["answer"],
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
        assertEquals("COMPLETED", result.status());
        assertEquals("收到：A-1", result.answer());
        assertTrue(result.traceId().startsWith("studio-debug-run-"));
        assertEquals(result.runId(), result.traceId());
        assertEquals(2, result.steps().size());
        assertEquals("input", result.steps().get(0).nodeId());
        assertEquals("answer", result.steps().get(1).nodeId());
        assertEquals("收到：A-1", result.stateSnapshot().get("lastOutput"));
    }

    @Test
    void evalDebugOptionCannotExecutePageAction() {
        RuntimeWorkflowDebugService.DebugRunRequest request =
                new RuntimeWorkflowDebugService.DebugRunRequest(
                        null, "wf-page", "Page action", "GENERAL", "orders", "GRAPH_SPEC",
                        null,
                        """
                                {
                                  "schemaVersion":2,
                                  "entryNodeId":"delete",
                                  "exitNodeIds":["delete"],
                                  "nodes":[{"id":"delete","type":"PAGE_ACTION","config":{
                                    "projectCode":"orders","pageKey":"orders.list","actionKey":"deleteOrder"
                                  }}],
                                  "edges":[]
                                }
                                """,
                        null, "删除订单", Map.of(),
                        Map.of("runId", "eval-debug-1", "evalMode", true,
                                "sandboxSideEffects", true));

        RuntimeWorkflowDebugService.DebugRunResult result = service.debugRun(request);

        assertFalse(result.success());
        assertEquals("FAILED", result.status());
        assertEquals("EVAL_SIDE_EFFECT_BLOCKED", result.errorCode());
    }

    @Test
    void debugNodeExecutesFromRequestedNodeAndCanLoadGraphSpecByWorkflowId() {
        RuntimeWorkflowDefinitionEntity workflow = new RuntimeWorkflowDefinitionEntity();
        workflow.setId("wf-1");
        workflow.setKeySlug("wf-orders");
        workflow.setName("Orders");
        workflow.setWorkflowKind("GENERAL");
        workflow.setExecutionEngine("GRAPH_SPEC");
        workflow.setGraphSpecJson("""
                {
                  "schemaVersion":2,
                  "entryNodeId":"input",
                  "exitNodeIds":["answer"],
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

    @Test
    void debugRunExposesIntentClassifierRouteInStepDetails() {
        RuntimeWorkflowDebugService.DebugRunRequest request = new RuntimeWorkflowDebugService.DebugRunRequest(
                null,
                "wf-router",
                "Router",
                "GENERAL",
                "orders",
                "GRAPH_SPEC",
                null,
                """
                        {
                          "schemaVersion":2,
                          "entryNodeId":"classifier",
                          "exitNodeIds":["answer"],
                          "nodes":[
                            {"id":"classifier","type":"INTENT_CLASSIFIER","config":{
                              "strategy":"KEYWORD",
                              "classes":[{"id":"search","keywords":["查询"]}],
                              "defaultRoute":"else"
                            }},
                            {"id":"answer","type":"ANSWER","config":{"template":"done"}}
                          ],
                          "edges":[{"from":"classifier","to":"answer","condition":"route:search"}]
                        }
                        """,
                null,
                "查询订单",
                Map.of(),
                Map.of());

        RuntimeWorkflowDebugService.DebugRunResult result = service.debugRun(request);

        assertEquals(true, result.success());
        assertEquals("search", result.steps().get(0).route());
        assertEquals("done", result.answer());
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
        public java.util.List<Map<String, Object>> listProjectTools(Long projectId) {
            return java.util.List.of();
        }

        @Override
        public Map<String, Object> projectReadinessFacts(Long projectId) {
            return Map.of();
        }

    }
}
