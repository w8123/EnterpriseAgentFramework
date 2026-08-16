package com.enterprise.ai.runtime.execution;

import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatData;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatRequest;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatRequest.ChatMessage;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeGraphSpecExecutorTest {

    private final CapturingModelClient modelClient = new CapturingModelClient("model answer");
    private final CapturingCapabilityClient capabilityClient = new CapturingCapabilityClient();
    private final RuntimeGraphSpecExecutor executor =
            new RuntimeGraphSpecExecutor(new ObjectMapper(), modelClient, capabilityClient,
                    org.mockito.Mockito.mock(com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient.class));

    @Test
    void executesAnswerEntryNodeWithInputTemplate() {
        RuntimeGraphSpecExecutionResult result = executor.execute("""
                {"entryNodeId":"answer","exitNodeIds":["answer"],"nodes":[{"id":"answer","type":"ANSWER","config":{"template":"收到：{{ input }}"}}]}
                """, Map.of("message", "hello"));

        assertEquals(true, result.success());
        assertEquals("收到：hello", result.answer());
        assertEquals("RUNTIME_GRAPH_EXECUTED", result.code());
        assertEquals("answer", result.nodeId());
        assertEquals("ANSWER", result.nodeType());
    }

    @Test
    void explicitScalarInputTakesPrecedenceOverTransportMessage() {
        RuntimeGraphSpecExecutionResult result = executor.execute("""
                {"entryNodeId":"answer","exitNodeIds":["answer"],"nodes":[{"id":"answer","type":"ANSWER","config":{"template":"收到：{{ input }}"}}]}
                """, Map.of("input", "state-input", "message", "transport-message"));

        assertTrue(result.success());
        assertEquals("收到：state-input", result.answer());
    }

    @Test
    void userInputWritesDeclaredFieldsIntoReservedParamsNamespace() {
        RuntimeGraphSpecExecutionResult result = executor.execute("""
                {
                  "entryNodeId":"input",
                  "exitNodeIds":["answer"],
                  "nodes":[
                    {"id":"input","type":"USER_INPUT","config":{
                      "outputAlias":"params",
                      "fields":[{"name":"question","type":"string","required":true,"source":"input.message"}]
                    }},
                    {"id":"answer","type":"ANSWER","config":{"template":"收到：{{ params.question }}"}}
                  ],
                  "edges":[{"from":"input","to":"answer","condition":"always"}]
                }
                """, Map.of("message", "查询班组"));

        assertTrue(result.success());
        assertEquals("收到：查询班组", result.answer());
    }

    @Test
    void executesLlmEntryNodeThroughModelGateway() {
        RuntimeGraphSpecExecutionResult result = executor.execute("""
                {
                  "entryNodeId":"llm",
                  "exitNodeIds":["llm"],
                  "nodes":[{
                    "id":"llm",
                    "type":"LLM",
                    "config":{
                      "modelInstanceId":"model-1",
                      "systemPrompt":"你是订单助手",
                      "userPrompt":"用户问题：{{ input }}"
                    }
                  }]
                }
                """, Map.of("message", "查订单"));

        assertEquals(true, result.success());
        assertEquals("model answer", result.answer());
        assertEquals("RUNTIME_GRAPH_EXECUTED", result.code());
        assertEquals("llm", result.nodeId());
        assertEquals("LLM", result.nodeType());
        assertEquals(1, modelClient.requests.size());
        ModelChatRequest request = modelClient.requests.get(0);
        assertEquals("model-1", request.getModelInstanceId());
        assertMessage(request.getMessages().get(0), "system", "你是订单助手");
        assertMessage(request.getMessages().get(1), "user", "用户问题：查订单");
    }

    @Test
    void publishedWorkflowDefaultModelTakesPrecedenceOverRequestInputModel() {
        RuntimeGraphSpecExecutionResult result = executor.execute("""
                {
                  "entryNodeId":"llm",
                  "exitNodeIds":["llm"],
                  "nodes":[{"id":"llm","type":"LLM","config":{"userPrompt":"{{ input }}"}}]
                }
                """, Map.of(
                "message", "查订单",
                "modelInstanceId", "untrusted-input-model",
                "workflowDefaultModelInstanceId", "published-model"));

        assertEquals(true, result.success());
        assertEquals("published-model", modelClient.requests.get(0).getModelInstanceId());
    }

    @Test
    void explicitBlankPublishedWorkflowDefaultDoesNotFallBackToRequestInputModel() {
        RuntimeGraphSpecExecutionResult result = executor.execute("""
                {
                  "entryNodeId":"llm",
                  "exitNodeIds":["llm"],
                  "nodes":[{"id":"llm","type":"LLM","config":{"userPrompt":"{{ input }}"}}]
                }
                """, Map.of(
                "message", "查订单",
                "modelInstanceId", "untrusted-input-model",
                "workflowDefaultModelInstanceId", ""));

        assertEquals(false, result.success());
        assertEquals("RUNTIME_GRAPH_MODEL_REQUIRED", result.code());
        assertEquals(0, modelClient.requests.size());
    }

    @Test
    void executesCrossRoutePageActionThroughControlBridgeProtocol() {
        RuntimeControlCatalogClient controlClient = mock(RuntimeControlCatalogClient.class);
        RuntimeGraphSpecExecutor pageExecutor = new RuntimeGraphSpecExecutor(
                new ObjectMapper(), modelClient, capabilityClient, controlClient);
        when(controlClient.executePageBridge(any())).thenReturn(
                new RuntimeControlCatalogClient.PageBridgeExecutionResponse(
                        true,
                        "PAGE_BRIDGE_COMPLETED",
                        "SUCCESS",
                        Map.of("teamName", "一班"),
                        List.of(
                                Map.of("phase", "NAVIGATE", "status", "SUCCESS"),
                                Map.of("phase", "TARGET_READY", "status", "SUCCESS"),
                                Map.of("phase", "PAGE_ACTION", "status", "SUCCESS"))));

        RuntimeGraphSpecExecutionResult result = pageExecutor.execute("""
                {"entryNodeId":"team","exitNodeIds":["team"],"nodes":[{"id":"team","type":"PAGE_ACTION","config":{
                  "projectCode":"qmssmp","pageKey":"team.departments","route":"/team-build/depart-management",
                  "actionKey":"queryFirstTeam","confirmRequired":true,"inputMapping":{},"args":{"keyword":"{{ teamName }}"}
                }}]}
                """, Map.of(
                "sessionId", "s1",
                "agentId", "agent-1",
                "projectCode", "qmssmp",
                "pageKey", "home",
                "teamName", "一班",
                "pageBridgeTimeoutMs", 20000));

        assertEquals(true, result.success());
        assertEquals("RUNTIME_PAGE_ACTION_EXECUTED", result.code());
        assertEquals("PAGE_ACTION", result.nodeType());
        assertEquals("PAGE_BRIDGE_COMPLETED", result.metadata().get("pageBridgeCode"));
        ArgumentCaptor<RuntimeControlCatalogClient.PageBridgeExecutionRequest> request =
                ArgumentCaptor.forClass(RuntimeControlCatalogClient.PageBridgeExecutionRequest.class);
        verify(controlClient).executePageBridge(request.capture());
        assertEquals("一班", request.getValue().args().get("keyword"));
        assertEquals(true, request.getValue().confirmRequired());
        assertEquals(90_000, request.getValue().confirmationTimeoutMs());
        assertEquals(20_000, request.getValue().executionTimeoutMs());
    }

    @Test
    void preservesPageBridgeFailureWhenOptionalResponseFieldsAreNull() {
        RuntimeControlCatalogClient controlClient = mock(RuntimeControlCatalogClient.class);
        RuntimeGraphSpecExecutor pageExecutor = new RuntimeGraphSpecExecutor(
                new ObjectMapper(), modelClient, capabilityClient, controlClient);
        when(controlClient.executePageBridge(any())).thenReturn(
                new RuntimeControlCatalogClient.PageBridgeExecutionResponse(
                        false,
                        "PAGE_BRIDGE_ACTION_FAILED",
                        "TIMEOUT",
                        null,
                        null));

        RuntimeGraphSpecExecutionResult result = pageExecutor.execute("""
                {"entryNodeId":"team","exitNodeIds":["team"],"nodes":[{"id":"team","type":"PAGE_ACTION","config":{
                  "projectCode":"qmssmp","pageKey":"teamArchive.list","actionKey":"setEnabled","inputMapping":{}
                }}]}
                """, Map.of(
                "sessionId", "s1", "agentId", "agent-1", "projectCode", "qmssmp", "pageKey", "teamArchive.list"));

        assertEquals(false, result.success());
        assertEquals("PAGE_BRIDGE_ACTION_FAILED", result.code());
        assertEquals("TIMEOUT", result.answer());
        assertEquals("TIMEOUT", result.metadata().get("pageBridgeStatus"));
    }

    @Test
    void normalizesNestedPageActionTotalCountWithoutCopyingBusinessRowsIntoTheSummary() {
        RuntimeControlCatalogClient controlClient = mock(RuntimeControlCatalogClient.class);
        RuntimeGraphSpecExecutor pageExecutor = new RuntimeGraphSpecExecutor(
                new ObjectMapper(), modelClient, capabilityClient, controlClient);
        when(controlClient.executePageBridge(any())).thenReturn(
                new RuntimeControlCatalogClient.PageBridgeExecutionResponse(
                        true,
                        "PAGE_BRIDGE_COMPLETED",
                        "SUCCESS",
                        Map.of("status", "SUCCESS", "data", Map.of(
                                "totalCount", 0,
                                "courses", List.of())),
                        List.of()));

        RuntimeGraphSpecExecutionResult result = pageExecutor.execute("""
                {"entryNodeId":"courses","exitNodeIds":["courses"],"nodes":[{"id":"courses","type":"PAGE_ACTION","config":{
                  "projectCode":"roncoo","pageKey":"course.list","actionKey":"course.list.readTable","inputMapping":{}
                }}]}
                """, Map.of(
                "sessionId", "s-course", "agentId", "agent-1", "projectCode", "roncoo", "pageKey", "course.list"));

        assertTrue(result.success());
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) result.metadata().get("pageActionResultSummary");
        assertEquals("course.list.readTable", summary.get("actionKey"));
        assertEquals(0L, summary.get("total"));
        assertEquals(true, summary.get("empty"));
        assertFalse(summary.containsKey("courses"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> remembered = (List<Map<String, Object>>) result.resumeCheckpoint()
                .get("pageActionResults");
        assertEquals(summary, remembered.get(0));
    }

    @Test
    void carriesVerifiedUserConfirmationIntoThePageActionSummary() {
        RuntimeControlCatalogClient controlClient = mock(RuntimeControlCatalogClient.class);
        RuntimeGraphSpecExecutor pageExecutor = new RuntimeGraphSpecExecutor(
                new ObjectMapper(), modelClient, capabilityClient, controlClient);
        when(controlClient.executePageBridge(any())).thenReturn(
                new RuntimeControlCatalogClient.PageBridgeExecutionResponse(
                        true,
                        "PAGE_BRIDGE_COMPLETED",
                        "SUCCESS",
                        Map.of("message", "Team state updated", "userConfirmed", true),
                        List.of()));

        RuntimeGraphSpecExecutionResult result = pageExecutor.execute("""
                {"entryNodeId":"update","exitNodeIds":["update"],"nodes":[{"id":"update","type":"PAGE_ACTION","config":{
                  "projectCode":"qmssmp","pageKey":"teamArchive.list","actionKey":"setEnabled","confirm":true,"inputMapping":{}
                }}]}
                """, Map.of(
                "sessionId", "s-confirm", "agentId", "agent-1",
                "projectCode", "qmssmp", "pageKey", "teamArchive.list"));

        assertTrue(result.success());
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) result.metadata()
                .get("pageActionResultSummary");
        assertEquals(true, summary.get("userConfirmed"));
        assertEquals("Team state updated", summary.get("message"));
    }

    @Test
    void treatsBusinessTerminalPageActionAsCompletedWorkflowOutcome() {
        RuntimeControlCatalogClient controlClient = mock(RuntimeControlCatalogClient.class);
        RuntimeGraphSpecExecutor pageExecutor = new RuntimeGraphSpecExecutor(
                new ObjectMapper(), modelClient, capabilityClient, controlClient);
        when(controlClient.executePageBridge(any())).thenReturn(
                new RuntimeControlCatalogClient.PageBridgeExecutionResponse(
                        true,
                        "PAGE_BRIDGE_BUSINESS_TERMINAL",
                        "PRECONDITION_FAILED",
                        Map.of("selectedCount", 0,
                                "message", "Select a row before opening details"),
                        List.of(Map.of("phase", "PAGE_ACTION",
                                "status", "PRECONDITION_FAILED"))));

        RuntimeGraphSpecExecutionResult result = pageExecutor.execute("""
                {"entryNodeId":"open","exitNodeIds":["open"],"nodes":[{"id":"open","type":"PAGE_ACTION","config":{
                  "projectCode":"qmssmp","pageKey":"teamArchive.list","actionKey":"openSelected","args":{}
                }}]}
                """, Map.of(
                "sessionId", "s1",
                "agentId", "agent-1",
                "projectCode", "qmssmp",
                "pageKey", "teamArchive.list"));

        assertTrue(result.success());
        assertEquals("RUNTIME_PAGE_ACTION_BUSINESS_TERMINAL", result.code());
        assertEquals("Select a row before opening details", result.answer());
        assertEquals("BUSINESS_TERMINAL", result.metadata().get("outcomeClass"));
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) result.metadata()
                .get("pageActionResultSummary");
        assertEquals("PRECONDITION_FAILED", summary.get("businessOutcome"));
        assertEquals("BUSINESS_TERMINAL", summary.get("outcomeClass"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> traces = (List<Map<String, Object>>) result.metadata()
                .get("workflowNodeTraces");
        assertEquals("BUSINESS_TERMINAL", traces.get(0).get("status"));
    }

    @Test
    void resolvesStructuredWorkflowInputForPageActionMappings() {
        RuntimeControlCatalogClient controlClient = mock(RuntimeControlCatalogClient.class);
        RuntimeGraphSpecExecutor pageExecutor = new RuntimeGraphSpecExecutor(
                new ObjectMapper(), modelClient, capabilityClient, controlClient);
        when(controlClient.executePageBridge(any())).thenReturn(
                new RuntimeControlCatalogClient.PageBridgeExecutionResponse(
                        true,
                        "PAGE_BRIDGE_COMPLETED",
                        "SUCCESS",
                        Map.of("total", 3),
                        List.of()));

        RuntimeGraphSpecExecutionResult result = pageExecutor.execute("""
                {"entryNodeId":"query","exitNodeIds":["query"],"nodes":[{"id":"query","type":"PAGE_ACTION","config":{
                  "projectCode":"mall","pageKey":"mall.oms.order","route":"/oms/order",
                  "actionKey":"query","inputMapping":{"orderSn":"input.orderSn","status":"input.status"}
                }}]}
                """, Map.of(
                "input", Map.of("orderSn", "201809150101000001", "status", 4),
                "message", "filter orders by status",
                "params", Map.of("orderSn", "201809150101000001", "status", 4),
                "sessionId", "s1",
                "agentId", "agent-1",
                "projectCode", "mall"));

        assertTrue(result.success());
        ArgumentCaptor<RuntimeControlCatalogClient.PageBridgeExecutionRequest> request =
                ArgumentCaptor.forClass(RuntimeControlCatalogClient.PageBridgeExecutionRequest.class);
        verify(controlClient).executePageBridge(request.capture());
        assertEquals("201809150101000001", request.getValue().args().get("orderSn"));
        assertEquals(4, request.getValue().args().get("status"));
    }

    @Test
    void preservesPageActionMapOutputForDownstreamConditionAndTemplate() {
        RuntimeControlCatalogClient controlClient = mock(RuntimeControlCatalogClient.class);
        RuntimeGraphSpecExecutor pageExecutor = new RuntimeGraphSpecExecutor(
                new ObjectMapper(), modelClient, capabilityClient, controlClient);
        when(controlClient.executePageBridge(any())).thenReturn(
                new RuntimeControlCatalogClient.PageBridgeExecutionResponse(
                        true,
                        "PAGE_BRIDGE_COMPLETED",
                        "SUCCESS",
                        Map.of("status", "OPENED", "team", Map.of("name", "Team A")),
                        List.of()));

        RuntimeGraphSpecExecutionResult result = pageExecutor.execute("""
                {
                  "entryNodeId":"open",
                  "exitNodeIds":["answer","failed"],
                  "nodes":[
                    {"id":"open","type":"PAGE_ACTION","config":{
                      "projectCode":"demo","pageKey":"teams","actionKey":"openTeam"
                    }},
                    {"id":"judge","type":"IF_ELSE","config":{
                      "conditionGroups":[{"id":"opened","conditions":[
                        {"left":"nodeOutput.open.status","operator":"equals","right":"OPENED"}
                      ]}],
                      "defaultRoute":"failed"
                    }},
                    {"id":"answer","type":"ANSWER","config":{
                      "template":"{{ lastOutput.team.name }}"
                    }},
                    {"id":"failed","type":"ANSWER","config":{"template":"failed"}}
                  ],
                  "edges":[
                    {"from":"open","to":"judge"},
                    {"from":"judge","to":"answer","condition":"opened"},
                    {"from":"judge","to":"failed","condition":"failed"}
                  ]
                }
                """, Map.of(
                "sessionId", "s1",
                "agentId", "agent-1",
                "projectCode", "demo"));

        assertEquals(true, result.success());
        assertEquals("Team A", result.answer());
        assertEquals("opened", result.steps().get(1).get("route"));
    }

    @Test
    void preservesInteractionPayloadForDownstreamParameterExtraction() {
        RuntimeGraphSpecExecutionResult result = executor.execute("""
                {
                  "entryNodeId":"form",
                  "exitNodeIds":["answer"],
                  "nodes":[
                    {"id":"form","type":"INTERACTION","config":{"interactionType":"COLLECT_INPUT","fields":[
                      {"key":"approved","type":"boolean","required":true},
                      {"key":"user","type":"object","required":true}
                    ]}},
                    {"id":"extract","type":"PARAMETER_EXTRACT","config":{
                      "extractMode":"expression",
                      "fields":[
                        {"name":"approved","type":"boolean","source":"lastOutput.approved","required":true},
                        {"name":"userId","source":"nodeOutput.form.user.id","required":true}
                      ]
                    }},
                    {"id":"answer","type":"ANSWER","config":{
                      "template":"{{ nodeOutput.extract.approved }}:{{ nodeOutput.extract.userId }}"
                    }}
                  ],
                  "edges":[
                    {"from":"form","to":"extract"},
                    {"from":"extract","to":"answer"}
                  ]
                }
                """, Map.of(
                "__interactionResume", Map.of(
                        "interactionId", "wfi_test1",
                        "nodeId", "form",
                        "action", "submit",
                        "values", Map.of(
                                "approved", true,
                                "user", Map.of("id", "u-1")))));

        assertEquals(true, result.success());
        assertEquals("true:u-1", result.answer());
    }

    @Test
    void interactionWithoutPayloadSuspendsWithUiRequest() {
        RuntimeGraphSpecExecutionResult result = executor.execute("""
                {
                  "entryNodeId":"form",
                  "exitNodeIds":["form"],
                  "nodes":[{"id":"form","type":"INTERACTION","config":{
                    "interactionType":"COLLECT_INPUT",
                    "title":"查询条件",
                    "fields":[{"key":"q","label":"关键词","type":"string","required":true}]
                  }}]
                }
                """, Map.of("traceId", "t1", "runId", "r1"));

        assertEquals(false, result.success());
        assertTrue(result.isWaitingUser());
        assertEquals(WorkflowExecutionStatus.SUSPENDED, result.executionStatus());
        assertTrue(result.interactionId() != null && result.interactionId().startsWith("wfi_"));
        @SuppressWarnings("unchecked")
        Map<String, Object> ui = (Map<String, Object>) result.uiRequest();
        assertEquals("form", ui.get("component"));
        assertEquals("COLLECT_INPUT", ui.get("type"));
        assertEquals("form", ui.get("nodeId"));
        assertEquals(result.interactionId(), ui.get("interactionId"));
    }

    @Test
    void interactionRejectsMismatchedResumeNode() {
        RuntimeGraphSpecExecutionResult waiting = executor.execute("""
                {"entryNodeId":"a","exitNodeIds":["b"],"nodes":[
                  {"id":"a","type":"INTERACTION","config":{"interactionType":"COLLECT_INPUT","fields":[{"key":"x","required":true}]}},
                  {"id":"b","type":"INTERACTION","config":{"interactionType":"COLLECT_INPUT","fields":[{"key":"y","required":true}]}}
                ],"edges":[{"from":"a","to":"b"}]}
                """, Map.of());
        assertTrue(waiting.isWaitingUser());

        RuntimeGraphSpecExecutionResult resumed = executor.executeFromNode("""
                {"entryNodeId":"a","exitNodeIds":["b"],"nodes":[
                  {"id":"a","type":"INTERACTION","config":{"interactionType":"COLLECT_INPUT","fields":[{"key":"x","required":true}]}},
                  {"id":"b","type":"INTERACTION","config":{"interactionType":"COLLECT_INPUT","fields":[{"key":"y","required":true}]}}
                ],"edges":[{"from":"a","to":"b"}]}
                """, Map.of(
                "__pendingInteractionId", waiting.interactionId(),
                "__pendingInteractionNodeId", "a",
                "__interactionResume", Map.of(
                        "interactionId", waiting.interactionId(),
                        "nodeId", "b",
                        "action", "submit",
                        "values", Map.of("x", "1"))), "a");
        assertTrue(resumed.isWaitingUser());
        assertEquals(waiting.interactionId(), resumed.interactionId());
    }

    @Test
    void collectInputRequiredValidationKeepsWaiting() {
        RuntimeGraphSpecExecutionResult waiting = executor.execute("""
                {"entryNodeId":"form","exitNodeIds":["form"],"nodes":[{"id":"form","type":"INTERACTION","config":{
                  "interactionType":"COLLECT_INPUT",
                  "fields":[{"key":"q","required":true,"type":"string"}]
                }}]}
                """, Map.of());
        RuntimeGraphSpecExecutionResult resumed = executor.executeFromNode("""
                {"entryNodeId":"form","exitNodeIds":["form"],"nodes":[{"id":"form","type":"INTERACTION","config":{
                  "interactionType":"COLLECT_INPUT",
                  "fields":[{"key":"q","required":true,"type":"string"}]
                }}]}
                """, Map.of(
                "__pendingInteractionId", waiting.interactionId(),
                "__pendingInteractionNodeId", "form",
                "__interactionResume", Map.of(
                        "interactionId", waiting.interactionId(),
                        "nodeId", "form",
                        "action", "submit",
                        "values", Map.of())), "form");
        assertTrue(resumed.isWaitingUser());
        assertEquals(waiting.interactionId(), resumed.interactionId());
    }

    @Test
    void userChoiceRejectsUndeclaredOption() {
        String graph = """
                {"entryNodeId":"choice","exitNodeIds":["choice"],"nodes":[{"id":"choice","type":"INTERACTION","config":{
                  "interactionType":"USER_CHOICE",
                  "options":[{"value":"a","label":"A"},{"value":"b","label":"B"}]
                }}]}
                """;
        RuntimeGraphSpecExecutionResult waiting = executor.execute(graph, Map.of());
        RuntimeGraphSpecExecutionResult resumed = executor.executeFromNode(graph, Map.of(
                "__pendingInteractionId", waiting.interactionId(),
                "__pendingInteractionNodeId", "choice",
                "__interactionResume", Map.of(
                        "interactionId", waiting.interactionId(),
                        "nodeId", "choice",
                        "action", "submit",
                        "values", Map.of("selected", "z"))), "choice");
        assertTrue(resumed.isWaitingUser());
    }

    @Test
    void confirmActionRoutesAndRejectDoesNotFollowAlwaysEdge() {
        String graph = """
                {
                  "entryNodeId":"confirm",
                  "exitNodeIds":["done"],
                  "nodes":[
                    {"id":"confirm","type":"INTERACTION","config":{"interactionType":"CONFIRM_ACTION"}},
                    {"id":"write","type":"TOOL","ref":{"qualifiedName":"system.echo"},"config":{}},
                    {"id":"done","type":"ANSWER","config":{"template":"ok"}}
                  ],
                  "edges":[
                    {"from":"confirm","to":"write","condition":"always"},
                    {"from":"write","to":"done"}
                  ]
                }
                """;
        RuntimeGraphSpecExecutionResult waiting = executor.execute(graph, Map.of());
        RuntimeGraphSpecExecutionResult rejected = executor.executeFromNode(graph, Map.of(
                "__pendingInteractionId", waiting.interactionId(),
                "__pendingInteractionNodeId", "confirm",
                "__interactionResume", Map.of(
                        "interactionId", waiting.interactionId(),
                        "nodeId", "confirm",
                        "action", "reject",
                        "values", Map.of())), "confirm");
        assertEquals(true, rejected.success());
        assertEquals(0, capabilityClient.requests.size());
    }

    @Test
    void presentOutputIsNonBlocking() {
        RuntimeGraphSpecExecutionResult result = executor.execute("""
                {
                  "entryNodeId":"show",
                  "exitNodeIds":["answer"],
                  "nodes":[
                    {"id":"show","type":"INTERACTION","config":{
                      "interactionType":"PRESENT_OUTPUT",
                      "component":"table",
                      "dataExpression":"lastOutput"
                    }},
                    {"id":"answer","type":"ANSWER","config":{"template":"done:{{ lastOutput }}"}}
                  ],
                  "edges":[{"from":"show","to":"answer"}]
                }
                """, Map.of("lastOutput", List.of(Map.of("id", 1))));
        assertEquals(true, result.success());
        assertTrue(result.answer().contains("done:"));
        assertTrue(result.uiRequest() instanceof Map<?, ?>);
        @SuppressWarnings("unchecked")
        Map<String, Object> behavior = (Map<String, Object>) ((Map<?, ?>) result.uiRequest()).get("behavior");
        assertEquals(false, behavior.get("blocking"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> traces = (List<Map<String, Object>>) result.metadata()
                .get("workflowNodeTraces");
        assertEquals(
                "PRESENT_OUTPUT",
                traces.get(0).get("interactionType"));
    }

    @Test
    void sequentialInteractionsDoNotReuseStalePayload() {
        String graph = """
                {
                  "entryNodeId":"a",
                  "exitNodeIds":["answer"],
                  "nodes":[
                    {"id":"a","type":"INTERACTION","config":{"interactionType":"COLLECT_INPUT","fields":[{"key":"x","required":true}]}},
                    {"id":"b","type":"INTERACTION","config":{"interactionType":"COLLECT_INPUT","fields":[{"key":"y","required":true}]}},
                    {"id":"answer","type":"ANSWER","config":{"template":"{{ x }}:{{ y }}"}}
                  ],
                  "edges":[{"from":"a","to":"b"},{"from":"b","to":"answer"}]
                }
                """;
        RuntimeGraphSpecExecutionResult firstWait = executor.execute(graph, Map.of());
        assertTrue(firstWait.isWaitingUser());
        RuntimeGraphSpecExecutionResult secondWait = executor.executeFromNode(graph, Map.of(
                "__pendingInteractionId", firstWait.interactionId(),
                "__pendingInteractionNodeId", "a",
                "__interactionResume", Map.of(
                        "interactionId", firstWait.interactionId(),
                        "nodeId", "a",
                        "action", "submit",
                        "values", Map.of("x", "1"))), "a");
        assertTrue(secondWait.isWaitingUser());
        assertEquals("b", secondWait.nodeId());
        assertTrue(!firstWait.interactionId().equals(secondWait.interactionId()));

        // stale global submittedPayload must not auto-complete second interaction
        RuntimeGraphSpecExecutionResult stillWaiting = executor.executeFromNode(graph, Map.of(
                "__pendingInteractionId", secondWait.interactionId(),
                "__pendingInteractionNodeId", "b",
                "submittedPayload", Map.of("x", "1"),
                "x", "1"), "b");
        assertTrue(stillWaiting.isWaitingUser());
        assertEquals(secondWait.interactionId(), stillWaiting.interactionId());
    }

    @Test
    void resumeDoesNotReExecutePriorToolSideEffect() {
        String graph = """
                {
                  "entryNodeId":"tool",
                  "exitNodeIds":["answer"],
                  "nodes":[
                    {"id":"tool","type":"TOOL","ref":{"qualifiedName":"system.echo"},"config":{"inputMapping":{"message":"hi"}}},
                    {"id":"form","type":"INTERACTION","config":{"interactionType":"COLLECT_INPUT","fields":[{"key":"q","required":true}]}},
                    {"id":"answer","type":"ANSWER","config":{"template":"tool={{ lastOutput }} q={{ q }}"}}
                  ],
                  "edges":[{"from":"tool","to":"form"},{"from":"form","to":"answer"}]
                }
                """;
        RuntimeGraphSpecExecutionResult waiting = executor.execute(graph, Map.of());
        assertTrue(waiting.isWaitingUser());
        assertEquals(1, capabilityClient.requests.size());
        assertFalse(waiting.resumeCheckpoint().isEmpty(), "pause must return live executor resumeCheckpoint");
        assertTrue(waiting.resumeCheckpoint().containsKey("nodeOutput")
                        || waiting.resumeCheckpoint().containsKey("lastOutput"),
                "snapshot must keep TOOL output: " + waiting.resumeCheckpoint().keySet());

        Map<String, Object> resumeContext = new java.util.LinkedHashMap<>(waiting.resumeCheckpoint());
        resumeContext.put("__pendingInteractionId", waiting.interactionId());
        resumeContext.put("__pendingInteractionNodeId", "form");
        resumeContext.put("__interactionResume", Map.of(
                "interactionId", waiting.interactionId(),
                "nodeId", "form",
                "action", "submit",
                "values", Map.of("q", "ok")));
        RuntimeGraphSpecExecutionResult done = executor.executeFromNode(graph, resumeContext, "form");
        assertEquals(true, done.success());
        assertTrue(String.valueOf(done.answer()).contains("q=ok"));
        assertEquals(1, capabilityClient.requests.size(), "TOOL must not re-run after resume");
    }

    @Test
    void waitingResumeCheckpointKeepsAccumulatedStateAcrossTwoInteractions() {
        String graph = """
                {
                  "entryNodeId":"a",
                  "exitNodeIds":["final"],
                  "nodes":[
                    {"id":"a","type":"INTERACTION","config":{"interactionType":"COLLECT_INPUT","fields":[{"key":"x","required":true}]}},
                    {"id":"mid","type":"PARAMETER_EXTRACT","config":{
                      "extractMode":"expression",
                      "fields":[{"name":"x","source":"lastOutput.x","required":true}]
                    }},
                    {"id":"b","type":"INTERACTION","config":{"interactionType":"COLLECT_INPUT","fields":[{"key":"y","required":true}]}},
                    {"id":"final","type":"ANSWER","config":{"template":"{{ x }}:{{ y }}"}}
                  ],
                  "edges":[
                    {"from":"a","to":"mid"},
                    {"from":"mid","to":"b"},
                    {"from":"b","to":"final"}
                  ]
                }
                """;
        RuntimeGraphSpecExecutionResult first = executor.execute(graph, Map.of("seed", 1));
        assertTrue(first.isWaitingUser());
        assertTrue(first.resumeCheckpoint().containsKey("seed"));

        Map<String, Object> afterA = new java.util.LinkedHashMap<>(first.resumeCheckpoint());
        afterA.put("__interactionResume", Map.of(
                "interactionId", first.interactionId(),
                "nodeId", "a",
                "action", "submit",
                "values", Map.of("x", "1")));
        RuntimeGraphSpecExecutionResult second = executor.executeFromNode(graph, afterA, "a");
        assertTrue(second.isWaitingUser());
        assertEquals("b", second.nodeId());
        assertTrue(second.resumeCheckpoint().containsKey("x")
                        || String.valueOf(second.resumeCheckpoint().get("lastOutput")).contains("1"),
                "second wait must accumulate first interaction output");

        Map<String, Object> afterB = new java.util.LinkedHashMap<>(second.resumeCheckpoint());
        afterB.put("__interactionResume", Map.of(
                "interactionId", second.interactionId(),
                "nodeId", "b",
                "action", "submit",
                "values", Map.of("y", "2")));
        RuntimeGraphSpecExecutionResult done = executor.executeFromNode(graph, afterB, "b");
        assertEquals(true, done.success());
        assertEquals("1:2", done.answer());
    }

    @Test
    void preservesStructuredToolInputTypes() {
        RuntimeGraphSpecExecutionResult result = executor.execute("""
                {
                  "entryNodeId":"team",
                  "exitNodeIds":["team"],
                  "nodes":[{
                    "id":"team",
                    "type":"TOOL",
                    "ref":{"qualifiedName":"qmssmp:teamArchivePage"},
                    "config":{"inputMapping":{"query":{"pageIndex":1,"pageSize":10,"enabled":true,"keyword":"{{ keyword }}"}}}
                  }]
                }
                """, Map.of("keyword", "一班"));

        assertEquals(true, result.success());
        assertEquals(Map.of(
                        "query", Map.of("pageIndex", 1, "pageSize", 10, "enabled", true, "keyword", "一班")),
                capabilityClient.requests.get(0).get("input"));
    }

    @Test
    void preservesLiteralToolArgumentsWhenVariableNamespaceExists() {
        RuntimeGraphSpecExecutionResult result = executor.execute("""
                {
                  "entryNodeId":"search",
                  "exitNodeIds":["search"],
                  "nodes":[{
                    "id":"search",
                    "type":"TOOL",
                    "ref":{"qualifiedName":"knowledge:business.search"},
                    "config":{"args":{
                      "query":"business memory canary team",
                      "scope":"businessScope"
                    }}
                  }]
                }
                """, Map.of("var", Map.of("businessScope", "authorized")));

        assertTrue(result.success());
        assertEquals(Map.of(
                        "query", "business memory canary team",
                        "scope", "authorized"),
                capabilityClient.requests.get(0).get("input"));
    }

    @Test
    void executesLinearUserInputLlmAnswerGraph() {
        RuntimeGraphSpecExecutionResult result = executor.execute("""
                {
                  "entryNodeId":"user_input",
                  "exitNodeIds":["answer"],
                  "nodes":[
                    {"id":"user_input","type":"USER_INPUT"},
                    {"id":"llm","type":"LLM","config":{"modelInstanceId":"model-1","userPrompt":"{{ input }}"}},
                    {"id":"answer","type":"ANSWER","config":{"template":"最终：{{ lastOutput }}"}}
                  ],
                  "edges":[
                    {"from":"user_input","to":"llm"},
                    {"from":"llm","to":"answer"}
                  ]
                }
                """, Map.of("message", "查订单"));

        assertEquals(true, result.success());
        assertEquals("最终：model answer", result.answer());
        assertEquals(List.of(
                Map.of("name", "execute-node", "detail", "user_input"),
                Map.of("name", "execute-node", "detail", "llm"),
                Map.of("name", "execute-node", "detail", "answer")
        ), result.steps());
        assertEquals(1, modelClient.requests.size());
        assertMessage(modelClient.requests.get(0).getMessages().get(0), "user", "查订单");
    }

    @Test
    void executesLinearUserInputToolAnswerGraphThroughCapabilityService() {
        capabilityClient.nextResult = Map.of("success", true, "data", Map.of("orderStatus", "PAID"));

        RuntimeGraphSpecExecutionResult result = executor.execute("""
                {
                  "entryNodeId":"user_input",
                  "exitNodeIds":["answer"],
                  "nodes":[
                    {"id":"user_input","type":"USER_INPUT"},
                    {"id":"query_order","type":"TOOL","ref":{"qualifiedName":"orders:queryOrder"},"config":{"inputMapping":{"orderNo":"{{ input }}"}}},
                    {"id":"answer","type":"ANSWER","config":{"template":"工具结果：{{ lastOutput }}"}}
                  ],
                  "edges":[
                    {"from":"user_input","to":"query_order"},
                    {"from":"query_order","to":"answer"}
                  ]
                }
                """, Map.of("message", "A001"));

        assertEquals(true, result.success());
        assertEquals("工具结果：{orderStatus=PAID}", result.answer());
        assertEquals(List.of(
                Map.of("name", "execute-node", "detail", "user_input"),
                Map.of("name", "execute-node", "detail", "query_order"),
                Map.of("name", "execute-node", "detail", "answer")
        ), result.steps());
        assertEquals("orders:queryOrder", capabilityClient.qualifiedNames.get(0));
        assertEquals(Map.of("orderNo", "A001"), ((Map<?, ?>) capabilityClient.requests.get(0).get("input")));
    }

    @Test
    void stopsToolWorkflowWhenCapabilityReportsBusinessFailure() {
        capabilityClient.nextResult = Map.of(
                "success", false,
                "code", "CAPABILITY_BUSINESS_RESPONSE_FAILED",
                "businessCode", "500",
                "message", "查询失败：无权查看班组",
                "data", Map.of("code", "500", "success", false, "message", "无权查看班组"));

        RuntimeGraphSpecExecutionResult result = executor.execute("""
                {
                  "entryNodeId":"query",
                  "exitNodeIds":["answer"],
                  "nodes":[
                    {"id":"query","type":"TOOL","ref":{"qualifiedName":"qmssmp:team.search"}},
                    {"id":"answer","type":"ANSWER","config":{"template":"不应执行"}}
                  ],
                  "edges":[{"from":"query","to":"answer"}]
                }
                """, Map.of());

        assertFalse(result.success());
        assertEquals("RUNTIME_GRAPH_TOOL_BUSINESS_RESPONSE_FAILED", result.code());
        assertEquals("查询失败：无权查看班组", result.answer());
        assertEquals("500", result.metadata().get("businessCode"));
        assertEquals(1, capabilityClient.requests.size());
    }

    @Test
    void rejectsBusinessFailurePayloadFromOlderCapabilityService() {
        capabilityClient.nextResult = Map.of(
                "success", true,
                "data", Map.of("code", 403, "success", false, "message", "角色无权限"));

        RuntimeGraphSpecExecutionResult result = executor.execute("""
                {"entryNodeId":"query","exitNodeIds":["query"],"nodes":[
                  {"id":"query","type":"CAPABILITY","ref":{"qualifiedName":"qmssmp:team.search"}}
                ]}
                """, Map.of());

        assertFalse(result.success());
        assertEquals("RUNTIME_GRAPH_TOOL_BUSINESS_RESPONSE_FAILED", result.code());
        assertEquals("查询失败：角色无权限", result.answer());
        assertEquals("403", result.metadata().get("businessCode"));
    }

    @Test
    void preservesToolMapOutputForConditionAndParameterExtraction() {
        capabilityClient.nextResult = Map.of(
                "success", true,
                "data", Map.of(
                        "status", "PAID",
                        "orderNo", "A001",
                        "detail", Map.of("owner", "alice")));

        RuntimeGraphSpecExecutionResult result = executor.execute("""
                {
                  "entryNodeId":"lookup",
                  "exitNodeIds":["answer","unpaid"],
                  "nodes":[
                    {"id":"lookup","type":"TOOL","ref":{"qualifiedName":"orders:query"}},
                    {"id":"judge","type":"IF_ELSE","config":{
                      "conditionGroups":[{"id":"paid","conditions":[
                        {"left":"lastOutput.status","operator":"equals","right":"PAID"}
                      ]}],
                      "defaultRoute":"unpaid"
                    }},
                    {"id":"extract","type":"PARAMETER_EXTRACT","config":{
                      "extractMode":"expression",
                      "fields":[
                        {"name":"orderNo","source":"nodeOutput.lookup.orderNo","required":true},
                        {"name":"owner","source":"nodeOutput.lookup.detail.owner","required":true}
                      ]
                    }},
                    {"id":"answer","type":"ANSWER","config":{
                      "template":"{{ nodeOutput.extract.orderNo }}:{{ nodeOutput.extract.owner }}"
                    }},
                    {"id":"unpaid","type":"ANSWER","config":{"template":"unpaid"}}
                  ],
                  "edges":[
                    {"from":"lookup","to":"judge"},
                    {"from":"judge","to":"extract","condition":"paid"},
                    {"from":"judge","to":"unpaid","condition":"unpaid"},
                    {"from":"extract","to":"answer"}
                  ]
                }
                """, Map.of("message", "query order"));

        assertEquals(true, result.success());
        assertEquals("A001:alice", result.answer());
        assertEquals("paid", result.steps().get(1).get("route"));
    }

    @Test
    void keepsStringToolOutputCompatibleAndResolvesNestedToolConfiguration() {
        capabilityClient.nextResult = Map.of("success", true, "data", "plain result");

        RuntimeGraphSpecExecutionResult result = executor.execute("""
                {
                  "entryNodeId":"tool",
                  "exitNodeIds":["answer"],
                  "nodes":[
                    {"id":"tool","type":"CAPABILITY","config":{
                      "capabilityConfig":{"ref":{"qualifiedName":"orders:plain"}}
                    }},
                    {"id":"answer","type":"ANSWER","config":{"template":"{{ lastOutput }}"}}
                  ],
                  "edges":[{"from":"tool","to":"answer"}]
                }
                """, Map.of());

        assertEquals(true, result.success());
        assertEquals("plain result", result.answer());
        assertEquals("orders:plain", capabilityClient.qualifiedNames.get(0));
    }

    @Test
    void keywordClassifierUsesInputExpressionAndRoutesToMatchedBranchWithoutModel() {
        RuntimeGraphSpecExecutionResult result = executor.execute("""
                {
                  "entryNodeId":"classifier",
                  "exitNodeIds":["search_answer","reset_answer","fallback_answer"],
                  "nodes":[
                    {"id":"classifier","type":"INTENT_CLASSIFIER","config":{
                      "strategy":"KEYWORD",
                      "inputExpression":"params.question",
                      "classes":[
                        {"id":"search","label":"查询","keywords":["查订单","查询"]},
                        {"id":"reset","label":"重置","keywords":["重置"]}
                      ],
                      "defaultRoute":"else"
                    }},
                    {"id":"search_answer","type":"ANSWER","config":{"template":"search"}},
                    {"id":"reset_answer","type":"ANSWER","config":{"template":"reset"}},
                    {"id":"fallback_answer","type":"ANSWER","config":{"template":"fallback"}}
                  ],
                  "edges":[
                    {"from":"classifier","to":"fallback_answer","condition":"always","priority":0},
                    {"from":"classifier","to":"search_answer","condition":"route:search","priority":100},
                    {"from":"classifier","to":"reset_answer","condition":"route:reset"},
                    {"from":"classifier","to":"fallback_answer","condition":"route:else"}
                  ]
                }
                """, Map.of("params", Map.of("question", "请帮我查订单 A001")));

        assertEquals(true, result.success());
        assertEquals("search", result.answer());
        assertEquals("search", result.steps().get(0).get("route"));
        assertEquals(0, modelClient.requests.size());
    }

    @Test
    void keywordClassifierUsesDefaultRouteWhenNoKeywordMatches() {
        RuntimeGraphSpecExecutionResult result = executor.execute("""
                {
                  "entryNodeId":"classifier",
                  "exitNodeIds":["search_answer","fallback_answer"],
                  "nodes":[
                    {"id":"classifier","type":"INTENT_CLASSIFIER","config":{
                      "strategy":"KEYWORD",
                      "classes":[{"id":"search","label":"查询","keywords":["查询"]}],
                      "defaultRoute":"else"
                    }},
                    {"id":"search_answer","type":"ANSWER","config":{"template":"search"}},
                    {"id":"fallback_answer","type":"ANSWER","config":{"template":"fallback"}}
                  ],
                  "edges":[
                    {"from":"classifier","to":"search_answer","condition":"route:search"},
                    {"from":"classifier","to":"fallback_answer","condition":"route:else"}
                  ]
                }
                """, Map.of("message", "你好"));

        assertEquals(true, result.success());
        assertEquals("fallback", result.answer());
        assertEquals("else", result.steps().get(0).get("route"));
    }

    @Test
    void hybridClassifierDefersAnEquallySpecificCrossIntentKeywordTieToModel() {
        CapturingModelClient classifierModel = new CapturingModelClient(
                "{\"route\":\"page_state\",\"confidence\":0.96}");
        RuntimeGraphSpecExecutor classifierExecutor = new RuntimeGraphSpecExecutor(
                new ObjectMapper(), classifierModel, capabilityClient, mock(RuntimeControlCatalogClient.class));

        RuntimeGraphSpecExecutionResult result = classifierExecutor.execute("""
                {
                  "entryNodeId":"classifier",
                  "exitNodeIds":["search_answer","state_answer"],
                  "nodes":[
                    {"id":"classifier","type":"INTENT_CLASSIFIER","config":{
                      "strategy":"HYBRID",
                      "modelInstanceId":"model-1",
                      "classes":[
                        {"id":"search","keywords":["班组名称"]},
                        {"id":"page_state","keywords":["页面状态"]}
                      ],
                      "defaultRoute":"search",
                      "confidenceThreshold":0.7
                    }},
                    {"id":"search_answer","type":"ANSWER","config":{"template":"search"}},
                    {"id":"state_answer","type":"ANSWER","config":{"template":"state"}}
                  ],
                  "edges":[
                    {"from":"classifier","to":"search_answer","condition":"route:search"},
                    {"from":"classifier","to":"state_answer","condition":"route:page_state"}
                  ]
                }
                """, Map.of("message", "读取页面状态和班组名称筛选值"));

        assertEquals(true, result.success());
        assertEquals("state", result.answer());
        assertEquals("page_state", result.steps().get(0).get("route"));
        assertEquals(1, classifierModel.requests.size());
    }

    @Test
    void llmClassifierParsesStructuredDecisionAndRoutesToMatchedBranch() {
        CapturingModelClient classifierModel = new CapturingModelClient("{\"route\":\"reset\",\"confidence\":0.92}");
        RuntimeGraphSpecExecutor classifierExecutor = new RuntimeGraphSpecExecutor(
                new ObjectMapper(), classifierModel, capabilityClient, mock(RuntimeControlCatalogClient.class));

        RuntimeGraphSpecExecutionResult result = classifierExecutor.execute("""
                {
                  "entryNodeId":"classifier",
                  "exitNodeIds":["reset_answer","fallback_answer"],
                  "nodes":[
                    {"id":"classifier","type":"INTENT_CLASSIFIER","config":{
                      "strategy":"LLM",
                      "modelInstanceId":"model-1",
                      "classes":[
                        {"id":"search","label":"查询","keywords":[]},
                        {"id":"reset","label":"重置","keywords":[]}
                      ],
                      "defaultRoute":"else",
                      "confidenceThreshold":0.7
                    }},
                    {"id":"reset_answer","type":"ANSWER","config":{"template":"reset"}},
                    {"id":"fallback_answer","type":"ANSWER","config":{"template":"fallback"}}
                  ],
                  "edges":[
                    {"from":"classifier","to":"reset_answer","condition":"route:reset"},
                    {"from":"classifier","to":"fallback_answer","condition":"route:else"}
                  ]
                }
                """, Map.of("message", "清空条件"));

        assertEquals(true, result.success());
        assertEquals("reset", result.answer());
        assertEquals("reset", result.steps().get(0).get("route"));
        assertEquals("model-1", classifierModel.requests.get(0).getModelInstanceId());
    }

    @Test
    void intentClassifierRouteDoesNotReplaceDownstreamDefaultInput() {
        RuntimeGraphSpecExecutionResult result = executor.execute("""
                {
                  "entryNodeId":"classifier",
                  "exitNodeIds":["tool"],
                  "nodes":[
                    {"id":"classifier","type":"INTENT_CLASSIFIER","config":{
                      "strategy":"KEYWORD",
                      "classes":[{"id":"search","keywords":["查询"]}],
                      "defaultRoute":"else"
                    }},
                    {"id":"tool","type":"TOOL","ref":{"qualifiedName":"orders:queryOrder"}}
                  ],
                  "edges":[{"from":"classifier","to":"tool","condition":"route:search"}]
                }
                """, Map.of("message", "查询订单 A001"));

        assertEquals(true, result.success());
        assertEquals(Map.of("input", "查询订单 A001"), capabilityClient.requests.get(0).get("input"));
    }

    @Test
    void conditionNodeEvaluatesGroupsRoutesAndPreservesPreviousOutput() {
        RuntimeGraphSpecExecutionResult result = executor.execute("""
                {
                  "entryNodeId":"input",
                  "exitNodeIds":["answer","reject"],
                  "nodes":[
                    {"id":"input","type":"USER_INPUT"},
                    {"id":"judge","type":"IF_ELSE","config":{
                      "conditionGroups":[{
                        "id":"metro",
                        "logic":"AND",
                        "conditions":[
                          {"left":"input","operator":"contains","right":"地铁"},
                          {"left":"params.city","operator":"equals","right":"广州"}
                        ]
                      }],
                      "defaultRoute":"reject"
                    }},
                    {"id":"answer","type":"ANSWER","config":{"template":"通过：{{ lastOutput }}"}},
                    {"id":"reject","type":"ANSWER","config":{"template":"拒绝"}}
                  ],
                  "edges":[
                    {"from":"input","to":"judge"},
                    {"from":"judge","to":"answer","condition":"metro"},
                    {"from":"judge","to":"reject","condition":"reject"}
                  ]
                }
                """, Map.of("message", "广州地铁末班车", "params", Map.of("city", "广州")));

        assertEquals(true, result.success());
        assertEquals("通过：广州地铁末班车", result.answer());
        assertEquals("metro", result.steps().get(1).get("route"));
    }

    @Test
    void conditionRouteWithoutMatchingEdgeFailsClearly() {
        RuntimeGraphSpecExecutionResult result = executor.execute("""
                {
                  "entryNodeId":"judge",
                  "exitNodeIds":["other"],
                  "nodes":[
                    {"id":"judge","type":"IF_ELSE","config":{
                      "conditionGroups":[{"id":"paid","conditions":[
                        {"left":"params.status","operator":"equals","right":"PAID"}
                      ]}],
                      "defaultRoute":"else"
                    }},
                    {"id":"other","type":"ANSWER","config":{"template":"other"}}
                  ],
                  "edges":[{"from":"judge","to":"other","condition":"other"}]
                }
                """, Map.of("params", Map.of("status", "PAID")));

        assertEquals(false, result.success());
        assertEquals("RUNTIME_GRAPH_ROUTE_UNRESOLVED", result.code());
        assertEquals("judge", result.nodeId());
        assertTrue(result.answer().contains("paid"));
    }

    @Test
    void conditionExitNodeCompletesSuccessfully() {
        RuntimeGraphSpecExecutionResult result = executor.execute("""
                {
                  "entryNodeId":"judge",
                  "exitNodeIds":["judge"],
                  "nodes":[{"id":"judge","type":"IF_ELSE","config":{
                    "conditionGroups":[{"id":"paid","conditions":[
                      {"left":"params.status","operator":"equals","right":"PAID"}
                    ]}],
                    "defaultRoute":"else"
                  }}]
                }
                """, Map.of("params", Map.of("status", "PAID")));

        assertEquals(true, result.success());
        assertEquals("paid", result.answer());
        assertEquals("IF_ELSE", result.nodeType());
        assertEquals(1, result.steps().size());
    }

    @Test
    void classifierRouteWithoutMatchingEdgeUsesSameFailureContract() {
        RuntimeGraphSpecExecutionResult result = executor.execute("""
                {
                  "entryNodeId":"classifier",
                  "exitNodeIds":["fallback"],
                  "nodes":[
                    {"id":"classifier","type":"INTENT_CLASSIFIER","config":{
                      "strategy":"KEYWORD",
                      "classes":[{"id":"search","keywords":["query"]}],
                      "defaultRoute":"else"
                    }},
                    {"id":"fallback","type":"ANSWER","config":{"template":"fallback"}}
                  ],
                  "edges":[{"from":"classifier","to":"fallback","condition":"route:else"}]
                }
                """, Map.of("message", "query orders"));

        assertEquals(false, result.success());
        assertEquals("RUNTIME_GRAPH_ROUTE_UNRESOLVED", result.code());
        assertEquals("classifier", result.nodeId());
        assertTrue(result.answer().contains("search"));
    }

    @Test
    void expressionParameterExtractPublishesTypedNodeOutputForToolMapping() {
        RuntimeGraphSpecExecutionResult result = executor.execute("""
                {
                  "entryNodeId":"extract",
                  "exitNodeIds":["tool"],
                  "nodes":[
                    {"id":"extract","type":"PARAMETER_EXTRACT","config":{
                      "extractMode":"expression",
                      "fields":[
                        {"name":"owner","type":"string","required":true,"source":"params.owner"},
                        {"name":"pageSize","type":"integer","source":"params.size"}
                      ]
                    }},
                    {"id":"tool","type":"TOOL","ref":{"qualifiedName":"orders:query"},"config":{
                      "inputMapping":{
                        "owner":"nodeOutput.extract.owner",
                        "pageSize":"nodeOutput.extract.pageSize"
                      }
                    }}
                  ],
                  "edges":[{"from":"extract","to":"tool"}]
                }
                """, Map.of("params", Map.of("owner", "张三", "size", "10")));

        assertEquals(true, result.success());
        assertEquals(Map.of("owner", "张三", "pageSize", 10), capabilityClient.requests.get(0).get("input"));
    }

    @Test
    void llmParameterExtractParsesJsonAndExposesFieldsToAnswerTemplate() {
        CapturingModelClient extractModel = new CapturingModelClient("{\"owner\":\"李四\",\"pageSize\":20}");
        RuntimeGraphSpecExecutor extractExecutor = new RuntimeGraphSpecExecutor(
                new ObjectMapper(), extractModel, capabilityClient, mock(RuntimeControlCatalogClient.class));

        RuntimeGraphSpecExecutionResult result = extractExecutor.execute("""
                {
                  "entryNodeId":"extract",
                  "exitNodeIds":["answer"],
                  "nodes":[
                    {"id":"extract","type":"PARAMETER_EXTRACT","config":{
                      "extractMode":"llm",
                      "fields":[
                        {"name":"owner","type":"string","required":true},
                        {"name":"pageSize","type":"integer"}
                      ]
                    }},
                    {"id":"answer","type":"ANSWER","config":{
                      "template":"owner={{ nodeOutput.extract.owner }}, size={{ nodeOutput.extract.pageSize }}"
                    }}
                  ],
                  "edges":[{"from":"extract","to":"answer"}]
                }
                """, Map.of(
                "message", "负责人李四，每页20条",
                "workflowDefaultModelInstanceId", "published-model"));

        assertEquals(true, result.success());
        assertEquals("owner=李四, size=20", result.answer());
        assertEquals("published-model", extractModel.requests.get(0).getModelInstanceId());
    }

    @Test
    void parameterExtractUsesMessageWhenTransportAddsEmptyStructuredInput() {
        CapturingModelClient extractModel = new CapturingModelClient("{\"courseName\":\"Java\"}");
        RuntimeGraphSpecExecutor extractExecutor = new RuntimeGraphSpecExecutor(
                new ObjectMapper(), extractModel, capabilityClient,
                mock(RuntimeControlCatalogClient.class));

        RuntimeGraphSpecExecutionResult result = extractExecutor.execute("""
                {
                  "entryNodeId":"user_input",
                  "exitNodeIds":["answer"],
                  "nodes":[
                    {"id":"user_input","type":"USER_INPUT"},
                    {"id":"extract","type":"PARAMETER_EXTRACT","config":{
                      "extractMode":"llm",
                      "userPrompt":"{{ input }}",
                      "fields":[{"name":"courseName","type":"string","required":true}]
                    }},
                    {"id":"answer","type":"ANSWER","config":{
                      "template":"{{ nodeOutput.extract.courseName }}"
                    }}
                  ],
                  "edges":[
                    {"from":"user_input","to":"extract"},
                    {"from":"extract","to":"answer"}
                  ]
                }
                """, Map.of(
                "input", Map.of(),
                "message", "Find the Java course",
                "workflowDefaultModelInstanceId", "published-model"));

        assertTrue(result.success());
        assertEquals("Java", result.answer());
        assertMessage(extractModel.requests.get(0).getMessages().get(1), "user", "Find the Java course");
    }

    @Test
    void reportsUnsupportedEntryNodeTypeWithoutFallingBackToLegacyAgent() {
        RuntimeGraphSpecExecutionResult result = executor.execute("""
                {"entryNodeId":"code","exitNodeIds":["code"],"nodes":[{"id":"code","type":"CODE","config":{"code":"input"}}]}
                """, Map.of("message", "hello"));

        assertEquals(false, result.success());
        assertEquals("RUNTIME_GRAPH_NODE_UNSUPPORTED", result.code());
        assertEquals("Runtime GraphSpec node type is not executable yet: CODE", result.answer());
        assertEquals("code", result.nodeId());
        assertEquals("CODE", result.nodeType());
    }

    @Test
    void stopsLinearExecutionWhenStepLimitIsExceeded() {
        RuntimeGraphSpecExecutionResult result = executor.execute("""
                {
                  "entryNodeId":"user_input",
                  "exitNodeIds":["done"],
                  "nodes":[
                    {"id":"user_input","type":"USER_INPUT"},
                    {"id":"done","type":"ANSWER","config":{"template":"done"}}
                  ],
                  "edges":[{"from":"user_input","to":"user_input"}]
                }
                """, Map.of("message", "hello"));

        assertEquals(false, result.success());
        assertEquals("RUNTIME_GRAPH_STEP_LIMIT_EXCEEDED", result.code());
    }

    private void assertMessage(ChatMessage message, String role, String content) {
        assertEquals(role, message.getRole());
        assertEquals(content, message.getContent());
    }

    private static final class CapturingModelClient implements RuntimeModelServiceClient {
        private final List<String> answers;
        private final List<ModelChatRequest> requests = new ArrayList<>();
        private int nextAnswer;

        private CapturingModelClient(String... answers) {
            this.answers = List.of(answers);
        }

        @Override
        public ModelChatResult chat(ModelChatRequest request) {
            requests.add(request);
            String answer = answers.get(Math.min(nextAnswer, answers.size() - 1));
            nextAnswer++;
            return new ModelChatResult(0, "ok",
                    new ModelChatData(answer, "gpt-test", "openai", null, null, null, "stop"));
        }
    }

    private static final class CapturingCapabilityClient implements RuntimeCapabilityCatalogClient {
        private final List<String> qualifiedNames = new ArrayList<>();
        private final List<Map<String, Object>> requests = new ArrayList<>();
        private Map<String, Object> nextResult = Map.of("success", true, "data", Map.of());

        @Override
        public Map<String, Object> getToolDefinition(String qualifiedName) {
            return Map.of("qualifiedName", qualifiedName);
        }

        @Override
        public Map<String, Object> executeTool(String qualifiedName, Map<String, Object> request) {
            qualifiedNames.add(qualifiedName);
            requests.add(request);
            return nextResult;
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

    @Test
    void emitsNodeEventsDuringExecutionWithTimingGaps() {
        List<String> events = new CopyOnWriteArrayList<>();
        List<Long> at = new CopyOnWriteArrayList<>();
        RuntimeGraphSpecExecutionEventSink sink = new RuntimeGraphSpecExecutionEventSink() {
            @Override
            public void onNodeStarted(String nodeId, String nodeType, String nodeName, Map<String, Object> safePayload) {
                events.add("started:" + nodeId);
                at.add(System.nanoTime());
            }

            @Override
            public void onNodeCompleted(String nodeId, String nodeType, String nodeName, Map<String, Object> safePayload) {
                events.add("completed:" + nodeId);
                at.add(System.nanoTime());
            }
        };
        RuntimeModelServiceClient slowModel = request -> {
            try {
                Thread.sleep(150);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return new ModelChatResult(200, "ok",
                    new ModelChatData("A", "m", "p", null, null, null, "stop"));
        };
        RuntimeGraphSpecExecutor timed = new RuntimeGraphSpecExecutor(
                new ObjectMapper(), slowModel, capabilityClient,
                org.mockito.Mockito.mock(RuntimeControlCatalogClient.class));

        RuntimeGraphSpecExecutionResult result = timed.execute("""
                {
                  "entryNodeId":"n1",
                  "exitNodeIds":["n2"],
                  "nodes":[
                    {"id":"n1","type":"LLM","name":"one","config":{"modelInstanceId":"m1","userPrompt":"{{input}}"}},
                    {"id":"n2","type":"ANSWER","name":"two","config":{"template":"{{lastOutput}}"}}
                  ],
                  "edges":[{"from":"n1","to":"n2"}]
                }
                """, Map.of("message", "q"), sink, RuntimeGraphSpecExecutionCancellation.none());

        assertTrue(result.success());
        assertEquals(List.of("started:n1", "completed:n1", "started:n2", "completed:n2"), events);
        assertTrue(at.get(1) - at.get(0) >= 100_000_000L, "node.completed should follow actual execution");
        assertTrue(at.get(2) > at.get(1), "second node.started must follow first node.completed");
    }

    @Test
    void cancelStopsSubsequentNodes() {
        List<String> events = new CopyOnWriteArrayList<>();
        RuntimeGraphSpecExecutionCancellation cancellation = new RuntimeGraphSpecExecutionCancellation();
        RuntimeGraphSpecExecutionEventSink sink = new RuntimeGraphSpecExecutionEventSink() {
            @Override
            public void onNodeStarted(String nodeId, String nodeType, String nodeName, Map<String, Object> safePayload) {
                events.add("started:" + nodeId);
                if ("n1".equals(nodeId)) {
                    cancellation.cancel();
                }
            }

            @Override
            public void onNodeCompleted(String nodeId, String nodeType, String nodeName, Map<String, Object> safePayload) {
                events.add("completed:" + nodeId);
            }

            @Override
            public void onExecutionCancelled(Map<String, Object> safePayload) {
                events.add("cancelled");
            }
        };

        RuntimeGraphSpecExecutionResult result = executor.execute("""
                {
                  "entryNodeId":"n1",
                  "exitNodeIds":["n2"],
                  "nodes":[
                    {"id":"n1","type":"USER_INPUT","name":"one"},
                    {"id":"n2","type":"ANSWER","name":"two","config":{"template":"done"}}
                  ],
                  "edges":[{"from":"n1","to":"n2"}]
                }
                """, Map.of("message", "q"), sink, cancellation);

        assertFalse(result.success());
        assertEquals("RUNTIME_GRAPH_CANCELLED", result.code());
        assertTrue(events.contains("started:n1"));
        assertTrue(events.contains("completed:n1") || events.contains("cancelled"));
        assertFalse(events.contains("started:n2"), "cancelled run must not start subsequent nodes");
    }

    @Test
    void cancelDuringLlmSyncChatDiscardsSuccessAndDoesNotEmitPublicDelta() {
        List<String> events = new CopyOnWriteArrayList<>();
        RuntimeGraphSpecExecutionCancellation cancellation = new RuntimeGraphSpecExecutionCancellation();
        RuntimeModelServiceClient cancellingModel = request -> {
            cancellation.cancel();
            return new ModelChatResult(0, "ok",
                    new ModelChatData("should-discard", "gpt-test", "openai", null, null, null, "stop"));
        };
        RuntimeGraphSpecExecutor cancellingExecutor = new RuntimeGraphSpecExecutor(
                new ObjectMapper(), cancellingModel, capabilityClient,
                org.mockito.Mockito.mock(RuntimeControlCatalogClient.class));
        RuntimeGraphSpecExecutionEventSink sink = new RuntimeGraphSpecExecutionEventSink() {
            @Override
            public void onNodeDelta(String nodeId, String nodeType, String text, Map<String, Object> safePayload) {
                events.add("delta:" + text);
            }

            @Override
            public void onNodeFailed(String nodeId, String nodeType, String nodeName, Map<String, Object> safePayload) {
                events.add("failed");
            }

            @Override
            public void onExecutionCancelled(Map<String, Object> safePayload) {
                events.add("cancelled");
            }
        };

        RuntimeGraphSpecExecutionResult result = cancellingExecutor.execute("""
                {
                  "entryNodeId":"llm",
                  "exitNodeIds":["llm"],
                  "nodes":[{
                    "id":"llm","type":"LLM",
                    "config":{"modelInstanceId":"m1","userPrompt":"{{input}}"}
                  }]
                }
                """, Map.of("message", "q"), sink, cancellation);

        assertEquals("RUNTIME_GRAPH_CANCELLED", result.code());
        assertTrue(events.contains("cancelled"));
        assertFalse(events.contains("failed"), "cancel must not map to node.failed");
        assertFalse(events.stream().anyMatch(e -> e.startsWith("delta:")),
                "cancelled LLM must not emit public delta");
    }

    @Test
    void realNodeExceptionStillFailsWhenNotCancelled() {
        RuntimeModelServiceClient exploding = request -> {
            throw new IllegalStateException("model down");
        };
        RuntimeGraphSpecExecutor failingExecutor = new RuntimeGraphSpecExecutor(
                new ObjectMapper(), exploding, capabilityClient,
                org.mockito.Mockito.mock(RuntimeControlCatalogClient.class));
        List<String> events = new CopyOnWriteArrayList<>();
        RuntimeGraphSpecExecutionEventSink sink = new RuntimeGraphSpecExecutionEventSink() {
            @Override
            public void onNodeFailed(String nodeId, String nodeType, String nodeName, Map<String, Object> safePayload) {
                events.add("failed:" + safePayload.get("code"));
            }

            @Override
            public void onExecutionCancelled(Map<String, Object> safePayload) {
                events.add("cancelled");
            }
        };

        RuntimeGraphSpecExecutionResult result = failingExecutor.execute("""
                {
                  "entryNodeId":"llm",
                  "exitNodeIds":["llm"],
                  "nodes":[{
                    "id":"llm","type":"LLM",
                    "config":{"modelInstanceId":"m1","userPrompt":"{{input}}"}
                  }]
                }
                """, Map.of("message", "q"), sink, RuntimeGraphSpecExecutionCancellation.none());

        assertEquals("RUNTIME_GRAPH_LLM_FAILED", result.code());
        assertTrue(events.stream().anyMatch(e -> e.startsWith("failed:")));
        assertFalse(events.contains("cancelled"));
    }

    @Test
    void internalClassifierLlmDoesNotEmitPublicNodeDelta() {
        List<String> deltas = new CopyOnWriteArrayList<>();
        RuntimeGraphSpecExecutionEventSink sink = new RuntimeGraphSpecExecutionEventSink() {
            @Override
            public void onNodeDelta(String nodeId, String nodeType, String text, Map<String, Object> safePayload) {
                deltas.add(nodeId + ":" + text);
            }
        };
        RuntimeGraphSpecExecutionResult result = executor.execute("""
                {
                  "entryNodeId":"cls",
                  "exitNodeIds":["ans"],
                  "nodes":[
                    {"id":"cls","type":"INTENT_CLASSIFIER","config":{
                      "strategy":"LLM","modelInstanceId":"m1",
                      "classes":[{"id":"a","label":"A"},{"id":"b","label":"B"}]
                    }},
                    {"id":"ans","type":"ANSWER","config":{"template":"route={{lastOutput}}"}}
                  ],
                  "edges":[{"from":"cls","to":"ans","condition":"a"},{"from":"cls","to":"ans","condition":"b"}]
                }
                """, Map.of("message", "hello"), sink, RuntimeGraphSpecExecutionCancellation.none());

        // INTENT_CLASSIFIER 不是 publicUserOutput LLM；不得公开内部 token/delta
        assertTrue(deltas.isEmpty(), "classifier must not emit public node delta, got " + deltas);
        assertTrue(result.success() || "RUNTIME_GRAPH_ROUTE_UNRESOLVED".equals(result.code())
                || result.code() != null);
    }
}
