package com.enterprise.ai.runtime.execution;

import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.client.knowledge.RuntimeKnowledgeWriteClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.execution.checkpoint.WorkflowCheckpointCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RuntimeKnowledgeWriteSafetyTest {
    private final RuntimeKnowledgeWriteClient client = mock(RuntimeKnowledgeWriteClient.class);
    private final RuntimeGraphSpecExecutor executor = new RuntimeGraphSpecExecutor(new ObjectMapper(),
            mock(RuntimeModelServiceClient.class), mock(RuntimeCapabilityCatalogClient.class),
            mock(RuntimeControlCatalogClient.class));

    private static final String GRAPH = """
            {"schemaVersion":2,"entryNodeId":"loop","exitNodeIds":["answer"],"nodes":[
              {"id":"loop","type":"LOOP","config":{"mode":"FOREACH","collection":"var.items",
               "parallelism":2,"errorMode":"SKIP","bodyEntry":"write","bodyExit":"write","bodyNodeIds":["write"]}},
              {"id":"write","type":"KNOWLEDGE_WRITE","config":{"knowledgeBaseCode":"kb","content":"{{ var.item }}"}},
              {"id":"answer","type":"ANSWER","config":{"template":"done"}}],
             "edges":[{"from":"loop","to":"answer","condition":"always"}]}
            """;

    @Test
    void debugCannotWriteUsingSuppliedProject() {
        executor.setKnowledgeWriteClient(client);
        var result = executor.executeSingleNode(GRAPH, Map.of("sys", Map.of("projectCode", "other"),
                "var", Map.of("item", "one")), "write");
        assertFalse(result.success());
        verifyNoInteractions(client);
    }

    @Test
    void iterationsHaveDistinctKeysAndUseTrustedProject() {
        executor.setKnowledgeWriteClient(client);
        List<RuntimeKnowledgeWriteClient.WriteTextRequest> requests = new CopyOnWriteArrayList<>();
        when(client.writeText(any())).thenAnswer(call -> {
            requests.add(call.getArgument(0));
            return new RuntimeKnowledgeWriteClient.WriteResult(200, "ok", Map.of());
        });
        var result = executor.execute(GRAPH, Map.of("runId", "run-1", "sys", Map.of("projectCode", "other"),
                "var", Map.of("items", List.of("one", "two"))), WorkflowExecutionIdentity.fromAgent(1L, "trusted"));
        assertTrue(result.success(), result::answer);
        assertEquals(2, requests.size());
        assertNotEquals(requests.get(0).idempotencyKey(), requests.get(1).idempotencyKey());
        assertTrue(requests.stream().allMatch(request -> "trusted".equals(request.projectCode())));
    }

    @Test
    void unknownWriteStopsLoopEvenWhenConfiguredToSkipErrors() {
        executor.setKnowledgeWriteClient(client);
        when(client.writeText(any())).thenThrow(new IllegalStateException("transport failure"));
        var result = executor.execute(GRAPH, Map.of("runId", "run-1", "var", Map.of("items", List.of("one"))),
                WorkflowExecutionIdentity.fromAgent(1L, "trusted"));
        assertFalse(result.success());
        assertEquals("RUNTIME_GRAPH_KNOWLEDGE_WRITE_FAILED", result.code());
        assertEquals(true, result.metadata().get("nonRecoverableWrite"));
    }

    @Test
    void checkpointRestoresRunKeyButExternalInputCannotChooseIt() {
        executor.setKnowledgeWriteClient(client);
        List<String> keys = new CopyOnWriteArrayList<>();
        when(client.writeText(any())).thenAnswer(call -> {
            RuntimeKnowledgeWriteClient.WriteTextRequest request = call.getArgument(0);
            keys.add(request.idempotencyKey());
            return new RuntimeKnowledgeWriteClient.WriteResult(200, "ok", Map.of());
        });
        String graph = """
                {"schemaVersion":2,"entryNodeId":"write","exitNodeIds":["answer"],"nodes":[
                  {"id":"write","type":"KNOWLEDGE_WRITE","config":{"knowledgeBaseCode":"kb","content":"one"}},
                  {"id":"approval","type":"HUMAN_APPROVAL","config":{}},
                  {"id":"answer","type":"ANSWER","config":{"template":"done"}}],
                 "edges":[{"from":"write","to":"approval","condition":"always"},
                          {"from":"approval","to":"answer","condition":"always"}]}
                """;
        var identity = WorkflowExecutionIdentity.fromAgent(1L, "trusted");
        Map<String, Object> supplied = Map.of("runId", "same", "__workflowInvocationScope",
                Map.of("runId", "00000000-0000-0000-0000-000000000001", "path", ""));
        var first = executor.execute(graph, supplied, identity);
        assertTrue(first.isWaitingUser());
        var codec = new WorkflowCheckpointCodec(new ObjectMapper());
        var encoded = codec.encode(first.resumeCheckpoint(), graph, "approval");
        var decoded = codec.decode(encoded.json(), encoded.schemaVersion(), encoded.engineVersion(),
                encoded.digest(), encoded.sizeBytes(), graph, "approval");
        executor.executeFromCheckpoint(graph, decoded.state(), "write", decoded.schemaVersion(),
                decoded.engineVersion(), identity);
        executor.execute(graph, supplied, identity);
        assertEquals(3, keys.size());
        assertEquals(keys.get(0), keys.get(1));
        assertNotEquals(keys.get(0), keys.get(2));
    }
}
