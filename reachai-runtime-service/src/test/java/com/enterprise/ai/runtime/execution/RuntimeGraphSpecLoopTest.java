package com.enterprise.ai.runtime.execution;

import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.client.knowledge.RuntimeKnowledgeRetrievalClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.credential.RuntimeWorkflowCredentialService;
import com.enterprise.ai.runtime.execution.http.WorkflowHttpClient;
import com.enterprise.ai.runtime.execution.http.WorkflowHttpEgressPolicy;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class RuntimeGraphSpecLoopTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RuntimeGraphSpecExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new RuntimeGraphSpecExecutor(
                objectMapper,
                mock(RuntimeModelServiceClient.class),
                mock(RuntimeCapabilityCatalogClient.class),
                mock(RuntimeControlCatalogClient.class),
                mock(RuntimeKnowledgeRetrievalClient.class),
                new WorkflowHttpClient(
                        objectMapper,
                        WorkflowHttpEgressPolicy.permissiveForTests(),
                        mock(RuntimeWorkflowCredentialService.class)));
    }

    @Test
    void foreachThreeItemsPreservesOrderAndIsolatesItemAlias() {
        String markerA = UUID.randomUUID().toString();
        String markerB = UUID.randomUUID().toString();
        String markerC = UUID.randomUUID().toString();
        String graph = """
                {
                  "entryNodeId":"loop",
                  "exitNodeIds":["loop"],
                  "nodes":[
                    {"id":"loop","type":"LOOP","config":{
                      "mode":"FOREACH","collection":"var.items","itemAlias":"item","indexAlias":"index",
                      "outputAlias":"results","bodyOutput":"lastOutput","maxIterations":100,
                      "bodyEntry":"tpl","bodyExit":"tpl","bodyNodeIds":["tpl"]
                    }},
                    {"id":"tpl","type":"TEMPLATE","config":{"template":"{{ var.item }}","outputAlias":"rendered"}}
                  ],
                  "edges":[]
                }
                """;
        RuntimeGraphSpecExecutionResult result = executor.execute(
                graph,
                Map.of("var", Map.of("items", List.of(markerA, markerB, markerC))),
                WorkflowExecutionIdentity.untrustedDebug());
        assertTrue(result.success(), result::answer);
        assertEquals(List.of(markerA, markerB, markerC), result.metadata().get("structuredOutput"));
        String trace = String.valueOf(result.metadata().get("traceSummary"));
        assertFalse(trace.contains(markerA));
        assertFalse(trace.contains(markerB));
        assertFalse(trace.contains(markerC));
        assertTrue(trace.contains("collectionSize=3") || trace.contains("\"collectionSize\":3"));
    }

    @Test
    void emptyCollectionWritesEmptyArray() {
        String graph = loopGraph("var.items", 100);
        RuntimeGraphSpecExecutionResult result = executor.execute(
                graph,
                Map.of("var", Map.of("items", List.of())),
                WorkflowExecutionIdentity.untrustedDebug());
        assertTrue(result.success(), result::answer);
        assertEquals(List.of(), result.metadata().get("structuredOutput"));
    }

    @Test
    void nonCollectionFailsClosed() {
        String graph = loopGraph("var.items", 100);
        RuntimeGraphSpecExecutionResult result = executor.execute(
                graph,
                Map.of("var", Map.of("items", "not-a-list")),
                WorkflowExecutionIdentity.untrustedDebug());
        assertFalse(result.success());
        assertEquals("RUNTIME_GRAPH_LOOP_COLLECTION_TYPE", result.code());
    }

    @Test
    void exceedsMaxIterationsFails() {
        String graph = loopGraph("var.items", 2);
        RuntimeGraphSpecExecutionResult result = executor.execute(
                graph,
                Map.of("var", Map.of("items", List.of("a", "b", "c"))),
                WorkflowExecutionIdentity.untrustedDebug());
        assertFalse(result.success());
        assertEquals("RUNTIME_GRAPH_LOOP_MAX_ITERATIONS", result.code());
    }

    @Test
    void bodyFailureStopsLoopAndReportsIterationIndex() {
        String graph = """
                {
                  "entryNodeId":"loop",
                  "exitNodeIds":["loop"],
                  "nodes":[
                    {"id":"loop","type":"LOOP","config":{
                      "collection":"var.items","itemAlias":"item","indexAlias":"index",
                      "outputAlias":"results","bodyOutput":"lastOutput","maxIterations":10,
                      "bodyEntry":"bad","bodyExit":"bad","bodyNodeIds":["bad"]
                    }},
                    {"id":"bad","type":"TEMPLATE","config":{}}
                  ],
                  "edges":[]
                }
                """;
        RuntimeGraphSpecExecutionResult result = executor.execute(
                graph,
                Map.of("var", Map.of("items", List.of("x", "y"))),
                WorkflowExecutionIdentity.untrustedDebug());
        assertFalse(result.success());
        assertEquals(0, result.metadata().get("failureIterationIndex"));
    }

    @Test
    void cancellationStopsRemainingIterations() {
        String graph = loopGraph("var.items", 100);
        RuntimeGraphSpecExecutionCancellation cancellation = RuntimeGraphSpecExecutionCancellation.none();
        cancellation.cancel();
        RuntimeGraphSpecExecutionResult result = executor.execute(
                graph,
                Map.of("var", Map.of("items", List.of("a", "b", "c"))),
                RuntimeGraphSpecExecutionEventSink.NOOP,
                cancellation,
                WorkflowExecutionIdentity.untrustedDebug());
        assertFalse(result.success());
        assertEquals("RUNTIME_GRAPH_CANCELLED", result.code());
    }

    @Test
    void nestedLoopRejectedAtRuntime() {
        String graph = """
                {
                  "entryNodeId":"outer",
                  "exitNodeIds":["outer"],
                  "nodes":[
                    {"id":"outer","type":"LOOP","config":{
                      "collection":"var.items","itemAlias":"item","indexAlias":"index",
                      "outputAlias":"results","bodyOutput":"lastOutput","maxIterations":10,
                      "bodyEntry":"inner","bodyExit":"inner","bodyNodeIds":["inner"]
                    }},
                    {"id":"inner","type":"LOOP","config":{
                      "collection":"var.items","itemAlias":"x","indexAlias":"i",
                      "outputAlias":"inner_out","bodyOutput":"lastOutput","maxIterations":10,
                      "bodyEntry":"tpl","bodyExit":"tpl","bodyNodeIds":["tpl"]
                    }},
                    {"id":"tpl","type":"TEMPLATE","config":{"template":"{{ var.x }}"}}
                  ],
                  "edges":[]
                }
                """;
        RuntimeGraphSpecExecutionResult result = executor.execute(
                graph,
                Map.of("var", Map.of("items", List.of("a"))),
                WorkflowExecutionIdentity.untrustedDebug());
        assertFalse(result.success());
        assertEquals("RUNTIME_GRAPH_LOOP_NESTED", result.code());
    }

    @Test
    void multiNodeBodyPreservesOrder() {
        String graph = """
                {
                  "entryNodeId":"loop",
                  "exitNodeIds":["loop"],
                  "nodes":[
                    {"id":"loop","type":"LOOP","config":{
                      "collection":"var.items","itemAlias":"item","indexAlias":"index",
                      "outputAlias":"results","bodyOutput":"var.step2","maxIterations":100,
                      "bodyEntry":"a","bodyExit":"b","bodyNodeIds":["a","b"]
                    }},
                    {"id":"a","type":"TEMPLATE","config":{"template":"{{ var.item }}-1","outputAlias":"step1"}},
                    {"id":"b","type":"TEMPLATE","config":{"template":"{{ var.step1 }}-2","outputAlias":"step2"}}
                  ],
                  "edges":[{"from":"a","to":"b","condition":"always"}]
                }
                """;
        RuntimeGraphSpecExecutionResult result = executor.execute(
                graph,
                Map.of("var", Map.of("items", List.of("x", "y"))),
                WorkflowExecutionIdentity.untrustedDebug());
        assertTrue(result.success(), result::answer);
        assertEquals(List.of("x-1-2", "y-1-2"), result.metadata().get("structuredOutput"));
    }

    @Test
    void invalidMaxIterationsFailsClosed() {
        for (Object bad : List.of(0, -1, 1001, "abc")) {
            String graph = """
                    {
                      "entryNodeId":"loop",
                      "exitNodeIds":["loop"],
                      "nodes":[
                        {"id":"loop","type":"LOOP","config":{
                          "collection":"var.items","itemAlias":"item","indexAlias":"index",
                          "outputAlias":"results","bodyOutput":"lastOutput","maxIterations":%s,
                          "bodyEntry":"tpl","bodyExit":"tpl","bodyNodeIds":["tpl"]
                        }},
                        {"id":"tpl","type":"TEMPLATE","config":{"template":"{{ var.item }}"}}
                      ],
                      "edges":[]
                    }
                    """.formatted(bad instanceof String ? "\"" + bad + "\"" : String.valueOf(bad));
            RuntimeGraphSpecExecutionResult result = executor.execute(
                    graph,
                    Map.of("var", Map.of("items", List.of("a"))),
                    WorkflowExecutionIdentity.untrustedDebug());
            assertFalse(result.success(), () -> "expected fail for maxIterations=" + bad);
            assertEquals("RUNTIME_GRAPH_LOOP_MAX_ITERATIONS_INVALID", result.code());
        }
    }

    @Test
    void missingMaxIterationsDefaultsToHundred() {
        String graph = """
                {
                  "entryNodeId":"loop",
                  "exitNodeIds":["loop"],
                  "nodes":[
                    {"id":"loop","type":"LOOP","config":{
                      "collection":"var.items","itemAlias":"item","indexAlias":"index",
                      "outputAlias":"results","bodyOutput":"lastOutput",
                      "bodyEntry":"tpl","bodyExit":"tpl","bodyNodeIds":["tpl"]
                    }},
                    {"id":"tpl","type":"TEMPLATE","config":{"template":"{{ var.item }}"}}
                  ],
                  "edges":[]
                }
                """;
        RuntimeGraphSpecExecutionResult result = executor.execute(
                graph,
                Map.of("var", Map.of("items", List.of("a", "b"))),
                WorkflowExecutionIdentity.untrustedDebug());
        assertTrue(result.success(), result::answer);
        assertEquals(List.of("a", "b"), result.metadata().get("structuredOutput"));
    }

    private String loopGraph(String collection, int maxIterations) {
        return """
                {
                  "entryNodeId":"loop",
                  "exitNodeIds":["loop"],
                  "nodes":[
                    {"id":"loop","type":"LOOP","config":{
                      "collection":"%s","itemAlias":"item","indexAlias":"index",
                      "outputAlias":"results","bodyOutput":"lastOutput","maxIterations":%d,
                      "bodyEntry":"tpl","bodyExit":"tpl","bodyNodeIds":["tpl"]
                    }},
                    {"id":"tpl","type":"TEMPLATE","config":{"template":"[{{ var.item }}]"}}
                  ],
                  "edges":[]
                }
                """.formatted(collection, maxIterations);
    }
}
