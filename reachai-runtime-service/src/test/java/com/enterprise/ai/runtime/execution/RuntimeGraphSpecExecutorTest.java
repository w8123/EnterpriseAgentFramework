package com.enterprise.ai.runtime.execution;

import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
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

class RuntimeGraphSpecExecutorTest {

    private final CapturingModelClient modelClient = new CapturingModelClient("model answer");
    private final CapturingCapabilityClient capabilityClient = new CapturingCapabilityClient();
    private final RuntimeGraphSpecExecutor executor =
            new RuntimeGraphSpecExecutor(new ObjectMapper(), modelClient, capabilityClient);

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

        @Override
        public List<Map<String, Object>> listRuntimeInstances() {
            return List.of();
        }
    }
}
