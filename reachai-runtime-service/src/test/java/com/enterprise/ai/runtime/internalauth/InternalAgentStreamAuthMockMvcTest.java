package com.enterprise.ai.runtime.internalauth;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import com.enterprise.ai.runtime.api.SseHeartbeatSupport;
import com.enterprise.ai.runtime.execution.RuntimeAgentExecutionService;
import com.enterprise.ai.runtime.execution.TrustedControlTiming;
import com.enterprise.ai.runtime.execution.identity.WorkflowExecutionIdentity;
import com.enterprise.ai.runtime.internal.RuntimeAgentExecutionInternalController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InternalAgentStreamAuthMockMvcTest {

    private static final String SECRET = "unit-test-internal-secret";
    private static final String PATH = "/internal/runtime/agents/execute/stream";

    private RuntimeAgentExecutionService executionService;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        executionService = mock(RuntimeAgentExecutionService.class);
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
    void rejectsUnsignedInternalStreamWithoutCallingExecute() throws Exception {
        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(bodyBytes("EMBED_SESSION", "claims-user", "hi")))
                .andExpect(status().isUnauthorized());
        verify(executionService, never()).execute(any(), anyBoolean(), any(), any(), any());
        verify(executionService, never()).execute(any(), anyBoolean(), any(), any(), any(), any());
        verify(executionService, never()).execute(any(), anyBoolean(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsStreamBodyTamperAfterSigning() throws Exception {
        byte[] original = bodyBytes("EMBED_SESSION", "claims-user", "original");
        Map<String, String> headers = sign("EMBED_SESSION", "claims-user",
                System.currentTimeMillis(), UUID.randomUUID().toString(), original);
        byte[] tampered = bodyBytes("EMBED_SESSION", "claims-user", "tampered");
        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .header(InternalServiceAuthHeaders.CALLER, headers.get(InternalServiceAuthHeaders.CALLER))
                        .header(InternalServiceAuthHeaders.TIMESTAMP, headers.get(InternalServiceAuthHeaders.TIMESTAMP))
                        .header(InternalServiceAuthHeaders.NONCE, headers.get(InternalServiceAuthHeaders.NONCE))
                        .header(InternalServiceAuthHeaders.IDENTITY_SOURCE,
                                headers.get(InternalServiceAuthHeaders.IDENTITY_SOURCE))
                        .header(InternalServiceAuthHeaders.IDENTITY_USER_ID,
                                headers.get(InternalServiceAuthHeaders.IDENTITY_USER_ID))
                        .header(InternalServiceAuthHeaders.BODY_SHA256,
                                headers.get(InternalServiceAuthHeaders.BODY_SHA256))
                        .header(InternalServiceAuthHeaders.SIGNATURE, headers.get(InternalServiceAuthHeaders.SIGNATURE))
                        .content(tampered))
                .andExpect(status().isUnauthorized());
        verify(executionService, never()).execute(any(), anyBoolean(), any(), any(), any());
        verify(executionService, never()).execute(any(), anyBoolean(), any(), any(), any(), any());
        verify(executionService, never()).execute(any(), anyBoolean(), any(), any(), any(), any(), any());
    }

    @Test
    void signedInternalStreamDeliversTrustedIdentityToExecute() throws Exception {
        AtomicReference<WorkflowExecutionIdentity> captured = new AtomicReference<>();
        when(executionService.execute(any(), anyBoolean(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            captured.set(inv.getArgument(4));
            return Map.of("success", true, "answer", "stream-ok", "sessionId", "s1");
        });
        byte[] body = bodyBytes("EMBED_SESSION", "claims-user", "查订单");
        Map<String, String> headers = sign("EMBED_SESSION", "claims-user",
                System.currentTimeMillis(), UUID.randomUUID().toString(), body);
        MvcResult mvcResult = mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .header(InternalServiceAuthHeaders.CALLER, headers.get(InternalServiceAuthHeaders.CALLER))
                        .header(InternalServiceAuthHeaders.TIMESTAMP, headers.get(InternalServiceAuthHeaders.TIMESTAMP))
                        .header(InternalServiceAuthHeaders.NONCE, headers.get(InternalServiceAuthHeaders.NONCE))
                        .header(InternalServiceAuthHeaders.IDENTITY_SOURCE,
                                headers.get(InternalServiceAuthHeaders.IDENTITY_SOURCE))
                        .header(InternalServiceAuthHeaders.IDENTITY_USER_ID,
                                headers.get(InternalServiceAuthHeaders.IDENTITY_USER_ID))
                        .header(InternalServiceAuthHeaders.BODY_SHA256,
                                headers.get(InternalServiceAuthHeaders.BODY_SHA256))
                        .header(InternalServiceAuthHeaders.SIGNATURE, headers.get(InternalServiceAuthHeaders.SIGNATURE))
                        .content(body))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(mvcResult)).andExpect(status().isOk());

        assertNotNull(captured.get());
        assertTrue(captured.get().userTrusted());
        assertEquals("claims-user", captured.get().userId());
        assertEquals(WorkflowExecutionIdentity.Source.EMBED_SESSION, captured.get().source());
    }

    @Test
    void signedInternalStreamPassesControlTimingAfterAuth() throws Exception {
        AtomicReference<TrustedControlTiming> capturedTiming = new AtomicReference<>();
        AtomicReference<Map<String, Object>> capturedBody = new AtomicReference<>();
        when(executionService.execute(any(), anyBoolean(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            capturedBody.set(inv.getArgument(0));
            capturedTiming.set(inv.getArgument(5));
            return Map.of("success", true, "answer", "stream-ok", "sessionId", "s1",
                    "metadata", Map.of("traceId", "t1"));
        });
        byte[] body = bodyBytesWithTiming("EMBED_SESSION", "claims-user", "你好",
                Map.of(
                        "control.sessionLookupMs", 12,
                        "control.userMessageAuditMs", 3,
                        "control.preRuntimeMs", 18));
        Map<String, String> headers = sign("EMBED_SESSION", "claims-user",
                System.currentTimeMillis(), UUID.randomUUID().toString(), body);
        MvcResult mvcResult = mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .header(InternalServiceAuthHeaders.CALLER, headers.get(InternalServiceAuthHeaders.CALLER))
                        .header(InternalServiceAuthHeaders.TIMESTAMP, headers.get(InternalServiceAuthHeaders.TIMESTAMP))
                        .header(InternalServiceAuthHeaders.NONCE, headers.get(InternalServiceAuthHeaders.NONCE))
                        .header(InternalServiceAuthHeaders.IDENTITY_SOURCE,
                                headers.get(InternalServiceAuthHeaders.IDENTITY_SOURCE))
                        .header(InternalServiceAuthHeaders.IDENTITY_USER_ID,
                                headers.get(InternalServiceAuthHeaders.IDENTITY_USER_ID))
                        .header(InternalServiceAuthHeaders.BODY_SHA256,
                                headers.get(InternalServiceAuthHeaders.BODY_SHA256))
                        .header(InternalServiceAuthHeaders.SIGNATURE, headers.get(InternalServiceAuthHeaders.SIGNATURE))
                        .content(body))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(mvcResult)).andExpect(status().isOk());

        assertNotNull(capturedTiming.get());
        assertEquals(12L, capturedTiming.get().sessionLookupMs());
        assertEquals(3L, capturedTiming.get().userMessageAuditMs());
        assertEquals(18L, capturedTiming.get().preRuntimeMs());
        assertFalse(capturedBody.get().containsKey("controlTiming"));
    }

    @Test
    void signedInternalStreamEmitsApprovalUiBeforeCompletingWaitingTurn() throws Exception {
        Map<String, Object> uiRequest = Map.of(
                "schemaVersion", "1.0",
                "interactionId", "approval-1",
                "component", "confirm",
                "message", "请确认停用班组3");
        when(executionService.execute(any(), anyBoolean(), any(), any(), any(), any(), any())).thenReturn(Map.of(
                "success", false,
                "answer", "请确认是否停用班组3",
                "sessionId", "s-approval",
                "uiRequest", uiRequest,
                "metadata", Map.of(
                        "code", "SUPERVISOR_CONFIRMATION_REQUIRED",
                        "interactionPending", true,
                        "interactionId", "approval-1")));
        byte[] body = bodyBytes("EMBED_SESSION", "claims-user", "请停用班组3");
        Map<String, String> headers = sign("EMBED_SESSION", "claims-user",
                System.currentTimeMillis(), UUID.randomUUID().toString(), body);

        MvcResult mvcResult = mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .header(InternalServiceAuthHeaders.CALLER, headers.get(InternalServiceAuthHeaders.CALLER))
                        .header(InternalServiceAuthHeaders.TIMESTAMP, headers.get(InternalServiceAuthHeaders.TIMESTAMP))
                        .header(InternalServiceAuthHeaders.NONCE, headers.get(InternalServiceAuthHeaders.NONCE))
                        .header(InternalServiceAuthHeaders.IDENTITY_SOURCE,
                                headers.get(InternalServiceAuthHeaders.IDENTITY_SOURCE))
                        .header(InternalServiceAuthHeaders.IDENTITY_USER_ID,
                                headers.get(InternalServiceAuthHeaders.IDENTITY_USER_ID))
                        .header(InternalServiceAuthHeaders.BODY_SHA256,
                                headers.get(InternalServiceAuthHeaders.BODY_SHA256))
                        .header(InternalServiceAuthHeaders.SIGNATURE, headers.get(InternalServiceAuthHeaders.SIGNATURE))
                        .content(body))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult completed = mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andReturn();

        String streamed = completed.getResponse().getContentAsString();
        assertTrue(streamed.contains("event:ui.requested"));
        assertTrue(streamed.contains("event:turn.waiting"));
        assertTrue(streamed.contains("event:execution.completed"));
        assertFalse(streamed.contains("event:execution.error"));
        assertTrue(streamed.indexOf("event:ui.requested") < streamed.indexOf("event:execution.completed"));
    }

    @Test
    void unsignedInternalStreamWithControlTimingNeverCallsExecute() throws Exception {
        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(bodyBytesWithTiming("EMBED_SESSION", "claims-user", "hi",
                                Map.of("control.sessionLookupMs", 987654))))
                .andExpect(status().isUnauthorized());
        verify(executionService, never()).execute(any(), anyBoolean(), any(), any(), any(), any());
        verify(executionService, never()).execute(any(), anyBoolean(), any(), any(), any());
        verify(executionService, never()).execute(any(), anyBoolean(), any(), any(), any(), any(), any());
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
        payload.put("body", Map.of("agentId", "demo", "message", message, "userId", "attacker"));
        payload.put("identity", Map.of("source", source, "userId", userId));
        return objectMapper.writeValueAsBytes(payload);
    }

    private byte[] bodyBytesWithTiming(String source, String userId, String message,
                                       Map<String, Object> controlTiming) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("agentId", "demo");
        body.put("message", message);
        body.put("userId", "attacker");
        body.put("controlTiming", controlTiming);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("body", body);
        payload.put("identity", Map.of("source", source, "userId", userId));
        return objectMapper.writeValueAsBytes(payload);
    }
}
