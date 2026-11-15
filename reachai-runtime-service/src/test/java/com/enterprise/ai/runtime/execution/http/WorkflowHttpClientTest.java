package com.enterprise.ai.runtime.execution.http;

import com.enterprise.ai.runtime.credential.RuntimeWorkflowCredentialEntity;
import com.enterprise.ai.runtime.credential.RuntimeWorkflowCredentialMapper;
import com.enterprise.ai.runtime.credential.RuntimeWorkflowCredentialCipher;
import com.enterprise.ai.runtime.credential.RuntimeWorkflowCredentialService;
import com.enterprise.ai.runtime.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient.EmployeeBusinessTokenResolution;
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
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
        assertEquals("RUNTIME_HTTP_TIMEOUT", result.code());
        assertEquals("SocketTimeoutException", result.transportExceptionClass());
        assertTrue(WorkflowHttpClient.safeTransportFailureSummary(result).contains("RUNTIME_HTTP_TIMEOUT"));
    }

    @Test
    void actualTlsHandshakeFailureHasOnlySafeTransportFactsAndDoesNotRetry() throws Exception {
        String secret = "private-tls-token-marker";
        AtomicInteger connections = new AtomicInteger();
        var responder = Executors.newSingleThreadExecutor();
        try (ServerSocket peer = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            var sent = responder.submit(() -> {
                try (Socket socket = peer.accept()) {
                    connections.incrementAndGet();
                    socket.setSoTimeout(5_000);
                    socket.getInputStream().read(new byte[4096]);
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nX-Token: "
                            + secret + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                }
                return null;
            });
            var secureClient = new WorkflowHttpClient(objectMapper, WorkflowHttpEgressPolicy.permissiveForTests(), null);
            var result = secureClient.execute(new WorkflowHttpClient.HttpExecutionRequest("GET",
                    "https://127.0.0.1:" + peer.getLocalPort() + "/private-path?token=" + secret,
                    Map.of(), Map.of("Authorization", "Bearer " + secret), "NONE", null, 5_000,
                    null, WorkflowExecutionIdentity.untrustedDebug(), null, false));
            sent.get(5, TimeUnit.SECONDS);

            assertFalse(result.success());
            assertEquals(0, result.statusCode());
            assertEquals("RUNTIME_HTTP_TLS_FAILED", result.code());
            assertTrue(List.of("SSLException", "SSLHandshakeException", "SSLProtocolException")
                    .contains(result.transportExceptionClass()));
            assertEquals("HTTP TLS connection failed", result.body());
            assertFalse(result.retryableFailure());
            assertEquals(1, connections.get());
            String diagnostic = WorkflowHttpClient.safeTransportFailureSummary(result);
            assertTrue(diagnostic.contains("RUNTIME_HTTP_TLS_FAILED"));
            assertTrue(diagnostic.contains("exceptionClass=SSL"));
            assertFalse(diagnostic.contains(secret));
            assertFalse(diagnostic.contains("127.0.0.1"));
            assertFalse(diagnostic.contains("private-path"));
            assertFalse(diagnostic.contains("Authorization"));
        } finally {
            responder.shutdownNow();
        }
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
    void queryValuesAreEncodedExactlyOnce() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> rawQuery = new java.util.concurrent.atomic.AtomicReference<>();
        server.createContext("/page", exchange -> {
            rawQuery.set(exchange.getRequestURI().getRawQuery());
            respond(exchange, 200, "ok");
        });
        server.start();
        WorkflowHttpClient.HttpExecutionResult result = client.execute(new WorkflowHttpClient.HttpExecutionRequest(
                "GET", url("/page?keep=a%3Ab"), new java.util.LinkedHashMap<>(Map.of(
                        "createTime", "2020-01-01 00:00:00,2030-12-31 23:59:59")),
                Map.of(), "none", null, 5000, null, WorkflowExecutionIdentity.untrustedDebug()));
        assertTrue(result.success());
        assertEquals("keep=a%3Ab&createTime=2020-01-01+00%3A00%3A00%2C2030-12-31+23%3A59%3A59", rawQuery.get());
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

    @Test
    void signsInForATokenReusesItAndSignsInAgainWhenTheSystemSaysItExpired() throws Exception {
        WorkflowHttpClient loginClient = new WorkflowHttpClient(objectMapper,
                WorkflowHttpEgressPolicy.permissiveForTests(host -> new InetAddress[]{InetAddress.getByName("127.0.0.1")}),
                new RuntimeWorkflowCredentialService(credentialMapper,
                        new RuntimeWorkflowCredentialCipher("test-secret-for-workflow-http-client-unit"), objectMapper));
        AtomicInteger logins = new AtomicInteger();
        AtomicInteger valid = new AtomicInteger(1);
        server.createContext("/login", exchange -> {
            assertEquals("{\"userName\":\"admin\",\"password\":\"p\"}",
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"resultCode\":200,\"data\":\"tok-" + logins.incrementAndGet() + "\"}");
        });
        server.createContext("/orders", exchange -> {
            boolean ok = ("tok-" + valid.get()).equals(exchange.getRequestHeaders().getFirst("token"));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            respond(exchange, 200, ok ? "{\"resultCode\":200,\"data\":[1]}" : "{\"resultCode\":419,\"message\":\"未登录\"}");
        });
        server.start();
        stubCredential("cred_login", "API_KEY_HEADER", "GLOBAL", null, null, Map.of("headerName", "token",
                "login", Map.of("url", url("/login"), "body", "{\"userName\":\"admin\",\"password\":\"p\"}")));

        assertTrue(String.valueOf(loginClient.execute(getRequest(url("/orders"), "cred_login")).body()).contains("[1]"));
        assertTrue(String.valueOf(loginClient.execute(getRequest(url("/orders"), "cred_login")).body()).contains("[1]"));
        assertEquals(1, logins.get(), "the token is reused while it is valid");

        valid.set(2);
        var renewed = loginClient.execute(getRequest(url("/orders"), "cred_login"));
        assertTrue(String.valueOf(renewed.body()).contains("[1]"));
        assertEquals(2, logins.get());
    }

    @Test
    void aFailedSignInIsReportedPlainlyAndNotRepeatedOnEveryCall() throws Exception {
        WorkflowHttpClient loginClient = new WorkflowHttpClient(objectMapper,
                WorkflowHttpEgressPolicy.permissiveForTests(host -> new InetAddress[]{InetAddress.getByName("127.0.0.1")}),
                new RuntimeWorkflowCredentialService(credentialMapper,
                        new RuntimeWorkflowCredentialCipher("test-secret-for-workflow-http-client-unit"), objectMapper));
        AtomicInteger logins = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/login", exchange -> {
            logins.incrementAndGet();
            respond(exchange, 200, "{\"resultCode\":500,\"message\":\"密码错误\"}");
        });
        server.createContext("/orders", exchange -> { calls.incrementAndGet(); respond(exchange, 200, "{}"); });
        server.start();
        stubCredential("cred_bad", "BEARER", "GLOBAL", null, null,
                Map.of("login", Map.of("url", url("/login"), "body", "{}")));

        var first = loginClient.execute(getRequest(url("/orders"), "cred_bad"));
        var second = loginClient.execute(getRequest(url("/orders"), "cred_bad"));

        assertEquals("RUNTIME_HTTP_LOGIN_FAILED", first.code());
        assertTrue(first.body().contains("密码错误"));
        assertEquals("RUNTIME_HTTP_LOGIN_FAILED", second.code());
        assertEquals(1, logins.get());
        assertEquals(0, calls.get());
    }

    @Test
    void findsTheTokenWhereLoginApisUsuallyPutIt() {
        assertEquals("a", WorkflowHttpClient.extractLoginToken(Map.of("data", Map.of("accessToken", "a")), ""));
        assertEquals("b", WorkflowHttpClient.extractLoginToken(Map.of("data", "b"), ""));
        assertEquals("c", WorkflowHttpClient.extractLoginToken(Map.of("body", Map.of("jwt", "c")), "body.jwt"));
        assertEquals(null, WorkflowHttpClient.extractLoginToken(Map.of("data", Map.of("user", "x")), ""));
        assertEquals(null, WorkflowHttpClient.extractLoginToken(Map.of("data", "a\nb"), ""));
    }

    /** A fake of Control's answer: mode, and the employee's token while there is one. */
    private static final class FakeEmployeeTokens implements EmployeeBusinessTokenSource {
        String mode = OFF;
        String token;
        List<String> origins = List.of();
        final AtomicInteger expired = new AtomicInteger();

        @Override
        public Resolution resolve(WorkflowExecutionIdentity employee) {
            return new Resolution(mode, origins, token);
        }

        @Override
        public void expired(WorkflowExecutionIdentity employee) {
            expired.incrementAndGet();
            token = null;
        }
    }

    private WorkflowHttpClient.HttpExecutionRequest employeeRequest(String url, String credentialRef,
                                                                    WorkflowExecutionIdentity identity) {
        return new WorkflowHttpClient.HttpExecutionRequest(
                "GET", url, Map.of(), Map.of(), "none", null, 5000, credentialRef, identity);
    }

    @Test
    void anEmployeesCallCarriesTheirOwnTokenOnlyWhereTheSystemAsksForIt() throws Exception {
        FakeEmployeeTokens own = new FakeEmployeeTokens();
        WorkflowHttpClient employeeClient = new WorkflowHttpClient(objectMapper,
                WorkflowHttpEgressPolicy.permissiveForTests(host -> new InetAddress[]{InetAddress.getByName("127.0.0.1")}),
                new RuntimeWorkflowCredentialService(credentialMapper,
                        new RuntimeWorkflowCredentialCipher("test-secret-for-workflow-http-client-unit"), objectMapper),
                own);
        java.util.List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        server.createContext("/orders", exchange -> {
            String auth = String.valueOf(exchange.getRequestHeaders().getFirst("Authorization"));
            seen.add(auth);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            respond(exchange, 200, "Bearer stale".equals(auth)
                    ? "{\"code\":401,\"msg\":\"未登录\"}" : "{\"code\":0,\"data\":[1]}");
        });
        server.start();
        stubCredential("cred_shared", "BEARER", "PROJECT", 7L, "mall", Map.of("token", "shared"));
        WorkflowExecutionIdentity employee = WorkflowExecutionIdentity.fromMcpEmployee(
                "default", 7L, "mall", "u1", List.of("ops"));
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();

        // Switched off: the shared credential, as before.
        assertTrue(employeeClient.execute(employeeRequest(url("/orders"), "cred_shared", employee)).success());
        // Switched on with a token: the employee's own token.
        own.mode = EmployeeBusinessTokenSource.PREFERRED;
        own.origins = List.of(origin);
        own.token = "mine";
        assertTrue(employeeClient.execute(employeeRequest(url("/orders"), "cred_shared", employee)).success());
        // A caller that is not a verified employee never gets anyone's own token.
        assertTrue(employeeClient.execute(employeeRequest(url("/orders"), "cred_shared",
                WorkflowExecutionIdentity.fromAgent(7L, "mall"))).success());
        // An address the login entry does not talk to keeps the shared credential.
        own.origins = List.of("https://elsewhere.example.com:443");
        assertTrue(employeeClient.execute(employeeRequest(url("/orders"), "cred_shared", employee)).success());
        assertEquals(List.of("Bearer shared", "Bearer mine", "Bearer shared", "Bearer shared"), seen);

        // The system refuses an old own token: forget it, and PREFERRED falls back to the shared one.
        seen.clear();
        own.origins = List.of(origin);
        own.token = "stale";
        var fallback = employeeClient.execute(employeeRequest(url("/orders"), "cred_shared", employee));
        assertTrue(String.valueOf(fallback.body()).contains("[1]"));
        assertEquals(List.of("Bearer stale", "Bearer shared"), seen);
        assertEquals(1, own.expired.get());

        // REQUIRED never uses the shared credential: the employee is told to sign in again.
        seen.clear();
        own.mode = EmployeeBusinessTokenSource.REQUIRED;
        var refused = employeeClient.execute(employeeRequest(url("/orders"), "cred_shared", employee));
        assertFalse(refused.success());
        assertEquals("RUNTIME_HTTP_EMPLOYEE_LOGIN_REQUIRED", refused.code());
        assertTrue(String.valueOf(refused.body()).contains("重新登录"));
        assertTrue(seen.isEmpty());
    }

    @Test
    void tighteningThePolicyStopsSharedCredentialDispatchOnEveryRuntimeImmediately() throws Exception {
        var control = mock(RuntimeControlCatalogClient.class);
        var current = new java.util.concurrent.atomic.AtomicReference<>(
                new EmployeeBusinessTokenResolution("OFF", List.of(), null));
        when(control.resolveEmployeeBusinessToken(any())).thenAnswer(call -> current.get());
        var credentials = new RuntimeWorkflowCredentialService(credentialMapper,
                new RuntimeWorkflowCredentialCipher("test-secret-for-workflow-http-client-unit"), objectMapper);
        var policy = WorkflowHttpEgressPolicy.permissiveForTests(
                host -> new InetAddress[]{InetAddress.getByName("127.0.0.1")});
        var runtimes = List.of(
                new WorkflowHttpClient(objectMapper, policy, credentials, new ControlEmployeeBusinessTokenSource(control)),
                new WorkflowHttpClient(objectMapper, policy, credentials, new ControlEmployeeBusinessTokenSource(control)));
        List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        server.createContext("/orders", exchange -> {
            seen.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            respond(exchange, 200, "{}");
        });
        server.start();
        stubCredential("cred_shared", "BEARER", "PROJECT", 7L, "mall", Map.of("token", "shared"));
        var employee = WorkflowExecutionIdentity.fromMcpEmployee("default", 7L, "mall", "u1", List.of("ops"));
        var request = employeeRequest(url("/orders"), "cred_shared", employee);

        for (var runtime : runtimes) assertTrue(runtime.execute(request).success());
        assertEquals(List.of("Bearer shared", "Bearer shared"), seen);
        seen.clear();

        current.set(new EmployeeBusinessTokenResolution("REQUIRED", List.of(baseOrigin()), null));
        for (var runtime : runtimes) {
            var result = runtime.execute(request);
            assertFalse(result.success());
            assertEquals("RUNTIME_HTTP_EMPLOYEE_LOGIN_REQUIRED", result.code());
        }
        assertTrue(seen.isEmpty());

        current.set(new EmployeeBusinessTokenResolution("REQUIRED", List.of(baseOrigin()), "mine"));
        for (var runtime : runtimes) assertTrue(runtime.execute(request).success());
        assertEquals(List.of("Bearer mine", "Bearer mine"), seen);
        seen.clear();

        current.set(new EmployeeBusinessTokenResolution("OFF", List.of(), null));
        for (var runtime : runtimes) assertTrue(runtime.execute(request).success());
        assertEquals(List.of("Bearer shared", "Bearer shared"), seen);
        seen.clear();

        when(control.resolveEmployeeBusinessToken(any())).thenThrow(new IllegalStateException("unavailable"));
        for (var runtime : runtimes) {
            var result = runtime.execute(request);
            assertFalse(result.success());
            assertEquals("RUNTIME_HTTP_EMPLOYEE_TOKEN_UNAVAILABLE", result.code());
        }
        assertTrue(seen.isEmpty());
    }

    private String baseOrigin() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
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
