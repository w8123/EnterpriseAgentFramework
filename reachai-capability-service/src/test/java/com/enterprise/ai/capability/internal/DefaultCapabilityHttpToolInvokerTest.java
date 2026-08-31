package com.enterprise.ai.capability.internal;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultCapabilityHttpToolInvokerTest {

    @Test
    void appliesDeadlineAndPropagatesIdempotencyAndTraceContext() {
        RestTemplateBuilder builder = mock(RestTemplateBuilder.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        CapabilityOutboundTransportPolicy policy = mock(CapabilityOutboundTransportPolicy.class);
        when(builder.connectTimeout(any(Duration.class))).thenReturn(builder);
        when(builder.readTimeout(any(Duration.class))).thenReturn(builder);
        when(builder.build()).thenReturn(restTemplate);
        when(restTemplate.exchange(eq("https://orders.example/query"), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(Object.class)))
                .thenReturn(ResponseEntity.ok(Map.of("ok", true)));
        long deadline = System.currentTimeMillis() + 30_000L;
        CapabilityHttpToolInvocation invocation = new CapabilityHttpToolInvocation(
                "POST",
                "https://orders.example/query",
                Map.of("orderNo", "A001"),
                Map.of(
                        "deadlineEpochMs", deadline,
                        "idempotencyKey", "wf:inv-7",
                        "traceContext", Map.of(
                                "traceparent",
                                "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01")));

        Map<String, Object> result =
                new DefaultCapabilityHttpToolInvoker(builder, policy).invoke(invocation);

        assertEquals(200, result.get("statusCode"));
        verify(policy).requireAllowed("https://orders.example/query");
        verify(builder).connectTimeout(any(Duration.class));
        verify(builder).readTimeout(any(Duration.class));
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<HttpEntity> entity = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq("https://orders.example/query"), eq(HttpMethod.POST),
                entity.capture(), eq(Object.class));
        assertEquals("wf:inv-7", entity.getValue().getHeaders().getFirst("Idempotency-Key"));
        assertEquals("00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
                entity.getValue().getHeaders().getFirst("traceparent"));
    }

    @Test
    void rejectsAnExpiredDeadlineBeforeNetworkIo() {
        assertThrows(IllegalStateException.class, () ->
                DefaultCapabilityHttpToolInvoker.remainingMillis(
                        Map.of("deadlineEpochMs", 99L), 100L));
    }
}
