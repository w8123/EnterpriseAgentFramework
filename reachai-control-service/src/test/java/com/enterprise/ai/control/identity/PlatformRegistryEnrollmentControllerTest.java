package com.enterprise.ai.control.identity;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PlatformRegistryEnrollmentControllerTest {

    @Test
    void rejectsEnrollmentBeforeCallingCapabilityWhenAdminPermissionIsMissing() {
        PlatformRequestAuthorization requestAuthorization = mock(PlatformRequestAuthorization.class);
        CapabilityRegistryEnrollmentGateway enrollmentGateway = mock(CapabilityRegistryEnrollmentGateway.class);
        PlatformAuthAuditService auditService = mock(PlatformAuthAuditService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        PlatformRegistryEnrollmentController controller = new PlatformRegistryEnrollmentController(
                requestAuthorization, enrollmentGateway, auditService);
        when(requestAuthorization.requireGlobalPermission(request, PlatformPermissions.PLATFORM_ADMIN))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> controller.issue(
                        request,
                        new PlatformRegistryEnrollmentController.RegistryEnrollmentCommand("orders")));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verifyNoInteractions(enrollmentGateway, auditService);
    }

    @Test
    void issuesAndAuditsEnrollmentForAnAuthorizedAdministrator() {
        PlatformRequestAuthorization requestAuthorization = mock(PlatformRequestAuthorization.class);
        CapabilityRegistryEnrollmentGateway enrollmentGateway = mock(CapabilityRegistryEnrollmentGateway.class);
        PlatformAuthAuditService auditService = mock(PlatformAuthAuditService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        PlatformAuthenticatedSession actor = mock(PlatformAuthenticatedSession.class);
        PlatformRegistryEnrollmentController controller = new PlatformRegistryEnrollmentController(
                requestAuthorization, enrollmentGateway, auditService);
        when(requestAuthorization.requireGlobalPermission(request, PlatformPermissions.PLATFORM_ADMIN))
                .thenReturn(actor);
        when(enrollmentGateway.issue(actor, "orders")).thenReturn(
                new CapabilityRegistryEnrollmentGateway.IssuedEnrollment(
                        "one-time-token", "orders", "2026-08-30T10:00:00Z", 72));

        ResponseEntity<PlatformRegistryEnrollmentController.RegistryEnrollmentView> response = controller.issue(
                request,
                new PlatformRegistryEnrollmentController.RegistryEnrollmentCommand("orders"));

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("orders", response.getBody().projectCode());
        verify(auditService).record(
                eq(actor),
                eq("REGISTRY_ENROLLMENT_ISSUED"),
                eq("REGISTRY_PROJECT"),
                eq("orders"),
                any());
    }
}
