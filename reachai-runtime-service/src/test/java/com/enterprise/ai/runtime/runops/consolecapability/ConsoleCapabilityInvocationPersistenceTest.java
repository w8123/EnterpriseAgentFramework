package com.enterprise.ai.runtime.runops.consolecapability;

import com.enterprise.ai.common.capability.CapabilityInvocationFailureCategory;
import com.enterprise.ai.common.capability.CapabilityInvocationResponse;
import com.enterprise.ai.common.capability.CapabilityInvocationStatus;
import com.enterprise.ai.common.capability.ConsoleCapabilityInvocationContracts;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.runops.RuntimeRunMapper;
import com.enterprise.ai.runtime.support.RuntimeQueryTestDatabase;
import com.enterprise.ai.runtime.trace.RuntimeTraceRootService;
import com.enterprise.ai.runtime.trace.RuntimeTraceSpanMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Real H2/MyBatis proof: claim is committed before dispatch and never retried by invocationId. */
class ConsoleCapabilityInvocationPersistenceTest {

    private final ObjectMapper json = new ObjectMapper();
    private RuntimeQueryTestDatabase database;
    private RuntimeCapabilityCatalogClient capabilities;
    private ConsoleCapabilityInvocationService service;
    private ConsoleCapabilityInvocationMapper invocations;

    @BeforeEach
    void setUp() throws Exception {
        database = new RuntimeQueryTestDatabase(List.of(
                "runtime_run", "runtime_trace_span", "runtime_console_capability_invocation"),
                RuntimeRunMapper.class, RuntimeTraceSpanMapper.class, ConsoleCapabilityInvocationMapper.class);
        capabilities = mock(RuntimeCapabilityCatalogClient.class);
        invocations = database.mapper(ConsoleCapabilityInvocationMapper.class);
        service = new ConsoleCapabilityInvocationService(
                invocations,
                database.mapper(RuntimeRunMapper.class),
                new RuntimeTraceRootService(database.mapper(RuntimeTraceSpanMapper.class), json),
                capabilities,
                json,
                new DataSourceTransactionManager(database.jdbc().getDataSource()));
    }

    @AfterEach
    void close() {
        if (database != null) database.close();
    }

