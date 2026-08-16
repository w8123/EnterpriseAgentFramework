package com.enterprise.ai.control.knowledge;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityProjectRequestVerificationGatewayTest {

    private static final String SECRET = "control-capability-verification-secret-32bytes";

    @Test
    void signsVerificationRequestAndDoesNotExposeCredentialSecret() throws Exception {
        AtomicReference<CapturedRequest> captured = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> capture(exchange, captured));
        server.start();
        try {
            CapabilityProjectRequestVerificationGateway gateway =
                    new CapabilityProjectRequestVerificationGateway(
                            new InternalServiceAuthSigner(SECRET),
                            new ObjectMapper(),
                            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                            "http://127.0.0.1:" + server.getAddress().getPort());

            var verified = gateway.verify(new CapabilityProjectRequestVerificationGateway.ProjectRequest(
                    "orders", "rak_orders", "POST",
                    "/api/knowledge-ingress/projects/orders/biz-index/orders_idx/batch",
                    "1700000000000", "external-nonce", "a".repeat(64), "b".repeat(64)));

            assertEquals("orders", verified.projectCode());
            assertEquals("credential:3", verified.internalActorId());
            CapturedRequest request = captured.get();
            assertEquals(CapabilityProjectRequestVerificationGateway.VERIFY_PATH, request.path());
            assertEquals(InternalServiceAuthHeaders.IDENTITY_SOURCE_PROJECT_CREDENTIAL_VERIFICATION,
                    request.header(InternalServiceAuthHeaders.IDENTITY_SOURCE));
            assertEquals("orders", request.header(InternalServiceAuthHeaders.IDENTITY_TENANT_ID));
            assertEquals("rak_orders", request.header(InternalServiceAuthHeaders.IDENTITY_USER_ID));
            assertEquals(InternalServiceHmac.bodySha256Hex(request.body()),
                    request.header(InternalServiceAuthHeaders.BODY_SHA256));
            String canonical = InternalServiceHmac.canonical(
                    "POST", request.path(),
                    request.header(InternalServiceAuthHeaders.CALLER),
                    request.header(InternalServiceAuthHeaders.IDENTITY_SOURCE),
                    request.header(InternalServiceAuthHeaders.IDENTITY_TENANT_ID),
                    request.header(InternalServiceAuthHeaders.IDENTITY_USER_ID),
                    request.header(InternalServiceAuthHeaders.TIMESTAMP),
                    request.header(InternalServiceAuthHeaders.NONCE),
                    request.header(InternalServiceAuthHeaders.BODY_SHA256));
            assertTrue(InternalServiceHmac.verifyConstantTime(
                    SECRET, canonical, request.header(InternalServiceAuthHeaders.SIGNATURE)));
            JsonNode body = new ObjectMapper().readTree(request.body());
            assertTrue(!body.has("appSecret"));
        } finally {
            server.stop(0);
        }
    }

    private static void capture(HttpExchange exchange,
                                AtomicReference<CapturedRequest> captured) throws java.io.IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        captured.set(new CapturedRequest(
                exchange.getRequestURI().getRawPath(), exchange.getRequestHeaders(), body));
        byte[] response = ("{\"verified\":true,\"projectId\":9,\"projectCode\":\"orders\"," +
                "\"credentialId\":3,\"appKeyHash\":\"hash\"}")
                .getBytes(StandardCharsets.UTF_8);
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
