package com.enterprise.ai.runtime.execution;

import com.enterprise.ai.common.capability.CapabilityInvocationFailureCategory;
import com.enterprise.ai.common.capability.CapabilityInvocationRequest;
import com.enterprise.ai.common.capability.CapabilityInvocationResponse;
import com.enterprise.ai.common.capability.CapabilityInvocationStatus;
import com.enterprise.ai.runtime.client.capability.RuntimeCapabilityCatalogClient;
import com.enterprise.ai.runtime.client.control.RuntimeControlCatalogClient;
import com.enterprise.ai.runtime.client.model.RuntimeModelServiceClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeCapabilityInvocationControlTest {

    @Test
    void retriesOneLogicalInvocationWithStableIdempotencyDeadlineAndTraceparent() {
        RuntimeCapabilityCatalogClient capability = mock(RuntimeCapabilityCatalogClient.class);
        List<Map<String, Object>> requests = new ArrayList<>();
        when(capability.invokeTool(eq("orders:query"), any())).thenAnswer(call -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = new java.util.LinkedHashMap<>(
                    (Map<String, Object>) call.getArgument(1));
            requests.add(request);
            String invocationId = String.valueOf(request.get("invocationId"));
            if (requests.size() == 1) {
                return response(invocationId, CapabilityInvocationStatus.TECHNICAL_FAILED,
                        false, CapabilityInvocationFailureCategory.CONNECTIVITY, true, null);
            }
            return response(invocationId, CapabilityInvocationStatus.SUCCEEDED,
                    true, CapabilityInvocationFailureCategory.NONE, false, Map.of("total", 1));
        });
        RuntimeGraphSpecExecutor executor = new RuntimeGraphSpecExecutor(
                new ObjectMapper(),
                mock(RuntimeModelServiceClient.class),
                capability,
                mock(RuntimeControlCatalogClient.class));

        RuntimeGraphSpecExecutionResult result = executor.execute("""
                {
                  "schemaVersion":2,
                  "entryNodeId":"tool",
                  "exitNodeIds":["tool"],
                  "nodes":[{
                    "id":"tool",
                    "type":"TOOL",
                    "ref":{"kind":"TOOL","qualifiedName":"orders:query"},
                    "retry":{"enabled":true,"maxAttempts":2,"backoffMs":0},
                    "config":{"timeoutMs":60000}
                  }],
                  "edges":[]
                }
                """, Map.of("traceId", "trace-business-7"));

        assertTrue(result.success(), result.code() + " / " + result.answer());
        assertEquals(2, requests.size());
        assertEquals(requests.get(0).get("invocationId"), requests.get(1).get("invocationId"));
        assertEquals(requests.get(0).get("idempotencyKey"), requests.get(1).get("idempotencyKey"));
        assertEquals(requests.get(0).get("deadlineEpochMs"), requests.get(1).get("deadlineEpochMs"));
        assertTrue(((Number) requests.get(0).get("deadlineEpochMs")).longValue()
                > System.currentTimeMillis());
        @SuppressWarnings("unchecked")
        Map<String, Object> traceContext =
                (Map<String, Object>) requests.get(0).get("traceContext");
        assertTrue(String.valueOf(traceContext.get("traceparent"))
                .matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01"));
        assertEquals(2, result.metadata().get("attempt"));
    }

    private CapabilityInvocationResponse response(
            String invocationId,
            CapabilityInvocationStatus status,
            boolean success,
            CapabilityInvocationFailureCategory category,
            boolean retryable,
            Object data) {
        return new CapabilityInvocationResponse(
                CapabilityInvocationRequest.CONTRACT_VERSION,
                invocationId,
                "orders:query",
                "query",
                "Query orders",
                status,
                success,
                data,
                success ? null : "CAPABILITY_CONNECTIVITY_FAILED",
                success ? null : "connection refused",
                category,
                retryable,
                1L,
                1,
                null,
                Map.of());
    }
}
