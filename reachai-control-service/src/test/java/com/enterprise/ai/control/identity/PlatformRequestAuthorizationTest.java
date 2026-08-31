package com.enterprise.ai.control.identity;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PlatformRequestAuthorizationTest {

    private final PlatformAuthorizationService authorizationService = mock(PlatformAuthorizationService.class);
    private final PlatformRequestAuthorization requestAuthorization =
            new PlatformRequestAuthorization(authorizationService);

    @Test
    void rejectsRequestsWithoutAnAttestedPlatformSession() {
        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> requestAuthorization.requireAuthenticated(new MockHttpServletRequest()));

        assertEquals(HttpStatus.UNAUTHORIZED, error.getStatusCode());
    }

    @Test
    void delegatesGlobalPermissionChecksForTheAttestedSession() {
        PlatformAuthenticatedSession session = mock(PlatformAuthenticatedSession.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session);

        PlatformAuthenticatedSession resolved = requestAuthorization.requireGlobalPermission(
                request, PlatformPermissions.BUSINESS_USER_MANAGE);

        assertSame(session, resolved);
        verify(authorizationService).requireGlobalPermission(
                session, PlatformPermissions.BUSINESS_USER_MANAGE);
    }

    @Test
    void delegatesAnyScopeAdmissionBeforeTheDomainResolvesItsResource() {
        PlatformAuthenticatedSession session = mock(PlatformAuthenticatedSession.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session);

        PlatformAuthenticatedSession resolved = requestAuthorization.requirePermission(
                request, PlatformPermissions.AUTOMATION_WRITE);

        assertSame(session, resolved);
        verify(authorizationService).requirePermission(
                session, PlatformPermissions.AUTOMATION_WRITE);
    }
}
