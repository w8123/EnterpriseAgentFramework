package com.enterprise.ai.runtime.client.knowledge;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuntimeKnowledgeRetrievalClientContractTest {

    @Test
    void exposesTheTypedKnowledgeOnlyInternalBoundary() throws Exception {
        FeignClient client = RuntimeKnowledgeRetrievalClient.class.getAnnotation(FeignClient.class);
        Method retrieve = RuntimeKnowledgeRetrievalClient.class.getDeclaredMethod(
                "retrieve", RuntimeKnowledgeRetrievalClient.KnowledgeRetrievalRequest.class);

        assertEquals("reachai-knowledge-service", client.name());
        assertEquals("${services.knowledge-service.url:http://localhost:18602}", client.url());
        assertEquals("/internal/knowledge/retrieval/query",
                retrieve.getAnnotation(PostMapping.class).value()[0]);
        assertEquals(RuntimeKnowledgeRetrievalClient.KnowledgeRetrievalResult.class,
                retrieve.getReturnType());

        RuntimeKnowledgeRetrievalClient.KnowledgeRetrievalData noEvidence =
                new RuntimeKnowledgeRetrievalClient.KnowledgeRetrievalData(
                        "query", List.of(), 0, "NO_EVIDENCE", true, java.util.Map.of());
        assertEquals("NO_EVIDENCE", noEvidence.getOutcome());
        assertEquals(true, noEvidence.getEmpty());
    }
}
