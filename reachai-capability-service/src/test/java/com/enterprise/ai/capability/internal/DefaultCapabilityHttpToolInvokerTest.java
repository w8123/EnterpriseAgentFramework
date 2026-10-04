package com.enterprise.ai.capability.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
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
                any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(json("{\"ok\":true}"));
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
                new DefaultCapabilityHttpToolInvoker(builder, policy, new ObjectMapper()).invoke(invocation);

        assertEquals(200, result.get("statusCode"));
        assertEquals(Map.of("ok", true), result.get("body"));
        verify(policy).requireAllowed("https://orders.example/query");
        verify(builder).connectTimeout(any(Duration.class));
        verify(builder).readTimeout(any(Duration.class));
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<HttpEntity> entity = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq("https://orders.example/query"), eq(HttpMethod.POST),
                entity.capture(), eq(byte[].class));
        assertEquals("wf:inv-7", entity.getValue().getHeaders().getFirst("Idempotency-Key"));
        assertEquals("00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
                entity.getValue().getHeaders().getFirst("traceparent"));
    }

    @Test
    void preservesAnSdkScalarWhenMvcLabelsAnUnquotedBodyAsJson() {
        RestTemplateBuilder builder = mock(RestTemplateBuilder.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        CapabilityOutboundTransportPolicy policy = mock(CapabilityOutboundTransportPolicy.class);
        when(builder.build()).thenReturn(restTemplate);
        when(restTemplate.exchange(eq("http://127.0.0.1/sdk"), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(json("N-A-1024"));

        Map<String, Object> result = new DefaultCapabilityHttpToolInvoker(builder, policy, new ObjectMapper()).invoke(
                new CapabilityHttpToolInvocation("POST", "http://127.0.0.1/sdk", Map.of(), Map.of()));

        assertEquals("N-A-1024", result.get("body"));
        verify(policy).requireAllowed("http://127.0.0.1/sdk");
    }

    @Test
    void decodesJsonObjectsAndScalarsOnlyWhenTheResponseIsJson() {
        RestTemplateBuilder builder = mock(RestTemplateBuilder.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        CapabilityOutboundTransportPolicy policy = mock(CapabilityOutboundTransportPolicy.class);
        when(builder.build()).thenReturn(restTemplate);
        when(restTemplate.exchange(eq("http://127.0.0.1/sdk"), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(json("{\"ok\":true}"), json("\"quoted\""), json("123"), json("true"),
                        response("{\"type\":\"problem\"}", MediaType.valueOf("application/problem+json")));

        assertEquals(Map.of("ok", true), invoke(builder, policy).get("body"));
        assertEquals("quoted", invoke(builder, policy).get("body"));
        assertEquals(123, invoke(builder, policy).get("body"));
        assertEquals(true, invoke(builder, policy).get("body"));
        assertEquals(Map.of("type", "problem"), invoke(builder, policy).get("body"));
    }

    @Test
    void textResponsesRemainStringsEvenWhenTheyLookLikeJsonScalarsOrObjects() {
        RestTemplateBuilder builder = mock(RestTemplateBuilder.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        CapabilityOutboundTransportPolicy policy = mock(CapabilityOutboundTransportPolicy.class);
        when(builder.build()).thenReturn(restTemplate);
        when(restTemplate.exchange(eq("http://127.0.0.1/sdk"), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(text("123", StandardCharsets.UTF_8), text("true", StandardCharsets.UTF_8),
                        text("{\"ok\":true}", StandardCharsets.UTF_8),
                        text("中文结果", Charset.forName("GB18030")));

        assertEquals("123", invoke(builder, policy).get("body"));
        assertEquals("true", invoke(builder, policy).get("body"));
        assertEquals("{\"ok\":true}", invoke(builder, policy).get("body"));
        assertEquals("中文结果", invoke(builder, policy).get("body"));
    }

    @Test
    void preservesNonTextNonJsonResponsesAsBytesAndTreatsEmptyResponsesAsNull() {
        RestTemplateBuilder builder = mock(RestTemplateBuilder.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        CapabilityOutboundTransportPolicy policy = mock(CapabilityOutboundTransportPolicy.class);
        when(builder.build()).thenReturn(restTemplate);
        byte[] binary = new byte[]{0, 1, 2, (byte) 0xff};
        when(restTemplate.exchange(eq("http://127.0.0.1/sdk"), eq(HttpMethod.POST),
                any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).body(binary),
                        ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).body(new byte[0]));

        assertArrayEquals(binary, assertInstanceOf(byte[].class, invoke(builder, policy).get("body")));
        assertNull(invoke(builder, policy).get("body"));
    }

    @Test
    void rejectsAnExpiredDeadlineBeforeNetworkIo() {
        assertThrows(IllegalStateException.class, () ->
                DefaultCapabilityHttpToolInvoker.remainingMillis(
                        Map.of("deadlineEpochMs", 99L), 100L));
    }

    private Map<String, Object> invoke(
            RestTemplateBuilder builder, CapabilityOutboundTransportPolicy policy) {
        return new DefaultCapabilityHttpToolInvoker(builder, policy, new ObjectMapper()).invoke(
                new CapabilityHttpToolInvocation("POST", "http://127.0.0.1/sdk", Map.of(), Map.of()));
    }

    private static ResponseEntity<byte[]> json(String response) {
        return response(response, MediaType.APPLICATION_JSON);
    }

    private static ResponseEntity<byte[]> response(String value, MediaType contentType) {
        return ResponseEntity.ok().contentType(contentType).body(value.getBytes(StandardCharsets.UTF_8));
    }

    private static ResponseEntity<byte[]> text(String response, Charset charset) {
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "plain", charset))
                .body(response.getBytes(charset));
    }
}
