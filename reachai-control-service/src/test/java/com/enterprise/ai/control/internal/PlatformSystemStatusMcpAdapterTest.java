package com.enterprise.ai.control.internal;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformDelegatedRequestScope;
import com.enterprise.ai.control.identity.PlatformIdentityController;
import com.enterprise.ai.control.identity.PlatformPrincipal;
import com.enterprise.ai.control.mcp.application.port.McpPlatformSystemStatusPort.Read;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class PlatformSystemStatusMcpAdapterTest {
    private final PlatformSystemCheckController check = mock(PlatformSystemCheckController.class);
    private final PlatformSystemStatusMcpAdapter adapter = new PlatformSystemStatusMcpAdapter(mock(InternalServicesHealthController.class), check,
            mock(PlatformIdentityController.class), new PlatformDelegatedRequestScope());

    @AfterEach void clearRequest() { RequestContextHolder.resetRequestAttributes(); }

    @Test
    void systemCheckIsForAdministratorsOnly() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest("POST", "/mcp/platform")));
        var builder = new PlatformAuthenticatedSession(new PlatformPrincipal(7L, "builder", "Builder"), "s", LocalDateTime.now().plusHours(1),
                List.of(), List.of("platform:read"), List.of());
        assertThrows(ResponseStatusException.class, () -> adapter.read(builder, Read.SYSTEM_CHECK));
        verifyNoInteractions(check);

        var admin = new PlatformAuthenticatedSession(new PlatformPrincipal(1L, "admin", "Admin"), "s", LocalDateTime.now().plusHours(1),
                List.of(), List.of("platform:admin"), List.of());
        when(check.systemCheck()).thenReturn(Map.of("summary", Map.of()));
        assertEquals(200, adapter.read(admin, Read.SYSTEM_CHECK).getStatusCode().value());
    }
}
