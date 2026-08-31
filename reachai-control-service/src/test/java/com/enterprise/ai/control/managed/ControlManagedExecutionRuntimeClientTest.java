package com.enterprise.ai.control.managed;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import com.enterprise.ai.control.managed.ControlManagedExecutionRuntimeClient.CreateRequest;
import com.enterprise.ai.control.managed.ControlManagedExecutionRuntimeClient.GatewayException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ControlManagedExecutionRuntimeClientTest {

    private static final String SECRET =
            "control-runtime-managed-executor-test-secret-32bytes";

    @Test
    void signsTheExactBodyPathTenantAndPlatformActor() throws Exception {
        AtomicReference<CapturedRequest> captured = new AtomicReference<>();
        HttpServer server = server(exchange -> {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            captured.set(new CapturedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getRawPath(),
                    exchange.getRequestHeaders(),
                    requestBody));
            respond(exchange, 200, "application/json", """
                    {"execution":{"executionId":"mex_1","tenantId":"tenant-a",
                    "projectCode":"PROJECT_A","requestedByUserId":"42",
                    "sourceType":"AI_CODING_TASK","sourceRef":"ait_1",
                    "executorProvider":"CODEX","sandboxProfile":"WORKSPACE_PATCH",
                    "acceptanceProfile":"PROJECT_DEFAULT","objectiveSha256":"%s",
                    "status":"QUEUED","cleanupStatus":"PENDING"}}
                    """.formatted("0".repeat(64)).getBytes(StandardCharsets.UTF_8));
        });
        try {
            ControlManagedExecutionRuntimeClient client = client(server);

            var created = client.create(new CreateRequest(
                            "PROJECT_A", "AI_CODING_TASK", "ait_1", "CODEX",
                            "WORKSPACE_PATCH", null, "PROJECT_DEFAULT",
                            "Implement the bounded task", 0, 1_800, 600),
                    "tenant-a", "42");

            assertThat(created.execution().executionId()).isEqualTo("mex_1");
            CapturedRequest request = captured.get();
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.path()).isEqualTo(
                    "/internal/runtime/managed-executions");
            assertThat(request.header(InternalServiceAuthHeaders.IDENTITY_SOURCE))
                    .isEqualTo(InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION);
            assertThat(request.header(InternalServiceAuthHeaders.IDENTITY_TENANT_ID))
                    .isEqualTo("tenant-a");
            assertThat(request.header(InternalServiceAuthHeaders.IDENTITY_USER_ID))
                    .isEqualTo("42");
            assertThat(request.header(InternalServiceAuthHeaders.BODY_SHA256))
                    .isEqualTo(InternalServiceHmac.bodySha256Hex(request.body()));
            String canonical = InternalServiceHmac.canonical(
                    request.method(), request.path(),
                    request.header(InternalServiceAuthHeaders.CALLER),
                    request.header(InternalServiceAuthHeaders.IDENTITY_SOURCE),
                    request.header(InternalServiceAuthHeaders.IDENTITY_TENANT_ID),
                    request.header(InternalServiceAuthHeaders.IDENTITY_USER_ID),
                    request.header(InternalServiceAuthHeaders.TIMESTAMP),
                    request.header(InternalServiceAuthHeaders.NONCE),
                    request.header(InternalServiceAuthHeaders.BODY_SHA256));
            assertThat(InternalServiceHmac.verifyConstantTime(
                    SECRET, canonical,
                    request.header(InternalServiceAuthHeaders.SIGNATURE))).isTrue();
            assertThat(new ObjectMapper().readTree(request.body()).path("objective").asText())
                    .isEqualTo("Implement the bounded task");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void verifiesArtifactDigestBeforeReturningBytes() throws Exception {
        byte[] artifact = "diff --git a/a b/a\n".getBytes(StandardCharsets.UTF_8);
        HttpServer server = server(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/x-diff");
            exchange.getResponseHeaders().add(
                    "Content-Disposition", "attachment; filename=workspace.patch");
            exchange.getResponseHeaders().add(
                    "X-ReachAI-Artifact-SHA256",
                    InternalServiceHmac.bodySha256Hex(artifact));
            exchange.sendResponseHeaders(200, artifact.length);
            exchange.getResponseBody().write(artifact);
            exchange.close();
        });
        try {
            var content = client(server).artifact(
                    "mex_1", "art_patch", "tenant-a", "42");

            assertThat(content.filename()).isEqualTo("workspace.patch");
            assertThat(content.mediaType()).isEqualTo("text/x-diff");
            assertThat(content.bytes()).isEqualTo(artifact);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsTamperedArtifactAndNeverReflectsRuntimeErrorBodies() throws Exception {
        byte[] artifact = "tampered".getBytes(StandardCharsets.UTF_8);
        HttpServer tampered = server(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.getResponseHeaders().add(
                    "X-ReachAI-Artifact-SHA256", "0".repeat(64));
            exchange.sendResponseHeaders(200, artifact.length);
            exchange.getResponseBody().write(artifact);
            exchange.close();
        });
        try {
            assertThatThrownBy(() -> client(tampered).artifact(
                    "mex_1", "art_patch", "tenant-a", "42"))
                    .isInstanceOfSatisfying(GatewayException.class, failure -> {
                        assertThat(failure.code())
                                .isEqualTo("MANAGED_ARTIFACT_INTEGRITY_FAILED");
                        assertThat(failure.getMessage()).doesNotContain("tampered");
                    });
        } finally {
            tampered.stop(0);
        }

        HttpServer failing = server(exchange -> respond(
                exchange,
                500,
                "application/json",
                "{\"code\":\"INTERNAL_FAILURE\",\"message\":\"secret=do-not-reflect\"}"
                        .getBytes(StandardCharsets.UTF_8)));
        try {
            assertThatThrownBy(() -> client(failing).get(
                    "mex_1", "tenant-a", "42"))
                    .isInstanceOfSatisfying(GatewayException.class, failure -> {
                        assertThat(failure.code()).isEqualTo("INTERNAL_FAILURE");
                        assertThat(failure.getMessage())
                                .doesNotContain("do-not-reflect", "secret=");
                    });
        } finally {
            failing.stop(0);
        }
    }

    private ControlManagedExecutionRuntimeClient client(HttpServer server) {
        return new ControlManagedExecutionRuntimeClient(
                new InternalServiceAuthSigner(SECRET),
                new ObjectMapper().findAndRegisterModules(),
                "http://127.0.0.1:" + server.getAddress().getPort(),
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build());
    }

    private HttpServer server(ThrowingHandler handler) throws Exception {
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                handler.handle(exchange);
            } catch (Exception failure) {
                exchange.close();
            }
        });
        server.start();
        return server;
    }

    private static void respond(
            HttpExchange exchange,
            int status,
            String mediaType,
            byte[] body) throws Exception {
        exchange.getResponseHeaders().add("Content-Type", mediaType);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    @FunctionalInterface
    private interface ThrowingHandler {
        void handle(HttpExchange exchange) throws Exception;
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
