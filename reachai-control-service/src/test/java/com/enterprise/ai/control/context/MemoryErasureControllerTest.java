package com.enterprise.ai.control.context;

import com.enterprise.ai.control.identity.PlatformAuthAuditService;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformAuthorizationService;
import com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor;
import com.enterprise.ai.control.identity.PlatformUserEntity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryErasureControllerTest {

    @Test
    void routeContractUsesBodyForRawOwnerAndDedicatedAdministrationRoot() throws Exception {
        assertArrayEquals(new String[]{"/api/context/memory-erasure-requests"},
                MemoryErasureController.class.getAnnotation(RequestMapping.class).value());
        Method create = MemoryErasureController.class.getMethod("create",
                jakarta.servlet.http.HttpServletRequest.class,
                MemoryErasureOrchestrationService.CreateCommand.class);
        Method get = MemoryErasureController.class.getMethod("get",
                jakarta.servlet.http.HttpServletRequest.class, String.class);
        Method retry = MemoryErasureController.class.getMethod("retry",
                jakarta.servlet.http.HttpServletRequest.class, String.class);
        Method attest = MemoryErasureController.class.getMethod("attest",
                jakarta.servlet.http.HttpServletRequest.class, String.class, String.class,
                MemoryErasureOrchestrationService.EvidenceCommand.class);

        assertArrayEquals(new String[0], create.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[]{"/{requestId}"}, get.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[]{"/{requestId}/retry"},
                retry.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[]{"/{requestId}/domains/{domainCode}/evidence"},
                attest.getAnnotation(PostMapping.class).value());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void createRequiresDedicatedPermissionAndAuditsOnlyPseudonymousTarget() {
        MemoryErasureOrchestrationService service = mock(MemoryErasureOrchestrationService.class);
        PlatformAuthorizationService authorization = mock(PlatformAuthorizationService.class);
        PlatformAuthAuditService audit = mock(PlatformAuthAuditService.class);
        MemoryErasureController controller =
                new MemoryErasureController(service, authorization, audit);
        PlatformAuthenticatedSession actor = actor();
        var command = new MemoryErasureOrchestrationService.CreateCommand(
                MemoryErasureOrchestrationService.CONFIRMATION,
                "client-42", "tenant-a", "raw-runtime-owner",
                "PRIVACY_REQUEST", "ticket/42");
        var view = new MemoryErasureOrchestrationService.RequestView(
                "request-42", "client-42", "tenant-a", "a".repeat(64),
                "REQUESTED", "PRIVACY_REQUEST", "ticket/42", 0, null,
                null, null, LocalDateTime.now(), LocalDateTime.now(), true, List.of());
        when(service.create(command, "42")).thenReturn(view);

        ResponseEntity<MemoryErasureOrchestrationService.RequestView> response =
                controller.create(request(actor), command);

        assertEquals(202, response.getStatusCode().value());
        verify(authorization).requireGlobalPermission(
                actor, MemoryErasureController.MANAGE_PERMISSION);
        ArgumentCaptor<Map> details = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq(actor), eq("MEMORY_ERASURE_REQUEST_CREATED"),
                eq("MEMORY_ERASURE_REQUEST"), eq("request-42"), details.capture());
        assertFalse(details.getValue().toString().contains("raw-runtime-owner"));
        assertEquals("a".repeat(64), details.getValue().get("runtimeUserHash"));
    }

    @Test
    void missingLiveSessionIsRejectedBeforeServiceOrAudit() {
        MemoryErasureOrchestrationService service = mock(MemoryErasureOrchestrationService.class);
        PlatformAuthorizationService authorization = mock(PlatformAuthorizationService.class);
        PlatformAuthAuditService audit = mock(PlatformAuthAuditService.class);
        MemoryErasureController controller =
                new MemoryErasureController(service, authorization, audit);

        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> controller.get(new MockHttpServletRequest(), "request-42"));

        assertEquals(401, failure.getStatusCode().value());
        verify(service, never()).get(anyString());
        verify(audit, never()).record(any(), anyString(), anyString(), anyString(), any());
    }

    private static PlatformAuthenticatedSession actor() {
        PlatformUserEntity user = new PlatformUserEntity();
        user.setId(42L);
        return new PlatformAuthenticatedSession(
                user, "platform-session", LocalDateTime.now().plusHours(1),
                List.of("PLATFORM_ADMIN"), List.of("*"), List.of());
    }

    private static MockHttpServletRequest request(PlatformAuthenticatedSession actor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, actor);
        return request;
    }
}
