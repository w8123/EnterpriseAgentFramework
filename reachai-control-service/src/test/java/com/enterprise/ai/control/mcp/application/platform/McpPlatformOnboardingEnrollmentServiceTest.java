package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.mcp.application.port.McpPlatformOnboardingResourcesPort;
import com.enterprise.ai.control.identity.CapabilityRegistryEnrollmentGateway;
import com.enterprise.ai.control.identity.PlatformAiApprovalService;
import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformAuthorizationService;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.identity.PlatformPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class McpPlatformOnboardingEnrollmentServiceTest {
    private final PlatformAiApprovalService approvals = mock(PlatformAiApprovalService.class);
    private final PlatformAuthorizationService authorization = mock(PlatformAuthorizationService.class);
    private final CapabilityRegistryEnrollmentGateway enrollments = mock(CapabilityRegistryEnrollmentGateway.class);
    private final McpPlatformOnboardingResourcesPort artifacts = mock(McpPlatformOnboardingResourcesPort.class);
    private final McpPlatformOnboardingEnrollmentService service =
            new McpPlatformOnboardingEnrollmentService(approvals, authorization, enrollments, artifacts);
    private final ConnectionIdentity actor = new ConnectionIdentity(19L, "CODEX",
            new PlatformAuthenticatedSession(new PlatformPrincipal(42L, "operator", "操作员"), "session", null,
                    List.of(), List.of(), List.of()));
    private static final String INVOCATION = "00000000-0000-0000-0000-000000000001";

    @Test
    void resourcesUsesForwardedPublicAddress() {
        var request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Host", "reachai.example:8443");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            when(artifacts.manifest("https://reachai.example:8443")).thenReturn(Map.of("skillPackageUrl", "https://reachai.example:8443/skill.zip"));
            assertEquals(HttpStatus.OK, service.resources().getStatusCode());
            verify(artifacts).manifest("https://reachai.example:8443");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void requestBindsApprovalToProjectAndRequiresAuthorization() {
        service.request(actor, "orders", INVOCATION);
        verify(authorization).requireResourcePermission(actor.session(), PlatformPermissions.PLATFORM_WRITE, "PROJECT", null, "orders");
        verify(approvals).prepare(actor, "REGISTRY_ENROLLMENT", "REGISTRY_ENROLLMENT:orders",
                Map.of("contractVersion", 1, "projectCode", "orders", "ttlHours", 72, "invocationId", INVOCATION));
        assertThrows(ResponseStatusException.class, () -> service.request(actor, "orders--prod", INVOCATION));
    }

    @Test
    void approvedRequestChecksPermissionAgainAndReturnsTokenOnce() {
        claim("REGISTRY_ENROLLMENT:orders", "orders");
        when(enrollments.issue(actor.session(), "orders"))
                .thenReturn(new CapabilityRegistryEnrollmentGateway.IssuedEnrollment("one-time-token", "orders", "2026-11-10", 72));
        var response = service.execute(actor, "approval");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("one-time-token", ((Map<?, ?>) response.getBody()).get("enrollmentToken"));
        verify(authorization).requireResourcePermission(actor.session(), PlatformPermissions.PLATFORM_WRITE, "PROJECT", null, "orders");
        verify(approvals).finish(actor, "approval", "SUCCEEDED", null);
    }

    @Test
    void changedSubjectNeverIssuesToken() {
        claim("REGISTRY_ENROLLMENT:other", "orders");
        assertThrows(ResponseStatusException.class, () -> service.execute(actor, "approval"));
        verify(approvals).finish(actor, "approval", "FAILED", "PLATFORM_APPROVAL_PRE_DISPATCH_FAILED");
        verifyNoInteractions(enrollments);
    }

    @Test
    void lostPermissionNeverIssuesToken() {
        claim("REGISTRY_ENROLLMENT:orders", "orders");
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
                .when(authorization).requireResourcePermission(actor.session(), PlatformPermissions.PLATFORM_WRITE, "PROJECT", null, "orders");
        assertThrows(ResponseStatusException.class, () -> service.execute(actor, "approval"));
        verify(approvals).finish(actor, "approval", "FAILED", "PLATFORM_APPROVAL_PRE_DISPATCH_FAILED");
        verifyNoInteractions(enrollments);
    }

    @Test
    void uncertainIssuanceIsNotRetried() {
        claim("REGISTRY_ENROLLMENT:orders", "orders");
        when(enrollments.issue(any(), anyString())).thenThrow(new IllegalStateException("timeout"));
        assertEquals(HttpStatus.ACCEPTED, service.execute(actor, "approval").getStatusCode());
        verify(enrollments, times(1)).issue(actor.session(), "orders");
        verify(approvals).finish(actor, "approval", "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
    }

    private void claim(String subject, String code) {
        when(approvals.claim(actor, "approval", "REGISTRY_ENROLLMENT")).thenReturn(
                new PlatformAiApprovalService.Claim("approval", "REGISTRY_ENROLLMENT", subject,
                        Map.of("contractVersion", 1, "projectCode", code, "ttlHours", 72, "invocationId", INVOCATION)));
    }
}
