package com.enterprise.ai.runtime.client.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import feign.Feign;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.cloud.openfeign.support.SpringMvcContract;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuntimeKnowledgeRetrievalClientContractTest {

    @Test
    void exposesTheTypedKnowledgeOnlyInternalBoundary() throws Exception {
        FeignClient client = RuntimeKnowledgeRetrievalClient.class.getAnnotation(FeignClient.class);
        Method retrieve = RuntimeKnowledgeRetrievalClient.class.getDeclaredMethod(
                "retrieve", RuntimeKnowledgeRetrievalClient.KnowledgeRetrievalRequest.class);

        assertEquals("reachai-knowledge-service", client.name());
        assertEquals("${services.knowledge-service.url:http://localhost:18602}", client.url());
        assertEquals("/ai/internal/knowledge/retrieval/query",
                retrieve.getAnnotation(PostMapping.class).value()[0]);
        assertEquals(RuntimeKnowledgeRetrievalClient.KnowledgeRetrievalResult.class,
                retrieve.getReturnType());

        RuntimeKnowledgeRetrievalClient.KnowledgeRetrievalData noEvidence =
                new RuntimeKnowledgeRetrievalClient.KnowledgeRetrievalData(
                        "query", List.of(), 0, "NO_EVIDENCE", true, java.util.Map.of());
        assertEquals("NO_EVIDENCE", noEvidence.getOutcome());
        assertEquals(true, noEvidence.getEmpty());
    }

    @Test
    void realFeignRequestReachesKnowledgeServletContextAndPreservesTypedResult() throws Exception {
        ObjectMapper json = new ObjectMapper();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<RuntimeKnowledgeRetrievalClient.KnowledgeRetrievalRequest> captured = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ai/internal/knowledge/retrieval/query", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            captured.set(json.readValue(exchange.getRequestBody(), RuntimeKnowledgeRetrievalClient.KnowledgeRetrievalRequest.class));
            byte[] body = json.writeValueAsBytes(new RuntimeKnowledgeRetrievalClient.KnowledgeRetrievalResult(
                    200, "ok", new RuntimeKnowledgeRetrievalClient.KnowledgeRetrievalData(
                    "public fixture", List.of(), 0, "NO_EVIDENCE", true, java.util.Map.of())));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            RuntimeKnowledgeRetrievalClient client = Feign.builder()
                    .contract(new SpringMvcContract())
                    .encoder((object, type, template) -> {
                        try { template.body(json.writeValueAsBytes(object), StandardCharsets.UTF_8); }
                        catch (Exception ex) { throw new IllegalStateException(ex); }
                    })
                    .decoder((response, type) -> json.readValue(response.body().asInputStream(), json.constructType(type)))
                    .target(RuntimeKnowledgeRetrievalClient.class, "http://127.0.0.1:" + server.getAddress().getPort());
            var result = client.retrieve(RuntimeKnowledgeRetrievalClient.KnowledgeRetrievalRequest.builder()
                    .query("public fixture").knowledgeBaseCodes(List.of("public-fixture")).userId("fixture-user")
                    .topK(5).searchMode("keyword").rerankEnabled(false).build());
            assertEquals("/ai/internal/knowledge/retrieval/query", path.get());
            assertEquals("fixture-user", captured.get().getUserId());
            assertEquals(List.of("public-fixture"), captured.get().getKnowledgeBaseCodes());
            assertEquals(200, result.getCode());
            assertEquals("NO_EVIDENCE", result.getData().getOutcome());
            assertEquals(0, result.getData().getHitCount());
        } finally { server.stop(0); }
    }
}
