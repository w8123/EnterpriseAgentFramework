package com.enterprise.ai.control.aiassist;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ControlAiCodingAgentDraftGatewayTest {
    private static final String SECRET = "fixture-project-agent-internal-secret-32bytes";
    private final RuntimeProxyClient runtime = mock(RuntimeProxyClient.class);
    private final ObjectMapper json = new ObjectMapper();
    private final ControlAiCodingAgentDraftGateway gateway = new ControlAiCodingAgentDraftGateway(runtime,
            new InternalServiceAuthSigner(SECRET), json);

    @AfterEach void clearRequest() { RequestContextHolder.resetRequestAttributes(); }

    @Test void versionHistoryRequiresTheSameSignedProjectAndRejectsForeignEntries() {
        bind(7L);
        when(runtime.projectAgentConfigVersions(anyMap(), any())).thenReturn(ResponseEntity.ok(Map.of("agentId", "agent-1", "projectId", 7L,
                "projectCode", "orders", "versions", java.util.List.of(Map.of("id", 11L, "agentId", "agent-foreign")))));
        assertEquals(502, assertThrows(ResponseStatusException.class, () -> gateway.versions(7L, "orders", "agent-1")).getStatusCode().value());
        when(runtime.projectAgentConfigVersions(anyMap(), any())).thenReturn(ResponseEntity.ok(Map.of("agentId", "agent-1", "projectId", 7L,
                "projectCode", "orders", "versions", java.util.List.of(Map.of("id", 11L, "agentId", "agent-1")))));
        assertEquals(1, ((java.util.List<?>) gateway.versions(7L, "orders", "agent-1").get("versions")).size());
        verify(runtime, never()).listAgentConfigVersions(anyString());
    }

    @Test void signsExactProjectRevisionAndUtf8BodyWithoutForwardingCredentialOrForgedActor() throws Exception {
        var actor = bind(7L);
        when(runtime.saveProjectAgentDraft(anyMap(), any())).thenReturn(ResponseEntity.ok(view("DRAFT", 11L, "orders")));
        gateway.save(7L, "orders", "agent-1", "a".repeat(64), Map.of("systemPrompt", "你好，项目助手"));
        var body = ArgumentCaptor.forClass(byte[].class);
        @SuppressWarnings("unchecked") var headers = ArgumentCaptor.forClass(Map.class);
        verify(runtime).saveProjectAgentDraft(headers.capture(), body.capture());
        var command = json.readTree(body.getValue());
        assertEquals("a".repeat(64), command.path("expectedRevision").asText());
        assertEquals(7, command.path("projectId").asInt());
        assertEquals("你好，项目助手", command.path("changes").path("systemPrompt").asText());
        @SuppressWarnings("unchecked") Map<String, String> signed = headers.getValue();
        assertEquals(actor.actorId(), signed.get(InternalServiceAuthHeaders.IDENTITY_USER_ID));
        assertEquals("orders", signed.get(InternalServiceAuthHeaders.IDENTITY_TENANT_ID));
        assertEquals(InternalServiceAuthHeaders.IDENTITY_SOURCE_PROJECT_CREDENTIAL, signed.get(InternalServiceAuthHeaders.IDENTITY_SOURCE));
        assertFalse(signed.toString().contains("rac_secret"));
        assertFalse(signed.toString().contains("forged-user"));
        assertEquals(InternalServiceHmac.bodySha256Hex(body.getValue()), signed.get(InternalServiceAuthHeaders.BODY_SHA256));
        String canonical = InternalServiceHmac.canonical("POST", "/internal/runtime/project-agent-config-drafts/save",
                InternalServiceAuthHeaders.CALLER_CONTROL, InternalServiceAuthHeaders.IDENTITY_SOURCE_PROJECT_CREDENTIAL,
                "orders", actor.actorId(), signed.get(InternalServiceAuthHeaders.TIMESTAMP),
                signed.get(InternalServiceAuthHeaders.NONCE), signed.get(InternalServiceAuthHeaders.BODY_SHA256));
        assertEquals(InternalServiceHmac.sign(SECRET, canonical), signed.get(InternalServiceAuthHeaders.SIGNATURE));
        verifyNoMoreInteractions(runtime);
    }

    @Test void headersAloneAndAnotherVerifiedProjectCannotDispatch() {
        var request = new MockHttpServletRequest();
        request.addHeader("X-ReachAI-AiCoding-Key", "rac_secret");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        assertThrows(ResponseStatusException.class, () -> gateway.context(7L, "orders", "agent-1"));
        bind(8L);
        assertThrows(ResponseStatusException.class, () -> gateway.context(7L, "orders", "agent-1"));
        verifyNoInteractions(runtime);
    }

    @Test void refusedOrUnconfirmedWritesNeverFallbackOrRetry() {
        bind(7L);
        when(runtime.saveProjectAgentDraft(anyMap(), any())).thenReturn(ResponseEntity.status(409).build());
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> gateway.save(7L, "orders", "agent-1", "a".repeat(64), Map.of("maxPlanSteps", 5))).getStatusCode().value());
        verify(runtime, times(1)).saveProjectAgentDraft(anyMap(), any());
        verifyNoMoreInteractions(runtime);
        reset(runtime);
        when(runtime.publishProjectAgentDraft(anyMap(), any())).thenReturn(ResponseEntity.ok(view("ACTIVE", 12L, "orders")));
        assertEquals(502, assertThrows(ResponseStatusException.class,
                () -> gateway.publish(7L, "orders", "agent-1", "a".repeat(64), 11L)).getStatusCode().value());
        verify(runtime, times(1)).publishProjectAgentDraft(anyMap(), any());
        verifyNoMoreInteractions(runtime);
    }

    @Test void contextMustMatchExactOwnerAndPublishMustConfirmExactVersion() {
        bind(7L);
        when(runtime.projectAgentDraftContext(anyMap(), any())).thenReturn(ResponseEntity.ok(view("DRAFT", 11L, "billing")));
        assertEquals(502, assertThrows(ResponseStatusException.class,
                () -> gateway.context(7L, "orders", "agent-1")).getStatusCode().value());
        when(runtime.publishProjectAgentDraft(anyMap(), any())).thenReturn(ResponseEntity.ok(view("ACTIVE", 11L, "orders")));
        assertEquals("b".repeat(64), gateway.publish(7L, "orders", "agent-1", "a".repeat(64), 11L).get("revision"));
    }

    private VerifiedAiCodingProject bind(Long id) {
        var request = new MockHttpServletRequest();
        request.addHeader(InternalServiceAuthHeaders.IDENTITY_USER_ID, "forged-user");
        var actor = VerifiedAiCodingProject.fromVerifiedKey(id, "rac_secret");
        request.setAttribute(VerifiedAiCodingProject.REQUEST_ATTRIBUTE, actor);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return actor;
    }
    private Map<String, Object> view(String status, Long id, String code) {
        return Map.of("agentId", "agent-1", "projectId", 7L, "projectCode", code, "revision", "b".repeat(64),
                "base", Map.of("id", id, "agentId", "agent-1", "status", status));
    }
}
