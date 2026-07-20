package com.enterprise.ai.runtime.internalauth;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import com.enterprise.ai.runtime.api.SseHeartbeatSupport;
import com.enterprise.ai.runtime.execution.RuntimeAgentExecutionService;
import com.enterprise.ai.runtime.execution.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.internal.RuntimeAgentExecutionInternalController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InternalServiceAuthFilterMockMvcTest {

    private static final String SECRET = "unit-test-internal-secret";
    private static final String PATH = "/internal/runtime/agents/execute";

    private RuntimeAgentExecutionService executionService;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        executionService = mock(RuntimeAgentExecutionService.class);
        when(executionService.execute(any(), anyBoolean(), any(), any(), any()))
                .thenReturn(Map.of("success", true, "answer", "ok"));
        InternalServiceAuthProperties properties =
                new InternalServiceAuthProperties(SECRET, 300, 600, 1000, 1_048_576);
        InternalServiceAuthVerifier verifier =
                new InternalServiceAuthVerifier(properties, new InMemoryInternalAuthNonceStore());
        RuntimeAgentExecutionInternalController controller =
                new RuntimeAgentExecutionInternalController(
                        executionService, new SseHeartbeatSupport(), 8_000L);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new InternalServiceAuthFilter(verifier, properties))
                .build();
    }

    @Test
    void rejectsMissingSignatureWithoutCallingExecutionService() throws Exception {
        byte[] body = bodyBytes("AGENT", "42", "hi");
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        verify(executionService, never()).execute(any(), anyBoolean(), any(), any(), any());
    }

    @Test
    void rejectsBodyTamperAfterSigning() throws Exception {
        byte[] original = bodyBytes("AGENT", "42", "original");
        Map<String, String> headers = sign("AGENT", "42", System.currentTimeMillis(),
                UUID.randomUUID().toString(), original);
        byte[] tampered = bodyBytes("AGENT", "42", "tampered-message");
        mockMvc.perform(signed(headers, tampered)).andExpect(status().isUnauthorized());
        verify(executionService, never()).execute(any(), anyBoolean(), any(), any(), any());
    }

    @Test
    void rejectsAgentIdTamperAndBodyShaHeaderTamper() throws Exception {
        byte[] original = bodyBytes("AGENT", "42", "hi");
        Map<String, String> headers = sign("AGENT", "42", System.currentTimeMillis(),
                UUID.randomUUID().toString(), original);
        Map<String, Object> env = objectMapper.readValue(original, Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = new LinkedHashMap<>((Map<String, Object>) env.get("body"));
        body.put("agentId", "attacker-agent");
        env.put("body", body);
        byte[] tamperedAgent = objectMapper.writeValueAsBytes(env);
        mockMvc.perform(signed(headers, tamperedAgent)).andExpect(status().isUnauthorized());

        Map<String, String> headers2 = sign("AGENT", "42", System.currentTimeMillis(),
                UUID.randomUUID().toString(), original);
        headers2.put(InternalServiceAuthHeaders.BODY_SHA256, "0".repeat(64));
        mockMvc.perform(signed(headers2, original)).andExpect(status().isUnauthorized());
        verify(executionService, never()).execute(any(), anyBoolean(), any(), any(), any());
    }

    @Test
    void rejectsExpiredTimestampAndReplayAndBadSignature() throws Exception {
        byte[] body = bodyBytes("AGENT", "42", "hi");
        Map<String, String> expired = sign("AGENT", "42", System.currentTimeMillis() - 3_600_000L,
                UUID.randomUUID().toString(), body);
        mockMvc.perform(signed(expired, body)).andExpect(status().isUnauthorized());

        String nonce = "replay-nonce";
        Map<String, String> ok = sign("AGENT", "42", System.currentTimeMillis(), nonce, body);
        mockMvc.perform(signed(ok, body)).andExpect(status().isOk());
        mockMvc.perform(signed(ok, body)).andExpect(status().isUnauthorized());

        Map<String, String> bad = sign("AGENT", "42", System.currentTimeMillis(),
                UUID.randomUUID().toString(), body);
        bad.put(InternalServiceAuthHeaders.SIGNATURE, "deadbeef");
        mockMvc.perform(signed(bad, body)).andExpect(status().isUnauthorized());
    }

    @Test
    void acceptsValidSignatureWithChineseUtf8Body() throws Exception {
        AtomicReference<WorkflowExecutionIdentity> captured = new AtomicReference<>();
        when(executionService.execute(any(), anyBoolean(), any(), any(), any())).thenAnswer(inv -> {
            captured.set(inv.getArgument(4));
            return Map.of("success", true, "answer", "ok");
        });
        byte[] body = bodyBytes("AGENT", "42", "查订单");
        Map<String, String> headers = sign("AGENT", "42", System.currentTimeMillis(),
                UUID.randomUUID().toString(), body);
        mockMvc.perform(signed(headers, body)).andExpect(status().isOk());
        assertNotNull(captured.get());
        assertTrue(captured.get().userTrusted());
        assertEquals("42", captured.get().userId());
    }

    @Test
    void rejectsIdentityMismatchAgainstSignedHeaders() throws Exception {
        byte[] body = bodyBytes("AGENT", "attacker", "hi");
        Map<String, String> headers = sign("AGENT", "42", System.currentTimeMillis(),
                UUID.randomUUID().toString(), bodyBytes("AGENT", "42", "hi"));
        // signed for different body than sent
        mockMvc.perform(signed(headers, body)).andExpect(status().isUnauthorized());
        verify(executionService, never()).execute(any(), anyBoolean(), any(), any(), any());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder signed(
            Map<String, String> headers, byte[] body) {
        return post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header(InternalServiceAuthHeaders.CALLER, headers.get(InternalServiceAuthHeaders.CALLER))
                .header(InternalServiceAuthHeaders.TIMESTAMP, headers.get(InternalServiceAuthHeaders.TIMESTAMP))
                .header(InternalServiceAuthHeaders.NONCE, headers.get(InternalServiceAuthHeaders.NONCE))
                .header(InternalServiceAuthHeaders.IDENTITY_SOURCE, headers.get(InternalServiceAuthHeaders.IDENTITY_SOURCE))
                .header(InternalServiceAuthHeaders.IDENTITY_USER_ID, headers.get(InternalServiceAuthHeaders.IDENTITY_USER_ID))
                .header(InternalServiceAuthHeaders.BODY_SHA256, headers.get(InternalServiceAuthHeaders.BODY_SHA256))
                .header(InternalServiceAuthHeaders.SIGNATURE, headers.get(InternalServiceAuthHeaders.SIGNATURE))
                .content(body);
    }

    private Map<String, String> sign(String source, String userId, long timestamp, String nonce, byte[] body) {
        String digest = InternalServiceHmac.bodySha256Hex(body);
        String canonical = InternalServiceHmac.canonical(
                "POST", PATH, InternalServiceAuthHeaders.CALLER_CONTROL, source, userId,
                String.valueOf(timestamp), nonce, digest);
        String signature = InternalServiceHmac.sign(SECRET, canonical);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(InternalServiceAuthHeaders.CALLER, InternalServiceAuthHeaders.CALLER_CONTROL);
        headers.put(InternalServiceAuthHeaders.TIMESTAMP, String.valueOf(timestamp));
        headers.put(InternalServiceAuthHeaders.NONCE, nonce);
        headers.put(InternalServiceAuthHeaders.IDENTITY_SOURCE, source);
        headers.put(InternalServiceAuthHeaders.IDENTITY_USER_ID, userId);
        headers.put(InternalServiceAuthHeaders.BODY_SHA256, digest);
        headers.put(InternalServiceAuthHeaders.SIGNATURE, signature);
        return headers;
    }

    private byte[] bodyBytes(String source, String userId, String message) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("agentId", "demo");
        body.put("message", message);
        body.put("userId", "attacker");
        payload.put("body", body);
        payload.put("identity", Map.of("source", source, "userId", userId));
        return objectMapper.writeValueAsBytes(payload);
    }
}
