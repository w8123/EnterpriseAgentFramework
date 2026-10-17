package com.enterprise.ai.control.capability;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformAuthorizationService;
import com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.identity.PlatformPrincipal;
import com.enterprise.ai.control.identity.PlatformRequestAuthorization;
import com.enterprise.ai.control.identity.PlatformPermissionGrant;
import com.enterprise.ai.control.identity.PlatformPermissionMapper;
import com.enterprise.ai.control.identity.PlatformRoleMapper;
import com.enterprise.ai.control.identity.PlatformRolePermissionMapper;
import com.enterprise.ai.control.identity.PlatformUserEntity;
import com.enterprise.ai.control.identity.PlatformUserRoleMapper;
import com.enterprise.ai.control.compat.CapabilityCompatibilityProxyController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CapabilityCatalogConsoleControllerTest {
    @Test void projectListsSendSignedSessionScopeAndRecheckOwnerWithoutAnIdentityHop() {
        var gateway = mock(CapabilityReviewGateway.class); var controller = controller(gateway, globalSession());
        var session = projectSession("orders", PlatformPermissions.PLATFORM_READ);
        var body = Map.<String, Object>of("records", List.of("orders.read"));
        var grant = CatalogReadAuthorization.body(session, 7L);
        when(gateway.listCapabilities(1, 100, null, null, true, 7L, "7", grant))
                .thenReturn(ownerPage(7L, "orders", body));
        var response = controller.list(request(session), 1, 100, null, null, true, 7L);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(body, response.getBody());
        assertEquals(null, response.getHeaders().getFirst(CatalogReadAuthorization.PROJECT_CODE));
        verify(gateway, never()).getProjectById(any(), any());
        verify(gateway).listCapabilities(1, 100, null, null, true, 7L, "7", grant);
    }

    @Test void ungroupedMethodsUseTheSameSignedProjectScopeAndOwnerResponse() {
        var gateway = mock(CapabilityReviewGateway.class); var controller = controller(gateway, globalSession());
        var session = projectSession("orders", PlatformPermissions.PLATFORM_READ);
        var request = request(session); request.setParameter("ungrouped", "true");
        when(gateway.listBusinessMethods(2, 5, "query", true, 7L, "7", null, true, CatalogReadAuthorization.body(session, 7L)))
                .thenReturn(ownerPage(7L, "orders", Map.of("records", List.of(), "total", 0)));
        assertEquals(HttpStatus.OK, controller.listBusinessMethods(request, 2, 5, "query", true, 7L).getStatusCode());
        verify(gateway, never()).getProjectById(any(), any());
        var unscoped = request(globalSession()); unscoped.setParameter("ungrouped", "true");
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> controller.listBusinessMethods(unscoped, 1, 20, null, null, null)).getStatusCode());
    }

    @Test void globalReadDeclaresGlobalScopeAndPreservesExplicitFalse() {
        var gateway = mock(CapabilityReviewGateway.class); var controller = controller(gateway, globalSession());
        var body = Map.<String, Object>of("records", List.of());
        when(gateway.listCapabilities(2, 500, "订单 & status=ALL#+/", "code+/source", false, null, "7",
                CatalogReadAuthorization.body(globalSession(), null))).thenReturn(ownerPage(null, null, body));
        var response = controller.list(request(globalSession()), 2, 500, "订单 & status=ALL#+/", "code+/source", false, null);
        assertEquals(HttpStatus.OK, response.getStatusCode()); assertEquals(body, response.getBody());
    }

    @Test void noSessionWriteOnlyInvalidIdAndUnscopedProjectGrantFailBeforeOwnerIo() {
        var gateway = mock(CapabilityReviewGateway.class); var controller = controller(gateway, globalSession());
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                () -> controller.list(new MockHttpServletRequest(), 1, 20, null, null, null, null)).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> controller.list(request(projectSession("orders", PlatformPermissions.PLATFORM_WRITE)), 1, 20, null, null, null, 7L)).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> controller.list(request(projectSession("orders", PlatformPermissions.PLATFORM_READ)), 1, 20, null, null, null, null)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, controller.list(request(globalSession()), 1, 20, null, null, null, 0L).getStatusCode());
        verifyNoInteractions(gateway);
    }

    @Test void ownerDeniedScopeCannotReturnCatalogDataAndUnexpectedOwnerCannotPassControlRecheck() {
        var gateway = mock(CapabilityReviewGateway.class); var controller = controller(gateway, globalSession());
        var session = projectSession("orders", PlatformPermissions.PLATFORM_READ);
        var grant = CatalogReadAuthorization.body(session, 8L);
        when(gateway.listCapabilities(1, 20, null, null, null, 8L, "7", grant))
                .thenReturn(ResponseEntity.status(403).body(Map.of("code", "CATALOG_READ_SCOPE_DENIED")));
        assertEquals(HttpStatus.FORBIDDEN, controller.list(request(session), 1, 20, null, null, null, 8L).getStatusCode());
        when(gateway.listCapabilities(1, 20, null, null, null, 8L, "7", grant))
                .thenReturn(ownerPage(8L, "billing", Map.of("records", List.of("secret"))));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> controller.list(request(session), 1, 20, null, null, null, 8L)).getStatusCode());
        verify(gateway, never()).getProjectById(any(), any());
    }

    @Test void missingOrMismatchedOwnerHeadersFailClosedAndOwnerFailuresAreSanitized() {
        for (var malformed : List.of(ResponseEntity.<Object>ok(Map.of("records", List.of())),
                ownerPage(8L, "orders", Map.of("records", List.of())))) {
            var gateway = mock(CapabilityReviewGateway.class); var controller = controller(gateway, globalSession());
            when(gateway.listCapabilities(1, 20, null, null, null, 7L, "7", CatalogReadAuthorization.body(globalSession(), 7L)))
                    .thenReturn(malformed);
            assertEquals(HttpStatus.BAD_GATEWAY, controller.list(request(globalSession()), 1, 20, null, null, null, 7L).getStatusCode());
        }
        var gateway = mock(CapabilityReviewGateway.class); var controller = controller(gateway, globalSession());
        when(gateway.listCapabilities(1, 20, null, null, null, 7L, "7", CatalogReadAuthorization.body(globalSession(), 7L)))
                .thenReturn(ResponseEntity.status(503).body(Map.of("internalUrl", "private")));
        var failure = controller.list(request(globalSession()), 1, 20, null, null, null, 7L);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.getStatusCode());
        assertEquals(Map.of("code", "CAPABILITY_SERVICE_UNAVAILABLE"), failure.getBody());
    }

    @Test void legacyProxyCannotBypassTheCatalogFacadeAndRemovedSummaryMethodIsAbsent() throws Exception {
        var gateway = mock(CapabilityReviewGateway.class); var catalog = controller(gateway, globalSession());
        var legacy = new CapabilityCompatibilityProxyController(new RestTemplateBuilder(), "http://capability:18605");
        var mvc = MockMvcBuilders.standaloneSetup(catalog, legacy).build();
        mvc.perform(get("/api/tools")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/tools/orders_read")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/tools")).andExpect(status().is4xxClientError());
        mvc.perform(get("/api/tools/orders_read/extra")).andExpect(status().isNotFound());
        assertEquals(false, java.util.Arrays.stream(CapabilityCatalogConsoleController.class.getDeclaredMethods())
                .anyMatch(method -> "businessMethodSummary".equals(method.getName())));
        verifyNoInteractions(gateway);
    }

    private static ResponseEntity<Object> ownerPage(Long id, String code, Map<String, Object> body) {
        var response = ResponseEntity.ok().header(CatalogReadAuthorization.SCOPE, id == null ? "GLOBAL" : "PROJECT");
        if (id != null) response.header(CatalogReadAuthorization.PROJECT_ID, String.valueOf(id))
                .header(CatalogReadAuthorization.PROJECT_CODE, java.net.URLEncoder.encode(code, java.nio.charset.StandardCharsets.UTF_8));
        return response.body(body);
    }

    @Test
    void authorizesAProjectCapabilityFromTheOwnerResolvedIdentity() {
        CapabilityReviewGateway gateway = mock(CapabilityReviewGateway.class);
        CapabilityCatalogConsoleController controller = controller(gateway,
                projectSession("orders", PlatformPermissions.PLATFORM_READ));
        ResponseEntity<Object> definition = ownerPage(7L, "orders", Map.of(
                "name", "orders_read", "projectId", 7L, "projectCode", "orders"));
        when(gateway.getCapability(org.mockito.ArgumentMatchers.eq("orders_read"), org.mockito.ArgumentMatchers.eq("7"), org.mockito.ArgumentMatchers.anyMap())).thenReturn(definition);
        when(gateway.getProjectById(7L, "7")).thenReturn(ResponseEntity.ok(
                Map.of("projectId", 7L, "projectCode", "orders")));

        assertEquals(definition.getBody(), controller.get(request(projectSession("orders", PlatformPermissions.PLATFORM_READ)),
                "orders_read").getBody());
        verify(gateway, never()).getProjectById(any(), any());
    }

    @Test
    void globalReadCanReadProjectAndPlatformDefinitions() {
        CapabilityReviewGateway gateway = mock(CapabilityReviewGateway.class);
        CapabilityCatalogConsoleController controller = controller(gateway, globalSession());
        ResponseEntity<Object> projectDefinition = ownerPage(7L, "orders", Map.of(
                "name", "orders_read", "projectId", 7L, "projectCode", "orders"));
        ResponseEntity<Object> platformDefinition = ownerPage(null, null, Map.of("name", "platform_read"));
        when(gateway.getCapability(org.mockito.ArgumentMatchers.eq("orders_read"), org.mockito.ArgumentMatchers.eq("7"), org.mockito.ArgumentMatchers.anyMap())).thenReturn(projectDefinition);
        when(gateway.getCapability(org.mockito.ArgumentMatchers.eq("platform_read"), org.mockito.ArgumentMatchers.eq("7"), org.mockito.ArgumentMatchers.anyMap())).thenReturn(platformDefinition);
        when(gateway.getProjectById(7L, "7")).thenReturn(ResponseEntity.ok(
                Map.of("projectId", 7L, "projectCode", "orders")));

        assertEquals(projectDefinition.getBody(), controller.get(request(globalSession()), "orders_read").getBody());
        assertEquals(platformDefinition.getBody(), controller.get(request(globalSession()), "platform_read").getBody());
        verify(gateway, never()).getProjectById(any(), any());
    }

    @Test
    void rejectsAProjectGrantForPlatformOrAnotherProjectCapability() {
        CapabilityReviewGateway gateway = mock(CapabilityReviewGateway.class);
        CapabilityCatalogConsoleController controller = controller(gateway,
                projectSession("orders", PlatformPermissions.PLATFORM_READ));
        when(gateway.getCapability(org.mockito.ArgumentMatchers.eq("platform_read"), org.mockito.ArgumentMatchers.eq("7"), org.mockito.ArgumentMatchers.anyMap())).thenReturn(
                ownerPage(null, null, Map.of("name", "platform_read")));
        when(gateway.getCapability(org.mockito.ArgumentMatchers.eq("billing_read"), org.mockito.ArgumentMatchers.eq("7"), org.mockito.ArgumentMatchers.anyMap())).thenReturn(ownerPage(8L, "billing", Map.of(
                "name", "billing_read", "projectId", 8L, "projectCode", "billing")));
        when(gateway.getProjectById(8L, "7")).thenReturn(ResponseEntity.ok(
                Map.of("projectId", 8L, "projectCode", "billing")));

        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ResponseStatusException.class,
                        () -> controller.get(request(projectSession("orders", PlatformPermissions.PLATFORM_READ)),
                                "platform_read")).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ResponseStatusException.class,
                        () -> controller.get(request(projectSession("orders", PlatformPermissions.PLATFORM_READ)),
                                "billing_read")).getStatusCode());
    }

    @Test
    void rejectsUnconfirmedAndMismatchedCapabilityProjectIdentityWithCodes() {
        CapabilityReviewGateway gateway = mock(CapabilityReviewGateway.class);
        CapabilityCatalogConsoleController controller = controller(gateway, globalSession());
        when(gateway.getCapability(org.mockito.ArgumentMatchers.eq("orphan"), org.mockito.ArgumentMatchers.eq("7"), org.mockito.ArgumentMatchers.anyMap())).thenReturn(ResponseEntity.ok(Map.of(
                "name", "orphan", "projectCode", "orders")));
        when(gateway.getCapability(org.mockito.ArgumentMatchers.eq("conflict"), org.mockito.ArgumentMatchers.eq("7"), org.mockito.ArgumentMatchers.anyMap())).thenReturn(ownerPage(7L, "orders", Map.of(
                "name", "conflict", "projectId", 7L, "projectCode", "billing")));
        when(gateway.getProjectById(7L, "7")).thenReturn(ResponseEntity.ok(
                Map.of("projectId", 7L, "projectCode", "orders")));

        ResponseEntity<Object> orphan = controller.get(request(globalSession()), "orphan");
        ResponseEntity<Object> conflict = controller.get(request(globalSession()), "conflict");

        assertEquals(HttpStatus.CONFLICT, orphan.getStatusCode());
        assertEquals("CAPABILITY_PROJECT_IDENTITY_UNCONFIRMED", ((Map<?, ?>) orphan.getBody()).get("code"));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        assertEquals("CAPABILITY_PROJECT_IDENTITY_MISMATCH", ((Map<?, ?>) conflict.getBody()).get("code"));
    }

    @Test
    void rejectsMalformedCapabilityIdentityAndNameWithoutReturningDefinition() {
        CapabilityReviewGateway gateway = mock(CapabilityReviewGateway.class);
        CapabilityCatalogConsoleController controller = controller(gateway, globalSession());
        when(gateway.getCapability(org.mockito.ArgumentMatchers.eq("bad_id"), org.mockito.ArgumentMatchers.eq("7"), org.mockito.ArgumentMatchers.anyMap())).thenReturn(ResponseEntity.ok(Map.of(
                "name", "bad_id", "projectId", "7")));
        when(gateway.getCapability(org.mockito.ArgumentMatchers.eq("wrong_name"), org.mockito.ArgumentMatchers.eq("7"), org.mockito.ArgumentMatchers.anyMap())).thenReturn(ResponseEntity.ok(Map.of(
                "name", "other")));

        ResponseEntity<Object> invalidId = controller.get(request(globalSession()), "bad_id");
        ResponseEntity<Object> wrongName = controller.get(request(globalSession()), "wrong_name");

        assertEquals(HttpStatus.BAD_GATEWAY, invalidId.getStatusCode());
        assertEquals("CAPABILITY_CATALOG_RESPONSE_INVALID", ((Map<?, ?>) invalidId.getBody()).get("code"));
        assertEquals(HttpStatus.BAD_GATEWAY, wrongName.getStatusCode());
        assertEquals("CAPABILITY_CATALOG_RESPONSE_INVALID", ((Map<?, ?>) wrongName.getBody()).get("code"));
    }

    @Test
    void preservesOwnerNotFoundAndSanitizesOwnerFailuresForSingleReads() {
        CapabilityReviewGateway gateway = mock(CapabilityReviewGateway.class);
        CapabilityCatalogConsoleController controller = controller(gateway, globalSession());
        when(gateway.getCapability(org.mockito.ArgumentMatchers.eq("missing"), org.mockito.ArgumentMatchers.eq("7"), org.mockito.ArgumentMatchers.anyMap())).thenReturn(ResponseEntity.notFound().build());
        when(gateway.getCapability(org.mockito.ArgumentMatchers.eq("down"), org.mockito.ArgumentMatchers.eq("7"), org.mockito.ArgumentMatchers.anyMap())).thenReturn(ResponseEntity.status(503)
                .body(Map.of("internalUrl", "http://capability:18605/internal")));

        assertEquals(HttpStatus.NOT_FOUND,
                controller.get(request(globalSession()), "missing").getStatusCode());
        ResponseEntity<Object> unavailable = controller.get(request(globalSession()), "down");
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, unavailable.getStatusCode());
        assertEquals("CAPABILITY_SERVICE_UNAVAILABLE", ((Map<?, ?>) unavailable.getBody()).get("code"));
        assertEquals(null, ((Map<?, ?>) unavailable.getBody()).get("internalUrl"));
    }

    private static CapabilityCatalogConsoleController controller(
            CapabilityReviewGateway gateway, PlatformAuthenticatedSession ignored) {
        return new CapabilityCatalogConsoleController(gateway, authorization());
    }

    private static PlatformRequestAuthorization authorization() {
        PlatformAuthorizationService service = new PlatformAuthorizationService(
                mock(PlatformUserRoleMapper.class),
                mock(PlatformRoleMapper.class),
                mock(PlatformRolePermissionMapper.class),
                mock(PlatformPermissionMapper.class));
        return new PlatformRequestAuthorization(service);
    }

    private static PlatformAuthenticatedSession globalSession() {
        return session(List.of(PlatformPermissions.PLATFORM_READ),
                List.of(new PlatformPermissionGrant(PlatformPermissions.PLATFORM_READ, "GLOBAL", "*")));
    }

    private static PlatformAuthenticatedSession projectSession(String projectCode, String permission) {
        return session(List.of(permission), List.of(new PlatformPermissionGrant(
                permission, "PROJECT", projectCode)));
    }

    private static PlatformAuthenticatedSession session(List<String> permissions,
                                                        List<PlatformPermissionGrant> grants) {
        PlatformUserEntity user = new PlatformUserEntity();
        user.setId(7L);
        user.setUsername("catalog-reader");
        return new PlatformAuthenticatedSession(
                PlatformPrincipal.fromUser(user), "session-1", LocalDateTime.now().plusHours(1),
                List.of("CATALOG_READER"), permissions, grants);
    }

    private static MockHttpServletRequest request(PlatformAuthenticatedSession session) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session);
        return request;
    }
}
