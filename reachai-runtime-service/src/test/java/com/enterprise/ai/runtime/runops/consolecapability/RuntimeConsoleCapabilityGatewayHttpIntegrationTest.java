package com.enterprise.ai.runtime.runops.consolecapability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.enterprise.ai.capability.internalauth.CapabilityInternalAuthProperties;
import com.enterprise.ai.capability.internalauth.CapabilityInternalAuthVerifier;
import com.enterprise.ai.common.capability.CapabilityInvocationFailureCategory;
import com.enterprise.ai.common.capability.CapabilityInvocationRequest;
import com.enterprise.ai.common.capability.CapabilityInvocationResponse;
import com.enterprise.ai.common.capability.CapabilityInvocationStatus;
import com.enterprise.ai.common.capability.ConsoleCapabilityInvocationContracts;
import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogFeignClient;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityInternalAuthSigner;
import com.enterprise.ai.runtime.execution.capability.RuntimeCapabilityCatalogGateway;
import com.enterprise.ai.runtime.runops.RuntimeRunMapper;
import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import com.enterprise.ai.runtime.trace.RuntimeTraceRootService;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2/E5/E6 local proof. Runtime uses its production Gateway and signer; the
 * test transport makes an actual loopback HTTP request whose handler invokes
 * the Capability-side production HMAC verifier. It never contacts a service.
 */
class RuntimeConsoleCapabilityGatewayHttpIntegrationTest {

    private static final String SECRET = "runtime-capability-test-secret";
    private final ObjectMapper json = new ObjectMapper();
    private RuntimeQueryTestDatabase database;
    private ConsoleCapabilityInvocationMapper invocations;
    private HttpServer server;
    private LocalCapabilityHandler handler;
    private LocalHttpCapabilityTransport transport;
    private ConsoleCapabilityInvocationService service;
    private ConsoleCapabilityInvocationService secondService;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() throws Exception {
        database = new RuntimeQueryTestDatabase(List.of(
                "runtime_run", "runtime_trace_span", "runtime_console_capability_invocation"),
                RuntimeRunMapper.class, RuntimeTraceSpanMapper.class, ConsoleCapabilityInvocationMapper.class);
        invocations = database.mapper(ConsoleCapabilityInvocationMapper.class);
        handler = new LocalCapabilityHandler(json);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(RuntimeCapabilityInternalAuthSigner.CAPABILITY_INVOCATION_PATH, handler);
        server.start();
        transport = new LocalHttpCapabilityTransport(server.getAddress().getPort(), json);
        RuntimeCapabilityCatalogGateway gateway = new RuntimeCapabilityCatalogGateway(transport,
                new RuntimeCapabilityInternalAuthSigner(SECRET), json);
        DataSourceTransactionManager transactions = new DataSourceTransactionManager(database.jdbc().getDataSource());
        service = service(gateway, transactions);
        secondService = service(gateway, transactions);
        logs = new ListAppender<>();
        logs.start();
        logger().addAppender(logs);
    }

    @AfterEach
    void close() {
        if (logs != null) {
            logger().detachAppender(logs);
            logs.stop();
        }
        if (server != null) server.stop(0);
        if (database != null) database.close();
    }

