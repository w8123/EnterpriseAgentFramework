package com.enterprise.ai.control.runtime;

import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformAuthorizationService;
import com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor;
import com.enterprise.ai.control.identity.PlatformPermissionGrant;
import com.enterprise.ai.control.identity.PlatformPrincipal;
import com.enterprise.ai.control.identity.PlatformRequestAuthorization;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ControlHttpApiFlowActorTest {
    private final RuntimeProxyClient runtime = mock(RuntimeProxyClient.class);
    private final ControlRuntimePublicController controller = new ControlRuntimePublicController(runtime);

    ControlHttpApiFlowActorTest() {
        controller.setRuntimeManagementAccess(new RuntimeManagementAccess(
                new PlatformRequestAuthorization(new PlatformAuthorizationService(null, null, null, null)),
                mock(CapabilityProjectOnboardingClient.class)));
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void generatedFlowsUseAuthenticatedPublisherInsteadOfClientValue() {
        var request = new MockHttpServletRequest();
        request.setAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE,
                new PlatformAuthenticatedSession(new PlatformPrincipal(42L, "alice", "Alice"), "session", null,
                        List.of(), List.of("workflow:write", "workflow:publish"), List.of(
                        new PlatformPermissionGrant("workflow:write", "PROJECT", "orders"),
                        new PlatformPermissionGrant("workflow:publish", "PROJECT", "orders"))));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(runtime.generateHttpApiFlows(anyMap())).thenReturn(ResponseEntity.ok(Map.of()));

        controller.generateHttpApiFlows(Map.of("projectCode", "orders", "apis", List.of(),
                "publish", true, "publishedBy", "forged"));

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("projectId", null);
        expected.put("projectCode", "orders");
        expected.put("apis", List.of());
        expected.put("publish", true);
        expected.put("publishedBy", "alice");
        verify(runtime).generateHttpApiFlows(expected);
    }

    @Test
    void generatedFlowsRejectMissingPlatformSession() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        var error = assertThrows(ResponseStatusException.class,
                () -> controller.generateHttpApiFlows(Map.of("projectCode", "orders", "publish", true)));
        assertEquals(401, error.getStatusCode().value());
        verify(runtime, never()).generateHttpApiFlows(anyMap());
    }
}
