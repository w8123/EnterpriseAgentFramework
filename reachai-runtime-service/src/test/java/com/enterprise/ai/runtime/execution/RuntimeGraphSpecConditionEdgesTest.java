package com.enterprise.ai.runtime.execution;

import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.client.knowledge.RuntimeKnowledgeRetrievalClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.credential.RuntimeWorkflowCredentialService;
import com.enterprise.ai.runtime.execution.http.WorkflowHttpClient;
import com.enterprise.ai.runtime.execution.http.WorkflowHttpEgressPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** "error" / "empty" / "not_empty" edges let a Workflow answer "not found" instead of failing or guessing. */
class RuntimeGraphSpecConditionEdgesTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RuntimeGraphSpecExecutor executor = new RuntimeGraphSpecExecutor(
            objectMapper,
            mock(RuntimeModelServiceClient.class),
            mock(RuntimeCapabilityCatalogClient.class),
            mock(RuntimeControlCatalogClient.class),
            mock(RuntimeKnowledgeRetrievalClient.class),
            new WorkflowHttpClient(objectMapper, WorkflowHttpEgressPolicy.permissiveForTests(),
                    mock(RuntimeWorkflowCredentialService.class)));

    private static final String FAILING_TEMPLATE_GRAPH = """
            {
              "entryNodeId":"bad",
              "exitNodeIds":["rescue","answer"],
              "nodes":[
                {"id":"bad","type":"TEMPLATE","config":{"template":""}},
                {"id":"rescue","type":"ANSWER","config":{"template":"failed:{{ lastError.nodeId }}"}},
                {"id":"answer","type":"ANSWER","config":{"template":"ok"}}
              ],
              "edges":[
                %s
                {"from":"bad","to":"answer","condition":"always"}
              ]
            }
            """;

    @Test
    void failedNodeFollowsErrorEdgeAndExposesLastError() {
        RuntimeGraphSpecExecutionResult result = executor.execute(FAILING_TEMPLATE_GRAPH.formatted(
                "{\"from\":\"bad\",\"to\":\"rescue\",\"condition\":\"error\"},"), Map.of("message", "x"));

        assertTrue(result.success(), result.code());
        assertEquals("failed:bad", result.answer());
    }

    @Test
    void failedNodeWithoutErrorEdgeStillEndsTheRun() {
        RuntimeGraphSpecExecutionResult result = executor.execute(FAILING_TEMPLATE_GRAPH.formatted(""),
                Map.of("message", "x"));

        assertFalse(result.success());
    }

    private static final String PRODUCT_GRAPH = """
            {
              "entryNodeId":"extract",
              "exitNodeIds":["found","missing"],
              "nodes":[
                {"id":"extract","type":"PARAMETER_EXTRACT","config":{
                  "extractMode":"expression",
                  "fields":[{"name":"productId","source":"productId","required":%s,
                             "optionsFrom":"products","optionValueKey":"id","optionLabelKey":"name"}]
                }},
                {"id":"found","type":"ANSWER","config":{"template":"found:{{ productId }}"}},
                {"id":"missing","type":"ANSWER","config":{"template":"not-found"}}
              ],
              "edges":[
                {"from":"extract","to":"found","condition":"not_empty"},
                {"from":"extract","to":"missing","condition":"empty"}
              ]
            }
            """;

    private static final List<Map<String, Object>> PRODUCTS = List.of(
            Map.of("id", 1, "name", "鼠标"),
            Map.of("id", 2, "name", "显示器"));

    @Test
    void valueFromOptionListFollowsNotEmptyEdge() {
        RuntimeGraphSpecExecutionResult result = executor.execute(PRODUCT_GRAPH.formatted("true"),
                Map.of("productId", 1, "products", PRODUCTS));

        assertTrue(result.success(), result.code());
        assertEquals("found:1", result.answer());
    }

    @Test
    void requiredValueOutsideOptionListFollowsEmptyEdge() {
        RuntimeGraphSpecExecutionResult result = executor.execute(PRODUCT_GRAPH.formatted("true"),
                Map.of("productId", 99, "products", PRODUCTS));

        assertTrue(result.success(), result.code());
        assertEquals("not-found", result.answer());
        assertTrue(result.steps().stream().anyMatch(step -> "extract".equals(step.get("detail"))
                && "empty".equals(step.get("route"))), "routed node keeps its step: " + result.steps());
    }

    @Test
    void optionalValueOutsideOptionListProducesEmptyOutput() {
        RuntimeGraphSpecExecutionResult result = executor.execute(PRODUCT_GRAPH.formatted("false"),
                Map.of("productId", 99, "products", PRODUCTS));

        assertTrue(result.success(), result.code());
        assertEquals("not-found", result.answer());
    }

    @Test
    void optionListWrappedInBusinessResponseIsUnwrapped() {
        RuntimeGraphSpecExecutionResult result = executor.execute(PRODUCT_GRAPH.formatted("true"),
                Map.of("productId", 2, "products", Map.of("code", 0, "msg", "", "data", PRODUCTS)));

        assertTrue(result.success(), result.code());
        assertEquals("found:2", result.answer());
    }

    @Test
    void optionsFromThatIsNotAListFailsInsteadOfLookingLikeNotFound() {
        RuntimeGraphSpecExecutionResult result = executor.execute(PRODUCT_GRAPH.formatted("true"),
                Map.of("productId", 1, "products", Map.of("code", 0, "msg", "")));

        assertFalse(result.success());
        assertEquals("RUNTIME_GRAPH_PARAMETER_OPTIONS_INVALID", result.code());
    }
}
