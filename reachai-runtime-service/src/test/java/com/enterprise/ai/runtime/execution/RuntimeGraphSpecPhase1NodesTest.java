package com.enterprise.ai.runtime.execution;

import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.client.knowledge.RuntimeKnowledgeRetrievalClient;
import com.enterprise.ai.runtime.client.knowledge.RuntimeKnowledgeRetrievalClient.KnowledgeHit;
import com.enterprise.ai.runtime.client.knowledge.RuntimeKnowledgeRetrievalClient.KnowledgeRetrievalData;
import com.enterprise.ai.runtime.client.knowledge.RuntimeKnowledgeRetrievalClient.KnowledgeRetrievalRequest;
import com.enterprise.ai.runtime.client.knowledge.RuntimeKnowledgeRetrievalClient.KnowledgeRetrievalResult;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.enterprise.ai.runtime.credential.RuntimeWorkflowCredentialRuntime;
import com.enterprise.ai.runtime.credential.RuntimeWorkflowCredentialService;
import com.enterprise.ai.runtime.execution.http.WorkflowHttpClient;
import com.enterprise.ai.runtime.execution.http.WorkflowHttpEgressPolicy;
import com.enterprise.ai.runtime.execution.identity.WorkflowExecutionIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeGraphSpecPhase1NodesTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RuntimeKnowledgeRetrievalClient knowledgeClient;
    private RuntimeWorkflowCredentialService credentialService;
    private WorkflowHttpClient httpClient;
    private RuntimeGraphSpecExecutor executor;
    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        knowledgeClient = mock(RuntimeKnowledgeRetrievalClient.class);
        credentialService = mock(RuntimeWorkflowCredentialService.class);
        httpClient = new WorkflowHttpClient(
                objectMapper,
                WorkflowHttpEgressPolicy.permissiveForTests(),
                credentialService);
        executor = new RuntimeGraphSpecExecutor(
                objectMapper,
                mock(RuntimeModelServiceClient.class),
                mock(RuntimeCapabilityCatalogClient.class),
                mock(RuntimeControlCatalogClient.class),
                knowledgeClient,
                httpClient);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/orders", exchange -> {
            byte[] body = "{\"status\":\"OK\",\"id\":42}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void scenarioA_deterministicTransformChain() {
        RuntimeGraphSpecExecutionResult result = executor.execute("""
                {
                  "entryNodeId":"input",
                  "exitNodeIds":["answer"],
                  "nodes":[
                    {"id":"input","type":"USER_INPUT"},
                    {"id":"assign","type":"VARIABLE_ASSIGN","config":{
                      "assignments":{"customer":"input","flag":true},
                      "outputAlias":"assign_out"
                    }},
                    {"id":"template","type":"TEMPLATE","config":{
                      "template":"hello {{ var.customer }}",
                      "outputAlias":"tpl"
                    }},
                    {"id":"agg","type":"VARIABLE_AGGREGATOR","config":{
                      "aggregateMode":"object",
                      "items":[{"name":"msg","source":"var.tpl"},{"name":"flag","source":"var.customer"}],
                      "outputAlias":"bundle"
                    }},
                    {"id":"answer","type":"ANSWER","config":{"template":"{{ var.bundle.msg }}"}}
                  ],
                  "edges":[
                    {"from":"input","to":"assign","condition":"always"},
                    {"from":"assign","to":"template","condition":"always"},
                    {"from":"template","to":"agg","condition":"always"},
                    {"from":"agg","to":"answer","condition":"always"}
                  ]
                }
                """, Map.of("message", "alice"));

        assertTrue(result.success(), result.code() + " / " + result.answer());
        assertEquals("hello alice", result.answer());
        assertEquals("alice", result.resumeCheckpoint().get("var.customer"));
        assertEquals("hello alice", result.resumeCheckpoint().get("var.tpl"));
        assertTrue(result.resumeCheckpoint().get("var.bundle") instanceof Map<?, ?>);
    }

    @Test
    void scenarioB_knowledgeRetrieval() {
        when(knowledgeClient.retrieve(any(KnowledgeRetrievalRequest.class))).thenReturn(new KnowledgeRetrievalResult(
                0,
                "ok",
                new KnowledgeRetrievalData(
                        "refund policy",
                        List.of(KnowledgeHit.builder()
                                .id("c1")
                                .chunkId("c1")
                                .knowledgeBaseCode("kb_demo")
                                .title("policy.md")
                                .source("policy.md")
                                .content("refund within 7 days")
                                .score(0.91f)
                                .metadata(Map.of("fileId", "f1"))
                                .build()),
                        1)));

        RuntimeGraphSpecExecutionResult result = executor.execute("""
                {
                  "entryNodeId":"kr",
                  "exitNodeIds":["answer"],
                  "nodes":[
                    {"id":"kr","type":"KNOWLEDGE_RETRIEVAL","config":{
                      "knowledgeBaseCodes":["kb_demo"],
                      "query":"{{ input }}",
                      "topK":3,
                      "outputAlias":"hits"
                    }},
                    {"id":"answer","type":"ANSWER","config":{"template":"count={{ var.hits.hitCount }}"}}
                  ],
                  "edges":[{"from":"kr","to":"answer","condition":"always"}]
                }
                """, Map.of("message", "refund policy", "userId", "forged-user"),
                WorkflowExecutionIdentity.fromAgent(1L, "demo", "u1"));

        assertTrue(result.success(), result.code() + " / " + result.answer());
        assertEquals("count=1", result.answer());
        assertTrue(result.resumeCheckpoint().get("var.hits") instanceof Map<?, ?>);
    }

    @Test
    void scenarioC_httpGetWithCredentialAndIfElse() {
        when(credentialService.resolve(eq("cred_demo"), any(WorkflowExecutionIdentity.class)))
                .thenReturn(Optional.of(new RuntimeWorkflowCredentialRuntime(
                        "cred_demo", "demo", "BEARER", Map.of("token", "secret-token"))));

        RuntimeGraphSpecExecutionResult result = executor.execute("""
                {
                  "entryNodeId":"http",
                  "exitNodeIds":["answer"],
                  "nodes":[
                    {"id":"http","type":"HTTP_REQUEST","config":{
                      "method":"GET",
                      "url":"%s/orders",
                      "credentialRef":"cred_demo",
                      "timeoutMs":5000,
                      "outputAlias":"http_out"
                    }},
                    {"id":"branch","type":"IF_ELSE","config":{
                      "conditionGroups":[{"id":"ok","conditions":[
                        {"left":"var.http_out.statusCode","operator":"equals","right":200}
                      ]}],
                      "defaultRoute":"else"
                    }},
                    {"id":"answer","type":"ANSWER","config":{"template":"status={{ var.http_out.statusCode }}"}}
                  ],
                  "edges":[
                    {"from":"http","to":"branch","condition":"always"},
                    {"from":"branch","to":"answer","condition":"route:ok"},
                    {"from":"branch","to":"answer","condition":"route:else"}
                  ]
                }
                """.formatted(baseUrl), Map.of("message", "x", "projectCode", "forged-project"),
                WorkflowExecutionIdentity.fromAgent(1L, "demo"));

        assertTrue(result.success(), result.code() + " / " + result.answer());
        assertEquals("status=200", result.answer());
        Object summary = result.steps().isEmpty() ? null : result.metadata();
        assertFalse(String.valueOf(summary).toLowerCase().contains("secret-token"));
    }

    @Test
    void scenarioD_errorPolicyContinueAndFallback() {
        RuntimeGraphSpecExecutionResult continued = executor.execute("""
                {
                  "entryNodeId":"http",
                  "exitNodeIds":["answer"],
                  "nodes":[
                    {"id":"http","type":"HTTP_REQUEST",
                     "retry":{"enabled":false},
                     "errorPolicy":{"strategy":"CONTINUE","defaultOutput":{"statusCode":0,"body":"fallback-body"}},
                     "config":{"method":"GET","url":"http://127.0.0.1:1/nope","timeoutMs":1000,"outputAlias":"http_out"}},
                    {"id":"answer","type":"ANSWER","config":{"template":"{{ var.http_out.body }}"}}
                  ],
                  "edges":[{"from":"http","to":"answer","condition":"always"}]
                }
                """, Map.of("message", "x"));
        assertTrue(continued.success(), continued.code());
        assertEquals("fallback-body", continued.answer());
        Object httpOut = continued.resumeCheckpoint().get("var.http_out");
        assertTrue(httpOut instanceof Map<?, ?>);
        assertEquals("fallback-body", ((Map<?, ?>) httpOut).get("body"));

        RuntimeGraphSpecExecutionResult fallback = executor.execute("""
                {
                  "entryNodeId":"bad",
                  "exitNodeIds":["rescue","answer"],
                  "nodes":[
                    {"id":"bad","type":"TEMPLATE",
                     "errorPolicy":{"strategy":"FALLBACK","fallbackNodeId":"rescue"},
                     "config":{"template":""}},
                    {"id":"rescue","type":"ANSWER","config":{"template":"rescued"}},
                    {"id":"answer","type":"ANSWER","config":{"template":"should-not"}}
                  ],
                  "edges":[
                    {"from":"bad","to":"answer","condition":"always"}
                  ]
                }
                """, Map.of("message", "x"));
        assertTrue(fallback.success(), fallback.code());
        assertEquals("rescued", fallback.answer());
    }

    @Test
    void scenarioE_egressRejectsMetadataAndNonHttp() {
        RuntimeGraphSpecExecutionResult metadata = executor.execute("""
                {"entryNodeId":"http","exitNodeIds":["http"],"nodes":[{"id":"http","type":"HTTP_REQUEST","config":{
                  "method":"GET","url":"http://169.254.169.254/latest/meta-data/"
                }}]}
                """, Map.of("message", "x"));
        assertFalse(metadata.success());
        assertTrue(metadata.code().contains("EGRESS") || metadata.code().contains("HTTP"));

        RuntimeGraphSpecExecutionResult scheme = executor.execute("""
                {"entryNodeId":"http","exitNodeIds":["http"],"nodes":[{"id":"http","type":"HTTP_REQUEST","config":{
                  "method":"GET","url":"file:///etc/passwd"
                }}]}
                """, Map.of("message", "x"));
        assertFalse(scheme.success());
        assertEquals("RUNTIME_HTTP_EGRESS_SCHEME_DENIED", scheme.code());
    }

    @Test
    void retrySucceedsOnIdempotentGet() throws IOException {
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/flaky", exchange -> {
            int n = hits.incrementAndGet();
            if (n == 1) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/flaky";
        RuntimeGraphSpecExecutionResult result = executor.execute("""
                {
                  "entryNodeId":"http",
                  "exitNodeIds":["answer"],
                  "nodes":[
                    {"id":"http","type":"HTTP_REQUEST",
                     "retry":{"enabled":true,"maxAttempts":3,"backoffMs":1},
                     "config":{"method":"GET","url":"%s","outputAlias":"http_out"}},
                    {"id":"answer","type":"ANSWER","config":{"template":"done"}}
                  ],
                  "edges":[{"from":"http","to":"answer","condition":"always"}]
                }
                """.formatted(url), Map.of("message", "yes"));
        assertTrue(result.success());
        assertEquals(2, hits.get());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> traces = (List<Map<String, Object>>) result.metadata().get("workflowNodeTraces");
        assertNotNull(traces);
        Map<String, Object> httpTrace = traces.stream()
                .filter(item -> "http".equals(item.get("nodeId")))
                .findFirst()
                .orElseThrow();
        assertEquals(2, ((Number) httpTrace.get("attempt")).intValue());
        assertEquals("SUCCESS", httpTrace.get("status"));
    }

    @Test
    void httpGet400DoesNotRetry() throws IOException {
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/bad", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
        });
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/bad";
        RuntimeGraphSpecExecutionResult result = executor.execute("""
                {"entryNodeId":"http","exitNodeIds":["http"],"nodes":[{"id":"http","type":"HTTP_REQUEST",
                  "retry":{"enabled":true,"maxAttempts":3,"backoffMs":1},
                  "config":{"method":"GET","url":"%s"}}]}
                """.formatted(url), Map.of("message", "x"));
        assertFalse(result.success());
        assertEquals(1, hits.get());
        assertEquals("RUNTIME_HTTP_STATUS_400", result.code());
    }

    @Test
    void http200WithNon200BusinessCodeFailsWithoutRetry() throws IOException {
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/business-failure", exchange -> {
            hits.incrementAndGet();
            byte[] body = "{\"code\":\"500\",\"success\":false,\"message\":\"无权查看班组\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/business-failure";

        RuntimeGraphSpecExecutionResult result = executor.execute("""
                {"entryNodeId":"http","exitNodeIds":["http"],"nodes":[
                  {"id":"http","type":"HTTP_REQUEST",
                   "retry":{"enabled":true,"maxAttempts":3,"backoffMs":1},
                   "config":{"method":"GET","url":"%s"}}
                ]}
                """.formatted(url), Map.of("message", "x"));

        assertFalse(result.success());
        assertEquals("RUNTIME_HTTP_BUSINESS_RESPONSE_FAILED", result.code());
        assertEquals("查询失败：无权查看班组", result.answer());
        assertEquals("500", result.metadata().get("businessCode"));
        assertEquals(1, hits.get());
    }

    @Test
    void httpPost500DoesNotRetryByDefault() throws IOException {
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/post-flaky", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/post-flaky";
        RuntimeGraphSpecExecutionResult result = executor.execute("""
                {"entryNodeId":"http","exitNodeIds":["http"],"nodes":[{"id":"http","type":"HTTP_REQUEST",
                  "retry":{"enabled":true,"maxAttempts":3,"backoffMs":1},
                  "config":{"method":"POST","url":"%s","bodyType":"json","body":"{}"}}]}
                """.formatted(url), Map.of("message", "x"));
        assertFalse(result.success());
        assertEquals(1, hits.get());
    }
}
