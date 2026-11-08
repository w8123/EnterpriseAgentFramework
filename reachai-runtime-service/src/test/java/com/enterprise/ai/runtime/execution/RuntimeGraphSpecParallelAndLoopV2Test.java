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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeGraphSpecParallelAndLoopV2Test {

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

    private static final String PARALLEL_GRAPH = """
            {
              "schemaVersion":2,
              "entryNodeId":"fork",
              "exitNodeIds":["answer"],
              "nodes":[
                {"id":"fork","type":"PARALLEL","config":{"maxConcurrency":2%s}},
                {"id":"a","type":"TEMPLATE","config":{"template":"A-{{ var.seed }}","outputAlias":"left"}},
                {"id":"b1","type":"TEMPLATE","config":{"template":"B-{{ var.seed }}"}},
                {"id":"b2","type":"TEMPLATE","config":%s},
                {"id":"answer","type":"ANSWER","config":{"template":"{{ var.left }}|{{ var.right }}"}}
              ],
              "edges":[
                {"from":"fork","to":"a","condition":"always","priority":1},
                {"from":"fork","to":"b1","condition":"always","priority":2},
                {"from":"b1","to":"b2","condition":"always"},
                {"from":"a","to":"answer","condition":"always"},
                {"from":"b2","to":"answer","condition":"always"}
              ]
            }
            """;

    @Test
    void parallelBranchesRunAndMergeOutputsAtJoin() {
        String graph = PARALLEL_GRAPH.formatted("",
                "{\"template\":\"{{ lastOutput }}!\",\"outputAlias\":\"right\"}");
        RuntimeGraphSpecExecutionResult result = executor.execute(graph,
                Map.of("var", Map.of("seed", "x")), WorkflowExecutionIdentity.untrustedDebug());
        assertTrue(result.success(), result::answer);
        assertEquals("ANSWER", result.nodeType());
        assertEquals("A-x|B-x!", result.answer());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> traces = (List<Map<String, Object>>) result.metadata().get("workflowNodeTraces");
        List<String> traced = traces.stream().map(trace -> String.valueOf(trace.get("nodeId"))).toList();
        assertTrue(traced.containsAll(List.of("fork", "a", "b1", "b2", "answer")), traced::toString);
    }

    @Test
    void failFastBranchFailureFailsTheParallelNode() {
        String graph = PARALLEL_GRAPH.formatted("", "{}");
        RuntimeGraphSpecExecutionResult result = executor.execute(graph,
                Map.of("var", Map.of("seed", "x")), WorkflowExecutionIdentity.untrustedDebug());
        assertFalse(result.success());
        assertEquals("fork", result.nodeId());
        assertEquals(1, result.metadata().get("failureBranchIndex"));
    }

    @Test
    void continueModeKeepsSuccessfulBranchesAndReachesJoin() {
        String graph = PARALLEL_GRAPH.formatted(",\"failureMode\":\"CONTINUE\"", "{}");
        RuntimeGraphSpecExecutionResult result = executor.execute(graph,
                Map.of("var", Map.of("seed", "x")), WorkflowExecutionIdentity.untrustedDebug());
        assertTrue(result.success(), result::answer);
        assertTrue(result.answer().startsWith("A-x|"), result.answer());
    }

    @Test
    void parallelWithoutJoinIsRejected() {
        String graph = """
                {"schemaVersion":2,"entryNodeId":"fork","exitNodeIds":["a","b"],
                 "nodes":[
                   {"id":"fork","type":"PARALLEL","config":{}},
                   {"id":"a","type":"ANSWER","config":{"template":"a"}},
                   {"id":"b","type":"ANSWER","config":{"template":"b"}}],
                 "edges":[{"from":"fork","to":"a"},{"from":"fork","to":"b"}]}
                """;
        RuntimeGraphSpecExecutionResult result = executor.execute(graph, Map.of(),
                WorkflowExecutionIdentity.untrustedDebug());
        assertFalse(result.success());
        assertEquals("RUNTIME_GRAPH_PARALLEL_JOIN_MISSING", result.code());
    }

    @Test
    void foreachParallelKeepsOrder() {
        List<String> items = new ArrayList<>();
        for (int i = 0; i < 12; i++) items.add("item-" + i);
        RuntimeGraphSpecExecutionResult result = executor.execute(
                foreachGraph(",\"parallelism\":4", "{\"template\":\"{{ var.item }}\"}"),
                Map.of("var", Map.of("items", items)), WorkflowExecutionIdentity.untrustedDebug());
        assertTrue(result.success(), result::answer);
        assertEquals(items, result.metadata().get("structuredOutput"));
    }

    @Test
    void foreachSkipAndKeepErrorModes() {
        // "{{ var.item.name }}" renders, but an empty template config fails for the item without a template.
        String body = "{\"template\":\"{{ var.item }}\"}";
        RuntimeGraphSpecExecutionResult skip = executor.execute(
                foreachGraphWithFailingItem(",\"errorMode\":\"SKIP\""),
                Map.of("var", Map.of("items", List.of("ok", "bad", "fine"))), WorkflowExecutionIdentity.untrustedDebug());
        assertTrue(skip.success(), skip::answer);
        assertEquals(List.of("ok", "fine"), skip.metadata().get("structuredOutput"));

        RuntimeGraphSpecExecutionResult keep = executor.execute(
                foreachGraphWithFailingItem(",\"errorMode\":\"KEEP\""),
                Map.of("var", Map.of("items", List.of("ok", "bad"))), WorkflowExecutionIdentity.untrustedDebug());
        assertTrue(keep.success(), keep::answer);
        List<?> output = (List<?>) keep.metadata().get("structuredOutput");
        assertEquals("ok", output.get(0));
        assertTrue(output.get(1) instanceof Map<?, ?> placeholder && Integer.valueOf(1).equals(placeholder.get("index")));
        assertEquals(body.isEmpty(), false);
    }

    @Test
    void whileLoopCarriesVariablesAndStopsOnCondition() {
        String graph = """
                {"schemaVersion":2,"entryNodeId":"loop","exitNodeIds":["answer"],
                 "nodes":[
                   {"id":"loop","type":"LOOP","config":{
                     "mode":"WHILE","indexAlias":"round","outputAlias":"tries","maxIterations":10,
                     "loopVariables":[{"name":"count","value":0}],
                     "breakConditions":[{"left":"var.count","operator":"gte","right":"3"}],
                     "bodyEntry":"inc","bodyExit":"inc","bodyNodeIds":["inc"]}},
                   {"id":"inc","type":"VARIABLE_ASSIGN","config":{"assignments":{"count":"{{ var.round }}"}}},
                   {"id":"answer","type":"ANSWER","config":{"template":"count={{ var.count }}"}}],
                 "edges":[{"from":"loop","to":"answer","condition":"always"}]}
                """;
        RuntimeGraphSpecExecutionResult result = executor.execute(graph, Map.of(),
                WorkflowExecutionIdentity.untrustedDebug());
        assertTrue(result.success(), result::answer);
        assertEquals("count=3", result.answer());
    }

    @Test
    void whileLoopStopsAtMaxIterations() {
        String graph = """
                {"schemaVersion":2,"entryNodeId":"loop","exitNodeIds":["loop"],
                 "nodes":[
                   {"id":"loop","type":"LOOP","config":{
                     "mode":"WHILE","outputAlias":"tries","maxIterations":3,
                     "breakConditions":[{"left":"var.never","operator":"equals","right":"yes"}],
                     "bodyEntry":"t","bodyExit":"t","bodyNodeIds":["t"]}},
                   {"id":"t","type":"TEMPLATE","config":{"template":"r{{ var.index }}"}}],
                 "edges":[]}
                """;
        RuntimeGraphSpecExecutionResult result = executor.execute(graph, Map.of(),
                WorkflowExecutionIdentity.untrustedDebug());
        assertTrue(result.success(), result::answer);
        Map<?, ?> structured = (Map<?, ?>) result.metadata().get("structuredOutput");
        assertEquals(3, structured.get("iterations"));
        assertEquals("MAX_ITERATIONS", structured.get("stoppedBy"));
        assertEquals(List.of("r0", "r1", "r2"), structured.get("outputs"));
    }

    @Test
    void timedOutNodeFailsWithTimeoutCodeAndDoesNotWaitForTheSlowCall() {
        RuntimeModelServiceClient slowModel = mock(RuntimeModelServiceClient.class);
        when(slowModel.chat(any())).thenAnswer(invocation -> {
            Thread.sleep(5_000);
            return null;
        });
        RuntimeGraphSpecExecutor slowExecutor = new RuntimeGraphSpecExecutor(
                objectMapper, slowModel,
                mock(RuntimeCapabilityCatalogClient.class),
                mock(RuntimeControlCatalogClient.class),
                mock(RuntimeKnowledgeRetrievalClient.class),
                new WorkflowHttpClient(objectMapper, WorkflowHttpEgressPolicy.permissiveForTests(),
                        mock(RuntimeWorkflowCredentialService.class)));
        String graph = """
                {"schemaVersion":2,"entryNodeId":"llm","exitNodeIds":["llm"],
                 "nodes":[{"id":"llm","type":"LLM","errorPolicy":{"timeoutMs":1000},
                   "config":{"modelInstanceId":"m1","userPrompt":"hi"}}],
                 "edges":[]}
                """;
        long started = System.currentTimeMillis();
        RuntimeGraphSpecExecutionResult result = slowExecutor.execute(graph, Map.of(),
                WorkflowExecutionIdentity.untrustedDebug());
        long elapsed = System.currentTimeMillis() - started;
        assertFalse(result.success());
        assertEquals("RUNTIME_GRAPH_NODE_TIMEOUT", result.code(), result::answer);
        assertTrue(elapsed < 4_000, "elapsed=" + elapsed);
    }

    private String foreachGraph(String extra, String bodyConfig) {
        return """
                {"schemaVersion":2,"entryNodeId":"loop","exitNodeIds":["loop"],
                 "nodes":[
                   {"id":"loop","type":"LOOP","config":{
                     "collection":"var.items","outputAlias":"results","maxIterations":100%s,
                     "bodyEntry":"t","bodyExit":"t","bodyNodeIds":["t"]}},
                   {"id":"t","type":"TEMPLATE","config":%s}],
                 "edges":[]}
                """.formatted(extra, bodyConfig);
    }

    private String foreachGraphWithFailingItem(String extra) {
        // The condition routes "bad" to a template without content, which fails that iteration only.
        return """
                {"schemaVersion":2,"entryNodeId":"loop","exitNodeIds":["loop"],
                 "nodes":[
                   {"id":"loop","type":"LOOP","config":{
                     "collection":"var.items","outputAlias":"results","maxIterations":100%s,
                     "bodyEntry":"check","bodyExit":"out","bodyNodeIds":["check","boom","out"]}},
                   {"id":"check","type":"IF_ELSE","config":{"groups":[{"id":"bad","logic":"AND",
                     "conditions":[{"left":"var.item","operator":"equals","right":"bad"}]}],"defaultRoute":"else"}},
                   {"id":"boom","type":"TEMPLATE","config":{}},
                   {"id":"out","type":"TEMPLATE","config":{"template":"{{ var.item }}"}}],
                 "edges":[
                   {"from":"check","to":"boom","condition":"route:bad"},
                   {"from":"check","to":"out","condition":"else"},
                   {"from":"boom","to":"out","condition":"always"}]}
                """.formatted(extra);
    }
}
