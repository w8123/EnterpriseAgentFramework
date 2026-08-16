package com.enterprise.ai.control.runtime;

import com.enterprise.ai.control.client.runtime.RuntimeSessionRetentionGateway;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformAuthAuditService;
import com.enterprise.ai.control.identity.PlatformAuthorizationService;
import com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor;
import com.enterprise.ai.control.identity.PlatformUserEntity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeSessionRetentionControllerTest {

    @Test
    void legalHoldRequiresDedicatedPermissionAndAuditsTheValidatedRequest() {
        RuntimeSessionRetentionGateway gateway = mock(RuntimeSessionRetentionGateway.class);
        PlatformAuthorizationService authorization = mock(PlatformAuthorizationService.class);
        PlatformAuthAuditService audit = mock(PlatformAuthAuditService.class);
        RuntimeSessionRetentionController controller =
                new RuntimeSessionRetentionController(gateway, authorization, audit);
        PlatformAuthenticatedSession actor = actor();
        MockHttpServletRequest request = request(actor);
        when(gateway.setLegalHold(
                "tenant-a", "session-a", true, "LEGAL_CASE", "CASE-7", "42"))
                .thenReturn(ResponseEntity.ok(Map.of("legalHold", true)));

        ResponseEntity<Map<String, Object>> response = controller.legalHold(
                request,
                "tenant-a",
                "session-a",
                new RuntimeSessionRetentionController.LegalHoldCommand(
                        true, "LEGAL_CASE", "CASE-7"));

        assertEquals(200, response.getStatusCode().value());
        verify(authorization).requireGlobalPermission(
                actor, RuntimeSessionRetentionController.MANAGE_PERMISSION);
        verify(audit).record(
                any(),
                org.mockito.ArgumentMatchers.eq("RUNTIME_SESSION_LEGAL_HOLD_SET_REQUESTED"),
                org.mockito.ArgumentMatchers.eq("RUNTIME_SESSION"),
                anyString(),
                any());
    }

    @Test
    void upstreamLegalHoldConflictKeepsRequestedAuditWithoutClaimingCompletion() {
        RuntimeSessionRetentionGateway gateway = mock(RuntimeSessionRetentionGateway.class);
        PlatformAuthorizationService authorization = mock(PlatformAuthorizationService.class);
        PlatformAuthAuditService audit = mock(PlatformAuthAuditService.class);
        RuntimeSessionRetentionController controller =
                new RuntimeSessionRetentionController(gateway, authorization, audit);
        PlatformAuthenticatedSession actor = actor();
        when(gateway.erase("tenant-a", "session-a", "PRIVACY_REQUEST", null, "42"))
                .thenReturn(ResponseEntity.status(HttpStatus.LOCKED)
                        .body(Map.of("code", "RUNTIME_SESSION_LEGAL_HOLD")));

        ResponseEntity<Map<String, Object>> response = controller.erase(
                request(actor),
                "tenant-a",
                "session-a",
                new RuntimeSessionRetentionController.EraseCommand("PRIVACY_REQUEST", null));

        assertEquals(423, response.getStatusCode().value());
        verify(audit).record(
                any(),
                org.mockito.ArgumentMatchers.eq("RUNTIME_SESSION_ERASE_REQUESTED"),
                org.mockito.ArgumentMatchers.eq("RUNTIME_SESSION"),
                anyString(),
                any());
    }

    @Test
    void missingLivePlatformSessionIsRejectedBeforeCallingRuntime() {
        RuntimeSessionRetentionController controller = new RuntimeSessionRetentionController(
                mock(RuntimeSessionRetentionGateway.class),
                mock(PlatformAuthorizationService.class),
                mock(PlatformAuthAuditService.class));

        ResponseStatusException failure = assertThrows(
                ResponseStatusException.class,
                () -> controller.policy(new MockHttpServletRequest(), "tenant-a"));

        assertEquals(HttpStatus.UNAUTHORIZED, failure.getStatusCode());
    }

    @Test
    void freeFormAuditReferenceIsRejectedBeforeAuditOrRuntimeMutation() {
        RuntimeSessionRetentionGateway gateway = mock(RuntimeSessionRetentionGateway.class);
        PlatformAuthAuditService audit = mock(PlatformAuthAuditService.class);
        RuntimeSessionRetentionController controller = new RuntimeSessionRetentionController(
                gateway, mock(PlatformAuthorizationService.class), audit);

        assertThrows(IllegalArgumentException.class,
                () -> controller.erase(
                        request(actor()),
                        "tenant-a",
                        "session-a",
                        new RuntimeSessionRetentionController.EraseCommand(
                                "PRIVACY_REQUEST", "copy the whole conversation here")));

        verify(audit, never()).record(any(), anyString(), anyString(), anyString(), any());
        verify(gateway, never()).erase(anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void invalidSessionIdIsRejectedBeforeAuditOrRuntimeAccess() {
        RuntimeSessionRetentionGateway gateway = mock(RuntimeSessionRetentionGateway.class);
        PlatformAuthAuditService audit = mock(PlatformAuthAuditService.class);
        RuntimeSessionRetentionController controller = new RuntimeSessionRetentionController(
                gateway, mock(PlatformAuthorizationService.class), audit);

        assertThrows(IllegalArgumentException.class,
                () -> controller.session(request(actor()), "tenant-a", "x".repeat(129)));

        verify(audit, never()).record(any(), anyString(), anyString(), anyString(), any());
        verify(gateway, never()).session(anyString(), anyString(), anyString());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void ownerEraseRequiresPermissionAndAuditsOnlyPseudonymousTarget() {
        RuntimeSessionRetentionGateway gateway = mock(RuntimeSessionRetentionGateway.class);
        PlatformAuthorizationService authorization = mock(PlatformAuthorizationService.class);
        PlatformAuthAuditService audit = mock(PlatformAuthAuditService.class);
        RuntimeSessionRetentionController controller =
                new RuntimeSessionRetentionController(gateway, authorization, audit);
        PlatformAuthenticatedSession actor = actor();
        when(gateway.eraseOwner("tenant-a", "runtime-user-secret", "PRIVACY_REQUEST",
                "REQ-10", 25, "42"))
                .thenReturn(ResponseEntity.ok(Map.of("complete", false)));

        ResponseEntity<Map<String, Object>> response = controller.eraseOwner(
                request(actor),
                "tenant-a",
                new RuntimeSessionRetentionController.OwnerEraseCommand(
                        "runtime-user-secret", "privacy_request", "REQ-10", 25));

        assertEquals(200, response.getStatusCode().value());
        verify(authorization).requireGlobalPermission(
                actor, RuntimeSessionRetentionController.MANAGE_PERMISSION);
        ArgumentCaptor<String> targetId = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map> details = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(any(),
                org.mockito.ArgumentMatchers.eq("RUNTIME_OWNER_SESSION_ERASE_REQUESTED"),
                org.mockito.ArgumentMatchers.eq("RUNTIME_USER"), targetId.capture(),
                details.capture());
        assertEquals(64, targetId.getValue().length());
        assertFalse(targetId.getValue().contains("runtime-user-secret"));
        assertFalse(details.getValue().toString().contains("runtime-user-secret"));
        verify(gateway).eraseOwner("tenant-a", "runtime-user-secret", "PRIVACY_REQUEST",
                "REQ-10", 25, "42");
    }

    private static PlatformAuthenticatedSession actor() {
        PlatformUserEntity user = new PlatformUserEntity();
        user.setId(42L);
        return new PlatformAuthenticatedSession(
                user, "platform-session", null,
                List.of("PLATFORM_ADMIN"), List.of("*"), List.of());
    }

    private static MockHttpServletRequest request(PlatformAuthenticatedSession actor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, actor);
        return request;
    }
}
