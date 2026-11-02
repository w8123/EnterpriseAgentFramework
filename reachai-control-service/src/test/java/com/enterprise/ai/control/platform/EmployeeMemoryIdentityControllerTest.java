package com.enterprise.ai.control.platform;

import com.enterprise.ai.control.mcp.application.employee.AiToolConnectionService.EmployeeOwner;
import com.enterprise.ai.control.memory.PersonalMemoryIdentityChallengeService;
import com.enterprise.ai.control.memory.PersonalMemoryIdentityLinkService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmployeeMemoryIdentityControllerTest {
    @Test void passesOnlyTheAuthenticatedSessionToBindingAndRevocation() {
        var sessions = mock(EmployeePortalSessionService.class);
        var challenges = mock(PersonalMemoryIdentityChallengeService.class);
        var links = mock(PersonalMemoryIdentityLinkService.class);
        var controller = new EmployeeMemoryIdentityController(sessions, challenges, links);
        var owner = new EmployeeOwner("tenant", 1L, "erp", "erp", "alice", "alice", List.of());
        when(sessions.resolve("valid")).thenReturn(Optional.of(owner));
        assertEquals("no-store", controller.issue("Bearer valid").getHeaders().getCacheControl());
        controller.confirm("Bearer valid", new EmployeeMemoryIdentityController.ConfirmCommand("code"));
        controller.revoke("Bearer valid", 17);
        verify(challenges).issue("valid");
        verify(challenges).confirm("valid", "code");
        verify(links).revokeByEmployee(owner, 17);
        assertEquals(401, assertThrows(ResponseStatusException.class,
                () -> controller.revoke("Bearer invalid", 17)).getStatusCode().value());
        verifyNoMoreInteractions(links);
    }
}
