package com.enterprise.ai.control.runtime;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformPrincipal;
import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import feign.FeignException;
import feign.Request;
import java.nio.charset.StandardCharsets;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuntimeAgentDraftGatewayTest {
    private static final String SECRET = "fixture-internal-signing-secret-32-bytes";
    private final RuntimeProxyClient runtime = mock(RuntimeProxyClient.class);
    private final ObjectMapper json = new ObjectMapper();
    private final RuntimeAgentDraftGateway gateway = new RuntimeAgentDraftGateway(runtime, new InternalServiceAuthSigner(SECRET), json);

    @Test void propagatesOnlyTheRecognizedRiskErrorCode() {
        var actor = new PlatformAuthenticatedSession(new PlatformPrincipal(42L, "operator", "Operator"), "session",
                null, List.of(), List.of(), List.of());
        var request = Request.create(Request.HttpMethod.POST, "/internal", Map.of(), null, StandardCharsets.UTF_8, null);
        when(runtime.saveAgentDraftIfUnchanged(anyMap(), any())).thenThrow(new FeignException.BadRequest(
                "bad", request, "{\"code\":\"BUSINESS_METHOD_WORKFLOW_RISK_DOWNGRADE\"}".getBytes(StandardCharsets.UTF_8), Map.of()));
        var result = gateway.save("agent-1", 7L, "orders", "a".repeat(64), Map.of("tools", List.of()), actor);
        assertEquals(400, result.getStatusCode().value());
        assertEquals("BUSINESS_METHOD_WORKFLOW_RISK_DOWNGRADE", ((Map<?, ?>) result.getBody()).get("code"));
    }

    @Test void signsTheExactRevisionProjectActorAndUtf8Changes() throws Exception {
        var actor = new PlatformAuthenticatedSession(new PlatformPrincipal(42L, "operator", "Operator"), "session",
                null, List.of(), List.of(), List.of());
        gateway.save("agent-1", 7L, "orders", "a".repeat(64), Map.of("systemPrompt", "你好，订单助手"), actor);
        var body = ArgumentCaptor.forClass(byte[].class);
        @SuppressWarnings("unchecked") var headers = ArgumentCaptor.forClass(Map.class);
        verify(runtime).saveAgentDraftIfUnchanged(headers.capture(), body.capture());
        var command = json.readTree(body.getValue());
        assertEquals("agent-1", command.path("agentId").asText());
        assertEquals(7, command.path("projectId").asInt());
        assertEquals("orders", command.path("projectCode").asText());
        assertEquals("a".repeat(64), command.path("expectedRevision").asText());
        assertEquals("你好，订单助手", command.path("changes").path("systemPrompt").asText());
        @SuppressWarnings("unchecked") Map<String, String> signed = headers.getValue();
        assertEquals("42", signed.get(InternalServiceAuthHeaders.IDENTITY_USER_ID));
        assertEquals("orders", signed.get(InternalServiceAuthHeaders.IDENTITY_TENANT_ID));
        assertEquals(InternalServiceHmac.bodySha256Hex(body.getValue()), signed.get(InternalServiceAuthHeaders.BODY_SHA256));
        String canonical = InternalServiceHmac.canonical("POST", "/internal/runtime/agent-config-drafts/save",
                InternalServiceAuthHeaders.CALLER_CONTROL, InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION,
                "orders", "42", signed.get(InternalServiceAuthHeaders.TIMESTAMP), signed.get(InternalServiceAuthHeaders.NONCE),
                signed.get(InternalServiceAuthHeaders.BODY_SHA256));
        assertEquals(InternalServiceHmac.sign(SECRET, canonical), signed.get(InternalServiceAuthHeaders.SIGNATURE));
    }

    @Test void missingActorOrRevisionCannotDispatch() {
        assertThrows(ResponseStatusException.class, () -> gateway.context("agent-1", 7L, "orders", null));
        assertThrows(IllegalArgumentException.class, () -> gateway.save("agent-1", 7L, "orders", "bad", Map.of("systemPrompt", "x"), null));
        verifyNoInteractions(runtime);
    }
}
