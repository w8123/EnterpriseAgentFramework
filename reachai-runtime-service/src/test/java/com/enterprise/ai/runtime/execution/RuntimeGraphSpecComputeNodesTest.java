package com.enterprise.ai.runtime.execution;

import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class RuntimeGraphSpecComputeNodesTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RuntimeGraphSpecExecutor executor = new RuntimeGraphSpecExecutor(objectMapper,
            mock(RuntimeModelServiceClient.class), mock(RuntimeCapabilityCatalogClient.class),
            mock(RuntimeControlCatalogClient.class));

    private RuntimeGraphSpecExecutionResult run(String nodeJson, String answerTemplate, Map<String, Object> request) {
        String graph = """
                {"schemaVersion":2,"entryNodeId":"n","exitNodeIds":["answer"],
                 "nodes":[%s,{"id":"answer","type":"ANSWER","config":{"template":"%s"}}],
                 "edges":[{"from":"n","to":"answer","condition":"always"}]}
                """.formatted(nodeJson, answerTemplate);
        return executor.execute(graph, request, WorkflowExecutionIdentity.untrustedDebug());
    }

    @Test
    void javascriptCodeReadsInputsAndReturnsObject() {
        String node = """
                {"id":"n","type":"CODE","config":{"language":"javascript","outputAlias":"calc",
                  "inputs":{"items":"var.items","rate":"0.5"},
                  "code":"function main(inputs) { var sum = 0; for (var i = 0; i < inputs.items.length; i++) sum += inputs.items[i].amount; return { total: sum * Number(inputs.rate), count: inputs.items.length }; }",
                  "outputs":{"total":"number","count":"number"}}}
                """;
        RuntimeGraphSpecExecutionResult result = run(node, "{{ var.calc.total }}/{{ var.calc.count }}",
                Map.of("var", Map.of("items", List.of(Map.of("amount", 10), Map.of("amount", 30)))));
        assertTrue(result.success(), result::answer);
        assertEquals("20/2", result.answer());
    }

    @Test
    void javascriptCodeCannotReachJavaAndInfiniteLoopIsStopped() {
        String javaAccess = """
                {"id":"n","type":"CODE","config":{"language":"javascript",
                  "code":"function main(inputs) { return { v: String(java.lang.System.getProperty('user.home')) }; }"}}
                """;
        RuntimeGraphSpecExecutionResult blocked = run(javaAccess, "x", Map.of());
        assertFalse(blocked.success());
        assertTrue(blocked.code().startsWith("RUNTIME_GRAPH_CODE"), blocked.code());

        String loop = """
                {"id":"n","type":"CODE","config":{"language":"javascript","timeoutMs":300,
                  "code":"function main(inputs) { while (true) {} }"}}
                """;
        long started = System.currentTimeMillis();
        RuntimeGraphSpecExecutionResult stopped = run(loop, "x", Map.of());
        assertFalse(stopped.success());
        assertEquals("RUNTIME_GRAPH_CODE_TIMEOUT", stopped.code());
        assertTrue(System.currentTimeMillis() - started < 5_000);

        String notObject = """
                {"id":"n","type":"CODE","config":{"language":"javascript","code":"function main(inputs) { return 3; }"}}
                """;
        assertEquals("RUNTIME_GRAPH_CODE_OUTPUT_INVALID", run(notObject, "x", Map.of()).code());
    }

    @Test
    void listOperatorFiltersSortsDedupesAndLimits() {
        String node = """
                {"id":"n","type":"LIST_OPERATOR","config":{"source":"var.orders","outputAlias":"picked",
                  "operations":[
                    {"type":"filter","logic":"AND","conditions":[{"left":"item.status","operator":"in","right":"open,pending"}]},
                    {"type":"unique","field":"customer"},
                    {"type":"sort","field":"amount","order":"desc"},
                    {"type":"limit","count":2},
                    {"type":"pluck","field":"customer"}]}}
                """;
        List<Map<String, Object>> orders = List.of(
                Map.of("customer", "A", "amount", 5, "status", "open"),
                Map.of("customer", "B", "amount", 50, "status", "closed"),
                Map.of("customer", "C", "amount", 20, "status", "pending"),
                Map.of("customer", "A", "amount", 99, "status", "open"),
                Map.of("customer", "D", "amount", 7, "status", "open"));
        RuntimeGraphSpecExecutionResult result = run(node, "{{ var.picked.count }}:{{ var.picked.first }}",
                Map.of("var", Map.of("orders", orders)));
        assertTrue(result.success(), result::answer);
        assertEquals("2:C", result.answer());
    }

    @Test
    void newConditionOperatorsRoute() {
        String graph = """
                {"schemaVersion":2,"entryNodeId":"c","exitNodeIds":["yes","no"],
                 "nodes":[
                   {"id":"c","type":"IF_ELSE","config":{"conditionGroups":[{"id":"hit","logic":"AND","conditions":[
                     {"left":"params.code","operator":"starts_with","right":"SO-"},
                     {"left":"params.code","operator":"regex","right":"^SO-\\\\d{4}$"},
                     {"left":"params.qty","operator":"is_number"},
                     {"left":"params.tags","operator":"is_list"}]}],"defaultRoute":"else"}},
                   {"id":"yes","type":"ANSWER","config":{"template":"yes"}},
                   {"id":"no","type":"ANSWER","config":{"template":"no"}}],
                 "edges":[{"from":"c","to":"yes","condition":"hit"},{"from":"c","to":"no","condition":"else"}]}
                """;
        RuntimeGraphSpecExecutionResult hit = executor.execute(graph,
                Map.of("params", Map.of("code", "SO-1234", "qty", "12", "tags", List.of("x"))),
                WorkflowExecutionIdentity.untrustedDebug());
        assertEquals("yes", hit.answer(), hit::toString);
        RuntimeGraphSpecExecutionResult miss = executor.execute(graph,
                Map.of("params", Map.of("code", "SO-12345", "qty", "12", "tags", List.of("x"))),
                WorkflowExecutionIdentity.untrustedDebug());
        assertEquals("no", miss.answer());
    }

    @Test
    void subWorkflowRunsPinnedChildWithMappedInputs() {
        String child = """
                {"schemaVersion":2,"entryNodeId":"t","exitNodeIds":["a"],
                 "nodes":[{"id":"t","type":"TEMPLATE","config":{"template":"hello {{ params.name }}"}},
                          {"id":"a","type":"ANSWER","config":{"template":"{{ lastOutput }}!"}}],
                 "edges":[{"from":"t","to":"a","condition":"always"}]}
                """;
        executor.setSubWorkflowPort((workflowId, versionId) -> {
            if (!"child".equals(workflowId) || versionId != 7L) throw new IllegalArgumentException("missing");
            return child;
        });
        String node = """
                {"id":"n","type":"SUB_WORKFLOW","config":{"workflowId":"child","versionId":7,
                  "inputs":{"name":"params.who"},"outputAlias":"greeting"}}
                """;
        RuntimeGraphSpecExecutionResult result = run(node, "{{ var.greeting.answer }}",
                Map.of("params", Map.of("who", "Li")));
        assertTrue(result.success(), result::answer);
        assertEquals("hello Li!", result.answer());

        String missing = node.replace("\"versionId\":7", "\"versionId\":8");
        RuntimeGraphSpecExecutionResult failed = run(missing, "x", Map.of());
        assertEquals("RUNTIME_GRAPH_SUB_WORKFLOW_NOT_FOUND", failed.code());
    }

    @Test
    void subWorkflowRejectsSelfCycle() {
        String self = """
                {"schemaVersion":2,"entryNodeId":"n","exitNodeIds":["answer"],
                 "nodes":[{"id":"n","type":"SUB_WORKFLOW","config":{"workflowId":"loopy","versionId":1}},
                          {"id":"answer","type":"ANSWER","config":{"template":"x"}}],
                 "edges":[{"from":"n","to":"answer","condition":"always"}]}
                """;
        executor.setSubWorkflowPort((workflowId, versionId) -> self);
        RuntimeGraphSpecExecutionResult result = executor.execute(self, Map.of(),
                WorkflowExecutionIdentity.untrustedDebug());
        assertFalse(result.success());
        assertTrue(String.valueOf(result.code()).startsWith("RUNTIME_GRAPH_SUB_WORKFLOW"), result.code());
    }

    @Test
    void conflictingSubWorkflowPinsFailBeforeResolvingChild() {
        executor.setSubWorkflowPort((workflowId, versionId) -> {
            throw new AssertionError("conflicting child must not be resolved");
        });
        String node = """
                {"id":"n","type":"SUB_WORKFLOW","config":{
                 "workflowId":"write-child","versionId":8,
                 "subWorkflowConfig":{"workflowId":"read-child","versionId":7}}}
                """;
        RuntimeGraphSpecExecutionResult result = run(node, "done", Map.of());
        assertEquals("RUNTIME_GRAPH_SUB_WORKFLOW_REFERENCE_CONFLICT", result.code());
    }

    @Test
    void knowledgeWriteSendsProjectScopedIdempotentText() {
        com.enterprise.ai.runtime.client.knowledge.RuntimeKnowledgeWriteClient client =
                mock(com.enterprise.ai.runtime.client.knowledge.RuntimeKnowledgeWriteClient.class);
        org.mockito.ArgumentCaptor<com.enterprise.ai.runtime.client.knowledge.RuntimeKnowledgeWriteClient.WriteTextRequest> sent =
                org.mockito.ArgumentCaptor.forClass(com.enterprise.ai.runtime.client.knowledge.RuntimeKnowledgeWriteClient.WriteTextRequest.class);
        org.mockito.Mockito.when(client.writeText(sent.capture())).thenReturn(
                new com.enterprise.ai.runtime.client.knowledge.RuntimeKnowledgeWriteClient.WriteResult(200, "success",
                        Map.of("fileId", "wf-1", "chunkCount", 1)));
        executor.setKnowledgeWriteClient(client);
        String node = """
                {"id":"n","type":"KNOWLEDGE_WRITE","config":{"knowledgeBaseCode":"faq","title":"客户 {{ params.name }}",
                  "content":"{{ params.name }} 的处理记录","outputAlias":"saved"}}
                """;
        String graph = """
                {"schemaVersion":2,"entryNodeId":"n","exitNodeIds":["answer"],
                 "nodes":[%s,{"id":"answer","type":"ANSWER","config":{"template":"{{ var.saved.written }}"}}],
                 "edges":[{"from":"n","to":"answer","condition":"always"}]}
                """.formatted(node);
        RuntimeGraphSpecExecutionResult result = executor.execute(graph,
                Map.of("params", Map.of("name", "Li"), "runId", "run-9", "projectCode", "demo"),
                WorkflowExecutionIdentity.fromAgent(1L, "demo"));
        assertTrue(result.success(), result::answer);
        assertEquals("true", result.answer());
        assertEquals("faq", sent.getValue().knowledgeBaseCode());
        assertEquals("Li 的处理记录", sent.getValue().text());
        assertEquals("客户 Li", sent.getValue().title());
        assertEquals("demo", sent.getValue().projectCode());
        assertEquals(64, sent.getValue().idempotencyKey().length());
    }

    @Test
    void workflowEnvComesFromGraphAndCannotBeOverriddenByRequest() {
        String graph = """
                {"schemaVersion":2,"entryNodeId":"answer","exitNodeIds":["answer"],
                 "env":{"region":"east"},
                 "nodes":[{"id":"answer","type":"ANSWER","config":{"template":"{{ env.region }}|{{ sys.today }}"}}],
                 "edges":[]}
                """;
        RuntimeGraphSpecExecutionResult result = executor.execute(graph,
                Map.of("env", Map.of("region", "hijack")), WorkflowExecutionIdentity.untrustedDebug());
        assertTrue(result.success(), result::answer);
        assertEquals("east|" + java.time.LocalDate.now(), result.answer());
    }

    @Test
    void singleNodeTestStopsAfterThatNode() {
        String graph = """
                {"schemaVersion":2,"entryNodeId":"n","exitNodeIds":["answer"],
                 "nodes":[{"id":"n","type":"CODE","config":{"language":"expression","outputs":{"result":"lastOutput"}}},
                          {"id":"answer","type":"ANSWER","config":{"template":"downstream ran"}}],
                 "edges":[{"from":"n","to":"answer","condition":"always"}]}
                """;
        RuntimeGraphSpecExecutionResult result = executor.executeSingleNode(graph, Map.of("input", "hi"), "n");
        assertTrue(result.success(), result::answer);
        assertEquals("n", result.nodeId());
        assertFalse(String.valueOf(result.answer()).contains("downstream ran"));
    }
}
