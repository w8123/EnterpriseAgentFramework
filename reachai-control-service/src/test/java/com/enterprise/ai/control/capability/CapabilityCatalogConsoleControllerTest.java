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
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CapabilityCatalogConsoleControllerTest {

    @Test
    void exposesOnlyTheTwoReadOnlyCatalogRoutes() throws Exception {
        var list = CapabilityCatalogConsoleController.class.getDeclaredMethod(
                "list", HttpServletRequest.class, int.class, int.class,
                String.class, String.class, Boolean.class, Long.class);
        var get = CapabilityCatalogConsoleController.class.getDeclaredMethod(
                "get", HttpServletRequest.class, String.class);

        assertArrayEquals(new String[] {"/api/tools"}, list.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/tools/{name}"}, get.getAnnotation(GetMapping.class).value());
    }

    @Test
    void mvcRoutesEnterTheAuthorizationFacadeWithoutLegacyToolsFallback() throws Exception {
        CapabilityReviewGateway gateway = mock(CapabilityReviewGateway.class);
        CapabilityCatalogConsoleController catalog = controller(gateway, globalSession());
        CapabilityCompatibilityProxyController legacyProxy = new CapabilityCompatibilityProxyController(
                new RestTemplateBuilder(), "http://capability:18605");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(catalog, legacyProxy).build();

        mvc.perform(get("/api/tools")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/tools/orders_read")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/tools")).andExpect(status().is4xxClientError());
        mvc.perform(get("/api/tools/orders_read/extra")).andExpect(status().isNotFound());

        verifyNoInteractions(gateway);
    }

    @Test
    void rejectsAnUnauthenticatedListBeforeOwnerIo() {
        CapabilityReviewGateway gateway = mock(CapabilityReviewGateway.class);
        CapabilityCatalogConsoleController controller = controller(gateway, globalSession());

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.list(new MockHttpServletRequest(), 1, 20,
                        null, null, null, null));

        assertEquals(HttpStatus.UNAUTHORIZED, error.getStatusCode());
        verifyNoInteractions(gateway);
    }

    @Test
    void requiresGlobalReadForAnUnfilteredList() {
        CapabilityReviewGateway gateway = mock(CapabilityReviewGateway.class);
        CapabilityCatalogConsoleController controller = controller(gateway,
                projectSession("orders", PlatformPermissions.PLATFORM_READ));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.list(request(projectSession("orders", PlatformPermissions.PLATFORM_READ)),
                        1, 20, null, null, null, null));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verifyNoInteractions(gateway);
    }

    @Test
    void usesGlobalReadForAnUnfilteredListAndPreservesExplicitFalse() {
        CapabilityReviewGateway gateway = mock(CapabilityReviewGateway.class);
        CapabilityCatalogConsoleController controller = controller(gateway, globalSession());
        ResponseEntity<Object> page = ResponseEntity.ok(Map.of("records", List.of()));
        when(gateway.listCapabilities(2, 500, "订单 & status=ALL#+/", "code+/source",
                false, null, "7")).thenReturn(page);

        assertEquals(page, controller.list(request(globalSession()), 2, 500,
                "订单 & status=ALL#+/", "code+/source", false, null));
        verify(gateway).listCapabilities(2, 500, "订单 & status=ALL#+/", "code+/source",
                false, null, "7");
    }

    @Test
    void resolvesTheOwnerProjectBeforeAuthorizingAFilteredList() {
        CapabilityReviewGateway gateway = mock(CapabilityReviewGateway.class);
        CapabilityCatalogConsoleController controller = controller(gateway,
                projectSession("orders", PlatformPermissions.PLATFORM_READ));
        ResponseEntity<Object> page = ResponseEntity.ok(Map.of("records", List.of("order.read")));
        when(gateway.getProjectById(7L, "7")).thenReturn(ResponseEntity.ok(
                Map.of("projectId", 7L, "projectCode", "orders")));
        when(gateway.listCapabilities(1, 20, null, null, null, 7L, "7")).thenReturn(page);

        assertEquals(page, controller.list(request(projectSession("orders", PlatformPermissions.PLATFORM_READ)),
                1, 20, null, null, null, 7L));
        verify(gateway).getProjectById(7L, "7");
        verify(gateway).listCapabilities(1, 20, null, null, null, 7L, "7");
    }

    @Test
    void permitsTheEnabledStudioCatalogSelectorWithinProjectScopeWithoutAGlobalGrant() {
        CapabilityReviewGateway gateway = mock(CapabilityReviewGateway.class);
        CapabilityCatalogConsoleController controller = controller(gateway,
                projectSession("orders", PlatformPermissions.PLATFORM_READ));
        ResponseEntity<Object> page = ResponseEntity.ok(Map.of("records", List.of("orders.read")));
        when(gateway.getProjectById(7L, "7")).thenReturn(ResponseEntity.ok(
                Map.of("projectId", 7L, "projectCode", "orders")));
        when(gateway.listCapabilities(1, 100, null, null, true, 7L, "7")).thenReturn(page);

        assertEquals(page, controller.list(request(projectSession("orders", PlatformPermissions.PLATFORM_READ)),
                1, 100, null, null, true, 7L));
        verify(gateway).getProjectById(7L, "7");
        verify(gateway).listCapabilities(1, 100, null, null, true, 7L, "7");
    }

    @Test
    void deniesAProjectGrantForAnotherResolvedProjectWithoutListing() {
        CapabilityReviewGateway gateway = mock(CapabilityReviewGateway.class);
        CapabilityCatalogConsoleController controller = controller(gateway,
                projectSession("orders", PlatformPermissions.PLATFORM_READ));
        when(gateway.getProjectById(8L, "7")).thenReturn(ResponseEntity.ok(
                Map.of("projectId", 8L, "projectCode", "billing")));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.list(request(projectSession("orders", PlatformPermissions.PLATFORM_READ)),
                        1, 20, null, null, null, 8L));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verify(gateway).getProjectById(8L, "7");
        verify(gateway, org.mockito.Mockito.never()).listCapabilities(
                any(Integer.class), any(Integer.class), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsWriteOnlyProjectGrantBeforeOwnerLookup() {
        CapabilityReviewGateway gateway = mock(CapabilityReviewGateway.class);
        CapabilityCatalogConsoleController controller = controller(gateway,
                projectSession("orders", PlatformPermissions.PLATFORM_WRITE));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.list(request(projectSession("orders", PlatformPermissions.PLATFORM_WRITE)),
                        1, 20, null, null, null, 7L));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verifyNoInteractions(gateway);
    }

    @Test
    void rejectsInvalidProjectIdsBeforeOwnerLookup() {
        CapabilityReviewGateway gateway = mock(CapabilityReviewGateway.class);
        CapabilityCatalogConsoleController controller = controller(gateway, globalSession());

        ResponseEntity<Object> response = controller.list(request(globalSession()), 1, 20,
                null, null, null, 0L);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("CAPABILITY_PROJECT_ID_INVALID", ((Map<?, ?>) response.getBody()).get("code"));
        verifyNoInteractions(gateway);
    }

    @Test
    void failsClosedForMissingMismatchedAndUnavailableProjectOwnerResponses() {
        for (ResponseEntity<Object> ownerResponse : List.of(
                ResponseEntity.<Object>ok(Map.of("projectCode", "orders")),
                ResponseEntity.<Object>ok(Map.of("projectId", 8L, "projectCode", "orders")),
                ResponseEntity.<Object>ok(Map.of("projectId", 7L, "projectCode", 7)),
                ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).<Object>build())) {
            CapabilityReviewGateway gateway = mock(CapabilityReviewGateway.class);
            CapabilityCatalogConsoleController controller = controller(gateway, globalSession());
            when(gateway.getProjectById(7L, "7")).thenReturn(ownerResponse);

            ResponseEntity<Object> response = controller.list(request(globalSession()), 1, 20,
                    null, null, null, 7L);

            assertEquals(ownerResponse.getStatusCode().value() >= 500
                            ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY,
                    response.getStatusCode(), ownerResponse.toString());
            verify(gateway, org.mockito.Mockito.never()).listCapabilities(
                    any(Integer.class), any(Integer.class), any(), any(), any(), any(), any());
        }
    }

    @Test
    void mapsAnOwnerNotFoundToNotFoundWithoutAUnboundedFallback() {
        CapabilityReviewGateway gateway = mock(CapabilityReviewGateway.class);
        CapabilityCatalogConsoleController controller = controller(gateway, globalSession());
        when(gateway.getProjectById(7L, "7")).thenReturn(ResponseEntity.notFound().build());

        ResponseEntity<Object> response = controller.list(request(globalSession()), 1, 20,
                null, null, null, 7L);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        verify(gateway, org.mockito.Mockito.never()).listCapabilities(
                any(Integer.class), any(Integer.class), any(), any(), any(), any(), any());
    }

    @Test
    void authorizesAProjectCapabilityFromTheOwnerResolvedIdentity() {
        CapabilityReviewGateway gateway = mock(CapabilityReviewGateway.class);
        CapabilityCatalogConsoleController controller = controller(gateway,
                projectSession("orders", PlatformPermissions.PLATFORM_READ));
        ResponseEntity<Object> definition = ResponseEntity.ok(Map.of(
                "name", "orders_read", "projectId", 7L, "projectCode", "orders"));
        when(gateway.getCapability("orders_read", "7")).thenReturn(definition);
        when(gateway.getProjectById(7L, "7")).thenReturn(ResponseEntity.ok(
                Map.of("projectId", 7L, "projectCode", "orders")));

        assertEquals(definition, controller.get(request(projectSession("orders", PlatformPermissions.PLATFORM_READ)),
                "orders_read"));
        verify(gateway).getProjectById(7L, "7");
    }

    @Test
    void globalReadCanReadProjectAndPlatformDefinitions() {
        CapabilityReviewGateway gateway = mock(CapabilityReviewGateway.class);
        CapabilityCatalogConsoleController controller = controller(gateway, globalSession());
        ResponseEntity<Object> projectDefinition = ResponseEntity.ok(Map.of(
                "name", "orders_read", "projectId", 7L));
        ResponseEntity<Object> platformDefinition = ResponseEntity.ok(Map.of("name", "platform_read"));
        when(gateway.getCapability("orders_read", "7")).thenReturn(projectDefinition);
        when(gateway.getCapability("platform_read", "7")).thenReturn(platformDefinition);
        when(gateway.getProjectById(7L, "7")).thenReturn(ResponseEntity.ok(
                Map.of("projectId", 7L, "projectCode", "orders")));

        assertEquals(projectDefinition, controller.get(request(globalSession()), "orders_read"));
        assertEquals(platformDefinition, controller.get(request(globalSession()), "platform_read"));
    }

    @Test
    void rejectsAProjectGrantForPlatformOrAnotherProjectCapability() {
        CapabilityReviewGateway gateway = mock(CapabilityReviewGateway.class);
        CapabilityCatalogConsoleController controller = controller(gateway,
                projectSession("orders", PlatformPermissions.PLATFORM_READ));
        when(gateway.getCapability("platform_read", "7")).thenReturn(
                ResponseEntity.ok(Map.of("name", "platform_read")));
        when(gateway.getCapability("billing_read", "7")).thenReturn(ResponseEntity.ok(Map.of(
                "name", "billing_read", "projectId", 8L)));
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
        when(gateway.getCapability("orphan", "7")).thenReturn(ResponseEntity.ok(Map.of(
                "name", "orphan", "projectCode", "orders")));
        when(gateway.getCapability("conflict", "7")).thenReturn(ResponseEntity.ok(Map.of(
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
        when(gateway.getCapability("bad_id", "7")).thenReturn(ResponseEntity.ok(Map.of(
                "name", "bad_id", "projectId", "7")));
        when(gateway.getCapability("wrong_name", "7")).thenReturn(ResponseEntity.ok(Map.of(
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
        when(gateway.getCapability("missing", "7")).thenReturn(ResponseEntity.notFound().build());
        when(gateway.getCapability("down", "7")).thenReturn(ResponseEntity.status(503)
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
