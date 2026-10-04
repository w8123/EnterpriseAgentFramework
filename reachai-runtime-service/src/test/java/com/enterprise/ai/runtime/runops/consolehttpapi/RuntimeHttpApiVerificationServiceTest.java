package com.enterprise.ai.runtime.runops.consolehttpapi;

import com.enterprise.ai.common.capability.HttpApiConsoleContracts;
import com.enterprise.ai.runtime.runops.RuntimeRunEntity;
import com.enterprise.ai.runtime.runops.RuntimeRunMapper;
import com.enterprise.ai.runtime.runops.consolecapability.ConsoleCapabilityInvocationEntity;
import com.enterprise.ai.runtime.runops.consolecapability.ConsoleCapabilityInvocationMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RuntimeHttpApiVerificationServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ConsoleCapabilityInvocationMapper attempts = mock(ConsoleCapabilityInvocationMapper.class);
    private final RuntimeRunMapper runs = mock(RuntimeRunMapper.class);
    private final RuntimeHttpApiVerificationService service = new RuntimeHttpApiVerificationService(attempts, runs, json);
    private final String reference = "http-api:orders:dev:market:" + "a".repeat(64);
    private final String hash = "b".repeat(64), source = "c".repeat(64);

    @Test void configuredWithoutAnActualAttemptIsNotVerified() {
        assertEquals("UNVERIFIED", service.read(owner(reference), connection(), null, null).status());
    }
    @Test void actualLinkedCurrentSuccessIsVerifiedWithoutReadingOrCopyingTheResult() throws Exception {
        var attempt = successfulAttempt(); attempt.setResultJson("must-not-be-read-or-copied");
        assertEquals("VERIFIED", service.read(owner(reference), connection(), null, null).status());
        assertFalse(json.writeValueAsString(service.read(owner(reference), connection(), null, null)).contains("must-not"));
    }
    @Test void connectionSourceAcceptedAndCredentialChangesInvalidateTheProof() throws Exception {
        var attempt = successfulAttempt();
        var connection = connection(); connection.setRevision(2L);
        assertEquals("STALE", service.read(owner(reference), connection, null, null).status());
        assertEquals("STALE", service.read(owner(reference), connection(), "d".repeat(64), null).status());
        attempt.setSourceSetRevision("e".repeat(64));
        assertEquals("STALE", service.read(owner(reference), connection(), null, null).status());
        attempt.setSourceSetRevision(source); attempt.setExpectedContractHash("e".repeat(64));
        assertEquals("STALE", service.read(owner(reference), connection(), null, null).status());
    }
    @Test void failureAndUnknownLatestAttemptDoNotFallBackToAnOlderSuccess() throws Exception {
        var attempt = successfulAttempt(); attempt.setStatus("HTTP_FAILED"); attempt.setHttpStatus(503);
        assertEquals("FAILED", service.read(owner(reference), connection(), null, null).status());
        attempt.setStatus("UNKNOWN"); attempt.setDispatchStage("UNCONFIRMED");
        assertEquals("UNKNOWN", service.read(owner(reference), connection(), null, null).status());
    }
    @Test void missingOrWrongRootRunApiIdCannotBeAProof() throws Exception {
        successfulAttempt(); var run = runs.selectById(7L); run.setSnapshotJson("{\"apiId\":99}");
        assertEquals("STALE", service.read(owner(reference), connection(), null, null).status());
        when(runs.selectById(7L)).thenReturn(null);
        assertNotEquals("VERIFIED", service.read(owner(reference), connection(), null, null).status());
    }
    @Test void sourceBlockingReasonWinsAndInternalApisKeepTheirExistingContract() {
        assertEquals("BLOCKED", service.read(owner(reference), connection(), null, "目录已下线").status());
        assertNull(service.read(owner("http-api:orders:dev:" + "a".repeat(64)), connection(), null, null));
        verifyNoInteractions(attempts, runs);
    }

    private ConsoleCapabilityInvocationEntity successfulAttempt() throws Exception {
        var now = LocalDateTime.now(); var row = new ConsoleCapabilityInvocationEntity();
        row.setId(4L); row.setInvocationId("fixture-attempt"); row.setTargetType("HTTP_API");
        row.setProjectId(41L); row.setProjectCode("orders"); row.setEnvironment("dev"); row.setQualifiedName(reference);
        row.setExpectedContractHash(hash); row.setSourceSetRevision(source); row.setConnectionRevision(1L);
        row.setRunId(7L); row.setTraceId("fixture-trace"); row.setStatus("SUCCEEDED");
        row.setDispatchStage("CONFIRMED"); row.setHttpStatus(200); row.setDispatchedAt(now); row.setEndedAt(now);
        when(attempts.selectOne(any())).thenReturn(row);
        var run = new RuntimeRunEntity(); run.setId(7L); run.setTraceId("fixture-trace"); run.setRunType("CONSOLE_HTTP_API");
        run.setRuntimeType("HTTP_API"); run.setProjectId(41L); run.setProjectCode("orders"); run.setStatus("COMPLETED"); run.setEndedAt(now);
        Map<String, Object> snapshot = new LinkedHashMap<>(Map.of("apiId", 1L, "qualifiedName", reference,
                "contractHash", hash, "sourceSetRevision", source, "connectionRevision", 1L));
        snapshot.put("credentialRevision", null); run.setSnapshotJson(json.writeValueAsString(snapshot));
        when(runs.selectById(7L)).thenReturn(run); return row;
    }
    private HttpApiConsoleContracts.ExecutionContext owner(String ref) {
        return new HttpApiConsoleContracts.ExecutionContext(1, 1L, ref, 41L, "orders", "dev", "GET", "/orders/{orderId}",
                true, "ACCEPTED", null, hash, hash, source, json.createObjectNode(), true, null);
    }
    private RuntimeHttpApiConnectionEntity connection() {
        var row = new RuntimeHttpApiConnectionEntity(); row.setRevision(1L); return row;
    }
}
