package com.enterprise.ai.common.capability;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CapabilityInvocationContractTest {

    @Test
    void rejectsPathBodyMismatchAndClassifiesLegacyFailuresFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> CapabilityInvocationRequest.fromRuntime(
                "orders:query", Map.of("qualifiedName", "orders:update")));

        CapabilityInvocationRequest request = CapabilityInvocationRequest.fromRuntime(
                "orders:query", Map.of("invocationId", "inv-1"));
        CapabilityInvocationResponse business = CapabilityInvocationResponse.fromLegacy(request, Map.of(
                "success", false,
                "code", "CAPABILITY_BUSINESS_RESPONSE_FAILED",
                "businessCode", "ORDER_CLOSED",
                "message", "closed"));
        assertEquals(CapabilityInvocationStatus.BUSINESS_FAILED, business.status());
        assertEquals(CapabilityInvocationFailureCategory.BUSINESS_RESPONSE, business.failureCategory());
        assertFalse(business.retryable());

        CapabilityInvocationResponse unknown = CapabilityInvocationResponse.fromLegacy(request, Map.of(
                "success", false,
                "code", "UNKNOWN",
                "retryable", true));
        assertEquals(CapabilityInvocationStatus.TECHNICAL_FAILED, unknown.status());
        assertEquals(CapabilityInvocationFailureCategory.INTERNAL, unknown.failureCategory());
        assertFalse(unknown.retryable());
    }

    @Test
    void responseMetadataUsesOnlyTheStableAllowlist() {
        CapabilityInvocationResponse response = new CapabilityInvocationResponse(
                1, "inv-1", "orders:query", "query", "Query",
                CapabilityInvocationStatus.SUCCEEDED, true, Map.of("total", 1),
                null, null, CapabilityInvocationFailureCategory.NONE, false,
                4L, 1, null,
                Map.of("statusCode", 200, "Authorization", "must-not-leak", "headers", Map.of()));
        assertEquals(Map.of("statusCode", 200), response.safeMetadata());
    }

    @Test
    void legacyInvokerAdapterPreservesExecutionControlFields() {
        CapabilityInvocationRequest request = new CapabilityInvocationRequest(
                1,
                "inv-7",
                "orders:query",
                Map.of("orderNo", "A001"),
                Map.of(),
                Map.of(),
                Map.of(),
                123456789L,
                "wf:inv-7",
                Map.of("traceparent", "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01"));

        Map<String, Object> legacy = request.toLegacyExecutionMap();

        assertEquals("inv-7", legacy.get("invocationId"));
        assertEquals(123456789L, legacy.get("deadlineEpochMs"));
        assertEquals("wf:inv-7", legacy.get("idempotencyKey"));
        assertEquals(request.traceContext(), legacy.get("traceContext"));
    }

    @Test
    void canonicalWireParserRejectsMissingCorrelationVersionAndMalformedDeadline() {
        assertThrows(IllegalArgumentException.class,
                () -> CapabilityInvocationRequest.fromWire(Map.of(
                        "contractVersion", 2,
                        "invocationId", "inv-1",
                        "qualifiedName", "orders:query")));
        assertThrows(IllegalArgumentException.class,
                () -> CapabilityInvocationRequest.fromWire(Map.of(
                        "contractVersion", 1,
                        "qualifiedName", "orders:query")));
        assertThrows(IllegalArgumentException.class,
                () -> CapabilityInvocationRequest.fromWire(Map.of(
                        "contractVersion", 1,
                        "invocationId", "inv-1",
                        "qualifiedName", "orders:query",
                        "deadlineEpochMs", "tomorrow")));
    }
}
