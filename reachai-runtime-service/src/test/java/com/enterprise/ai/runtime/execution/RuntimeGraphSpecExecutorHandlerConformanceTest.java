package com.enterprise.ai.runtime.execution;

import com.enterprise.ai.agent.graph.AgentGraphNodeType;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatData;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatRequest;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient.ModelChatResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proves RuntimeGraphSpecExecutor.handledNodeTypes() matches real executeNode() dispatch branches.
 */
class RuntimeGraphSpecExecutorHandlerConformanceTest {

    private static final String UNSUPPORTED = "RUNTIME_GRAPH_NODE_UNSUPPORTED";

    private final RuntimeModelServiceClient modelClient = mock(RuntimeModelServiceClient.class);
    private final RuntimeCapabilityCatalogClient capabilityClient = mock(RuntimeCapabilityCatalogClient.class);
    private final RuntimeControlCatalogClient controlClient = mock(RuntimeControlCatalogClient.class);
    private final RuntimeGraphSpecExecutor executor =
            new RuntimeGraphSpecExecutor(new ObjectMapper(), modelClient, capabilityClient, controlClient);

    @Test
    void everyHandledNodeTypeIsDispatchedByRealHandler() {
        when(modelClient.chat(any(ModelChatRequest.class))).thenReturn(new ModelChatResult(
                0, "ok", new ModelChatData("llm-answer", "gpt-test", "openai", null, null, null, "stop")));
        when(capabilityClient.executeTool(any(), any())).thenReturn(Map.of("data", "tool-ok"));

        Map<String, String> codes = new LinkedHashMap<>();
        for (String nodeType : new TreeSet<>(RuntimeGraphSpecExecutor.handledNodeTypes())) {
            RuntimeGraphSpecExecutionResult result = executor.execute(minimalGraph(nodeType), minimalContext(nodeType));
            codes.put(nodeType, result.code());
            assertNotEquals(UNSUPPORTED, result.code(),
                    () -> nodeType + " must not fall through to UNSUPPORTED, got " + result.code()
                            + " / " + result.answer());
        }

        assertEquals("RUNTIME_GRAPH_INTERACTION_WAITING", codes.get("INTERACTION"));
        assertEquals("RUNTIME_PAGE_ACTION_CONTEXT_REQUIRED", codes.get("PAGE_ACTION"));
        assertEquals("RUNTIME_KNOWLEDGE_CLIENT_UNAVAILABLE", codes.get("KNOWLEDGE_RETRIEVAL"));
        assertEquals("RUNTIME_HTTP_CLIENT_UNAVAILABLE", codes.get("HTTP_REQUEST"));
        assertNotEquals(UNSUPPORTED, codes.get("LLM"));
        assertNotEquals(UNSUPPORTED, codes.get("TOOL"));
        assertNotEquals(UNSUPPORTED, codes.get("VARIABLE_ASSIGN"));
        assertNotEquals(UNSUPPORTED, codes.get("TEMPLATE"));
        assertNotEquals(UNSUPPORTED, codes.get("VARIABLE_AGGREGATOR"));
        assertNotEquals(UNSUPPORTED, codes.get("LOOP"));
        assertEquals(15, codes.size());
    }

    @Test
    void everyUnhandledProtocolTypeReturnsUnsupported() {
        Set<String> handled = RuntimeGraphSpecExecutor.handledNodeTypes();
        for (AgentGraphNodeType type : AgentGraphNodeType.values()) {
            if (handled.contains(type.type())) {
                continue;
            }
            RuntimeGraphSpecExecutionResult result = executor.execute("""
                    {"entryNodeId":"n1","nodes":[{"id":"n1","type":"%s"}],"exitNodeIds":["n1"]}
                    """.formatted(type.type()), Map.of("message", "x"));
            assertFalse(result.success(), type.type());
            assertEquals(UNSUPPORTED, result.code(), type.type());
        }
    }

    @Test
    void handledSetEqualsKnownDispatchBranches() {
        assertEquals(Set.of(
                "USER_INPUT",
                "INTENT_CLASSIFIER",
                "IF_ELSE",
                "PARAMETER_EXTRACT",
                "ANSWER",
                "LLM",
                "TOOL",
                "PAGE_ACTION",
                "INTERACTION",
                "VARIABLE_ASSIGN",
                "TEMPLATE",
                "VARIABLE_AGGREGATOR",
                "KNOWLEDGE_RETRIEVAL",
                "HTTP_REQUEST",
                "LOOP"), RuntimeGraphSpecExecutor.handledNodeTypes());
        assertEquals(Arrays.stream(AgentGraphNodeType.values()).count(), 20);
    }

    private Map<String, Object> minimalContext(String nodeType) {
        return switch (nodeType) {
            case "IF_ELSE" -> Map.of("params", Map.of("status", "PAID"));
            case "INTENT_CLASSIFIER" -> Map.of("message", "query orders");
            case "PARAMETER_EXTRACT" -> Map.of("message", "owner=alice");
            case "LOOP" -> Map.of("var", Map.of("items", java.util.List.of("a")));
            default -> Map.of("message", "hello");
        };
    }

