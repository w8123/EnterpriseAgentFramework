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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                {"entry":"answer","nodes":[{"id":"answer","type":"ANSWER","config":{"template":"收到：{{ input }}"}}]}
                """, Map.of("message", "hello"));

        assertEquals(true, result.success());
        assertEquals("收到：hello", result.answer());
        assertEquals("RUNTIME_GRAPH_EXECUTED", result.code());
        assertEquals("answer", result.nodeId());
        assertEquals("ANSWER", result.nodeType());
    }

    @Test
    void executesLlmEntryNodeThroughModelGateway() {
        RuntimeGraphSpecExecutionResult result = executor.execute("""
                {
                  "entry":"llm",
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
                  "entry":"llm",
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
                  "entry":"llm",
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
                {"entry":"team","nodes":[{"id":"team","type":"PAGE_ACTION","config":{
                  "projectCode":"qmssmp","pageKey":"team.departments","route":"/team-build/depart-management",
                  "actionKey":"queryFirstTeam","args":{"keyword":"{{ teamName }}"}
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
        verify(controlClient).executePageBridge(any());
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
                  "entry":"open",
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
                  "entry":"form",
                  "nodes":[
                    {"id":"form","type":"INTERACTION"},
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
                "submittedPayload", Map.of(
                        "approved", true,
                        "user", Map.of("id", "u-1"))));

        assertEquals(true, result.success());
        assertEquals("true:u-1", result.answer());
    }

    @Test
    void preservesStructuredToolInputTypes() {
        RuntimeGraphSpecExecutionResult result = executor.execute("""
                {
                  "entry":"team",
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
    void executesLinearUserInputLlmAnswerGraph() {
        RuntimeGraphSpecExecutionResult result = executor.execute("""
                {
                  "entry":"user_input",
                  "nodes":[
                    {"id":"user_input","type":"USER_INPUT"},
                    {"id":"llm","type":"LLM","config":{"modelInstanceId":"model-1","userPrompt":"{{ input }}"}},
                    {"id":"answer","type":"ANSWER","config":{"template":"最终：{{ lastOutput }}"}}
                  ],
                  "edges":[
                    {"from":"user_input","to":"llm"},
                    {"from":"llm","to":"answer"},
                    {"from":"answer","to":"END"}
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
                  "entry":"user_input",
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
    void preservesToolMapOutputForConditionAndParameterExtraction() {
        capabilityClient.nextResult = Map.of(
                "success", true,
                "data", Map.of(
                        "status", "PAID",
                        "orderNo", "A001",
                        "detail", Map.of("owner", "alice")));

        RuntimeGraphSpecExecutionResult result = executor.execute("""
                {
                  "entry":"lookup",
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
                  "entry":"tool",
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
                  "entry":"classifier",
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
                  "entry":"classifier",
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
    void llmClassifierParsesStructuredDecisionAndRoutesToMatchedBranch() {
        CapturingModelClient classifierModel = new CapturingModelClient("{\"route\":\"reset\",\"confidence\":0.92}");
        RuntimeGraphSpecExecutor classifierExecutor = new RuntimeGraphSpecExecutor(
                new ObjectMapper(), classifierModel, capabilityClient, mock(RuntimeControlCatalogClient.class));

        RuntimeGraphSpecExecutionResult result = classifierExecutor.execute("""
                {
                  "entry":"classifier",
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
                  "entry":"classifier",
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
                  "entry":"input",
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
                  "entry":"judge",
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
    void conditionRouteToEndCompletesSuccessfully() {
        RuntimeGraphSpecExecutionResult result = executor.execute("""
                {
                  "entry":"judge",
                  "nodes":[{"id":"judge","type":"IF_ELSE","config":{
                    "conditionGroups":[{"id":"paid","conditions":[
                      {"left":"params.status","operator":"equals","right":"PAID"}
                    ]}],
                    "defaultRoute":"else"
                  }}],
                  "edges":[{"from":"judge","to":"END","condition":"route:paid"}]
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
                  "entry":"classifier",
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
                  "entry":"extract",
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
                  "entry":"extract",
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
    void reportsUnsupportedEntryNodeTypeWithoutFallingBackToLegacyAgent() {
        RuntimeGraphSpecExecutionResult result = executor.execute("""
                {"entry":"code","nodes":[{"id":"code","type":"CODE","config":{"code":"input"}}]}
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
                  "entry":"user_input",
                  "nodes":[{"id":"user_input","type":"USER_INPUT"}],
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

    }
}
