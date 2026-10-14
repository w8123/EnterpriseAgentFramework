package com.enterprise.ai.control.capability;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformPermissionGrant;
import com.enterprise.ai.control.identity.PlatformPermissions;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CatalogReadAuthorizationTest {
    @Test void aNamedOwnerMayBeUnknownButClaimsRemainLimitedToTheCallersExistingPermission() {
        var writeOnly = session(List.of(new PlatformPermissionGrant(PlatformPermissions.PLATFORM_WRITE, "PROJECT", "orders")));
        var grant = (Map<?, ?>) CatalogReadAuthorization.resourceBody(writeOnly, PlatformPermissions.PLATFORM_WRITE).get("catalogReadGrant");
        assertEquals(false, grant.get("global")); assertEquals(List.of("orders"), grant.get("projects"));
        assertNull(grant.get("requestedProjectId"));
        assertThrows(ResponseStatusException.class, () -> CatalogReadAuthorization.resourceBody(writeOnly, PlatformPermissions.PLATFORM_READ));
    }

    @Test void anOwnerAttestationMustAgreeWithTheDtoAndIsStrippedBeforeReturningTheBody() {
        var authorization = org.mockito.Mockito.mock(com.enterprise.ai.control.identity.PlatformRequestAuthorization.class);
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        var body = Map.<String, Object>of("projectId", 7L, "projectCode", "orders + api");
        var owner = org.springframework.http.ResponseEntity.ok().header(CatalogReadAuthorization.SCOPE, "PROJECT")
                .header(CatalogReadAuthorization.PROJECT_ID, "7").header(CatalogReadAuthorization.PROJECT_CODE, "orders%20%2B%20api").body((Object) body);
        var result = CatalogReadAuthorization.verifyOwnerResponse(request, PlatformPermissions.PLATFORM_READ, 7L, "orders + api", owner, authorization);
        assertEquals(body, result.getBody()); assertTrue(result.getHeaders().isEmpty());
        org.mockito.Mockito.verify(authorization).requireResourcePermission(request, PlatformPermissions.PLATFORM_READ, "PROJECT", null, "orders + api");
        org.mockito.Mockito.clearInvocations(authorization);
        assertEquals(502, CatalogReadAuthorization.verifyOwnerResponse(request, PlatformPermissions.PLATFORM_READ, 8L, "orders + api", owner, authorization).getStatusCode().value());
        assertEquals(502, CatalogReadAuthorization.verifyOwnerResponse(request, PlatformPermissions.PLATFORM_READ, 7L, "billing", owner, authorization).getStatusCode().value());
        assertEquals(502, CatalogReadAuthorization.verifyOwnerResponse(request, PlatformPermissions.PLATFORM_READ, 7L, "orders + api", org.springframework.http.ResponseEntity.ok(body), authorization).getStatusCode().value());
        org.mockito.Mockito.verifyNoInteractions(authorization);
    }
    @Test void onlyMatchingReadGrantsFromTheServerSessionBecomeSignedProjectClaims() {
        var session = session(List.of(
                new PlatformPermissionGrant(PlatformPermissions.PLATFORM_READ, "PROJECT", " orders "),
                new PlatformPermissionGrant("*", "PROJECT", "orders"),
                new PlatformPermissionGrant(PlatformPermissions.PLATFORM_WRITE, "PROJECT", "billing"),
                new PlatformPermissionGrant(PlatformPermissions.PLATFORM_READ, "WORKSPACE", "all-projects")));
        var grant = (Map<?, ?>) CatalogReadAuthorization.body(session, 7L).get("catalogReadGrant");
        assertEquals(false, grant.get("global"));
        assertEquals(List.of("orders"), grant.get("projects"));
        assertEquals(7L, grant.get("requestedProjectId"));
    }

    @Test void rolesAndFlatPermissionCodesCannotInventGlobalOrProjectScope() {
        var session = session(List.of(new PlatformPermissionGrant(PlatformPermissions.PLATFORM_READ, "WORKSPACE", "workspace-1")));
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> CatalogReadAuthorization.body(session, 7L)).getStatusCode().value());
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> CatalogReadAuthorization.body(session(List.of(new PlatformPermissionGrant(PlatformPermissions.PLATFORM_READ, "PROJECT", "orders"))), null))
                .getStatusCode().value());
    }

    @Test void globalReadScopeIsExplicitAndUnfilteredRequestedIdRemainsNull() {
        var body = CatalogReadAuthorization.body(session(List.of(new PlatformPermissionGrant("*", "GLOBAL", "*"))), null);
        var grant = (Map<?, ?>) body.get("catalogReadGrant");
        assertEquals(true, grant.get("global")); assertNull(grant.get("requestedProjectId"));
        assertEquals(List.of(), grant.get("projects"));
    }

    private static PlatformAuthenticatedSession session(List<PlatformPermissionGrant> grants) {
        return new PlatformAuthenticatedSession(null, "scope-test", LocalDateTime.now().plusHours(1),
                List.of("ADMIN"), List.of(PlatformPermissions.ALL, PlatformPermissions.PLATFORM_READ), grants);
    }
}
