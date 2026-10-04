package com.enterprise.ai.control.capability;

import com.enterprise.ai.control.identity.PlatformPrincipal;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformAuthorizationService;
import com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.identity.PlatformRequestAuthorization;
import com.enterprise.ai.control.identity.PlatformUserEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CapabilityReviewConsoleControllerTest {

    @Test
    void exposesDedicatedConsoleReviewRoutes() throws Exception {
        RequestMapping controllerMapping =
                CapabilityReviewConsoleController.class.getAnnotation(RequestMapping.class);
        Method sync = CapabilityReviewConsoleController.class.getDeclaredMethod(
                "syncCapabilities", HttpServletRequest.class, String.class, Map.class);
        Method diff = CapabilityReviewConsoleController.class.getDeclaredMethod(
                "diffCapabilities", HttpServletRequest.class, String.class, Map.class);
        Method snapshots = CapabilityReviewConsoleController.class.getDeclaredMethod(
                "listCapabilitySnapshots", HttpServletRequest.class, String.class);
        Method diffItems = CapabilityReviewConsoleController.class.getDeclaredMethod(
                "listCapabilityDiffItems", HttpServletRequest.class, String.class, Long.class);
        Method review = CapabilityReviewConsoleController.class.getDeclaredMethod(
                "reviewCapabilityDiffItem", HttpServletRequest.class, String.class, Long.class, Map.class);
        Method rollback = CapabilityReviewConsoleController.class.getDeclaredMethod(
                "rollbackCapabilityDiffItem", HttpServletRequest.class, String.class, Long.class, Map.class);

        assertArrayEquals(new String[] {"/api/capability-review"}, controllerMapping.value());
        assertArrayEquals(new String[] {"/projects/{projectCode}/capabilities/sync"},
                sync.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/projects/{projectCode}/capabilities/diff"},
                diff.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/projects/{projectCode}/snapshots"},
                snapshots.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/projects/{projectCode}/snapshots/{snapshotId}/diff-items"},
                diffItems.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/projects/{projectCode}/diff-items/{diffItemId}/review"},
                review.getAnnotation(PostMapping.class).value());
        assertArrayEquals(new String[] {"/projects/{projectCode}/diff-items/{diffItemId}/rollback"},
                rollback.getAnnotation(PostMapping.class).value());
        assertThrows(NoSuchMethodException.class, () -> CapabilityReviewConsoleController.class.getDeclaredMethod(
                "applyCapabilities", HttpServletRequest.class, String.class, Map.class));
        assertThrows(NoSuchMethodException.class, () -> CapabilityReviewGateway.class.getDeclaredMethod(
                "applySnapshot", String.class, Map.class, String.class));
    }

    @Test
    void delegatesConsoleSnapshotFlowToCapabilityService() {
        CapabilityReviewGateway gateway = mock(CapabilityReviewGateway.class);
        PlatformAuthorizationService authorization = mock(PlatformAuthorizationService.class);
        CapabilityReviewConsoleController controller = controller(gateway, authorization);
        MockHttpServletRequest request = authenticatedRequest("reviewer");
        Map<String, Object> body = Map.of("syncId", "sync-1");
        ResponseEntity<Object> delegated = ResponseEntity.ok(Map.of("ok", true));

        when(gateway.createPendingSnapshot("orders", body, "7")).thenReturn(delegated);
        when(gateway.listSnapshots("orders", "7")).thenReturn(delegated);
        when(gateway.listDiffItems("orders", 11L, "7")).thenReturn(delegated);

        assertEquals(delegated, controller.syncCapabilities(request, "orders", body));
        assertEquals(delegated, controller.diffCapabilities(request, "orders", body));
        assertEquals(delegated, controller.listCapabilitySnapshots(request, "orders"));
        assertEquals(delegated, controller.listCapabilityDiffItems(request, "orders", 11L));

        verify(gateway, times(2)).createPendingSnapshot("orders", body, "7");
        verify(gateway).listSnapshots("orders", "7");
        verify(gateway).listDiffItems("orders", 11L, "7");
        verify(authorization, times(2)).requireResourcePermission(
                any(), eq(PlatformPermissions.PLATFORM_WRITE), eq("PROJECT"), isNull(), eq("orders"));
        verify(authorization, times(2)).requireResourcePermission(
                any(), eq(PlatformPermissions.PLATFORM_READ), eq("PROJECT"), isNull(), eq("orders"));
    }

    @Test
    void replacesClientOperatorForReviewAndRollbackWithoutMutatingInput() {
        CapabilityReviewGateway gateway = mock(CapabilityReviewGateway.class);
        PlatformAuthorizationService authorization = mock(PlatformAuthorizationService.class);
        CapabilityReviewConsoleController controller = controller(gateway, authorization);
        MockHttpServletRequest request = authenticatedRequest("alice");
        Map<String, Object> reviewBody = new LinkedHashMap<>(Map.of(
                "action", "APPLY", "note", "approved", "operator", "spoofed-reviewer"));
        Map<String, Object> rollbackBody = new LinkedHashMap<>(Map.of(
                "note", "restore", "operator", "spoofed-rollback-user"));
        ResponseEntity<Object> delegated = ResponseEntity.ok(Map.of("ok", true));

        when(gateway.reviewDiffItem(eq("orders"), eq(12L), anyMap(), eq("7"))).thenReturn(delegated);
        when(gateway.rollbackDiffItem(eq("orders"), eq(12L), anyMap(), eq("7"))).thenReturn(delegated);

        assertEquals(delegated, controller.reviewCapabilityDiffItem(request, "orders", 12L, reviewBody));
        assertEquals(delegated, controller.rollbackCapabilityDiffItem(request, "orders", 12L, rollbackBody));

        verify(gateway).reviewDiffItem("orders", 12L, Map.of(
                "action", "APPLY", "note", "approved", "operator", "alice"), "7");
        verify(gateway).rollbackDiffItem("orders", 12L, Map.of(
                "note", "restore", "operator", "alice"), "7");
        verify(authorization, times(2)).requireResourcePermission(
                any(), eq(PlatformPermissions.PLATFORM_WRITE), eq("PROJECT"), isNull(), eq("orders"));
        assertEquals("spoofed-reviewer", reviewBody.get("operator"));
        assertEquals("spoofed-rollback-user", rollbackBody.get("operator"));
    }

    @Test
    void rejectsRequestsWithoutAnAuthenticatedPlatformSession() {
        CapabilityReviewGateway gateway = mock(CapabilityReviewGateway.class);
        CapabilityReviewConsoleController controller = controller(gateway);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.listCapabilitySnapshots(new MockHttpServletRequest(), "orders"));

        assertEquals(HttpStatus.UNAUTHORIZED, error.getStatusCode());
        verifyNoInteractions(gateway);
    }

    @Test void currentChangesUseProjectReadAuthorizationBeforeLoadingEvidence() {
        CapabilityReviewGateway gateway = mock(CapabilityReviewGateway.class);
        PlatformAuthorizationService authorization = mock(PlatformAuthorizationService.class);
        CapabilityChangeImpactService impacts = mock(CapabilityChangeImpactService.class);
        var controller = new CapabilityReviewConsoleController(gateway, new PlatformRequestAuthorization(authorization), impacts);
        ResponseEntity<Object> page = ResponseEntity.ok(Map.of("records", List.of()));
        when(gateway.listChanges("orders", "PENDING", "", 1, 20, "7")).thenReturn(page);
        when(impacts.enrich(page, "orders", "7")).thenReturn(page);
        assertEquals(page, controller.listChanges(authenticatedRequest("alice"), "orders", "PENDING", "", 1, 20));
        verify(authorization).requireResourcePermission(any(), eq(PlatformPermissions.PLATFORM_READ), eq("PROJECT"), isNull(), eq("orders"));
        assertThrows(ResponseStatusException.class, () -> controller.listChanges(new MockHttpServletRequest(), "orders", "PENDING", "", 1, 20));
        verify(gateway, times(1)).listChanges("orders", "PENDING", "", 1, 20, "7");
    }

    private static CapabilityReviewConsoleController controller(CapabilityReviewGateway gateway) {
        return controller(gateway, mock(PlatformAuthorizationService.class));
    }

    @Test void capabilityReferencesResolveAliasesFromTheOwningCatalog() {
        var gateway = mock(CapabilityReviewGateway.class);
        var authorization = mock(PlatformAuthorizationService.class);
        var impacts = mock(CapabilityChangeImpactService.class);
        var controller = new CapabilityReviewConsoleController(gateway, new PlatformRequestAuthorization(authorization), impacts);
        when(gateway.getCapability("orders_read", "7")).thenReturn(ResponseEntity.ok(Map.of(
                "projectCode", "orders", "qualifiedName", "orders:read", "name", "orders_read")));
        when(impacts.references("orders", "orders:read", "orders_read", "7")).thenReturn(Map.of("runtimeEvidence", "UNKNOWN"));
        assertEquals("UNKNOWN", ((Map<?, ?>) controller.capabilityReferences(authenticatedRequest("alice"), "orders", "orders_read").getBody()).get("runtimeEvidence"));
        verify(authorization).requireResourcePermission(any(), eq(PlatformPermissions.PLATFORM_READ), eq("PROJECT"), isNull(), eq("orders"));
        verify(impacts).references("orders", "orders:read", "orders_read", "7");
        assertThrows(ResponseStatusException.class, () -> controller.capabilityReferences(new MockHttpServletRequest(), "orders", "orders_read"));
        verify(gateway, times(1)).getCapability("orders_read", "7");
    }

    @Test void capabilityReferencesRejectCrossProjectAliasesAndPropagateLookupFailures() {
        var gateway = mock(CapabilityReviewGateway.class);
        var impacts = mock(CapabilityChangeImpactService.class);
        var controller = new CapabilityReviewConsoleController(gateway, new PlatformRequestAuthorization(mock(PlatformAuthorizationService.class)), impacts);
        when(gateway.getCapability("foreign", "7")).thenReturn(ResponseEntity.ok(Map.of("projectCode", "other", "qualifiedName", "other:read", "name", "foreign")));
        var error = assertThrows(ResponseStatusException.class, () -> controller.capabilityReferences(authenticatedRequest("alice"), "orders", "foreign"));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        when(gateway.getCapability("foreign", "7")).thenReturn(ResponseEntity.status(503).build());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, controller.capabilityReferences(authenticatedRequest("alice"), "orders", "foreign").getStatusCode());
        verifyNoInteractions(impacts);
    }

    private static CapabilityReviewConsoleController controller(CapabilityReviewGateway gateway,
                                                                  PlatformAuthorizationService authorizationService) {
        PlatformRequestAuthorization authorization = new PlatformRequestAuthorization(
                authorizationService);
        return new CapabilityReviewConsoleController(gateway, authorization, mock(CapabilityChangeImpactService.class));
    }

    private static MockHttpServletRequest authenticatedRequest(String username) {
        PlatformUserEntity user = new PlatformUserEntity();
        user.setId(7L);
        user.setUsername(username);
        PlatformAuthenticatedSession session = new PlatformAuthenticatedSession(
                PlatformPrincipal.fromUser(user),
                "session-1",
                LocalDateTime.now().plusHours(1),
                List.of("CAPABILITY_REVIEWER"),
                List.of(),
                List.of());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session);
        return request;
    }
}
