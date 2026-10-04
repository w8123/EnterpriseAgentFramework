package com.enterprise.ai.runtime.execution.http;

import com.enterprise.ai.runtime.credential.RuntimeWorkflowCredentialEntity;
import com.enterprise.ai.runtime.credential.RuntimeWorkflowCredentialMapper;
import com.enterprise.ai.runtime.credential.RuntimeWorkflowCredentialCipher;
import com.enterprise.ai.runtime.credential.RuntimeWorkflowCredentialService;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// credential mapper stubs use Mockito when(...)

class WorkflowHttpClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicInteger resolveCalls = new AtomicInteger();
    private HttpServer server;
    private String baseUrl;
    private WorkflowHttpClient client;
    private RuntimeWorkflowCredentialMapper credentialMapper;

    @BeforeEach
    void setUp() throws Exception {
        resolveCalls.set(0);
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        InetAddress decoy = InetAddress.getByName("127.0.0.2");
        WorkflowDnsResolver resolver = host -> {
            int call = resolveCalls.incrementAndGet();
            // If the client re-resolved, the second call would pin the decoy (not listening).
            if (call == 1) {
                return new InetAddress[]{loopback, decoy};
            }
            return new InetAddress[]{decoy, loopback};
        };
        credentialMapper = mock(RuntimeWorkflowCredentialMapper.class);
        RuntimeWorkflowCredentialCipher cipher =
                new RuntimeWorkflowCredentialCipher("test-secret-for-workflow-http-client-unit");
        RuntimeWorkflowCredentialService credentialService = new RuntimeWorkflowCredentialService(
                credentialMapper, cipher, objectMapper);
        client = new WorkflowHttpClient(
                objectMapper,
                WorkflowHttpEgressPolicy.permissiveForTests(resolver),
                credentialService);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void singleDispatchWriteDoesNotFollowActual307OrRetryActual503() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger redirectedWrites = new AtomicInteger();
        AtomicInteger failedWrites = new AtomicInteger();
        WorkflowHttpClient singleClient = new WorkflowHttpClient(objectMapper,
                WorkflowHttpEgressPolicy.permissiveForTests(host -> new InetAddress[]{InetAddress.getByName("127.0.0.1")}),
                null);
        server.createContext("/write-redirect", exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals("{\"note\":\"one\"}", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            writes.incrementAndGet();
            exchange.getResponseHeaders().add("Location", url("/write-again"));
            respond(exchange, 307, "already changed state");
        });
        server.createContext("/write-again", exchange -> { redirectedWrites.incrementAndGet(); respond(exchange, 200, "{}"); });
        server.createContext("/write-failed", exchange -> { failedWrites.incrementAndGet(); respond(exchange, 503, "already changed state"); });
        server.start();
        var redirected = singleClient.execute(new WorkflowHttpClient.HttpExecutionRequest("POST", url("/write-redirect"),
                Map.of(), Map.of("Content-Type", "application/json"), "JSON", "{\"note\":\"one\"}",
                1000, null, WorkflowExecutionIdentity.untrustedDebug(), null, false));
        assertEquals(1, writes.get());
        assertEquals(0, redirectedWrites.get(), "a redirect must never re-dispatch a Console write");
        assertEquals(307, redirected.statusCode(), "retain the real HTTP response, not a pre-dispatch claim");
        assertFalse(redirected.retryableFailure());
        var failed = singleClient.execute(new WorkflowHttpClient.HttpExecutionRequest("POST", url("/write-failed"),
                Map.of(), Map.of(), "JSON", "{}", 1000, null, WorkflowExecutionIdentity.untrustedDebug(), null, false));
        assertEquals(503, failed.statusCode());
        assertEquals(1, failedWrites.get());
        assertFalse(failed.retryableFailure());
    }

    @Test
    void dnsResolvedOncePerRequestEvenWhenResolverWouldReturnDifferentSecondResult() throws Exception {
        server.createContext("/once", exchange -> respond(exchange, 200, "ok"));
        server.start();
        baseUrl = url("/once");

        WorkflowHttpClient.HttpExecutionResult result = client.execute(getRequest(baseUrl, null));
        assertTrue(result.success(), result.code() + " " + result.body());
        assertEquals(1, resolveCalls.get(), "must pin the first resolution; no second getAllByName");
    }

    @Test
    void singleDispatchTimeoutAfterStateChangeHasNoRetryHintOrSecondPost() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        WorkflowHttpClient singleClient = new WorkflowHttpClient(objectMapper, WorkflowHttpEgressPolicy.permissiveForTests(), null);
        server.createContext("/write-timeout", exchange -> {
            assertEquals("POST", exchange.getRequestMethod()); exchange.getRequestBody().readAllBytes(); writes.incrementAndGet();
            try { Thread.sleep(2000); respond(exchange, 200, "{}"); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            catch (IOException ignored) { exchange.close(); } // the caller timed out after the state change
        });
        server.start();
        var result = singleClient.execute(new WorkflowHttpClient.HttpExecutionRequest("POST", url("/write-timeout"),
                Map.of(), Map.of(), "JSON", "{}", 1000, null, WorkflowExecutionIdentity.untrustedDebug(), null, false));
        assertEquals(1, writes.get()); assertEquals(0, result.statusCode()); assertFalse(result.success());
        assertFalse(result.retryableFailure());
    }

    @Test
    void pinnedDnsResolverNeverFallsBackToASecondAddress() throws Exception {
        InetAddress approvedAddress = InetAddress.getByName("127.0.0.1");
        WorkflowHttpClient.PinnedDnsResolver resolver =
                new WorkflowHttpClient.PinnedDnsResolver("api.example.test", approvedAddress);

        assertEquals(approvedAddress, resolver.resolve("API.EXAMPLE.TEST")[0]);
        assertThrows(UnknownHostException.class, () -> resolver.resolve("other.example.test"),
                "the transport must never perform an unrestricted fallback resolution");
    }

    @Test
    void redirectHopResolvesDnsOncePerHop() throws Exception {
        AtomicInteger hops = new AtomicInteger();
        // Use a resolver that always returns the listening loopback, but still counts calls.
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        WorkflowDnsResolver counting = host -> {
            resolveCalls.incrementAndGet();
            return new InetAddress[]{loopback};
        };
        client = new WorkflowHttpClient(
                objectMapper,
                WorkflowHttpEgressPolicy.permissiveForTests(counting),
                new RuntimeWorkflowCredentialService(
                        credentialMapper,
                        new RuntimeWorkflowCredentialCipher("test-secret-for-workflow-http-client-unit"),
                        objectMapper));
        resolveCalls.set(0);
        server.createContext("/r1", exchange -> {
            hops.incrementAndGet();
            exchange.getResponseHeaders().add("Location", url("/r2"));
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/r2", exchange -> respond(exchange, 200, "done"));
        server.start();
        baseUrl = url("/r1");

        WorkflowHttpClient.HttpExecutionResult result = client.execute(getRequest(baseUrl, null));
        assertTrue(result.success(), result.code() + " " + result.body());
        assertEquals(2, resolveCalls.get(), "one DNS resolve per redirect hop");
        assertEquals(1, hops.get());
    }

    @Test
    void bearerCredentialInjected() throws Exception {
        stubCredential("cred_bearer", "BEARER", "GLOBAL", null, null,
                Map.of("token", "secret-marker-bearer"));
        AtomicInteger sawAuth = new AtomicInteger();
        server.createContext("/b", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if ("Bearer secret-marker-bearer".equals(auth)) {
                sawAuth.incrementAndGet();
            }
            respond(exchange, 200, "ok");
        });
        server.start();
        WorkflowHttpClient.HttpExecutionResult result = client.execute(getRequest(url("/b"), "cred_bearer"));
        assertTrue(result.success());
        assertEquals(1, sawAuth.get());
        assertFalse(String.valueOf(result.traceSummary()).contains("secret-marker-bearer"));
        assertFalse(result.code().toLowerCase().contains("secret"));
    }

    @Test
    void basicCredentialInjected() throws Exception {
        stubCredential("cred_basic", "BASIC", "GLOBAL", null, null,
                Map.of("username", "u", "password", "secret-marker-basic"));
        AtomicInteger sawAuth = new AtomicInteger();
        server.createContext("/basic", exchange -> {
            String expected = "Basic " + Base64.getEncoder().encodeToString("u:secret-marker-basic".getBytes(StandardCharsets.UTF_8));
            if (expected.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                sawAuth.incrementAndGet();
            }
            respond(exchange, 200, "ok");
        });
        server.start();
        assertTrue(client.execute(getRequest(url("/basic"), "cred_basic")).success());
        assertEquals(1, sawAuth.get());
    }

    @Test
    void apiKeyHeaderCredentialInjected() throws Exception {
        stubCredential("cred_hdr", "API_KEY_HEADER", "GLOBAL", null, null,
                Map.of("headerName", "X-Api-Key", "apiKey", "secret-marker-hdr"));
        AtomicInteger saw = new AtomicInteger();
        server.createContext("/hdr", exchange -> {
            if ("secret-marker-hdr".equals(exchange.getRequestHeaders().getFirst("X-Api-Key"))) {
                saw.incrementAndGet();
            }
            respond(exchange, 200, "ok");
        });
        server.start();
        assertTrue(client.execute(getRequest(url("/hdr"), "cred_hdr")).success());
        assertEquals(1, saw.get());
    }

    @Test
    void apiKeyQueryCredentialInjected() throws Exception {
        stubCredential("cred_q", "API_KEY_QUERY", "GLOBAL", null, null,
                Map.of("paramName", "api_key", "apiKey", "secret-marker-query"));
        AtomicInteger saw = new AtomicInteger();
        server.createContext("/q", exchange -> {
            if (exchange.getRequestURI().getQuery() != null
                    && exchange.getRequestURI().getQuery().contains("api_key=secret-marker-query")) {
                saw.incrementAndGet();
            }
            respond(exchange, 200, "ok");
        });
        server.start();
        WorkflowHttpClient.HttpExecutionResult result = client.execute(getRequest(url("/q"), "cred_q"));
        assertTrue(result.success());
        assertEquals(1, saw.get());
        assertFalse(result.urlSummary().contains("secret-marker-query"));
    }

    @Test
    void customHeadersCredentialInjected() throws Exception {
        stubCredential("cred_custom", "CUSTOM_HEADERS", "GLOBAL", null, null,
                Map.of("headers", Map.of("X-Custom-Token", "secret-marker-custom")));
        AtomicInteger saw = new AtomicInteger();
        server.createContext("/c", exchange -> {
            if ("secret-marker-custom".equals(exchange.getRequestHeaders().getFirst("X-Custom-Token"))) {
                saw.incrementAndGet();
            }
            respond(exchange, 200, "ok");
        });
        server.start();
        assertTrue(client.execute(getRequest(url("/c"), "cred_custom")).success());
        assertEquals(1, saw.get());
    }

    @Test
    void status400NotRetryable_408And429And5xxRetryable() throws Exception {
        server.createContext("/s400", exchange -> respond(exchange, 400, "bad"));
        server.createContext("/s408", exchange -> respond(exchange, 408, "timeout"));
        server.createContext("/s429", exchange -> respond(exchange, 429, "rate"));
        server.createContext("/s500", exchange -> respond(exchange, 500, "err"));
        server.start();
        assertFalse(client.execute(getRequest(url("/s400"), null)).retryableFailure());
        assertTrue(client.execute(getRequest(url("/s408"), null)).retryableFailure());
        assertTrue(client.execute(getRequest(url("/s429"), null)).retryableFailure());
        assertTrue(client.execute(getRequest(url("/s500"), null)).retryableFailure());
    }

    @Test
    void postDefaultsNotRetryableEvenOn500() throws Exception {
        server.createContext("/post", exchange -> respond(exchange, 500, "err"));
        server.start();
        WorkflowHttpClient.HttpExecutionResult result = client.execute(new WorkflowHttpClient.HttpExecutionRequest(
                "POST", url("/post"), Map.of(), Map.of(), "json", "{}", 5000, null,
                WorkflowExecutionIdentity.untrustedDebug()));
        assertFalse(result.success());
        assertTrue(result.retryableFailure(), "transport marks status retryable");
        // Method-level default no-retry is enforced by RuntimeGraphSpecExecutor, not client flag alone.
        assertFalse(client.isIdempotentMethod("POST"));
    }

    @Test
    void crossOriginCredentialRedirectDenied() throws Exception {
        stubCredential("cred_redir", "BEARER", "GLOBAL", null, null, Map.of("token", "t"));
        server.createContext("/from", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + (server.getAddress().getPort() + 1) + "/to");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        WorkflowHttpClient.HttpExecutionResult result = client.execute(getRequest(url("/from"), "cred_redir"));
        assertFalse(result.success());
        assertEquals("RUNTIME_HTTP_REDIRECT_CREDENTIAL_DENIED", result.code());
    }

    @Test
    void httpsToHttpDowngradeDenied() {
        assertTrue(WorkflowHttpClient.isHttpsToHttpDowngrade(
                java.net.URI.create("https://example.com/a"),
                java.net.URI.create("http://example.com/b")));
        assertFalse(WorkflowHttpClient.isHttpsToHttpDowngrade(
                java.net.URI.create("https://example.com/a"),
                java.net.URI.create("https://example.com/b")));
        assertFalse(WorkflowHttpClient.isHttpsToHttpDowngrade(
                java.net.URI.create("http://example.com/a"),
                java.net.URI.create("http://example.com/b")));
    }

    @Test
    void secretNeverAppearsInErrorOrTraceSummary() throws Exception {
        stubCredential("cred_err", "BEARER", "GLOBAL", null, null, Map.of("token", "secret-marker-err"));
        server.createContext("/err", exchange -> respond(exchange, 500, "response-body-marker"));
        server.start();
        WorkflowHttpClient.HttpExecutionResult result = client.execute(getRequest(url("/err"), "cred_err"));
        assertFalse(result.success());
        String dump = result.code() + result.body() + result.traceSummary() + result.urlSummary();
        assertFalse(dump.contains("secret-marker-err"));
        assertFalse(String.valueOf(result.traceSummary()).contains("response-body-marker"));
    }

    private WorkflowHttpClient.HttpExecutionRequest getRequest(String url, String credentialRef) {
        return new WorkflowHttpClient.HttpExecutionRequest(
                "GET", url, Map.of(), Map.of(), "none", null, 5000, credentialRef,
                WorkflowExecutionIdentity.untrustedDebug());
    }

    private void stubCredential(String ref, String type, String scope, Long projectId, String projectCode,
                                Map<String, Object> secret) throws Exception {
        RuntimeWorkflowCredentialEntity entity = new RuntimeWorkflowCredentialEntity();
        entity.setCredentialRef(ref);
        entity.setName(ref);
        entity.setType(type);
        entity.setScope(scope);
        entity.setStatus("ACTIVE");
        entity.setProjectId(projectId);
        entity.setProjectCode(projectCode);
        entity.setSecretJson(objectMapper.writeValueAsString(secret));
        when(credentialMapper.selectOne(ArgumentMatchers.any())).thenReturn(entity);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

}
