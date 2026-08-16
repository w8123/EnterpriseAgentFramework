package com.enterprise.ai.control.client.runtime;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeSessionRetentionGatewayTest {

    @Test
    void signsTheExactEncodedPathTenantActorAndBodyBytes() throws Exception {
        String secret = "control-runtime-retention-gateway-secret-32bytes";
        AtomicReference<CapturedRequest> captured = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> capture(exchange, captured));
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            RuntimeSessionRetentionGateway gateway = new RuntimeSessionRetentionGateway(
                    new InternalServiceAuthSigner(secret),
                    new ObjectMapper().findAndRegisterModules(),
                    baseUrl,
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());

            var response = gateway.setLegalHold(
                    "tenant-a", "session-a", true, "LEGAL_CASE", "CASE-9", "42");

            assertEquals(200, response.getStatusCode().value());
            CapturedRequest request = captured.get();
            assertEquals("PUT", request.method());
            assertTrue(request.path().endsWith("/tenants/tenant-a/sessions/session-a/legal-hold"));
            assertEquals(InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION,
                    request.header(InternalServiceAuthHeaders.IDENTITY_SOURCE));
            assertEquals("tenant-a", request.header(InternalServiceAuthHeaders.IDENTITY_TENANT_ID));
            assertEquals("42", request.header(InternalServiceAuthHeaders.IDENTITY_USER_ID));
            assertEquals(InternalServiceHmac.bodySha256Hex(request.body()),
                    request.header(InternalServiceAuthHeaders.BODY_SHA256));
            String canonical = InternalServiceHmac.canonical(
                    request.method(),
                    request.path(),
                    request.header(InternalServiceAuthHeaders.CALLER),
                    request.header(InternalServiceAuthHeaders.IDENTITY_SOURCE),
                    request.header(InternalServiceAuthHeaders.IDENTITY_TENANT_ID),
                    request.header(InternalServiceAuthHeaders.IDENTITY_USER_ID),
                    request.header(InternalServiceAuthHeaders.TIMESTAMP),
                    request.header(InternalServiceAuthHeaders.NONCE),
                    request.header(InternalServiceAuthHeaders.BODY_SHA256));
            assertTrue(InternalServiceHmac.verifyConstantTime(
                    secret, canonical, request.header(InternalServiceAuthHeaders.SIGNATURE)));
            Map<?, ?> body = new ObjectMapper().readValue(request.body(), Map.class);
            assertEquals(Boolean.TRUE, body.get("enabled"));
            assertEquals("LEGAL_CASE", body.get("reasonCode"));
            assertFalse(body.containsKey("actorId"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void ownerEraseKeepsRawUserOutOfTheUrlAndBindsItToTheSignedBody() throws Exception {
        String secret = "control-runtime-retention-gateway-secret-32bytes";
        AtomicReference<CapturedRequest> captured = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> capture(exchange, captured));
        server.start();
        try {
            RuntimeSessionRetentionGateway gateway = new RuntimeSessionRetentionGateway(
                    new InternalServiceAuthSigner(secret),
                    new ObjectMapper().findAndRegisterModules(),
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());

            var response = gateway.eraseOwner(
                    "tenant-a", "runtime-user-secret", "PRIVACY_REQUEST", "REQ-11", 40, "42");

            assertEquals(200, response.getStatusCode().value());
            CapturedRequest request = captured.get();
            assertEquals("POST", request.method());
            assertTrue(request.path().endsWith("/tenants/tenant-a/owner-erasure"));
            assertFalse(request.path().contains("runtime-user-secret"));
            Map<?, ?> body = new ObjectMapper().readValue(request.body(), Map.class);
            assertEquals("runtime-user-secret", body.get("runtimeUserId"));
            assertEquals("PRIVACY_REQUEST", body.get("reasonCode"));
            assertEquals(40, body.get("batchSize"));
            assertEquals(InternalServiceHmac.bodySha256Hex(request.body()),
                    request.header(InternalServiceAuthHeaders.BODY_SHA256));
            String canonical = InternalServiceHmac.canonical(
                    request.method(), request.path(),
                    request.header(InternalServiceAuthHeaders.CALLER),
                    request.header(InternalServiceAuthHeaders.IDENTITY_SOURCE),
                    request.header(InternalServiceAuthHeaders.IDENTITY_TENANT_ID),
                    request.header(InternalServiceAuthHeaders.IDENTITY_USER_ID),
                    request.header(InternalServiceAuthHeaders.TIMESTAMP),
                    request.header(InternalServiceAuthHeaders.NONCE),
                    request.header(InternalServiceAuthHeaders.BODY_SHA256));
            assertTrue(InternalServiceHmac.verifyConstantTime(
                    secret, canonical, request.header(InternalServiceAuthHeaders.SIGNATURE)));
        } finally {
            server.stop(0);
        }
    }

    private static void capture(
            HttpExchange exchange,
            AtomicReference<CapturedRequest> captured) throws java.io.IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        captured.set(new CapturedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getRawPath(),
                exchange.getRequestHeaders(),
                body));
        byte[] response = "{\"legalHold\":true}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private record CapturedRequest(
            String method,
            String path,
            com.sun.net.httpserver.Headers headers,
            byte[] body) {

        String header(String name) {
            return headers.getFirst(name);
        }
    }
}
