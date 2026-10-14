package com.enterprise.ai.control.capability;

import com.enterprise.ai.control.identity.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HttpApiReferenceControllerTest {
    @Test void ownerIdentityIsMinimalAndScopeAuthorizationStillPrecedesUsageReads() {
        var capability = mock(CapabilityReviewGateway.class);
        var authorization = mock(PlatformRequestAuthorization.class);
        var impact = mock(CapabilityChangeImpactService.class);
        var request = new MockHttpServletRequest();
        var session = new PlatformAuthenticatedSession(new PlatformPrincipal(42L, "ops", "Ops"),
                "session", LocalDateTime.now().plusMinutes(5), List.of(), List.of(),
                List.of(new PlatformPermissionGrant(PlatformPermissions.PLATFORM_READ, "PROJECT", "orders")));
        when(authorization.requirePermission(request, PlatformPermissions.PLATFORM_READ)).thenReturn(session);
        String ref = "http-api:orders:dev:GET:/orders";
        when(capability.getHttpApiIdentity(org.mockito.ArgumentMatchers.eq(9L), org.mockito.ArgumentMatchers.eq("42"), org.mockito.ArgumentMatchers.anyMap())).thenReturn(ResponseEntity.ok()
                .header(CatalogReadAuthorization.SCOPE, "PROJECT").header(CatalogReadAuthorization.PROJECT_ID, "7")
                .header(CatalogReadAuthorization.PROJECT_CODE, "orders").body(Map.of(
                "id", 9L, "projectId", 7L, "projectCode", "orders", "qualifiedName", ref)));
        when(impact.references("orders", ref, ref, "42")).thenReturn(Map.of("runtimeEvidence", "COMPLETE"));
        var controller = new HttpApiReferenceController(capability, authorization, impact);
        assertEquals(200, controller.references(request, 9L).getStatusCode().value());
        var order = inOrder(authorization, impact);
        order.verify(authorization).requirePermission(request, PlatformPermissions.PLATFORM_READ);
        order.verify(authorization).requireResourcePermission(request, PlatformPermissions.PLATFORM_READ, "PROJECT", null, "orders");
        order.verify(impact).references("orders", ref, ref, "42");
        verify(capability, never()).getHttpApi(org.mockito.ArgumentMatchers.eq(9L), org.mockito.ArgumentMatchers.eq("42"), org.mockito.ArgumentMatchers.anyMap());
        verify(capability, never()).getProjectById(7L, "42");
        clearInvocations(impact);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(authorization)
                .requireResourcePermission(request, PlatformPermissions.PLATFORM_READ, "PROJECT", null, "orders");
        assertThrows(ResponseStatusException.class, () -> controller.references(request, 9L));
        verifyNoInteractions(impact);
    }
}
