package com.enterprise.ai.runtime.internal;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.runtime.internalauth.VerifiedInternalServiceAuth;
import com.enterprise.ai.runtime.memory.RuntimeSessionRetentionException;
import com.enterprise.ai.runtime.memory.RuntimeSessionRetentionService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeSessionRetentionInternalControllerTest {

    @Test
    void policyManagementUsesOnlyVerifiedPlatformActor() {
        RuntimeSessionRetentionService service = mock(RuntimeSessionRetentionService.class);
        RuntimeSessionRetentionInternalController controller =
                new RuntimeSessionRetentionInternalController(service);
        MockHttpServletRequest request = platformRequest("42");
        RuntimeSessionRetentionService.PolicyView view =
                new RuntimeSessionRetentionService.PolicyView(
                        "tenant-a", 30, 24, true, LocalDateTime.now());
        when(service.savePolicy("tenant-a", 30, 24, "42")).thenReturn(view);

        var response = controller.savePolicy(
                request,
                "tenant-a",
                new RuntimeSessionRetentionInternalController.PolicyCommand(30, 24));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(view, response.getBody());
        verify(service).savePolicy("tenant-a", 30, 24, "42");
    }

    @Test
    void agentIdentityCannotInvokeRetentionAdministration() {
        RuntimeSessionRetentionInternalController controller =
                new RuntimeSessionRetentionInternalController(mock(RuntimeSessionRetentionService.class));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(VerifiedInternalServiceAuth.REQUEST_ATTR,
                new VerifiedInternalServiceAuth(
                        InternalServiceAuthHeaders.CALLER_CONTROL,
                        "AGENT",
                        "tenant-a",
                        "user-a"));

        RuntimeSessionRetentionException failure = assertThrows(
                RuntimeSessionRetentionException.class,
                () -> controller.policy(request, "tenant-a"));

        assertEquals(401, failure.status().value());
    }

    @Test
    void signedTenantMustMatchThePathTarget() {
        RuntimeSessionRetentionInternalController controller =
                new RuntimeSessionRetentionInternalController(mock(RuntimeSessionRetentionService.class));
        MockHttpServletRequest request = platformRequest("42");

        RuntimeSessionRetentionException failure = assertThrows(
                RuntimeSessionRetentionException.class,
                () -> controller.policy(request, "tenant-b"));

        assertEquals(403, failure.status().value());
        assertEquals("RUNTIME_SESSION_RETENTION_TENANT_MISMATCH", failure.code());
    }

    @Test
    void ownerEraseUsesVerifiedActorAndKeepsTargetInTheSignedBody() {
        RuntimeSessionRetentionService service = mock(RuntimeSessionRetentionService.class);
        RuntimeSessionRetentionInternalController controller =
                new RuntimeSessionRetentionInternalController(service);
        RuntimeSessionRetentionService.OwnerEraseResult result =
                new RuntimeSessionRetentionService.OwnerEraseResult(
                        "tenant-a", "a".repeat(64), "ERASED", 50, 1, 1,
                        0, 0, 0, 0, 0, true, "PRIVACY_REQUEST", LocalDateTime.now());
        when(service.eraseOwner("tenant-a", "runtime-user-a", "PRIVACY_REQUEST",
                "REQ-9", 50, "42")).thenReturn(result);

        var response = controller.eraseOwner(
                platformRequest("42"),
                "tenant-a",
                new RuntimeSessionRetentionInternalController.OwnerEraseCommand(
                        "runtime-user-a", "PRIVACY_REQUEST", "REQ-9", 50));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(result, response.getBody());
        verify(service).eraseOwner("tenant-a", "runtime-user-a", "PRIVACY_REQUEST",
                "REQ-9", 50, "42");
    }

    private static MockHttpServletRequest platformRequest(String actorId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(VerifiedInternalServiceAuth.REQUEST_ATTR,
                new VerifiedInternalServiceAuth(
                        InternalServiceAuthHeaders.CALLER_CONTROL,
                        InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION,
                        "tenant-a",
                        actorId));
        return request;
    }
}
