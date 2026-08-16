package com.enterprise.ai.control.knowledge;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeBizIndexGatewayTest {

    private static final String SECRET = "control-knowledge-gateway-test-secret-32bytes";

    @TempDir
    Path temporaryDirectory;

    @Test
    void signsExactJsonBodyAndReturnsBoundedResponse() throws Exception {
        AtomicReference<CapturedRequest> captured = new AtomicReference<>();
        HttpServer server = server(captured);
        try {
            KnowledgeBizIndexGateway gateway = gateway(server, 1_048_576);
            byte[] body = "{\"query\":\"contract\"}".getBytes(StandardCharsets.UTF_8);

            KnowledgeBizIndexGateway.GatewayResponse response = gateway.exchange(
                    "POST",
                    KnowledgeBizIndexGateway.INTERNAL_ROOT + "/contracts/search",
                    "default",
                    "42",
                    body,
                    "application/json");

            assertEquals(200, response.status());
            assertEquals("{\"ok\":true}", new String(response.body(), StandardCharsets.UTF_8));
            verifySignature(captured.get());
            assertEquals("application/json", captured.get().header("Content-Type"));
            assertEquals("PLATFORM_SESSION",
                    captured.get().header(InternalServiceAuthHeaders.IDENTITY_SOURCE));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void streamsMultipartToTemporaryFileSignsExactBytesAndCleansUp() throws Exception {
        AtomicReference<CapturedRequest> captured = new AtomicReference<>();
        HttpServer server = server(captured);
        try {
            KnowledgeBizIndexGateway gateway = gateway(server, 1_048_576);
            MockMultipartFile attachment = new MockMultipartFile(
                    "attachments",
                    "unsafe\r\nname.txt",
                    "text/plain",
                    "attachment-body".getBytes(StandardCharsets.UTF_8));

            gateway.exchangeMultipart(
                    KnowledgeBizIndexGateway.INTERNAL_ROOT + "/contracts/upsert",
                    "default",
                    "42",
                    "{\"bizId\":\"C-1\",\"fields\":{\"name\":\"demo\"}}",
                    List.of(attachment));

            CapturedRequest request = captured.get();
            verifySignature(request);
            String multipart = new String(request.body(), StandardCharsets.UTF_8);
            assertTrue(multipart.contains("name=\"data\""));
            assertTrue(multipart.contains("attachment-body"));
            assertTrue(!multipart.contains("unsafe\r\nname"));
            try (var files = Files.list(temporaryDirectory)) {
                assertEquals(0, files.count(), "signed multipart temp file must be removed");
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void signsCapabilityVerifiedProjectIdentityForProjectIngress() throws Exception {
        AtomicReference<CapturedRequest> captured = new AtomicReference<>();
        HttpServer server = server(captured);
        try {
            KnowledgeBizIndexGateway gateway = gateway(server, 1_048_576);
            byte[] body = "{\"items\":[]}".getBytes(StandardCharsets.UTF_8);
            String path = KnowledgeBizIndexGateway.PROJECT_INTERNAL_ROOT
                    + "/orders/biz-index/orders_idx/batch";

            gateway.exchangeProject("POST", path, "orders", "credential:3",
                    body, "application/json");

            verifySignature(captured.get());
            assertEquals(InternalServiceAuthHeaders.IDENTITY_SOURCE_PROJECT_CREDENTIAL,
                    captured.get().header(InternalServiceAuthHeaders.IDENTITY_SOURCE));
            assertEquals("orders",
                    captured.get().header(InternalServiceAuthHeaders.IDENTITY_TENANT_ID));
            assertEquals("credential:3",
                    captured.get().header(InternalServiceAuthHeaders.IDENTITY_USER_ID));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsRequestAboveConfiguredLimitBeforeNetworkCall() {
        KnowledgeBizIndexGateway gateway = new KnowledgeBizIndexGateway(
                new InternalServiceAuthSigner(SECRET),
                HttpClient.newHttpClient(),
                "http://127.0.0.1:1",
                temporaryDirectory,
                4,
                1024);

        ResponseStatusException failure = assertThrows(ResponseStatusException.class, () -> gateway.exchange(
                "POST", KnowledgeBizIndexGateway.INTERNAL_ROOT, "default", "42",
                "12345".getBytes(StandardCharsets.UTF_8), "application/json"));

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, failure.getStatusCode());
    }

    private KnowledgeBizIndexGateway gateway(HttpServer server, long maxRequestBytes) {
        return new KnowledgeBizIndexGateway(
                new InternalServiceAuthSigner(SECRET),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                "http://127.0.0.1:" + server.getAddress().getPort(),
                temporaryDirectory,
                maxRequestBytes,
                1_048_576);
    }

    private static HttpServer server(AtomicReference<CapturedRequest> captured) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> capture(exchange, captured));
        server.start();
        return server;
    }

    private static void capture(HttpExchange exchange,
                                AtomicReference<CapturedRequest> captured) throws java.io.IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        captured.set(new CapturedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getRawPath(),
                exchange.getRequestHeaders(),
                body));
        byte[] response = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private static void verifySignature(CapturedRequest request) {
        assertEquals(InternalServiceHmac.bodySha256Hex(request.body()),
                request.header(InternalServiceAuthHeaders.BODY_SHA256));
        String rawPath = request.path();
        String signedPath = rawPath.startsWith("/ai") ? rawPath.substring(3) : rawPath;
        String canonical = InternalServiceHmac.canonical(
                request.method(),
                signedPath,
                request.header(InternalServiceAuthHeaders.CALLER),
                request.header(InternalServiceAuthHeaders.IDENTITY_SOURCE),
                request.header(InternalServiceAuthHeaders.IDENTITY_TENANT_ID),
                request.header(InternalServiceAuthHeaders.IDENTITY_USER_ID),
                request.header(InternalServiceAuthHeaders.TIMESTAMP),
                request.header(InternalServiceAuthHeaders.NONCE),
                request.header(InternalServiceAuthHeaders.BODY_SHA256));
        assertTrue(InternalServiceHmac.verifyConstantTime(
                SECRET, canonical, request.header(InternalServiceAuthHeaders.SIGNATURE)));
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
