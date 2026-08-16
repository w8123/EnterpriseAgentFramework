package com.enterprise.ai.control.context;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonalMemoryKnowledgeErasureClientTest {

    @Test
    void requestsSignedOwnerAggregateAndReturnsNoRawIdentity() throws Exception {
        String secret = "control-knowledge-owner-status-secret-32bytes";
        AtomicReference<CapturedRequest> captured = new AtomicReference<>();
        String response = "{\"runtimeUserHash\":\"" + "a".repeat(64)
                + "\",\"totalCount\":2,\"activeCount\":0,\"deletedCount\":2,"
                + "\"unsafeDeletedVectorCount\":0,\"maxSourceVersion\":9,"
                + "\"latestUpdatedAt\":null,\"projectionErased\":true}";
        HttpServer server = server(response, captured);
        try {
            PersonalMemoryKnowledgeErasureClient client = client(secret, server);

            var result = client.status("tenant-a", "runtime-user-secret");

            assertTrue(result.projectionErased());
            assertEquals(2, result.deletedCount());
            assertFalse(result.runtimeUserHash().contains("runtime-user-secret"));
            CapturedRequest request = captured.get();
            assertTrue(request.path().endsWith("/personal-memories/owner-status"));
            Map<?, ?> body = new ObjectMapper().readValue(request.body(), Map.class);
            assertEquals("runtime-user-secret", body.get("runtimeUserId"));
            assertEquals(InternalServiceHmac.bodySha256Hex(request.body()),
                    request.header(InternalServiceAuthHeaders.BODY_SHA256));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void inconsistentErasureClaimFailsClosed() throws Exception {
        AtomicReference<CapturedRequest> captured = new AtomicReference<>();
        String response = "{\"runtimeUserHash\":\"" + "b".repeat(64)
                + "\",\"totalCount\":1,\"activeCount\":1,\"deletedCount\":0,"
                + "\"unsafeDeletedVectorCount\":0,\"projectionErased\":true}";
        HttpServer server = server(response, captured);
        try {
            PersonalMemoryKnowledgeErasureClient client = client(
                    "control-knowledge-owner-status-secret-32bytes", server);

            assertThrows(IllegalStateException.class,
                    () -> client.status("tenant-a", "runtime-user-secret"));
        } finally {
            server.stop(0);
        }
    }

    private static PersonalMemoryKnowledgeErasureClient client(String secret, HttpServer server) {
        return new PersonalMemoryKnowledgeErasureClient(
                new InternalServiceAuthSigner(secret),
                new ObjectMapper().findAndRegisterModules(),
                "http://127.0.0.1:" + server.getAddress().getPort(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    private static HttpServer server(
            String response,
            AtomicReference<CapturedRequest> captured) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> capture(exchange, captured, response));
        server.start();
        return server;
    }

    private static void capture(
            HttpExchange exchange,
            AtomicReference<CapturedRequest> captured,
            String responseJson) throws java.io.IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        captured.set(new CapturedRequest(
                exchange.getRequestURI().getRawPath(), exchange.getRequestHeaders(), body));
        byte[] response = responseJson.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private record CapturedRequest(
            String path,
            com.sun.net.httpserver.Headers headers,
            byte[] body) {
        String header(String name) {
            return headers.getFirst(name);
        }
    }
}
