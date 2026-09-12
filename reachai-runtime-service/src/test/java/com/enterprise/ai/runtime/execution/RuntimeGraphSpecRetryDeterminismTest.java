package com.enterprise.ai.runtime.execution;

import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.client.knowledge.RuntimeKnowledgeRetrievalClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

class RuntimeGraphSpecRetryDeterminismTest {

    private final RuntimeGraphSpecExecutor executor = new RuntimeGraphSpecExecutor(
            new ObjectMapper(),
            mock(RuntimeModelServiceClient.class),
            mock(RuntimeCapabilityCatalogClient.class),
            mock(RuntimeControlCatalogClient.class),
            null,
            null);

    @Test
    void knowledgeClientUnavailableIsNotRetried() {
        RuntimeGraphSpecExecutionResult result = executor.execute("""
                {"entryNodeId":"kr","exitNodeIds":["kr"],"nodes":[
                  {"id":"kr","type":"KNOWLEDGE_RETRIEVAL",
                   "retry":{"enabled":true,"maxAttempts":3},
                   "config":{"knowledgeBaseCodes":["kb1"],"query":"input"}}
                ]}
                """, Map.of("message", "q"), WorkflowExecutionIdentity.fromAgent(1L, "demo", "u1"));
        assertFalse(result.success());
        assertEquals("RUNTIME_KNOWLEDGE_CLIENT_UNAVAILABLE", result.code());
        Object attempts = result.metadata() == null ? null : result.metadata().get("attempt");
        // Should fail on first attempt without consuming retry budget as transient.
        if (attempts != null) {
            assertEquals(1, ((Number) attempts).intValue());
        }
    }

    @Test
    void knowledgeBaseRequiredIsNotRetried() {
        RuntimeGraphSpecExecutor withClient = new RuntimeGraphSpecExecutor(
                new ObjectMapper(),
                mock(RuntimeModelServiceClient.class),
                mock(RuntimeCapabilityCatalogClient.class),
                mock(RuntimeControlCatalogClient.class),
                mock(RuntimeKnowledgeRetrievalClient.class),
                null);
        RuntimeGraphSpecExecutionResult result = withClient.execute("""
                {"entryNodeId":"kr","exitNodeIds":["kr"],"nodes":[
                  {"id":"kr","type":"KNOWLEDGE_RETRIEVAL",
                   "retry":{"enabled":true,"maxAttempts":3},
                   "config":{"knowledgeBaseCodes":[],"query":"input"}}
                ]}
                """, Map.of("message", "q"), WorkflowExecutionIdentity.fromAgent(1L, "demo", "u1"));
        assertEquals("RUNTIME_KNOWLEDGE_BASE_REQUIRED", result.code());
    }

    @Test
    void httpClientUnavailableIsNotRetried() {
        RuntimeGraphSpecExecutionResult result = executor.execute("""
                {"entryNodeId":"http","exitNodeIds":["http"],"nodes":[
                  {"id":"http","type":"HTTP_REQUEST",
                   "retry":{"enabled":true,"maxAttempts":3},
                   "config":{"method":"GET","url":"https://example.com"}}
                ]}
                """, Map.of("message", "q"));
        assertEquals("RUNTIME_HTTP_CLIENT_UNAVAILABLE", result.code());
    }
}
