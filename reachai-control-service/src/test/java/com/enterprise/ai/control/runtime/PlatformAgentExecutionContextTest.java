package com.enterprise.ai.control.runtime;

import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor;
import com.enterprise.ai.control.identity.PlatformPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PlatformAgentExecutionContextTest {
    @AfterEach void resetRequest() { RequestContextHolder.resetRequestAttributes(); }

    @Test void syncAndDetailedUseSessionRolesAndSignedTenantInsteadOfClientIdentity() {
        RuntimeTrustedAgentExecutionGateway gateway = mock(RuntimeTrustedAgentExecutionGateway.class);
        var controller = controller(gateway);
        var request = request();
        when(gateway.executeTrusted(any(), eq("AGENT"), eq("7")))
                .thenReturn(ResponseEntity.ok(Map.of("success", true)));

        controller.executeAgent(null, request);
        controller.executeAgentDetailed(null, request);

        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(gateway, times(2)).executeTrusted(body.capture(), eq("AGENT"), eq("7"));
        body.getAllValues().forEach(this::assertContext);
        assertEquals("forged-tenant", request.get("tenantId"));
        assertEquals("attacker", ((Map<?, ?>) request.get("metadata")).get("userId"));
    }

    @Test void streamUsesTheSameAttestedContextBeforeSigningFinalBytes() throws Exception {
        RuntimeTrustedAgentExecutionGateway gateway = mock(RuntimeTrustedAgentExecutionGateway.class);
        var controller = controller(gateway);
        var output = new ByteArrayOutputStream();

        controller.executeAgentStream(null, request()).getBody().writeTo(output);

        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(gateway).streamTrusted(body.capture(), eq("AGENT"), eq("7"), eq(output), any());
        assertContext(body.getValue());
    }

    private ControlRuntimePublicController controller(RuntimeTrustedAgentExecutionGateway gateway) {
        var http = new MockHttpServletRequest();
        http.setAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE,
                new PlatformAuthenticatedSession(new PlatformPrincipal(7L, "tester", "tester"),
                        "test-session", LocalDateTime.now().plusMinutes(5), List.of("PROJECT_OWNER"),
                        List.of(), List.of()));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(http));
        return new ControlRuntimePublicController(mock(RuntimeProxyClient.class), null,
                mock(RuntimeAgentStreamProxy.class), gateway, null);
    }

    private Map<String, Object> request() {
        return Map.of("message", "query", "userId", "attacker", "tenantId", "forged-tenant",
                "roles", List.of("PLATFORM_ADMIN"), "metadata", Map.of("tenantId", "metadata-tenant",
                        "roles", List.of("SUPERUSER"), "userId", "attacker", "projectCode", "sales"));
    }

    private void assertContext(Map<String, Object> body) {
        assertEquals("default", body.get("tenantId"));
        assertEquals(List.of("PROJECT_OWNER"), body.get("roles"));
        assertEquals("7", body.get("userId"));
        Map<?, ?> metadata = (Map<?, ?>) body.get("metadata");
        assertEquals("default", metadata.get("tenantId"));
        assertEquals(List.of("PROJECT_OWNER"), metadata.get("roles"));
        assertEquals("7", metadata.get("userId"));
        assertEquals("sales", metadata.get("projectCode"));
    }
}
