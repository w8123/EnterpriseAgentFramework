package com.enterprise.ai.control.aiassist;

import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import com.enterprise.ai.common.internalauth.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class ControlAiCodingWorkflowAttachmentGatewayTest {
    private final RuntimeProxyClient runtime = mock(RuntimeProxyClient.class);
    private final String secret = "fixture-attachment-signing-secret-at-least-32bytes";
    private final ObjectMapper json = new ObjectMapper();
    private final ControlAiCodingWorkflowAttachmentGateway gateway = new ControlAiCodingWorkflowAttachmentGateway(runtime, new InternalServiceAuthSigner(secret), json);
    @AfterEach void clear() { RequestContextHolder.resetRequestAttributes(); }
    @Test void signsTheExactProjectIdentityPathAndUtf8Body() throws Exception {
        var request = new MockHttpServletRequest();
        var actor = VerifiedAiCodingProject.fromVerifiedKey(7L, "fixture-key");
        request.setAttribute(VerifiedAiCodingProject.REQUEST_ATTRIBUTE, actor);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        gateway.attach(7L, Map.of("id", 7L, "projectCode", "orders"), Map.of("workflowId", "wf-1", "toolName", "订单查询", "baseRevision", "a".repeat(64)));
        var body = org.mockito.ArgumentCaptor.forClass(byte[].class);
        @SuppressWarnings("unchecked") var headers = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(runtime).attachProjectWorkflowToolSigned(eq(7L), headers.capture(), body.capture());
        @SuppressWarnings("unchecked") Map<String, String> signed = headers.getValue();
        assertEquals("订单查询", json.readTree(body.getValue()).path("toolName").asText());
        assertEquals("a".repeat(64), json.readTree(body.getValue()).path("baseRevision").asText());
        assertEquals(actor.actorId(), signed.get(InternalServiceAuthHeaders.IDENTITY_USER_ID));
        assertFalse(signed.toString().contains("fixture-key"));
        var canonical = InternalServiceHmac.canonical("POST", "/internal/runtime/projects/7/agent-supervisor/workflow-tools/attach",
                InternalServiceAuthHeaders.CALLER_CONTROL, InternalServiceAuthHeaders.IDENTITY_SOURCE_PROJECT_CREDENTIAL,
                "orders", actor.actorId(), signed.get(InternalServiceAuthHeaders.TIMESTAMP), signed.get(InternalServiceAuthHeaders.NONCE),
                InternalServiceHmac.bodySha256Hex(body.getValue()));
        assertEquals(InternalServiceHmac.sign(secret, canonical), signed.get(InternalServiceAuthHeaders.SIGNATURE));
        verifyNoMoreInteractions(runtime);
    }
    @Test void missingOrMismatchedProjectCannotDispatch() {
        assertThrows(ResponseStatusException.class, () -> gateway.attach(7L, Map.of(), Map.of()));
        var request = new MockHttpServletRequest(); request.setAttribute(VerifiedAiCodingProject.REQUEST_ATTRIBUTE, VerifiedAiCodingProject.fromVerifiedKey(7L, "fixture-key"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        assertThrows(ResponseStatusException.class, () -> gateway.attach(8L, Map.of(), Map.of()));
        assertThrows(ResponseStatusException.class, () -> gateway.attach(7L, Map.of("id", 8L, "projectCode", "orders"), Map.of()));
        verifyNoInteractions(runtime);
    }
}