    @Test
    void concurrentSameIdUsesOneActualHttpRequestAndRedactsFlatSensitiveValueEverywhere() throws Exception {
        handler.reset(LocalCapabilityHandler.Mode.BLOCK_SUCCESS);
        String id = UUID.randomUUID().toString();
        ConsoleCapabilityInvocationContracts.InvocationCommand command = command(id, "sentinel-phone");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ConsoleCapabilityInvocationContracts.InvocationOutcome> first = executor.submit(() -> service.invoke(command));
            assertTrue(handler.received.await(3, TimeUnit.SECONDS), "local Capability handler should receive one request");
            Future<ConsoleCapabilityInvocationContracts.InvocationOutcome> duplicate = executor.submit(() -> secondService.invoke(command));
            assertEquals("DISPATCHING", duplicate.get(3, TimeUnit.SECONDS).status());
            handler.release.countDown();
            ConsoleCapabilityInvocationContracts.InvocationOutcome outcome = first.get(5, TimeUnit.SECONDS);

            assertEquals("SUCCEEDED", outcome.status());
            assertEquals(1, handler.requests.get());
            assertEquals(1, handler.verifiedRequests.get(), "actual Runtime signer must pass Capability verifier");
            assertNoSentinel("sentinel-phone");
            assertEquals("COMPLETED", runStatus(outcome.runId()));
            assertEquals("SUCCESS", traceStatus(outcome.traceId()));
            String traceMetadata = database.jdbc().queryForObject(
                    "SELECT metadata_json FROM runtime_trace_span WHERE trace_id = ?", String.class, outcome.traceId());
            assertTrue(traceMetadata.contains("\"dispatchStage\":\"CONFIRMED\""));

            ConsoleCapabilityInvocationContracts.InvocationOutcome stored = service.get(id, "42");
            assertNotNull(stored);
            assertFalse(String.valueOf(stored.result()).contains("sentinel-phone"));
            assertEquals("SUCCEEDED", service.invoke(command).status());
            assertEquals(1, handler.requests.get(), "idempotent POST and GET are read-only after dispatch");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void businessFailureTimeoutAndConnectionCloseEachProduceOneRequestWithoutAutomaticRetry() throws Exception {
        handler.reset(LocalCapabilityHandler.Mode.BUSINESS_FAILURE);
        ConsoleCapabilityInvocationContracts.InvocationOutcome business = service.invoke(command(UUID.randomUUID().toString(), "business-secret"));
        assertEquals("BUSINESS_FAILED", business.status());
        assertEquals(1, handler.requests.get());
        assertEquals("FAILED", runStatus(business.runId()));
        assertEquals("FAILED", traceStatus(business.traceId()));
        assertNoSentinel("business-secret");

        handler.reset(LocalCapabilityHandler.Mode.DELAY_SUCCESS);
        transport.timeout = Duration.ofMillis(80);
        String timeoutId = UUID.randomUUID().toString();
        ConsoleCapabilityInvocationContracts.InvocationOutcome timeout = service.invoke(command(timeoutId, "timeout-secret"));
        assertEquals("UNKNOWN", timeout.status());
        assertEquals("UNCONFIRMED", timeout.dispatchStage());
        assertTrue(handler.finished.await(3, TimeUnit.SECONDS));
        assertEquals(1, handler.requests.get());
        assertEquals("UNKNOWN", service.invoke(command(timeoutId, "timeout-secret")).status());
        assertEquals(1, handler.requests.get());
        assertNoSentinel("timeout-secret");

        handler.reset(LocalCapabilityHandler.Mode.CLOSE_CONNECTION);
        transport.timeout = Duration.ofSeconds(2);
        String closeId = UUID.randomUUID().toString();
        ConsoleCapabilityInvocationContracts.InvocationOutcome closed = service.invoke(command(closeId, "close-secret"));
        assertEquals("UNKNOWN", closed.status());
        assertEquals(1, handler.requests.get());
        assertEquals("UNKNOWN", service.invoke(command(closeId, "close-secret")).status());
        assertEquals(1, handler.requests.get());
        assertNoSentinel("close-secret");
    }

    @Test
    void expiryRecoveryWinsLateHttpCompletionAndNeverSendsAgain() throws Exception {
        handler.reset(LocalCapabilityHandler.Mode.BLOCK_SUCCESS);
        transport.timeout = Duration.ofSeconds(5);
        String id = UUID.randomUUID().toString();
        ConsoleCapabilityInvocationContracts.InvocationCommand command = command(id, "late-secret");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ConsoleCapabilityInvocationContracts.InvocationOutcome> inflight = executor.submit(() -> service.invoke(command));
            assertTrue(handler.received.await(3, TimeUnit.SECONDS));
            database.jdbc().update("UPDATE runtime_console_capability_invocation SET deadline_epoch_ms = ? WHERE invocation_id = ?",
                    System.currentTimeMillis() - 1L, id);
            secondService.recoverExpiredInvocations();
            handler.release.countDown();
            ConsoleCapabilityInvocationContracts.InvocationOutcome outcome = inflight.get(5, TimeUnit.SECONDS);

            assertEquals("UNKNOWN", outcome.status());
            assertEquals("UNCONFIRMED", outcome.dispatchStage());
            assertEquals(1, handler.requests.get());
            assertEquals("FAILED", runStatus(outcome.runId()));
            assertEquals("ERROR", traceStatus(outcome.traceId()));
            assertEquals("UNKNOWN", service.invoke(command).status());
            assertEquals(1, handler.requests.get());
            assertNoSentinel("late-secret");
        } finally {
            executor.shutdownNow();
        }
    }

    private ConsoleCapabilityInvocationService service(RuntimeCapabilityCatalogGateway gateway,
                                                        DataSourceTransactionManager transactions) {
        return new ConsoleCapabilityInvocationService(
                invocations,
                database.mapper(RuntimeRunMapper.class),
                new RuntimeTraceRootService(database.mapper(RuntimeTraceSpanMapper.class), json),
                gateway,
                json,
                transactions);
    }

