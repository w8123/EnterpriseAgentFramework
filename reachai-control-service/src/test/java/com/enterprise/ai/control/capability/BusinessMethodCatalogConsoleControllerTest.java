package com.enterprise.ai.control.capability;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformAuthorizationService;
import com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor;
import com.enterprise.ai.control.identity.PlatformPermissionGrant;
import com.enterprise.ai.control.identity.PlatformPermissionMapper;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.identity.PlatformPrincipal;
import com.enterprise.ai.control.identity.PlatformRequestAuthorization;
import com.enterprise.ai.control.identity.PlatformRoleMapper;
import com.enterprise.ai.control.identity.PlatformRolePermissionMapper;
import com.enterprise.ai.control.identity.PlatformUserEntity;
import com.enterprise.ai.control.identity.PlatformUserRoleMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class BusinessMethodCatalogConsoleControllerTest {

    @Test
    void groupSelectionRequiresAnExplicitProjectAndIsForwardedAfterOwnerAuthorization() {
        var gateway = mock(CapabilityReviewGateway.class);
        var controller = controller(gateway);
        for (String raw : List.of("0", "-1", "abc", "1.5", "9223372036854775808")) {
            var invalid = request(globalSession());
            invalid.addParameter("groupId", raw);
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                    () -> controller.listBusinessMethods(invalid, 1, 20, null, null, 7L)).getStatusCode());
        }
        var noProject = request(globalSession());
        noProject.addParameter("groupId", "71");
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> controller.listBusinessMethods(noProject, 1, 20, null, null, null)).getStatusCode());
        verifyNoInteractions(gateway);

        var selected = request(projectSession("orders"));
        selected.addParameter("groupId", "71");
        when(gateway.getProjectById(7L, "7")).thenReturn(ResponseEntity.ok(
                Map.of("projectId", 7L, "projectCode", "orders")));
        when(gateway.listBusinessMethods(2, 10, "order", true, 7L, "7", 71L, false, CatalogReadAuthorization.body(projectSession("orders"), 7L)))
                .thenReturn(ownerPage(Map.of("records", List.of(), "total", 0)));
        assertEquals(HttpStatus.OK, controller.listBusinessMethods(selected, 2, 10, "order", true, 7L).getStatusCode());
        verify(gateway).listBusinessMethods(2, 10, "order", true, 7L, "7", 71L, false, CatalogReadAuthorization.body(projectSession("orders"), 7L));
        verify(gateway, never()).getProjectById(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void exposesDedicatedPublicBusinessMethodReadRoutes() throws Exception {
        Method list = CapabilityCatalogConsoleController.class.getDeclaredMethod(
                "listBusinessMethods", HttpServletRequest.class, int.class, int.class,
                String.class, Boolean.class, Long.class);
        Method get = CapabilityCatalogConsoleController.class.getDeclaredMethod(
                "getBusinessMethod", HttpServletRequest.class, String.class);

        assertArrayEquals(new String[] {"/api/business-methods"}, list.getAnnotation(GetMapping.class).value());
        assertArrayEquals(new String[] {"/api/business-methods/{name}"}, get.getAnnotation(GetMapping.class).value());
    }

    @Test
    void resolvesTheOwnerProjectBeforeForwardingAProjectScopedBusinessMethodList() {
        CapabilityReviewGateway gateway = mock(CapabilityReviewGateway.class);
        CapabilityCatalogConsoleController controller = controller(gateway);
        ResponseEntity<Object> ownerPage = ownerPage(Map.of("records", List.of(), "total", 0));
        when(gateway.getProjectById(7L, "7")).thenReturn(ResponseEntity.ok(
                Map.of("projectId", 7L, "projectCode", "orders")));
        when(gateway.listBusinessMethods(1, 20, "order", true, 7L, "7", null, false, CatalogReadAuthorization.body(projectSession("orders"), 7L))).thenReturn(ownerPage);

        ResponseEntity<Object> response = controller.listBusinessMethods(
                request(projectSession("orders")), 1, 20, "order", true, 7L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(ownerPage.getBody(), response.getBody());
        verify(gateway, never()).getProjectById(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(gateway).listBusinessMethods(1, 20, "order", true, 7L, "7", null, false, CatalogReadAuthorization.body(projectSession("orders"), 7L));
    }

    @Test
    void returnsOnlyTheOwnerConfirmedBusinessMethodDetail() {
        CapabilityReviewGateway gateway = mock(CapabilityReviewGateway.class);
        CapabilityCatalogConsoleController controller = controller(gateway);
        ResponseEntity<Object> method = ownerPage(Map.of(
                "assetType", "BUSINESS_METHOD",
                "name", "orders_create",
                "projectId", 7L,
                "projectCode", "orders"));
        when(gateway.getBusinessMethod(org.mockito.ArgumentMatchers.eq("orders_create"), org.mockito.ArgumentMatchers.eq("7"), org.mockito.ArgumentMatchers.anyMap())).thenReturn(method);
        when(gateway.getProjectById(7L, "7")).thenReturn(ResponseEntity.ok(
                Map.of("projectId", 7L, "projectCode", "orders")));

        ResponseEntity<Object> response = controller.getBusinessMethod(
                request(projectSession("orders")), "orders_create");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("BUSINESS_METHOD", ((Map<?, ?>) response.getBody()).get("assetType"));
        verify(gateway).getBusinessMethod(org.mockito.ArgumentMatchers.eq("orders_create"), org.mockito.ArgumentMatchers.eq("7"), org.mockito.ArgumentMatchers.anyMap());
        verify(gateway, never()).getProjectById(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void acceptsTheStableQualifiedRuntimeReferenceReturnedByTheOwner() {
        CapabilityReviewGateway gateway = mock(CapabilityReviewGateway.class);
        CapabilityCatalogConsoleController controller = controller(gateway);
        ResponseEntity<Object> method = ownerPage(Map.of(
                "assetType", "BUSINESS_METHOD",
                "name", "orders_read",
                "qualifiedName", "orders:orders_read",
                "projectId", 7L,
                "projectCode", "orders"));
        when(gateway.getBusinessMethod(org.mockito.ArgumentMatchers.eq("orders:orders_read"), org.mockito.ArgumentMatchers.eq("7"), org.mockito.ArgumentMatchers.anyMap())).thenReturn(method);
        when(gateway.getProjectById(7L, "7")).thenReturn(ResponseEntity.ok(
                Map.of("projectId", 7L, "projectCode", "orders")));

        ResponseEntity<Object> response = controller.getBusinessMethod(
                request(projectSession("orders")), "orders:orders_read");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("orders:orders_read", ((Map<?, ?>) response.getBody()).get("qualifiedName"));
        verify(gateway).getBusinessMethod(org.mockito.ArgumentMatchers.eq("orders:orders_read"), org.mockito.ArgumentMatchers.eq("7"), org.mockito.ArgumentMatchers.anyMap());
        verify(gateway, never()).getProjectById(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void preservesNotFoundWhenTheOwnerDoesNotClassifyTheRequestedDefinitionAsABusinessMethod() {
        CapabilityReviewGateway gateway = mock(CapabilityReviewGateway.class);
        CapabilityCatalogConsoleController controller = controller(gateway);
        when(gateway.getBusinessMethod(org.mockito.ArgumentMatchers.eq("legacy_http_api"), org.mockito.ArgumentMatchers.eq("7"), org.mockito.ArgumentMatchers.anyMap())).thenReturn(ResponseEntity.notFound().build());

        assertEquals(HttpStatus.NOT_FOUND, controller.getBusinessMethod(
                request(globalSession()), "legacy_http_api").getStatusCode());
    }

    private static ResponseEntity<Object> ownerPage(Map<String, Object> body) {
        return ResponseEntity.ok().header(CatalogReadAuthorization.SCOPE, "PROJECT")
                .header(CatalogReadAuthorization.PROJECT_ID, "7").header(CatalogReadAuthorization.PROJECT_CODE, "orders").body(body);
    }

    private static CapabilityCatalogConsoleController controller(CapabilityReviewGateway gateway) {
        PlatformAuthorizationService service = new PlatformAuthorizationService(
                mock(PlatformUserRoleMapper.class),
                mock(PlatformRoleMapper.class),
                mock(PlatformRolePermissionMapper.class),
                mock(PlatformPermissionMapper.class));
        return new CapabilityCatalogConsoleController(gateway, new PlatformRequestAuthorization(service));
    }

    private static PlatformAuthenticatedSession globalSession() {
        return session(List.of(new PlatformPermissionGrant(
                PlatformPermissions.PLATFORM_READ, "GLOBAL", "*")));
    }

    private static PlatformAuthenticatedSession projectSession(String projectCode) {
        return session(List.of(new PlatformPermissionGrant(
                PlatformPermissions.PLATFORM_READ, "PROJECT", projectCode)));
    }

    private static PlatformAuthenticatedSession session(List<PlatformPermissionGrant> grants) {
        PlatformUserEntity user = new PlatformUserEntity();
        user.setId(7L);
        user.setUsername("business-method-reader");
        return new PlatformAuthenticatedSession(
                PlatformPrincipal.fromUser(user), "session-1", LocalDateTime.now().plusHours(1),
                List.of("CATALOG_READER"), List.of(PlatformPermissions.PLATFORM_READ), grants);
    }

    private static MockHttpServletRequest request(PlatformAuthenticatedSession session) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session);
        return request;
    }
}