    @Test
    void concurrentSameInvocationDispatchesExactlyOnceAndPersistsNoRawInput() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        when(capabilities.invokeTool(any(), anyMap())).thenAnswer(call -> {
            calls.incrementAndGet();
            Map<?, ?> request = call.getArgument(1);
            assertEquals(Map.of(), request.get("context"));
            assertFalse(request.containsKey(RuntimeCapabilityCatalogClient.TRUSTED_IDENTITY_ATTRIBUTE));
            return success(request.get("invocationId").toString(),
                    Map.of("echo", "ok", "password", "must-not-leak", "message", "alias private-input"));
        });
        ConsoleCapabilityInvocationContracts.InvocationCommand command = command(UUID.randomUUID().toString(),
                Map.of("customerNo", "C-123", "password", "private-input"));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ConsoleCapabilityInvocationContracts.InvocationOutcome> one = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return service.invoke(command);
            });
            Future<ConsoleCapabilityInvocationContracts.InvocationOutcome> two = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return service.invoke(command);
            });
            start.countDown();
            String first = one.get(10, TimeUnit.SECONDS).status();
            String second = two.get(10, TimeUnit.SECONDS).status();
            assertTrue(List.of(first, second).contains("SUCCEEDED"));
            assertTrue(List.of("ACCEPTED", "SUCCEEDED", "DISPATCHING").contains(first));
            assertTrue(List.of("ACCEPTED", "SUCCEEDED", "DISPATCHING").contains(second));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, calls.get(), "same invocationId must never send twice");
        String persisted = database.jdbc().queryForObject(
                "SELECT CONCAT(COALESCE(result_json,''),' ',COALESCE(input_fingerprint,'')) FROM runtime_console_capability_invocation",
                String.class);
        assertFalse(persisted.contains("private-input"));
        assertFalse(persisted.contains("must-not-leak"));
        assertTrue(persisted.contains("[redacted]"));
        assertNoRawInputInAudits("private-input");
        assertEquals(1, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_run WHERE run_type = 'CONSOLE_CAPABILITY'", Integer.class));
        assertEquals(1, database.jdbc().queryForObject("SELECT COUNT(*) FROM runtime_trace_span WHERE span_type = 'CONSOLE_CAPABILITY'", Integer.class));
        assertEquals("COMPLETED", database.jdbc().queryForObject(
                "SELECT status FROM runtime_run WHERE run_type = 'CONSOLE_CAPABILITY'", String.class));
        assertEquals("SUCCESS", database.jdbc().queryForObject(
                "SELECT status FROM runtime_trace_span WHERE span_type = 'CONSOLE_CAPABILITY'", String.class));
    }

    @Test
    void unconfirmedTechnicalFailureRemainsUnknownAndAReadDoesNotRetry() {
        AtomicInteger calls = new AtomicInteger();
        String id = UUID.randomUUID().toString();
        when(capabilities.invokeTool(any(), anyMap())).thenAnswer(call -> {
            calls.incrementAndGet();
            return new CapabilityInvocationResponse(1, id, "orders.lookup", "lookup", "Lookup",
                    CapabilityInvocationStatus.TECHNICAL_FAILED, false, null, "CAPABILITY_TIMEOUT",
                    "private upstream error", CapabilityInvocationFailureCategory.TIMEOUT, true, 12L, 1, null, Map.of());
        });
        ConsoleCapabilityInvocationContracts.InvocationCommand command = command(id, Map.of("orderNo", "O-1"));
        ConsoleCapabilityInvocationContracts.InvocationOutcome first = service.invoke(command);
        ConsoleCapabilityInvocationContracts.InvocationOutcome replay = service.invoke(command);
        ConsoleCapabilityInvocationContracts.InvocationOutcome read = service.get(id, "42");

        assertEquals("UNKNOWN", first.status());
        assertEquals("UNKNOWN", replay.status());
        assertEquals("UNKNOWN", read.status());
        assertNull(service.get(id, "not-the-owner"), "foreign actors cannot read an invocation record");
        assertEquals(1, calls.get(), "UNKNOWN means inspect/query, never auto-retry");
        assertNull(read.result());
        assertEquals("UNCONFIRMED", read.dispatchStage());
        assertEquals("FAILED", database.jdbc().queryForObject("SELECT status FROM runtime_run WHERE id = ?", String.class,
                read.runId()));
        assertEquals("ERROR", database.jdbc().queryForObject("SELECT status FROM runtime_trace_span WHERE trace_id = ?", String.class,
                read.traceId()));
    }

    @Test
    void sameIdWithDifferentInputIsAConflictAndNeverReplacesTheOriginalClaim() {
        AtomicInteger calls = new AtomicInteger();
        String id = UUID.randomUUID().toString();
        when(capabilities.invokeTool(any(), anyMap())).thenAnswer(call -> {
            calls.incrementAndGet();
            return success(id, Map.of("ok", true));
        });
        service.invoke(command(id, Map.of("orderNo", "O-original")));

        assertThrows(ConsoleCapabilityInvocationService.InvocationConflictException.class,
                () -> service.invoke(command(id, Map.of("orderNo", "O-replaced"))));
        assertEquals(1, calls.get());
    }

    @Test
    void expiredBeforeDispatchIsNotSent() {
        ConsoleCapabilityInvocationContracts.InvocationCommand command = new ConsoleCapabilityInvocationContracts.InvocationCommand(
                ConsoleCapabilityInvocationContracts.CONTRACT_VERSION, UUID.randomUUID().toString(), "42", 7L, "orders",
                "orders.lookup", "a".repeat(64), "d".repeat(64), Map.of("orderNo", "O-expired"), List.of(), "READ_ONLY", false,
                System.currentTimeMillis() - 1L);

        ConsoleCapabilityInvocationContracts.InvocationOutcome outcome = service.invoke(command);

        assertEquals("NOT_DISPATCHED", outcome.status());
        assertEquals("NOT_DISPATCHED", outcome.dispatchStage());
        verifyNoInteractions(capabilities);
        assertEquals("FAILED", database.jdbc().queryForObject("SELECT status FROM runtime_run WHERE id = ?", String.class,
                outcome.runId()));
        assertEquals("FAILED", database.jdbc().queryForObject("SELECT status FROM runtime_trace_span WHERE trace_id = ?", String.class,
                outcome.traceId()));
    }

    @Test
    void rejectedInputDiagnosticIsPersistedSafelyAndTraceUsesItsActualDispatchStage() {
        String id = UUID.randomUUID().toString();
        when(capabilities.invokeTool(any(), anyMap())).thenReturn(new CapabilityInvocationResponse(
                1, id, "orders.lookup", "lookup", "Lookup",
                CapabilityInvocationStatus.REJECTED, false, null,
                "CAPABILITY_INPUT_INVALID", "safe rejection", CapabilityInvocationFailureCategory.POLICY_REJECTED,
                false, 1L, 1, null,
                Map.of("inputDiagnostics", List.of(Map.of("path", "phone", "reason", "REQUIRED")))));
        ConsoleCapabilityInvocationContracts.InvocationCommand command = new ConsoleCapabilityInvocationContracts.InvocationCommand(
                ConsoleCapabilityInvocationContracts.CONTRACT_VERSION, id, "42", 7L, "orders",
                "orders.lookup", "a".repeat(64), "d".repeat(64), Map.of("phone", "sentinel-phone"), List.of("phone"),
                "READ_ONLY", false, System.currentTimeMillis() + 30_000L);

        ConsoleCapabilityInvocationContracts.InvocationOutcome outcome = service.invoke(command);

        assertEquals("NOT_DISPATCHED", outcome.status());
        assertEquals("NOT_DISPATCHED", outcome.dispatchStage());
        assertEquals(Map.of("metadata", Map.of("inputDiagnostics",
                List.of(Map.of("path", "phone", "reason", "REQUIRED")))), outcome.result());
        assertNoRawInputInAudits("sentinel-phone");
        String traceMetadata = database.jdbc().queryForObject(
                "SELECT metadata_json FROM runtime_trace_span WHERE trace_id = ?", String.class, outcome.traceId());
        assertTrue(traceMetadata.contains("\"dispatchStage\":\"NOT_DISPATCHED\""));
        assertFalse(traceMetadata.contains("sentinel-phone"));
    }

    @Test
    void persistenceFailureCannotReachTheOutboundClient() {
        database.jdbc().execute("DROP TABLE runtime_console_capability_invocation");

        assertThrows(RuntimeException.class,
                () -> service.invoke(command(UUID.randomUUID().toString(), Map.of("orderNo", "O-fail"))));

        verifyNoInteractions(capabilities);
    }

    @Test
    void expiredResultIsHiddenButTheInvocationIdStillCannotExecuteAgain() {
        AtomicInteger calls = new AtomicInteger();
        when(capabilities.invokeTool(any(), anyMap())).thenAnswer(call -> {
            calls.incrementAndGet();
            return success(((Map<?, ?>) call.getArgument(1)).get("invocationId").toString(), Map.of("ok", true));
        });
        ConsoleCapabilityInvocationContracts.InvocationCommand command = command(UUID.randomUUID().toString(), Map.of("orderNo", "O-2"));
        long before = System.currentTimeMillis();
        ConsoleCapabilityInvocationContracts.InvocationOutcome initial = service.invoke(command);
        assertTrue(initial.resultExpiresAtEpochMs() >= before + TimeUnit.HOURS.toMillis(23));
        assertTrue(initial.resultExpiresAtEpochMs() <= before + TimeUnit.HOURS.toMillis(25));
        database.jdbc().update("UPDATE runtime_console_capability_invocation SET result_expires_at = ?", LocalDateTime.now().minusSeconds(1));
        service.clearExpiredResults();
        ConsoleCapabilityInvocationContracts.InvocationOutcome read = service.get(command.invocationId(), "42");
        ConsoleCapabilityInvocationContracts.InvocationOutcome replay = service.invoke(command);

        assertNull(read.result());
        assertEquals("[result-expired]", read.message());
        assertNull(database.jdbc().queryForObject(
                "SELECT result_json FROM runtime_console_capability_invocation WHERE invocation_id = ?",
                String.class, command.invocationId()));
        assertEquals(1, calls.get());
        assertEquals("SUCCEEDED", replay.status());
        assertNull(replay.result(), "idempotent POST read-back must not bypass the result retention window");
    }

    @Test
    void resultLimitAndTtlConfigurationAreBoundedAndTruncationCannotBypassIdempotency() {
        AtomicInteger calls = new AtomicInteger();
        String oversized = "x".repeat(70 * 1024);
        String id = UUID.randomUUID().toString();
        when(capabilities.invokeTool(any(), anyMap())).thenAnswer(call -> {
            calls.incrementAndGet();
            return success(id, Map.of("payload", oversized));
        });
        // The runtime accepts configuration but never permits a result larger than 64 KiB
        // or a retention period longer than 24 hours.
        ReflectionTestUtils.setField(service, "configuredResultMaxBytes", 128 * 1024);
        ReflectionTestUtils.setField(service, "configuredResultTtlHours", 48);
        long before = System.currentTimeMillis();
        ConsoleCapabilityInvocationContracts.InvocationOutcome initial = service.invoke(command(id, Map.of("orderNo", "O-large")));

        assertTrue(initial.resultTruncated());
        assertFalse(String.valueOf(initial.result()).contains(oversized));
        assertTrue(initial.resultExpiresAtEpochMs() >= before + TimeUnit.HOURS.toMillis(23));
        assertTrue(initial.resultExpiresAtEpochMs() <= before + TimeUnit.HOURS.toMillis(25));
        String persisted = database.jdbc().queryForObject(
                "SELECT result_json FROM runtime_console_capability_invocation WHERE invocation_id = ?", String.class, id);
        assertTrue(persisted.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 1024);
        assertFalse(persisted.contains(oversized.substring(0, 128)));

        database.jdbc().update("UPDATE runtime_console_capability_invocation SET result_expires_at = ? WHERE invocation_id = ?",
                LocalDateTime.now().minusSeconds(1), id);
        service.clearExpiredResults();
        ConsoleCapabilityInvocationContracts.InvocationOutcome replay = service.invoke(command(id, Map.of("orderNo", "O-large")));
        assertNull(replay.result());
        assertEquals(1, calls.get(), "result expiry must not turn a durable claim into a resend");
    }

    @Test
    void businessFailureAndAliasedSensitiveCodeNeverPersistTheInputValue() {
        String id = UUID.randomUUID().toString();
        when(capabilities.invokeTool(any(), anyMap())).thenReturn(new CapabilityInvocationResponse(1, id,
                "orders.lookup", "lookup", "Lookup", CapabilityInvocationStatus.BUSINESS_FAILED, false,
                Map.of("message", "echo private-input", "values", List.of("private-input")), "private-input",
                "business response echoed a value", CapabilityInvocationFailureCategory.BUSINESS_RESPONSE,
                false, 9L, 1, "private-input", Map.of("statusCode", 422)));

        ConsoleCapabilityInvocationContracts.InvocationOutcome outcome = service.invoke(command(id,
                Map.of("orderNo", "O-business", "password", "private-input")));

        assertEquals("BUSINESS_FAILED", outcome.status());
        assertEquals("CAPABILITY_BUSINESS_RESPONSE_FAILED", outcome.code());
        assertFalse(String.valueOf(outcome.result()).contains("private-input"));
        assertNoRawInputInAudits("private-input");
        assertEquals("FAILED", database.jdbc().queryForObject("SELECT status FROM runtime_run WHERE id = ?", String.class,
                outcome.runId()));
        assertEquals("FAILED", database.jdbc().queryForObject("SELECT status FROM runtime_trace_span WHERE trace_id = ?", String.class,
                outcome.traceId()));
    }

    @Test
    void recoveryConvergesOnlyExpiredRowsAndDoesNotStopAnotherInstanceWork() {
        when(capabilities.invokeTool(any(), anyMap())).thenAnswer(call -> success(
                ((Map<?, ?>) call.getArgument(1)).get("invocationId").toString(), Map.of("ok", true)));
        ConsoleCapabilityInvocationContracts.InvocationOutcome expired = service.invoke(command(UUID.randomUUID().toString(),
                Map.of("orderNo", "O-expired-recovery")));
        ConsoleCapabilityInvocationContracts.InvocationOutcome active = service.invoke(command(UUID.randomUUID().toString(),
                Map.of("orderNo", "O-active-recovery")));
        resetOpenForRecovery(expired, System.currentTimeMillis() - 1L);
        resetOpenForRecovery(active, System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(2));

        service.recoverExpiredInvocations();

        assertEquals("UNKNOWN", status(expired.invocationId()));
        assertEquals("FAILED", runStatus(expired.runId()));
        assertEquals("ERROR", traceStatus(expired.traceId()));
        assertEquals("DISPATCHING", status(active.invocationId()));
        assertEquals("RUNNING", runStatus(active.runId()));
        assertEquals("RUNNING", traceStatus(active.traceId()));
    }

    @Test
    void terminalRunOrTraceFailureRollsBackTheInvocationTerminalWithoutRedispatch() {
        String id = UUID.randomUUID().toString();
        AtomicInteger calls = new AtomicInteger();
        when(capabilities.invokeTool(any(), anyMap())).thenAnswer(call -> {
            calls.incrementAndGet();
            database.jdbc().update("UPDATE runtime_run SET root_span_id = NULL WHERE id = "
                    + "(SELECT run_id FROM runtime_console_capability_invocation WHERE invocation_id = ?)", id);
            return success(id, Map.of("ok", true));
        });
        ConsoleCapabilityInvocationContracts.InvocationCommand command = command(id, Map.of("orderNo", "O-terminal-failure"));

        assertThrows(IllegalStateException.class, () -> service.invoke(command));
        assertEquals("DISPATCHING", status(id));
        assertEquals(1, calls.get());
        assertEquals("DISPATCHING", service.invoke(command).status());
        assertEquals(1, calls.get(), "a failed terminal update must not permit a second outbound request");
    }

    private void resetOpenForRecovery(ConsoleCapabilityInvocationContracts.InvocationOutcome outcome, long deadline) {
        database.jdbc().update("UPDATE runtime_console_capability_invocation SET status = 'DISPATCHING', dispatch_stage = 'DISPATCHING', "
                + "deadline_epoch_ms = ?, ended_at = NULL, error_code = NULL, error_message = NULL WHERE invocation_id = ?",
                deadline, outcome.invocationId());
        database.jdbc().update("UPDATE runtime_run SET status = 'RUNNING', ended_at = NULL, error_code = NULL, error_message = NULL WHERE id = ?",
                outcome.runId());
        database.jdbc().update("UPDATE runtime_trace_span SET status = 'RUNNING', ended_at = NULL, error_code = NULL, error_message = NULL WHERE trace_id = ?",
                outcome.traceId());
    }

    private String status(String invocationId) {
        return database.jdbc().queryForObject("SELECT status FROM runtime_console_capability_invocation WHERE invocation_id = ?",
                String.class, invocationId);
    }

    private String runStatus(Long runId) {
        return database.jdbc().queryForObject("SELECT status FROM runtime_run WHERE id = ?", String.class, runId);
    }

    private String traceStatus(String traceId) {
        return database.jdbc().queryForObject("SELECT status FROM runtime_trace_span WHERE trace_id = ?", String.class, traceId);
    }

    private void assertNoRawInputInAudits(String raw) {
        String extension = database.jdbc().queryForObject(
                "SELECT CONCAT(COALESCE(result_json,''),' ',COALESCE(error_code,''),' ',COALESCE(error_message,'')) "
                        + "FROM runtime_console_capability_invocation", String.class);
        String run = database.jdbc().queryForObject(
                "SELECT CONCAT(COALESCE(input_summary,''),' ',COALESCE(snapshot_json,''),' ',COALESCE(metadata_json,'')," 
                        + "' ',COALESCE(output_summary,''),' ',COALESCE(error_code,''),' ',COALESCE(error_message,'')) "
                        + "FROM runtime_run", String.class);
        String trace = database.jdbc().queryForObject(
                "SELECT CONCAT(COALESCE(input_summary,''),' ',COALESCE(metadata_json,''),' ',COALESCE(output_summary,'')," 
                        + "' ',COALESCE(error_code,''),' ',COALESCE(error_message,'')) FROM runtime_trace_span", String.class);
        assertFalse((extension + " " + run + " " + trace).contains(raw));
    }

    private ConsoleCapabilityInvocationContracts.InvocationCommand command(String invocationId, Map<String, Object> input) {
        return new ConsoleCapabilityInvocationContracts.InvocationCommand(
                ConsoleCapabilityInvocationContracts.CONTRACT_VERSION, invocationId, "42", 7L, "orders",
                "orders.lookup", "a".repeat(64), "d".repeat(64), input, List.of("password"), "READ_ONLY", false,
                System.currentTimeMillis() + 30_000L);
    }

    private CapabilityInvocationResponse success(String invocationId, Object data) {
        return new CapabilityInvocationResponse(1, invocationId, "orders.lookup", "lookup", "Lookup",
                CapabilityInvocationStatus.SUCCEEDED, true, data, null, null,
                CapabilityInvocationFailureCategory.NONE, false, 8L, 1, null, Map.of("statusCode", 200));
    }
}
