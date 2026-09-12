package com.enterprise.ai.control.runtime;

import com.enterprise.ai.control.identity.PlatformPrincipal;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformUserEntity;
import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RuntimeWorkflowReleaseGatewayTest {
    private static final String SECRET = "workflow-release-test-signing-secret-32-bytes";
    private final RuntimeProxyClient client = mock(RuntimeProxyClient.class);
    private final ObjectMapper json = new ObjectMapper();
    private final RuntimeWorkflowReleaseGateway gateway = new RuntimeWorkflowReleaseGateway(
            client, new InternalServiceAuthSigner(SECRET), json);

    @Test
    void callerCannotChoosePublisherAndSignatureCoversTheExactCommand() throws Exception {
        gateway.publish("wf-orders", Map.of("version", "v1", "baseRevision", "seen-revision",
                "publishedBy", "someone-else", "operator", "forged"), session());
        var body = ArgumentCaptor.forClass(byte[].class);
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        verify(client).publishWorkflowVersion(eq("wf-orders"), headers.capture(), body.capture());
        var command = json.readTree(body.getValue());
        assertFalse(command.has("publishedBy"));
        assertFalse(command.has("operator"));
        assertEquals("seen-revision", command.path("baseRevision").asText());
        assertEquals("42", headers.getValue().get(InternalServiceAuthHeaders.IDENTITY_USER_ID));
        assertSignature("/api/workflows/wf-orders/versions/publish", headers.getValue(), body.getValue());
    }

    @Test
    void rollbackBindsCurrentSessionAndOnlyForwardsTheSeenRevision() throws Exception {
        gateway.rollback("wf-orders", 9L, Map.of("operator", "forged", "baseRevision", "seen-revision"), session());
        var body = ArgumentCaptor.forClass(byte[].class);
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        verify(client).rollbackWorkflowVersion(eq("wf-orders"), eq(9L), headers.capture(), body.capture());
        assertEquals(Map.of("baseRevision", "seen-revision"), json.readValue(body.getValue(), Map.class));
        assertSignature("/api/workflows/wf-orders/versions/9/rollback", headers.getValue(), body.getValue());
    }

    @Test
    void absentPlatformSessionCannotDispatch() {
        assertThrows(ResponseStatusException.class, () -> gateway.publish("wf-orders", Map.of(), null));
        assertThrows(ResponseStatusException.class, () -> gateway.rollback("wf-orders", 9L, Map.of(), null));
        verifyNoInteractions(client);
    }

    private void assertSignature(String path, Map<String, String> headers, byte[] body) {
        assertEquals(InternalServiceHmac.bodySha256Hex(body), headers.get(InternalServiceAuthHeaders.BODY_SHA256));
        String canonical = InternalServiceHmac.canonical("POST", path, InternalServiceAuthHeaders.CALLER_CONTROL,
                InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION, "default", "42",
                headers.get(InternalServiceAuthHeaders.TIMESTAMP), headers.get(InternalServiceAuthHeaders.NONCE),
                headers.get(InternalServiceAuthHeaders.BODY_SHA256));
        assertEquals(InternalServiceHmac.sign(SECRET, canonical), headers.get(InternalServiceAuthHeaders.SIGNATURE));
    }

    private PlatformAuthenticatedSession session() {
        var user = new PlatformUserEntity();
        user.setId(42L);
        return new PlatformAuthenticatedSession(PlatformPrincipal.fromUser(user), "session-42", null, List.of(), List.of(), List.of());
    }
}
