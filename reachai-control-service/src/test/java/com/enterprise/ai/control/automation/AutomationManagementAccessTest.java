package com.enterprise.ai.control.automation;

import com.enterprise.ai.control.identity.PlatformPrincipal;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformPermissionGrant;
import com.enterprise.ai.control.identity.PlatformRequestAuthorization;
import com.enterprise.ai.control.identity.PlatformUserEntity;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class AutomationManagementAccessTest {

    private final AutomationManagementAccess access = new AutomationManagementAccess(
            mock(PlatformRequestAuthorization.class));

    @Test
    void projectGrantCannotReadAnotherAutomationProject() {
        PlatformAuthenticatedSession session = session(
                new PlatformPermissionGrant("automation:read", "PROJECT", "sales"));

        assertDoesNotThrow(() -> access.requireProject(session, "automation:read", "sales"));
        assertThrows(ResponseStatusException.class,
                () -> access.requireProject(session, "automation:read", "finance"));
        assertThrows(ResponseStatusException.class,
                () -> access.requireProject(session, "automation:read", null));
    }

    @Test
    void globalWildcardMayReadAcrossProjectsAndDetailProjectionIsResolved() {
        PlatformAuthenticatedSession session = session(
                new PlatformPermissionGrant("*", "GLOBAL", "*"));

        assertDoesNotThrow(() -> access.requireProject(session, "automation:read", null));
        assertEquals("sales", access.projectCode(Map.of(
                "automation", Map.of("projectCode", "sales"),
                "versions", List.of())));
    }

    private PlatformAuthenticatedSession session(PlatformPermissionGrant grant) {
        PlatformUserEntity user = new PlatformUserEntity();
        user.setId(7L);
        return new PlatformAuthenticatedSession(
                PlatformPrincipal.fromUser(user),
                "session",
                LocalDateTime.now().plusHours(1),
                List.of("PROJECT_OWNER"),
                List.of(grant.permissionCode()),
                List.of(grant));
    }
}