    private ConsoleCapabilityInvocationContracts.InvocationCommand command(String id, String phone) {
        return new ConsoleCapabilityInvocationContracts.InvocationCommand(
                ConsoleCapabilityInvocationContracts.CONTRACT_VERSION, id, "42", 7L, "orders",
                "orders.lookup", "a".repeat(64),
                Map.of("phone", phone, "normal", "normal-value",
                        "request", Map.of("phone", phone, "normalNested", "normal-nested-value"),
                        "items", List.of(Map.of("phone", phone, "code", "normal-code"))),
                List.of("phone", "request.phone", "items[].phone"), "READ_ONLY", false,
                System.currentTimeMillis() + 5_000L);
    }

    private String runStatus(Long runId) {
        return database.jdbc().queryForObject("SELECT status FROM runtime_run WHERE id = ?", String.class, runId);
    }

    private String traceStatus(String traceId) {
        return database.jdbc().queryForObject("SELECT status FROM runtime_trace_span WHERE trace_id = ?", String.class, traceId);
    }

    private void assertNoSentinel(String value) {
        String all = String.join(" ", database.jdbc().queryForList(
                "SELECT CONCAT(COALESCE(result_json,''),' ',COALESCE(error_message,'')) "
                        + "FROM runtime_console_capability_invocation", String.class));
        String runs = String.join(" ", database.jdbc().queryForList(
                "SELECT CONCAT(COALESCE(input_summary,''),' ',COALESCE(snapshot_json,''),' ',"
                        + "COALESCE(metadata_json,''),' ',COALESCE(output_summary,''),' ',COALESCE(error_message,'')) "
                        + "FROM runtime_run", String.class));
        String traces = String.join(" ", database.jdbc().queryForList(
                "SELECT CONCAT(COALESCE(input_summary,''),' ',COALESCE(metadata_json,''),' ',"
                        + "COALESCE(output_summary,''),' ',COALESCE(error_message,'')) FROM runtime_trace_span", String.class));
        assertFalse((all + " " + runs + " " + traces).contains(value));
        String captured = logs.list.stream()
                .map(event -> event.getFormattedMessage() + " "
                        + java.util.Arrays.toString(event.getArgumentArray()))
                .collect(Collectors.joining(" "));
        assertFalse(captured.contains(value), "captured Runtime logs must not include a sensitive input value");
    }

    private Logger logger() {
        return (Logger) LoggerFactory.getLogger(ConsoleCapabilityInvocationService.class);
    }

    private static final class LocalHttpCapabilityTransport implements RuntimeCapabilityCatalogFeignClient {
        private final HttpClient client = HttpClient.newBuilder().build();
        private final URI uri;
        private final ObjectMapper json;
        private Duration timeout = Duration.ofSeconds(2);

        private LocalHttpCapabilityTransport(int port, ObjectMapper json) {
            this.uri = URI.create("http://127.0.0.1:" + port + RuntimeCapabilityInternalAuthSigner.CAPABILITY_INVOCATION_PATH);
            this.json = json;
        }