    private String minimalGraph(String nodeType) {
        return switch (nodeType) {
            case "USER_INPUT" -> """
                    {"entryNodeId":"n1","nodes":[{"id":"n1","type":"USER_INPUT"}],"exitNodeIds":["n1"]}
                    """;
            case "ANSWER" -> """
                    {"entryNodeId":"n1","nodes":[{"id":"n1","type":"ANSWER","config":{"template":"ok {{ input }}"}}],"exitNodeIds":["n1"]}
                    """;
            case "LLM" -> """
                    {"entryNodeId":"n1","nodes":[{"id":"n1","type":"LLM","config":{
                      "modelInstanceId":"model-1","userPrompt":"{{ input }}"
                    }}],"exitNodeIds":["n1"]}
                    """;
            case "TOOL" -> """
                    {"entryNodeId":"n1","nodes":[{"id":"n1","type":"TOOL","config":{}}],"exitNodeIds":["n1"]}
                    """;
            case "PAGE_ACTION" -> """
                    {"entryNodeId":"n1","nodes":[{"id":"n1","type":"PAGE_ACTION","config":{
                      "projectCode":"demo","pageKey":"orders","actionKey":"open"
                    }}],"exitNodeIds":["n1"]}
                    """;
            case "INTERACTION" -> """
                    {"entryNodeId":"n1","nodes":[{"id":"n1","type":"INTERACTION"}],"exitNodeIds":["n1"]}
                    """;
            case "INTENT_CLASSIFIER" -> """
                    {"entryNodeId":"n1","nodes":[{"id":"n1","type":"INTENT_CLASSIFIER","config":{
                      "strategy":"KEYWORD",
                      "classes":[{"id":"search","keywords":["query"]}],
                      "defaultRoute":"else"
                    }}],"exitNodeIds":["n1"]}
                    """;
            case "IF_ELSE" -> """
                    {"entryNodeId":"n1","nodes":[{"id":"n1","type":"IF_ELSE","config":{
                      "conditionGroups":[{"id":"paid","conditions":[
                        {"left":"params.status","operator":"equals","right":"PAID"}
                      ]}],
                      "defaultRoute":"else"
                    }}],"exitNodeIds":["n1"]}
                    """;
            case "PARAMETER_EXTRACT" -> """
                    {"entryNodeId":"n1","nodes":[{"id":"n1","type":"PARAMETER_EXTRACT","config":{
                      "mode":"expression",
                      "fields":[{"name":"owner","expression":"input"}]
                    }}],"exitNodeIds":["n1"]}
                    """;
            case "VARIABLE_ASSIGN" -> """
                    {"entryNodeId":"n1","nodes":[{"id":"n1","type":"VARIABLE_ASSIGN","config":{
                      "assignments":{"demo":"input"}
                    }}],"exitNodeIds":["n1"]}
                    """;
            case "TEMPLATE" -> """
                    {"entryNodeId":"n1","nodes":[{"id":"n1","type":"TEMPLATE","config":{
                      "template":"hi {{ input }}"
                    }}],"exitNodeIds":["n1"]}
                    """;
            case "VARIABLE_AGGREGATOR" -> """
                    {"entryNodeId":"n1","nodes":[{"id":"n1","type":"VARIABLE_AGGREGATOR","config":{
                      "mode":"object",
                      "items":[{"name":"v","source":"input"}]
                    }}],"exitNodeIds":["n1"]}
                    """;
            case "KNOWLEDGE_RETRIEVAL" -> """
                    {"entryNodeId":"n1","nodes":[{"id":"n1","type":"KNOWLEDGE_RETRIEVAL","config":{
                      "knowledgeBaseCodes":["kb"],"query":"input"
                    }}],"exitNodeIds":["n1"]}
                    """;
            case "HTTP_REQUEST" -> """
                    {"entryNodeId":"n1","nodes":[{"id":"n1","type":"HTTP_REQUEST","config":{
                      "method":"GET","url":"https://example.com"
                    }}],"exitNodeIds":["n1"]}
                    """;
            case "LOOP" -> """
                    {"entryNodeId":"n1","nodes":[
                      {"id":"n1","type":"LOOP","config":{
                        "collection":"var.items","itemAlias":"item","indexAlias":"index",
                        "outputAlias":"results","bodyOutput":"lastOutput","maxIterations":10,
                        "bodyEntry":"tpl","bodyExit":"tpl","bodyNodeIds":["tpl"]
                      }},
                      {"id":"tpl","type":"TEMPLATE","config":{"template":"{{ var.item }}"}}
                    ],"exitNodeIds":["n1"]}
                    """;
            default -> throw new IllegalArgumentException("unexpected handled type: " + nodeType);
        };
    }
}
