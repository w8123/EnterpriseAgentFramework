package com.enterprise.ai.runtime.runops.consolehttpapi;

import com.enterprise.ai.common.capability.HttpApiConsoleContracts;
import com.enterprise.ai.runtime.credential.RuntimeWorkflowCredentialRuntime;
import com.enterprise.ai.runtime.credential.RuntimeWorkflowCredentialService;
import com.enterprise.ai.runtime.execution.http.WorkflowHttpClient;
import com.enterprise.ai.runtime.execution.http.WorkflowHttpEgressPolicy;
import com.enterprise.ai.runtime.runops.RuntimeRunMapper;
import com.enterprise.ai.runtime.runops.consolecapability.ConsoleCapabilityInvocationMapper;
import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import com.enterprise.ai.runtime.trace.RuntimeTraceRootService;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Actual H2/MyBatis attempt and Run/Trace writes; transport is replaced with a bounded fake. */
class RuntimeHttpApiInvocationPersistenceTest {
    private static final String HASH = "a".repeat(64);
    private static final String SOURCE = "b".repeat(64);
    private final ObjectMapper json = new ObjectMapper();
    private RuntimeQueryTestDatabase database;
    private RuntimeHttpApiConnectionService connections;
    private WorkflowHttpClient http;
    private RuntimeHttpApiInvocationService service;
    private final List<Integer> transactionIsolation = new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        transactionIsolation.clear();
        database = new RuntimeQueryTestDatabase(List.of("runtime_run", "runtime_trace_span",
                "runtime_console_capability_invocation", "runtime_http_api_connection"), RuntimeRunMapper.class,
                RuntimeTraceSpanMapper.class, ConsoleCapabilityInvocationMapper.class,
                RuntimeHttpApiConnectionMapper.class);
        connections = mock(RuntimeHttpApiConnectionService.class);
        http = mock(WorkflowHttpClient.class);
        service = new RuntimeHttpApiInvocationService(database.mapper(ConsoleCapabilityInvocationMapper.class),
                database.mapper(RuntimeRunMapper.class),
                new RuntimeTraceRootService(database.mapper(RuntimeTraceSpanMapper.class), json),
                connections, http, json, new DataSourceTransactionManager(database.jdbc().getDataSource()) {
                    @Override protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {
                        transactionIsolation.add(definition.getIsolationLevel()); super.doBegin(transaction, definition);
                    }
                });
        when(connections.owner(any())).thenReturn(owner());
        when(connections.find("orders.dev.GET./orders/{orderId}")).thenReturn(connection());
        when(connections.selectedCredential(eq("API_KEY_HEADER"), eq("cred-1"), any()))
                .thenReturn(credential("c".repeat(64)));
    }

    @AfterEach
    void close() {
        if (database != null) database.close();
    }

    @Test
    void onlyTheNewInvocationClaimSelectsReadCommittedInsteadOfDatabaseDefault() {
        when(http.execute(any())).thenReturn(response(200, true, Map.of("ok", true), "application/json"));
        assertEquals("SUCCEEDED", service.invoke(command(UUID.randomUUID().toString(), "isolation-fixture")).status());
        assertEquals(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED, transactionIsolation.get(0));
        assertTrue(transactionIsolation.size() >= 3);
        assertTrue(transactionIsolation.subList(1, transactionIsolation.size()).stream().allMatch(level ->
                level == org.springframework.transaction.TransactionDefinition.ISOLATION_DEFAULT));
    }

    /** H2 verifies the unique-key/rollback/dispatch contract, not MySQL next-key locking. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void simultaneousAbsentClaimsKeepOneDispatchPerIdAndAtomicRunTraceRoots(boolean sameId) throws Exception {
        var actual = database.mapper(ConsoleCapabilityInvocationMapper.class);
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        var wrapped = mock(ConsoleCapabilityInvocationMapper.class, org.mockito.AdditionalAnswers.delegatesTo(actual));
        doAnswer(call -> {
            var existing = actual.selectByInvocationIdForUpdate(call.getArgument(0));
            if (existing == null) {
                assertEquals(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED,
                        org.springframework.transaction.support.TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
                barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
            }
            return existing;
        }).when(wrapped).selectByInvocationIdForUpdate(any());
        service = new RuntimeHttpApiInvocationService(wrapped, database.mapper(RuntimeRunMapper.class),
                new RuntimeTraceRootService(database.mapper(RuntimeTraceSpanMapper.class), json), connections, http, json,
                new DataSourceTransactionManager(database.jdbc().getDataSource()));
        var sends = new AtomicInteger();
        when(http.execute(any())).thenAnswer(call -> {
            sends.incrementAndGet(); return response(200, true, Map.of("ok", true), "application/json");
        });
        var first = command(UUID.randomUUID().toString(), "concurrent-fixture");
        var second = sameId ? first : command(UUID.randomUUID().toString(), "concurrent-other");
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var one = pool.submit(() -> service.invoke(first));
            var two = pool.submit(() -> service.invoke(second));
            one.get(10, java.util.concurrent.TimeUnit.SECONDS); two.get(10, java.util.concurrent.TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        int expected = sameId ? 1 : 2;
        assertEquals(expected, sends.get());
        for (var command : List.of(first, second)) {
            assertEquals("SUCCEEDED", service.get(command.invocationId(), "actor-1").status());
            assertEquals("SUCCEEDED", service.invoke(command).status());
        }
        assertEquals(expected, sends.get(), "read-back and duplicate POST must not dispatch again");
        assertEquals(expected, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_console_capability_invocation", Integer.class));
        assertEquals(expected, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_run WHERE status='COMPLETED'", Integer.class));
        assertEquals(expected, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_trace_span WHERE status='SUCCESS'", Integer.class));
    }

    @Test
    void firstCallPersistsOneDispatchAndRedactsSecretEchoWithoutRawAuditData() {
        String secret = "private-test-key";
        when(http.execute(any())).thenAnswer(call -> {
            WorkflowHttpClient.HttpExecutionRequest request = call.getArgument(0);
            assertEquals("GET", request.method());
            assertEquals("http://example.org/orders/O-123", request.url());
            assertEquals(Map.of("expanded", "true"), request.queryParams());
            assertEquals("c".repeat(64), request.expectedCredentialRevision());
            return response(200, true, Map.of("ok", true, "token", secret,
                    "message", "echo " + secret), "application/json; secret=" + secret);
        });
        HttpApiConsoleContracts.InvocationCommand command = command(UUID.randomUUID().toString(), "O-123");

        HttpApiConsoleContracts.InvocationOutcome first = service.invoke(command);
        HttpApiConsoleContracts.InvocationOutcome replay = service.invoke(command);
        HttpApiConsoleContracts.InvocationOutcome reloaded = service.get(command.invocationId(), "actor-1");

        assertEquals("SUCCEEDED", first.status());
        assertEquals("CONFIRMED", first.dispatchStage());
        assertEquals(200, first.httpStatus());
        assertEquals(first.runId(), replay.runId());
        assertEquals(first.traceId(), reloaded.traceId());
        assertNull(service.get(command.invocationId(), "other-actor"));
        verify(http, times(1)).execute(any());
        assertEquals("COMPLETED", database.jdbc().queryForObject(
                "SELECT status FROM runtime_run WHERE id = ?", String.class, first.runId()));
        assertEquals("SUCCESS", database.jdbc().queryForObject(
                "SELECT status FROM runtime_trace_span WHERE trace_id = ?", String.class, first.traceId()));
        String attempt = database.jdbc().queryForObject("SELECT result_json FROM runtime_console_capability_invocation "
                + "WHERE invocation_id = ?", String.class, command.invocationId());
        String run = database.jdbc().queryForObject("SELECT CONCAT(input_summary, ' ', snapshot_json, ' ', metadata_json, ' ', output_summary) "
                + "FROM runtime_run WHERE id = ?", String.class, first.runId());
        String trace = database.jdbc().queryForObject("SELECT CONCAT(input_summary, ' ', metadata_json, ' ', output_summary) "
                + "FROM runtime_trace_span WHERE trace_id = ?", String.class, first.traceId());
        assertFalse((attempt + run + trace).contains(secret));
        assertFalse((attempt + run + trace).contains("O-123"));
        assertTrue(attempt.contains("[redacted]"));
    }

    @Test
    void changedCredentialBeforeDispatchTerminalizesWithoutSending() {
        when(connections.selectedCredential(eq("API_KEY_HEADER"), eq("cred-1"), any()))
                .thenReturn(credential("c".repeat(64)), credential("d".repeat(64)));
        HttpApiConsoleContracts.InvocationOutcome outcome = service.invoke(
                command(UUID.randomUUID().toString(), "O-456"));

        assertEquals("NOT_DISPATCHED", outcome.status());
        assertEquals("HTTP_API_CREDENTIAL_REVISION_CHANGED", outcome.errorCode());
        verifyNoInteractions(http);
        assertEquals("FAILED", database.jdbc().queryForObject(
                "SELECT status FROM runtime_run WHERE id = ?", String.class, outcome.runId()));
    }

    @Test
    void invalidInputAndStaleConnectionCannotClaimOrSend() {
        HttpApiConsoleContracts.InvocationCommand invalid = new HttpApiConsoleContracts.InvocationCommand(
                1, UUID.randomUUID().toString(), "actor-1", 9L, "orders.dev.GET./orders/{orderId}",
                7L, "orders", "dev", HASH, SOURCE, 1L,
                Map.of("unknown", "O-1"), Map.of(), System.currentTimeMillis() + 30_000);
        assertThrows(IllegalArgumentException.class, () -> service.invoke(invalid));
        HttpApiConsoleContracts.InvocationCommand stale = new HttpApiConsoleContracts.InvocationCommand(
                1, UUID.randomUUID().toString(), "actor-1", 9L, "orders.dev.GET./orders/{orderId}",
                7L, "orders", "dev", HASH, SOURCE, 2L,
                Map.of("orderId", "O-1"), Map.of(), System.currentTimeMillis() + 30_000);
        assertEquals("HTTP_API_CONNECTION_CHANGED",
                assertThrows(RuntimeHttpApiInvocationService.Conflict.class, () -> service.invoke(stale)).code());
        assertEquals(0, database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM runtime_console_capability_invocation", Integer.class));
        verifyNoInteractions(http);
    }

    @Test
    void upstream401IsAConfirmedHttpFailureAndIdIsNeverResent() {
        AtomicInteger sends = new AtomicInteger();
        when(http.execute(any())).thenAnswer(call -> {
            sends.incrementAndGet();
            return response(401, false, Map.of("error", "unauthorized"), "application/json");
        });
        HttpApiConsoleContracts.InvocationCommand command = command(UUID.randomUUID().toString(), "O-401");
        HttpApiConsoleContracts.InvocationOutcome first = service.invoke(command);
        HttpApiConsoleContracts.InvocationOutcome replay = service.invoke(command);

        assertEquals("HTTP_FAILED", first.status());
        assertEquals("CONFIRMED", first.dispatchStage());
        assertEquals(401, first.httpStatus());
        assertEquals(first.status(), replay.status());
        assertEquals(1, sends.get());
    }

    @Test
    void realLoopbackTransportReceivesExpectedRouteQueryAndProjectApiKey() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger received = new AtomicInteger();
        AtomicReference<String> uri = new AtomicReference<>();
        AtomicReference<String> key = new AtomicReference<>();
        server.createContext("/orders/", exchange -> {
            received.incrementAndGet();
            uri.set(exchange.getRequestURI().toString());
            key.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
            byte[] bytes = "{\"orderId\":\"O-789\",\"apiKey\":\"private-test-key\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            RuntimeHttpApiConnectionEntity saved = connection();
            saved.setOrigin("http://127.0.0.1:" + server.getAddress().getPort());
            when(connections.find("orders.dev.GET./orders/{orderId}")).thenReturn(saved);
            RuntimeWorkflowCredentialService vault = mock(RuntimeWorkflowCredentialService.class);
            when(vault.resolve(eq("cred-1"), any())).thenReturn(Optional.of(credential("c".repeat(64))));
            WorkflowHttpClient realHttp = new WorkflowHttpClient(json,
                    WorkflowHttpEgressPolicy.permissiveForTests(), vault);
            service = new RuntimeHttpApiInvocationService(database.mapper(ConsoleCapabilityInvocationMapper.class),
                    database.mapper(RuntimeRunMapper.class),
                    new RuntimeTraceRootService(database.mapper(RuntimeTraceSpanMapper.class), json),
                    connections, realHttp, json, new DataSourceTransactionManager(database.jdbc().getDataSource()));

            HttpApiConsoleContracts.InvocationOutcome outcome = service.invoke(
                    command(UUID.randomUUID().toString(), "O-789"));

            assertEquals("SUCCEEDED", outcome.status());
            assertEquals(200, outcome.httpStatus());
            assertEquals(1, received.get());
            assertEquals("/orders/O-789?expanded=true", uri.get());
            assertEquals("private-test-key", key.get());
            assertFalse(String.valueOf(outcome.result()).contains("private-test-key"));
            assertEquals("SUCCESS", database.jdbc().queryForObject(
                    "SELECT status FROM runtime_trace_span WHERE trace_id = ?", String.class, outcome.traceId()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void lostUpstreamResponseRemainsUnknownAndNeverResendsTheSameInvocationId() {
        when(http.execute(any())).thenReturn(new WorkflowHttpClient.HttpExecutionResult(false,
                "RUNTIME_HTTP_TIMEOUT", 0, Map.of(), null, null, null, 30_000L, 0, 0,
                "[redacted-url]", false));
        HttpApiConsoleContracts.InvocationCommand command = command(UUID.randomUUID().toString(), "O-TIMEOUT");

        var first = service.invoke(command);
        var replay = service.invoke(command);

        assertEquals("UNKNOWN", first.status());
        assertEquals("UNCONFIRMED", first.dispatchStage());
        assertEquals(first.runId(), replay.runId());
        assertEquals(first.traceId(), service.get(command.invocationId(), "actor-1").traceId());
        verify(http, times(1)).execute(any());
    }

    @Test
    void safeTlsCausePersistsInAttemptAndRunWhileReceiptStaysUnknownAndIdIsNeverResent() {
        String secret = "private-diagnostic-token-marker";
        when(http.execute(any())).thenReturn(new WorkflowHttpClient.HttpExecutionResult(false,
                "RUNTIME_HTTP_TLS_FAILED", 0, Map.of("Authorization", List.of("Bearer " + secret)),
                "raw failure https://private.example/path?token=" + secret, null, null, 5031L, 0, 0,
                "https://private.example/path?token=" + secret, false, "SSLHandshakeException"));
        var command = command(UUID.randomUUID().toString(), "O-TLS");

        var first = service.invoke(command);
        var second = service.invoke(command);
        var read = service.get(command.invocationId(), "actor-1");

        assertEquals("UNKNOWN", first.status());
        assertEquals("UNCONFIRMED", first.dispatchStage());
        assertEquals("HTTP_API_DISPATCH_UNKNOWN", first.errorCode());
        assertTrue(first.terminal());
        assertNull(first.result());
        assertNull(first.httpStatus());
        assertEquals(first.runId(), second.runId());
        assertEquals(first.traceId(), read.traceId());
        String expected = "[rejected:HTTP_API_DISPATCH_UNKNOWN] "
                + "[transport:code=RUNTIME_HTTP_TLS_FAILED;exceptionClass=SSLHandshakeException;elapsedMs=5031]";
        assertEquals(expected, database.jdbc().queryForObject(
                "SELECT error_message FROM runtime_console_capability_invocation WHERE invocation_id = ?",
                String.class, command.invocationId()));
        assertEquals(expected, database.jdbc().queryForObject(
                "SELECT error_message FROM runtime_run WHERE id = ?", String.class, first.runId()));
        String persisted = database.jdbc().queryForMap(
                "SELECT error_code, error_message, result_json FROM runtime_console_capability_invocation WHERE invocation_id = ?",
                command.invocationId()).toString() + database.jdbc().queryForMap(
                "SELECT error_code, error_message, metadata_json FROM runtime_run WHERE id = ?", first.runId())
                + database.jdbc().queryForMap(
                "SELECT error_code, error_message, metadata_json FROM runtime_trace_span WHERE trace_id = ?", first.traceId());
        assertFalse(persisted.contains(secret));
        assertFalse(persisted.contains("private.example"));
        assertFalse(persisted.contains("Authorization"));
        verify(http, times(1)).execute(any());
    }

    @Test
    void untrustedTransportCodeOrExceptionClassCannotCrossThePersistenceBoundary() {
        String secret = "poisoned-transport-token-marker";
        for (var result : List.of(
                new WorkflowHttpClient.HttpExecutionResult(false, "RUNTIME_HTTP_TLS_FAILED?token=" + secret,
                        0, Map.of(), secret, null, null, 12, 0, 0, "https://private.example/" + secret,
                        false, "SSLHandshakeException"),
                new WorkflowHttpClient.HttpExecutionResult(false, "RUNTIME_HTTP_TLS_FAILED",
                        0, Map.of(), secret, null, null, 12, 0, 0, "https://private.example/" + secret,
                        false, "SSLHandshakeException?token=" + secret))) {
            when(http.execute(any())).thenReturn(result);
            var command = command(UUID.randomUUID().toString(), "O-POISON");
            var outcome = service.invoke(command);
            assertEquals("UNKNOWN", outcome.status());
            assertEquals("UNCONFIRMED", outcome.dispatchStage());
            assertEquals("HTTP_API_DISPATCH_UNKNOWN", outcome.errorCode());
            assertNull(outcome.result());
            String expected = "[rejected:HTTP_API_DISPATCH_UNKNOWN]";
            assertEquals(expected, database.jdbc().queryForObject(
                    "SELECT error_message FROM runtime_console_capability_invocation WHERE invocation_id = ?",
                    String.class, command.invocationId()));
            assertEquals(expected, database.jdbc().queryForObject(
                    "SELECT error_message FROM runtime_run WHERE id = ?", String.class, outcome.runId()));
        }
        verify(http, times(2)).execute(any());
    }

    @Test
    void unconfirmedSourceOrMissingRequiredCredentialCannotClaimAnAttempt() throws Exception {
        var current = owner();
        when(connections.owner(any())).thenReturn(new HttpApiConsoleContracts.ExecutionContext(
                current.contractVersion(), current.apiId(), current.qualifiedName(), current.projectId(),
                current.projectCode(), current.environment(), current.httpMethod(), current.routeTemplate(),
                false, "SOURCE_UNCONFIRMED", "本次未确认", current.candidateContractHash(),
                current.acceptedContractHash(), current.sourceSetRevision(), current.acceptedContract(),
                current.firstCallSupported(), current.unsupportedReason()));
        assertEquals("HTTP_API_CONTRACT_CHANGED", assertThrows(RuntimeHttpApiInvocationService.Conflict.class,
                () -> service.invoke(command(UUID.randomUUID().toString(), "O-1"))).code());
        when(connections.owner(any())).thenReturn(current);
        when(connections.selectedCredential(eq("API_KEY_HEADER"), eq("cred-1"), any())).thenReturn(null);
        assertEquals("HTTP_API_AUTH_MISMATCH", assertThrows(RuntimeHttpApiInvocationService.Conflict.class,
                () -> service.invoke(command(UUID.randomUUID().toString(), "O-1"))).code());
        assertEquals(0, database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM runtime_console_capability_invocation", Integer.class));
        verifyNoInteractions(http);
    }

    private HttpApiConsoleContracts.InvocationCommand command(String id, String orderId) {
        return new HttpApiConsoleContracts.InvocationCommand(1, id, "actor-1", 9L,
                "orders.dev.GET./orders/{orderId}", 7L, "orders", "dev", HASH, SOURCE, 1L,
                Map.of("orderId", orderId), Map.of("expanded", true), System.currentTimeMillis() + 30_000);
    }

    @Test
    void readExplicitStaleCredentialRevisionCannotClaimOrDispatch() {
        when(http.execute(any())).thenReturn(response(200, true, Map.of("ok", true), "application/json"));
        var command = withCredentialRevision(command(UUID.randomUUID().toString(), "read-stale"), "d".repeat(64), false);
        var failure = assertThrows(RuntimeHttpApiInvocationService.Conflict.class, () -> service.invoke(command));
        assertEquals("HTTP_API_CREDENTIAL_CHANGED", failure.code());
        verifyNoInteractions(http);
        assertEquals(0, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_console_capability_invocation", Integer.class));
        assertEquals(0, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_run", Integer.class));
        assertEquals(0, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_trace_span", Integer.class));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void readCurrentOrOmittedCredentialRevisionRemainsCompatible(boolean explicit) {
        when(http.execute(any())).thenAnswer(call -> {
            WorkflowHttpClient.HttpExecutionRequest request = call.getArgument(0);
            assertEquals("c".repeat(64), request.expectedCredentialRevision());
            return response(200, true, Map.of("ok", true), "application/json");
        });
        var command = withCredentialRevision(command(UUID.randomUUID().toString(), "read-compatible"),
                explicit ? "c".repeat(64) : null, false);
        var first = service.invoke(command);
        assertEquals("SUCCEEDED", first.status());
        assertEquals(first.runId(), service.invoke(command).runId());
        verify(http, times(1)).execute(any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"WRITE", "IDEMPOTENT_WRITE", "IRREVERSIBLE"})
    void confirmedWritesStillRequireAnExactCredentialRevision(String effect) throws Exception {
        var previous = owner();
        var contract = previous.acceptedContract().deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) contract).put("sideEffect", effect);
        when(connections.owner(any())).thenReturn(new HttpApiConsoleContracts.ExecutionContext(
                1, 9L, previous.qualifiedName(), 7L, "orders", "dev", "GET", "/orders/{orderId}",
                true, "ACCEPTED", null, HASH, HASH, SOURCE, contract, false, "write", true, null));
        for (String revision : new String[] {null, "d".repeat(64)}) {
            var command = withCredentialRevision(command(UUID.randomUUID().toString(), "write-revision"), revision, true);
            var failure = assertThrows(RuntimeHttpApiInvocationService.Conflict.class, () -> service.invoke(command));
            assertEquals("HTTP_API_CREDENTIAL_CHANGED", failure.code());
        }
        verifyNoInteractions(http);
        assertEquals(0, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_console_capability_invocation", Integer.class));
    }

    private HttpApiConsoleContracts.InvocationCommand withCredentialRevision(HttpApiConsoleContracts.InvocationCommand original,
                                                                            String revision, boolean confirmed) {
        return new HttpApiConsoleContracts.InvocationCommand(original.contractVersion(), original.invocationId(),
                original.platformActorId(), original.apiId(), original.qualifiedName(), original.projectId(),
                original.projectCode(), original.environment(), original.expectedContractHash(), original.expectedSourceSetRevision(),
                original.connectionRevision(), original.pathParams(), original.queryParams(), original.deadlineEpochMs(),
                original.body(), confirmed, revision);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"WRITE", "IDEMPOTENT_WRITE", "IRREVERSIBLE"})
    void everyDeclaredWriteEffectNeedsConfirmationBeforeClaimOrDispatch(String effect) throws Exception {
        var previous = owner();
        var contract = previous.acceptedContract().deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) contract).put("sideEffect", effect);
        when(connections.owner(any())).thenReturn(new HttpApiConsoleContracts.ExecutionContext(
                1, 9L, previous.qualifiedName(), 7L, "orders", "dev", "GET", "/orders/{orderId}",
                true, "ACCEPTED", null, HASH, HASH, SOURCE, contract, false, "not first-call read-only", true, null));
        var failure = assertThrows(RuntimeHttpApiInvocationService.Conflict.class,
                () -> service.invoke(command(UUID.randomUUID().toString(), "O-WRITE")));
        assertEquals("HTTP_API_CONFIRMATION_REQUIRED", failure.code());
        verifyNoInteractions(http);
        assertEquals(0, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_console_capability_invocation", Integer.class));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE", "TEXT"})
    void realLoopbackPreservesRootArrayAndTextBytesAndNeverResendsAWrite(String shape) throws Exception {
        boolean text = "TEXT".equals(shape);
        String method = text ? "POST" : shape;
        Object body = text ? "  原文 \"role\"\n" : List.of(Map.of("profile", Map.of("pin", "nested-input-secret"), "enabled", true));
        String schema = text ? "{\"type\":\"string\"}" : """
                {"type":"array","items":{"type":"object","properties":{
                  "profile":{"type":"object","properties":{"pin":{"type":"string","writeOnly":true}}},
                  "enabled":{"type":"boolean"}},"required":["profile","enabled"]}}
                """;
        var contract = (com.fasterxml.jackson.databind.node.ObjectNode) owner().acceptedContract().deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) contract.path("identity")).put("method", method);
        contract.put("sideEffect", "IRREVERSIBLE");
        contract.set("requestBody", json.readTree("{\"required\":true,\"schema\":" + schema
                + ",\"contentTypes\":[\"" + (text ? "text/plain" : "application/json") + "\"]}"));
        when(connections.owner(any())).thenReturn(new HttpApiConsoleContracts.ExecutionContext(1, 9L,
                owner().qualifiedName(), 7L, "orders", "dev", method, "/orders/{orderId}", true,
                "ACCEPTED", null, HASH, HASH, SOURCE, contract, false, null, true, null));
        var received = new AtomicInteger(); var bytes = new AtomicReference<String>();
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/orders/", exchange -> {
            received.incrementAndGet();
            assertEquals(method, exchange.getRequestMethod());
            assertEquals(text ? "text/plain;charset=UTF-8" : "application/json", exchange.getRequestHeaders().getFirst("Content-Type"));
            bytes.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] reply = "{\"echo\":\"nested-input-secret\",\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, reply.length);
            try (var out = exchange.getResponseBody()) { out.write(reply); }
        });
        upstream.start();
        try {
            var connection = connection(); connection.setOrigin("http://127.0.0.1:" + upstream.getAddress().getPort());
            when(connections.find(connection.getQualifiedName())).thenReturn(connection);
            var vault = mock(RuntimeWorkflowCredentialService.class);
            when(vault.resolve(eq("cred-1"), any())).thenReturn(Optional.of(credential("c".repeat(64))));
            var real = new WorkflowHttpClient(json, WorkflowHttpEgressPolicy.permissiveForTests(), vault);
            service = new RuntimeHttpApiInvocationService(database.mapper(ConsoleCapabilityInvocationMapper.class),
                    database.mapper(RuntimeRunMapper.class), new RuntimeTraceRootService(database.mapper(RuntimeTraceSpanMapper.class), json),
                    connections, real, json, new DataSourceTransactionManager(database.jdbc().getDataSource()));
            var command = new HttpApiConsoleContracts.InvocationCommand(1, UUID.randomUUID().toString(), "actor-1", 9L,
                    connection.getQualifiedName(), 7L, "orders", "dev", HASH, SOURCE, 1L,
                    Map.of("orderId", "fixture"), Map.of(), System.currentTimeMillis() + 30_000, body, true, "c".repeat(64));
            var first = service.invoke(command); var replay = service.invoke(command);
            assertEquals("SUCCEEDED", first.status()); assertEquals(first.runId(), replay.runId()); assertEquals(1, received.get());
            if (text) assertEquals(body, bytes.get());
            else {
                assertEquals(json.valueToTree(body), json.readTree(bytes.get()));
                assertFalse(String.valueOf(first.result()).contains("nested-input-secret"));
            }
        } finally { upstream.stop(0); }
    }

    private HttpApiConsoleContracts.ExecutionContext owner() throws Exception {
        return new HttpApiConsoleContracts.ExecutionContext(1, 9L, "orders.dev.GET./orders/{orderId}",
                7L, "orders", "dev", "GET", "/orders/{orderId}", true, "ACCEPTED", null,
                HASH, HASH, SOURCE, json.readTree("""
                {"identity":{"method":"GET","routeTemplate":"/orders/{orderId}",
                 "mappingConditions":{"conditions":[],"consumes":[]}},"sideEffect":"READ_ONLY",
                 "parameters":[{"location":"PATH","name":"orderId","required":true,"schema":{"type":"string"}},
                               {"location":"QUERY","name":"expanded","required":false,"schema":{"type":"boolean"}}],
                 "requestBody":null,"responses":[{"schema":{"type":"object"},"contentTypes":["application/json"]}],
                 "authentication":{"state":"REQUIRED","schemes":["api_key:header:X-API-Key"],
                   "requiredHeaderNames":["X-API-Key"]}}
                """), true, null);
    }

    private RuntimeHttpApiConnectionEntity connection() {
        RuntimeHttpApiConnectionEntity value = new RuntimeHttpApiConnectionEntity();
        value.setQualifiedName("orders.dev.GET./orders/{orderId}");
        value.setProjectId(7L); value.setProjectCode("orders"); value.setEnvironment("dev");
        value.setOrigin("http://example.org"); value.setAuthMode("API_KEY_HEADER");
        value.setCredentialRef("cred-1"); value.setRevision(1L);
        return value;
    }

    private RuntimeWorkflowCredentialRuntime credential(String revision) {
        return new RuntimeWorkflowCredentialRuntime("cred-1", "Order API Key", "API_KEY_HEADER",
                Map.of("headerName", "X-API-Key", "apiKey", "private-test-key"), revision);
    }

    private WorkflowHttpClient.HttpExecutionResult response(int status, boolean success, Object body, String contentType) {
        return new WorkflowHttpClient.HttpExecutionResult(success, success ? "OK" : "HTTP_ERROR", status,
                Map.of(), json(body), body, contentType, 13L, 42, 0, "[redacted-url]", false);
    }

    private String json(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
