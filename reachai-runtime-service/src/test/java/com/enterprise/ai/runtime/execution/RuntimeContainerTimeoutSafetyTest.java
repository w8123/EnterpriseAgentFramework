package com.enterprise.ai.runtime.execution;

import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.client.knowledge.RuntimeKnowledgeWriteClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RuntimeContainerTimeoutSafetyTest {
    private final RuntimeKnowledgeWriteClient knowledge = mock(RuntimeKnowledgeWriteClient.class);
    private final RuntimeModelServiceClient model = mock(RuntimeModelServiceClient.class);
    private final RuntimeGraphSpecExecutor executor = new RuntimeGraphSpecExecutor(new ObjectMapper(), model,
            mock(RuntimeCapabilityCatalogClient.class), mock(RuntimeControlCatalogClient.class));

    @Test
    void loopTimeoutCannotContinueOrFollowAnErrorEdgeWhileWriteIsUnconfirmed() throws Exception {
        for (String policy : List.of("CONTINUE", "TERMINATE")) {
            String graph = """
                    {"schemaVersion":2,"entryNodeId":"loop","exitNodeIds":["answer"],"nodes":[
                      {"id":"loop","type":"LOOP","errorPolicy":{"timeoutMs":1000,"strategy":"%s"},"config":{
                       "collection":"var.items","bodyEntry":"write","bodyExit":"write","bodyNodeIds":["write"]}},
                      {"id":"write","type":"KNOWLEDGE_WRITE","config":{"knowledgeBaseCode":"kb","content":"item"}},
                      {"id":"answer","type":"ANSWER","config":{"template":"continued"}}],
                     "edges":[{"from":"loop","to":"answer","condition":"error"},
                              {"from":"loop","to":"answer","condition":"success"}]}
                    """.formatted(policy);
            assertUnknownWrite(graph);
        }
    }

    @Test
    void parallelTimeoutCannotFallBackWhileNestedLoopWriteIsUnconfirmed() throws Exception {
        String graph = """
                {"schemaVersion":2,"entryNodeId":"fork","exitNodeIds":["answer"],"nodes":[
                  {"id":"fork","type":"PARALLEL","errorPolicy":{"timeoutMs":1000,"strategy":"FALLBACK","fallbackNodeId":"answer"}},
                  {"id":"loop","type":"LOOP","config":{"collection":"var.items","bodyEntry":"write","bodyExit":"write","bodyNodeIds":["write"]}},
                  {"id":"write","type":"KNOWLEDGE_WRITE","config":{"knowledgeBaseCode":"kb","content":"item"}},
                  {"id":"other","type":"TEMPLATE","config":{"template":"other"}},
                  {"id":"answer","type":"ANSWER","config":{"template":"continued"}}],
                 "edges":[{"from":"fork","to":"loop"},{"from":"fork","to":"other"},
                          {"from":"loop","to":"answer"},{"from":"other","to":"answer"}]}
                """;
        assertUnknownWrite(graph);
    }

    @Test
    void parallelTimeoutCannotContinueWhileBranchWriteIsUnconfirmed() throws Exception {
        String graph = """
                {"schemaVersion":2,"entryNodeId":"fork","exitNodeIds":["answer"],"nodes":[
                  {"id":"fork","type":"PARALLEL","errorPolicy":{"timeoutMs":1000,"strategy":"CONTINUE"}},
                  {"id":"write","type":"KNOWLEDGE_WRITE","config":{"knowledgeBaseCode":"kb","content":"item"}},
                  {"id":"other","type":"TEMPLATE","config":{"template":"other"}},
                  {"id":"answer","type":"ANSWER","config":{"template":"continued"}}],
                 "edges":[{"from":"fork","to":"write"},{"from":"fork","to":"other"},
                          {"from":"write","to":"answer"},{"from":"other","to":"answer"}]}
                """;
        assertUnknownWrite(graph);
    }

    @Test
    void loopTimeoutTreatsPinnedSubWorkflowAsPotentiallyWriting() throws Exception {
        String child = """
                {"schemaVersion":2,"entryNodeId":"write","exitNodeIds":["answer"],"nodes":[
                  {"id":"write","type":"KNOWLEDGE_WRITE","config":{"knowledgeBaseCode":"kb","content":"item"}},
                  {"id":"answer","type":"ANSWER","config":{"template":"done"}}],
                 "edges":[{"from":"write","to":"answer"}]}
                """;
        executor.setSubWorkflowPort((id, version) -> child);
        String graph = """
                {"schemaVersion":2,"entryNodeId":"loop","exitNodeIds":["answer"],"nodes":[
                  {"id":"loop","type":"LOOP","errorPolicy":{"timeoutMs":1000,"strategy":"CONTINUE"},"config":{
                   "collection":"var.items","bodyEntry":"sub","bodyExit":"sub","bodyNodeIds":["sub"]}},
                  {"id":"sub","type":"SUB_WORKFLOW","config":{"workflowId":"child","versionId":1}},
                  {"id":"answer","type":"ANSWER","config":{"template":"continued"}}],
                 "edges":[{"from":"loop","to":"answer"}]}
                """;
        assertUnknownWrite(graph);
    }

    private void assertUnknownWrite(String graph) throws Exception {
        reset(knowledge);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        when(knowledge.writeText(any())).thenAnswer(call -> {
            started.countDown();
            boolean interrupted = false;
            while (true) {
                try {
                    release.await();
                    break;
                } catch (InterruptedException ex) {
                    interrupted = true;
                }
            }
            completed.countDown();
            if (interrupted) Thread.currentThread().interrupt();
            return new RuntimeKnowledgeWriteClient.WriteResult(200, "ok", Map.of());
        });
        executor.setKnowledgeWriteClient(knowledge);
        try {
            var result = executor.execute(graph, Map.of("var", Map.of("items", List.of("one"))),
                    WorkflowExecutionIdentity.fromAgent(1L, "project"));
            assertEquals(0, started.getCount(), "write must have been dispatched before timeout");
            assertFalse(result.success(), result::answer);
            assertEquals("RUNTIME_GRAPH_NODE_TIMEOUT", result.code());
            assertEquals(true, result.metadata().get("nonRecoverableWrite"));
            assertEquals("UNKNOWN", result.metadata().get("dispatchStage"));
            assertEquals(1, completed.getCount(), "the remote write can finish after the timeout");
        } finally {
            release.countDown();
        }
        assertTrue(completed.await(5, TimeUnit.SECONDS));
    }

    @Test
    void readOnlyLoopTimeoutRetainsContinuePolicy() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        when(model.chat(any())).thenAnswer(call -> {
            try {
                release.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return null;
        });
        String graph = """
                {"schemaVersion":2,"entryNodeId":"loop","exitNodeIds":["answer"],"nodes":[
                  {"id":"loop","type":"LOOP","errorPolicy":{"timeoutMs":1000,"strategy":"CONTINUE"},"config":{
                   "collection":"var.items","bodyEntry":"llm","bodyExit":"llm","bodyNodeIds":["llm"]}},
                  {"id":"llm","type":"LLM","config":{"modelInstanceId":"m1","userPrompt":"hi"}},
                  {"id":"answer","type":"ANSWER","config":{"template":"continued"}}],
                 "edges":[{"from":"loop","to":"answer","condition":"always"}]}
                """;
        try {
            var result = executor.execute(graph, Map.of("var", Map.of("items", List.of("one"))),
                    WorkflowExecutionIdentity.fromAgent(1L, "project"));
            assertTrue(result.success(), result::answer);
            assertEquals("continued", result.answer());
            verify(model).chat(any());
        } finally {
            release.countDown();
        }
    }
}
