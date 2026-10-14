package com.enterprise.ai.control.runtime;

import com.enterprise.ai.control.client.capability.CapabilityProjectOnboardingClient;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.identity.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ControlRuntimeRunOpsReplayAuthorizationTest {
    @AfterEach void clearRequest() { RequestContextHolder.resetRequestAttributes(); }

    @Test void replaySignsOnlyCurrentOperatorRolesAndExplicitInput() {
        var proxy = mock(RuntimeProxyClient.class);
        var authorization = mock(PlatformRequestAuthorization.class);
        var gateway = mock(RuntimeTrustedRunOpsReplayGateway.class);
        var controller = new ControlRuntimePublicController(proxy);
        controller.setRuntimeManagementAccess(new RuntimeManagementAccess(authorization, mock(CapabilityProjectOnboardingClient.class)));
        controller.setRunOpsReplayGateway(gateway);
        var user = new PlatformUserEntity(); user.setId(7L);
        var session = new PlatformAuthenticatedSession(PlatformPrincipal.fromUser(user), "session",
                LocalDateTime.now().plusHours(1), List.of("PROJECT_OWNER"), List.of(PlatformPermissions.RUNOPS_OPERATE),
                List.of(new PlatformPermissionGrant(PlatformPermissions.RUNOPS_OPERATE,"PROJECT","orders")));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        when(authorization.requirePermission(any(), eq(PlatformPermissions.RUNOPS_OPERATE))).thenReturn(session);
        when(proxy.runOpsDetail("trace-1")).thenReturn(ResponseEntity.ok(Map.of("summary",Map.of("projectCode","orders"))));
        when(gateway.replay(anyString(),anyMap(),anyString())).thenReturn(ResponseEntity.ok(Map.of("success",true)));
        assertEquals(200,controller.runOpsReplay("trace-1", Map.of("messageOverride","只读查询",
                "sessionId","new-session","userId","original-business-user","roles",List.of("ADMIN"),
                "tenantId","forged-tenant","trustedIdentity",Map.of("userTrusted",true))).getStatusCode().value());
        ArgumentCaptor<Map<String,Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(gateway).replay(eq("trace-1"),body.capture(),eq("7"));
        assertEquals(Map.of("messageOverride","只读查询","sessionId","new-session","roles",List.of("PROJECT_OWNER")),body.getValue());
        verify(proxy,never()).runOpsReplay(anyString(),anyMap());
    }

    @Test void anotherProjectIsDeniedBeforeAnyReplayDispatch() {
        var proxy = mock(RuntimeProxyClient.class);
        var authorization = mock(PlatformRequestAuthorization.class);
        var gateway = mock(RuntimeTrustedRunOpsReplayGateway.class);
        var controller = new ControlRuntimePublicController(proxy);
        controller.setRuntimeManagementAccess(new RuntimeManagementAccess(authorization, mock(CapabilityProjectOnboardingClient.class)));
        controller.setRunOpsReplayGateway(gateway);
        var user = new PlatformUserEntity(); user.setId(7L);
        var session = new PlatformAuthenticatedSession(PlatformPrincipal.fromUser(user), "session",
                LocalDateTime.now().plusHours(1),List.of("PROJECT_OWNER"),List.of(PlatformPermissions.RUNOPS_OPERATE),
                List.of(new PlatformPermissionGrant(PlatformPermissions.RUNOPS_OPERATE,"PROJECT","orders")));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        when(authorization.requirePermission(any(), any())).thenReturn(session);
        when(proxy.runOpsDetail("trace-1")).thenReturn(ResponseEntity.ok(Map.of("summary",Map.of("projectCode","finance"))));
        assertEquals(403,assertThrows(ResponseStatusException.class,
                ()->controller.runOpsReplay("trace-1",Map.of("messageOverride","query"))).getStatusCode().value());
        verifyNoInteractions(gateway);
        verify(proxy,never()).runOpsReplay(anyString(),anyMap());
    }
}