        @Override
        public CapabilityInvocationResponse invokeCapability(Map<String, String> headers, byte[] exactBody) {
            try {
                HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(timeout)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(exactBody));
                headers.forEach(request::header);
                HttpResponse<byte[]> response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException("local Capability HTTP status " + response.statusCode());
                }
                return json.readValue(response.body(), CapabilityInvocationResponse.class);
            } catch (Exception failure) {
                throw new IllegalStateException("local Capability HTTP transport failed", failure);
            }
        }

        @Override public Map<String, Object> getToolDefinition(String qualifiedName) { throw unsupported(); }
        @Override public CapabilityInvocationResponse invokeTool(String qualifiedName, Map<String, String> headers, byte[] body) { throw unsupported(); }
        @Override public Map<String, Object> getCompositionDefinition(String qualifiedName) { throw unsupported(); }
        @Override public Map<String, Object> getProject(String projectCode) { throw unsupported(); }
        @Override public Map<String, Object> getProjectById(Long projectId) { throw unsupported(); }
        @Override public List<Map<String, Object>> listProjectTools(Long projectId) { throw unsupported(); }
        @Override public Map<String, Object> projectReadinessFacts(Long projectId) { throw unsupported(); }
        @Override public com.enterprise.ai.common.capability.HttpApiConsoleContracts.ExecutionContext httpApiExecutionContext(
                Long apiId, Map<String, String> headers, byte[] body) { throw unsupported(); }
        @Override public com.enterprise.ai.common.capability.ConsoleCapabilityInvocationContracts.InvocationContext businessMethodExecutionContext(
                String qualifiedName, Map<String, String> headers, byte[] body) { throw unsupported(); }

        private UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("not used by Console invocation test");
        }
    }

    private static final class LocalCapabilityHandler implements com.sun.net.httpserver.HttpHandler {
        private final ObjectMapper json;
        private final CapabilityInternalAuthVerifier verifier;
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicInteger verifiedRequests = new AtomicInteger();
        private volatile Mode mode = Mode.SUCCESS;
        private volatile CountDownLatch received = new CountDownLatch(0);
        private volatile CountDownLatch release = new CountDownLatch(0);
        private volatile CountDownLatch finished = new CountDownLatch(0);

        private LocalCapabilityHandler(ObjectMapper json) {
            this.json = json;
            this.verifier = new CapabilityInternalAuthVerifier(
                    new CapabilityInternalAuthProperties(SECRET, 300, 600, 1000, 1_048_576),
                    (caller, nonce, now, ttl, max) -> true, json);
        }

        void reset(Mode next) {
            mode = next;
            requests.set(0);
            verifiedRequests.set(0);
            received = new CountDownLatch(1);
            release = new CountDownLatch(1);
            finished = new CountDownLatch(1);
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            byte[] body = exchange.getRequestBody().readAllBytes();
            requests.incrementAndGet();
            boolean verified = verifier.verifyToolExecution(
                    exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    header(exchange, InternalServiceAuthHeaders.CALLER),
                    header(exchange, InternalServiceAuthHeaders.IDENTITY_SOURCE),
                    header(exchange, InternalServiceAuthHeaders.IDENTITY_TENANT_ID),
                    header(exchange, InternalServiceAuthHeaders.IDENTITY_USER_ID),
                    header(exchange, InternalServiceAuthHeaders.TIMESTAMP),
                    header(exchange, InternalServiceAuthHeaders.NONCE),
                    header(exchange, InternalServiceAuthHeaders.BODY_SHA256),
                    header(exchange, InternalServiceAuthHeaders.SIGNATURE),
                    body, System.currentTimeMillis()).isPresent();
            if (!verified) {
                write(exchange, 401, Map.of("code", "HMAC_INVALID"));
                return;
            }
            verifiedRequests.incrementAndGet();
            received.countDown();
            try {
                if (mode == Mode.CLOSE_CONNECTION) {
                    exchange.close();
                    return;
                }
                if (mode == Mode.BLOCK_SUCCESS) release.await(4, TimeUnit.SECONDS);
                if (mode == Mode.DELAY_SUCCESS) Thread.sleep(250L);
                CapabilityInvocationRequest request = CapabilityInvocationRequest.fromWire(
                        json.readValue(body, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { }));
                if (mode == Mode.BUSINESS_FAILURE) {
                    write(exchange, 200, response(request, CapabilityInvocationStatus.BUSINESS_FAILED, false,
                            Map.of("alias", "business-" + sensitive(request)), "ORDER_CLOSED",
                            CapabilityInvocationFailureCategory.BUSINESS_RESPONSE));
                } else {
                    write(exchange, 200, response(request, CapabilityInvocationStatus.SUCCEEDED, true,
                            Map.of("alias", "alias-" + sensitive(request),
                                    "nested", List.of(Map.of("code", sensitive(request)))), null,
                            CapabilityInvocationFailureCategory.NONE));
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                write(exchange, 500, Map.of("code", "INTERRUPTED"));
            } finally {
                finished.countDown();
            }
        }

        private CapabilityInvocationResponse response(CapabilityInvocationRequest request,
                                                      CapabilityInvocationStatus status,
                                                      boolean success,
                                                      Object data,
                                                      String code,
                                                      CapabilityInvocationFailureCategory category) {
            return new CapabilityInvocationResponse(1, request.invocationId(), request.qualifiedName(),
                    "lookup", "Lookup", status, success, data, code, code, category,
                    false, 2L, 1, code, Map.of("statusCode", 200));
        }

        private String sensitive(CapabilityInvocationRequest request) {
            Object value = request.input().get("phone");
            return value == null ? "" : String.valueOf(value);
        }

        private String header(HttpExchange exchange, String name) {
            return exchange.getRequestHeaders().getFirst(name);
        }

        private void write(HttpExchange exchange, int status, Object response) throws IOException {
            byte[] bytes = json.writeValueAsBytes(response);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        enum Mode { SUCCESS, BLOCK_SUCCESS, DELAY_SUCCESS, BUSINESS_FAILURE, CLOSE_CONNECTION }
    }
}
