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

    @BeforeEach
    void setUp() throws Exception {
        database = new RuntimeQueryTestDatabase(List.of("runtime_run", "runtime_trace_span",
                "runtime_console_capability_invocation", "runtime_http_api_connection"), RuntimeRunMapper.class,
                RuntimeTraceSpanMapper.class, ConsoleCapabilityInvocationMapper.class,
                RuntimeHttpApiConnectionMapper.class);
        connections = mock(RuntimeHttpApiConnectionService.class);
        http = mock(WorkflowHttpClient.class);
        service = new RuntimeHttpApiInvocationService(database.mapper(ConsoleCapabilityInvocationMapper.class),
                database.mapper(RuntimeRunMapper.class),
                new RuntimeTraceRootService(database.mapper(RuntimeTraceSpanMapper.class), json),
                connections, http, json, new DataSourceTransactionManager(database.jdbc().getDataSource()));
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
    void catalogPageStateIsBoundedAndLatestHistoryIsActorScoped() {
        when(http.execute(any())).thenReturn(response(200, true, Map.of("ok", true), "application/json"));
        HttpApiConsoleContracts.InvocationCommand command = command(UUID.randomUUID().toString(), "O-900");
        service.invoke(command);
        LocalDateTime now = LocalDateTime.now();
        RuntimeHttpApiConnectionEntity saved = connection();
        saved.setSavedBy("actor-1"); saved.setCreatedAt(now); saved.setUpdatedAt(now);
        database.mapper(RuntimeHttpApiConnectionMapper.class).insert(saved);
        RuntimeHttpApiCatalogStateService catalogStates = new RuntimeHttpApiCatalogStateService(
                database.mapper(RuntimeHttpApiConnectionMapper.class),
                database.mapper(ConsoleCapabilityInvocationMapper.class));
        var request = new HttpApiConsoleContracts.CatalogStatesRequest(1, 7L, "orders",
                List.of(command.qualifiedName(), "orders.dev.GET./other"));

        var own = catalogStates.read(request, "actor-1");
        var other = catalogStates.read(request, "actor-2");

        assertEquals("SAVED", own.get(0).connectionStatus());
        assertEquals("SUCCEEDED", own.get(0).latestInvocationStatus());
        assertEquals(200, own.get(0).latestHttpStatus());
        assertEquals("UNCONFIGURED", own.get(1).connectionStatus());
        assertNull(own.get(1).latestInvocationStatus());
        assertEquals("SAVED", other.get(0).connectionStatus());
        assertNull(other.get(0).latestInvocationStatus());
        assertThrows(IllegalArgumentException.class, () -> catalogStates.read(
                new HttpApiConsoleContracts.CatalogStatesRequest(1, 7L, "orders",
                        List.of(command.qualifiedName(), command.qualifiedName())), "actor-1"));
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
