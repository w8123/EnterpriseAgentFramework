package com.enterprise.ai.control.runtime;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformUserEntity;
import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RuntimePageAssistantAttachmentGatewayTest {
    private final RuntimeProxyClient runtime = mock(RuntimeProxyClient.class);
    private final String secret = "fixture-page-assistant-signing-secret-32bytes";
    private final ObjectMapper json = new ObjectMapper();
    private final RuntimePageAssistantAttachmentGateway gateway =
            new RuntimePageAssistantAttachmentGateway(runtime, new InternalServiceAuthSigner(secret), json);

    @Test
    void signsTheAuthorizedProjectAndActorWithTheExactBody() throws Exception {
        when(runtime.attachPageAssistantWorkflowToolSigned(eq("wf-1"), any(), any())).thenReturn(ResponseEntity.ok(Map.of()));
        gateway.attach("wf-1", 7L, "orders", Map.of("baseRevision", "a".repeat(64), "modelInstanceId", "llm-1"), actor(42L));

        var body = ArgumentCaptor.forClass(byte[].class);
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        verify(runtime).attachPageAssistantWorkflowToolSigned(eq("wf-1"), headers.capture(), body.capture());
        var sent = json.readTree(body.getValue());
        assertEquals(7L, sent.path("projectId").asLong());
        assertEquals("orders", sent.path("projectCode").asText());
        assertEquals("a".repeat(64), sent.path("baseRevision").asText());
        assertFalse(sent.path("createTarget").asBoolean());
        assertFalse(sent.has("publishedBy"));
        Map<String, String> signed = headers.getValue();
        assertEquals(InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION, signed.get(InternalServiceAuthHeaders.IDENTITY_SOURCE));
        assertEquals("42", signed.get(InternalServiceAuthHeaders.IDENTITY_USER_ID));
        var canonical = InternalServiceHmac.canonical("POST", "/internal/runtime/workflows/wf-1/page-assistant/attach",
                InternalServiceAuthHeaders.CALLER_CONTROL, InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION,
                "orders", "42", signed.get(InternalServiceAuthHeaders.TIMESTAMP), signed.get(InternalServiceAuthHeaders.NONCE),
                InternalServiceHmac.bodySha256Hex(body.getValue()));
        assertEquals(InternalServiceHmac.sign(secret, canonical), signed.get(InternalServiceAuthHeaders.SIGNATURE));
    }

    @Test
    void browserOrModelCannotChooseProjectOrPublisher() {
        for (String forged : new String[]{"projectId", "projectCode", "publishedBy"}) {
            Map<String, Object> request = new HashMap<>(Map.of("baseRevision", "a".repeat(64)));
            request.put(forged, "x");
            var failure = assertThrows(ResponseStatusException.class, () -> gateway.attach("wf-1", 7L, "orders", request, actor(42L)));
            assertEquals(HttpStatus.BAD_REQUEST, failure.getStatusCode());
        }
        verifyNoInteractions(runtime);
    }

    @Test
    void requiresExactlyOneOfRevisionOrInitialization() {
        assertEquals(400, gateway.attach("wf-1", 7L, "orders", Map.of(), actor(42L)).getStatusCode().value());
        assertEquals(400, gateway.attach("wf-1", 7L, "orders", Map.of("baseRevision", "a".repeat(64), "createTarget", true), actor(42L))
                .getStatusCode().value());
        assertEquals(400, gateway.attach("wf-1", 7L, "orders", Map.of("baseRevision", "stale"), actor(42L)).getStatusCode().value());
        assertThrows(ResponseStatusException.class, () -> gateway.attach("../x", 7L, "orders", Map.of("createTarget", true), actor(42L)));
        assertThrows(ResponseStatusException.class, () -> gateway.attach("wf-1", 7L, "orders", Map.of("createTarget", true), null));
        verifyNoInteractions(runtime);
    }

    private static PlatformAuthenticatedSession actor(Long id) {
        var user = new PlatformUserEntity();
        user.setId(id);
        user.setUsername("dev");
        return new PlatformAuthenticatedSession(com.enterprise.ai.control.identity.PlatformPrincipal.fromUser(user), "s", null, null, null, null);
    }
}
