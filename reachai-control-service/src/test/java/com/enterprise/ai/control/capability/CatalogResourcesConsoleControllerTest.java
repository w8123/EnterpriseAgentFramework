package com.enterprise.ai.control.capability;

import com.enterprise.ai.control.identity.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CatalogResourcesConsoleControllerTest {
    @Test void standaloneClassificationCreationUsesExactProjectWritePathActorAndBody() throws Exception {
        var gateway = mock(CapabilityReviewGateway.class);
        var body = Map.of("displayName", "字典查询与统计");
        when(gateway.getProjectById(7L, "7")).thenReturn(ResponseEntity.ok(Map.of("projectId", 7L, "projectCode", "orders")));
        when(gateway.catalogResources("POST", 7L, false, "/groups", Map.of(), body, "7"))
                .thenReturn(ResponseEntity.ok(Map.of("id", 4, "projectId", 7, "displayName", "字典查询与统计", "sourceClasses", List.of())));
        MockMvcBuilders.standaloneSetup(controller(gateway)).build()
                .perform(post("/api/business-capabilities/projects/7/groups?projectCode=other&projectId=8")
                        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).content("{\"displayName\":\"字典查询与统计\"}")
                        .requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session("orders", PlatformPermissions.PLATFORM_WRITE)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(4))
                .andExpect(jsonPath("$.projectId").value(7)).andExpect(jsonPath("$.displayName").value("字典查询与统计"));
        verify(gateway).catalogResources("POST", 7L, false, "/groups", Map.of(), body, "7");
        assertTrue(CatalogResourcesConsoleController.allowed(false, "POST", "/groups"));
        assertFalse(CatalogResourcesConsoleController.allowed(false, "POST", "/groups/merge"));
        assertFalse(CatalogResourcesConsoleController.allowed(false, "POST", "/groups/4"));
    }

    @Test void standaloneClassificationCreationRejectsReadOnlyAndForeignProjectWriteGrants() {
        var gateway = mock(CapabilityReviewGateway.class); var controller = controller(gateway);
        var read = request("POST", session("orders", PlatformPermissions.PLATFORM_READ));
        var deniedRead = assertThrows(ResponseStatusException.class, () -> controller.resource(read, input("POST"), 7L, "/groups", Map.of()));
        assertEquals(403, deniedRead.getStatusCode().value());
        verifyNoInteractions(gateway);
        when(gateway.getProjectById(7L, "7")).thenReturn(ResponseEntity.ok(Map.of("projectId", 7L, "projectCode", "orders")));
        var foreign = request("POST", session("other", PlatformPermissions.PLATFORM_WRITE));
        var deniedForeign = assertThrows(ResponseStatusException.class, () -> controller.resource(foreign, input("POST"), 7L, "/groups", Map.of()));
        assertEquals(403, deniedForeign.getStatusCode().value());
        verify(gateway, never()).catalogResources(any(), any(), anyBoolean(), any(), any(), any(), any());
    }

    @Test void removedClassificationMergeIsRejectedWithoutForwardingEvenWithProjectWriteGrant() throws Exception {
        var gateway = mock(CapabilityReviewGateway.class);
        MockMvcBuilders.standaloneSetup(controller(gateway)).build()
                .perform(post("/api/business-capabilities/projects/7/groups/merge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetId\":4,\"sourceIds\":[5],\"displayName\":\"Combined\"}")
                        .requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session("orders", PlatformPermissions.PLATFORM_WRITE)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CATALOG_RESOURCE_INVALID"));
        assertFalse(CatalogResourcesConsoleController.allowed(false, "POST", "/groups/merge"));
        assertTrue(CatalogResourcesConsoleController.allowed(false, "POST", "/semantic/generate"));
        verifyNoInteractions(gateway);
    }

    @Test void classificationIndexUsesExactTypedReadPathAndProjectAuthorization() throws Exception {
        var gateway = mock(CapabilityReviewGateway.class); var controller = controller(gateway);
        when(gateway.getProjectById(7L, "7")).thenReturn(ResponseEntity.ok(Map.of("projectId", 7L, "projectCode", "orders")));
        when(gateway.catalogResources("GET", 7L, false, "/classification/BUSINESS_METHOD", Map.of(), grant("orders"), "7"))
                .thenReturn(ownerResponse(Map.of("projectId", 7L, "subjectType", "BUSINESS_METHOD", "groups", List.of(), "memberships", List.of(), "ungroupedCount", 0)));
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(get("/api/business-capabilities/projects/7/classification/BUSINESS_METHOD?subjectType=HTTP_API&projectCode=other")
                        .requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session("orders", PlatformPermissions.PLATFORM_READ)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.subjectType").value("BUSINESS_METHOD"));
        assertTrue(CatalogResourcesConsoleController.allowed(false, "GET", "/classification/HTTP_API"));
        assertTrue(CatalogResourcesConsoleController.allowed(false, "PUT", "/classification/HTTP_API"));
        assertTrue(CatalogResourcesConsoleController.allowed(false, "GET", "/subjects/HTTP_API/21/classification"));
        assertFalse(CatalogResourcesConsoleController.allowed(false, "PUT", "/subjects/HTTP_API/21/groups/4"));
        assertThrows(ResponseStatusException.class, () -> controller.resource(request("PUT", session("orders", PlatformPermissions.PLATFORM_READ)), input("PUT"), 7L, "/classification/HTTP_API", Map.of()));
        assertFalse(CatalogResourcesConsoleController.allowed(false, "GET", "/classification/SOURCE_OBSERVATION"));
        assertFalse(CatalogResourcesConsoleController.allowed(false, "GET", "/classification/http_api"));
        when(gateway.catalogResources("GET", 7L, false, "/classification/HTTP_API", Map.of(), grant("other"), "7"))
                .thenReturn(ResponseEntity.status(403).body(Map.of("code", "CATALOG_READ_SCOPE_DENIED")));
        var foreign = request("GET", session("other", PlatformPermissions.PLATFORM_READ));
        assertEquals(403, controller.resource(foreign, input("GET"), 7L, "/classification/HTTP_API", Map.of()).getStatusCode().value());
        verify(gateway).catalogResources("GET", 7L, false, "/classification/BUSINESS_METHOD", Map.of(), grant("orders"), "7");
        verify(gateway).catalogResources("GET", 7L, false, "/classification/HTTP_API", Map.of(), grant("other"), "7");
    }
    @Test void typedOwnerRouteUsesOwningProjectReadGrantAndFiltersQuery() throws Exception {
        var gateway = mock(CapabilityReviewGateway.class); var controller = controller(gateway);
        when(gateway.getProjectById(7L, "7")).thenReturn(ResponseEntity.ok(Map.of("projectId", 7L, "projectCode", "orders")));
        when(gateway.catalogResources("GET", 7L, false, "/subjects/HTTP_API/21/enrichment", Map.of(), grant("orders"), "7"))
                .thenReturn(ownerResponse(Map.of("subjectType", "HTTP_API", "subjectId", 21)));
        MockMvcBuilders.standaloneSetup(controller).build().perform(get("/api/business-capabilities/projects/7/subjects/HTTP_API/21/enrichment?projectCode=other")
                        .requestAttr(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session("orders", PlatformPermissions.PLATFORM_READ)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.subjectId").value(21));
        verify(gateway, never()).getProjectById(any(), any()); verify(gateway).catalogResources("GET", 7L, false, "/subjects/HTTP_API/21/enrichment", Map.of(), grant("orders"), "7");
    }
    @Test void anotherProjectGrantAndReadOnlyGrantCannotPerformWrites() {
        var gateway = mock(CapabilityReviewGateway.class); var controller = controller(gateway);
        when(gateway.getProjectById(7L, "7")).thenReturn(ResponseEntity.ok(Map.of("projectId", 7L, "projectCode", "orders")));
        var read = request("GET", session("other", PlatformPermissions.PLATFORM_READ));
        when(gateway.catalogResources("GET", 7L, false, "/groups", Map.of(), grant("other"), "7"))
                .thenReturn(ResponseEntity.status(403).body(Map.of("code", "CATALOG_READ_SCOPE_DENIED")));
        assertEquals(403, controller.resource(read, input("GET"), 7L, "/groups", Map.of()).getStatusCode().value());
        var write = request("PUT", session("orders", PlatformPermissions.PLATFORM_READ));
        assertThrows(ResponseStatusException.class, () -> controller.resource(write, input("PUT"), 7L, "/subjects/HTTP_API/21/semantic", Map.of()));
        verify(gateway, never()).catalogResources(eq("PUT"), any(), anyBoolean(), any(), any(), any(), any());
    }
    @Test void ownerLookupMalformedIdentityAndUnsupportedPathCannotBeForwarded() {
        var gateway = mock(CapabilityReviewGateway.class); var controller = controller(gateway);
        var request = request("GET", session("orders", PlatformPermissions.PLATFORM_READ));
        when(gateway.catalogResources("GET", 7L, false, "/groups", Map.of(), grant("orders"), "7")).thenReturn(ResponseEntity.ok(Map.of("groups", List.of())));
        assertEquals(502, controller.resource(request, input("GET"), 7L, "/groups", Map.of()).getStatusCode().value());
        assertEquals(400, controller.resource(request, input("GET"), 7L, "/subjects/TOOL/21/enrichment", Map.of()).getStatusCode().value());
        assertFalse(CatalogResourcesConsoleController.allowed(true, "GET", "/tools/orders_read/param-hints"));
        assertFalse(CatalogResourcesConsoleController.allowed(false, "PUT", "/subjects/HTTP_API/21/contract"));
        verify(gateway, never()).getProjectById(any(), any());
    }
    @Test void graphMutationUsesWriteGrantAndExactProjectFacade() {
        var gateway = mock(CapabilityReviewGateway.class); var controller = controller(gateway);
        var request = request("POST", session("orders", PlatformPermissions.PLATFORM_WRITE)); request.setRequestURI("/api/api-graph/projects/7/edges");
        when(gateway.getProjectById(7L, "7")).thenReturn(ResponseEntity.ok(Map.of("projectId", 7L, "projectCode", "orders")));
        when(gateway.catalogResources("POST", 7L, true, "/edges", Map.of(), null, "7")).thenReturn(ResponseEntity.ok(Map.of("id", 3)));
        assertEquals(200, controller.resource(request, input("POST"), 7L, "/edges", Map.of()).getStatusCode().value());
        verify(gateway).catalogResources("POST", 7L, true, "/edges", Map.of(), null, "7");
    }
    private static Map<String, Object> grant(String project) {
        return CatalogReadAuthorization.body(session(project, PlatformPermissions.PLATFORM_READ), 7L);
    }
    private static ResponseEntity<Object> ownerResponse(Map<String, Object> body) {
        return ResponseEntity.ok().header(CatalogReadAuthorization.SCOPE, "PROJECT")
                .header(CatalogReadAuthorization.PROJECT_ID, "7").header(CatalogReadAuthorization.PROJECT_CODE, "orders").body(body);
    }
    private static CatalogResourcesConsoleController controller(CapabilityReviewGateway gateway) {
        return new CatalogResourcesConsoleController(gateway, new PlatformRequestAuthorization(new PlatformAuthorizationService(
                mock(PlatformUserRoleMapper.class), mock(PlatformRoleMapper.class), mock(PlatformRolePermissionMapper.class), mock(PlatformPermissionMapper.class))));
    }
    private static PlatformAuthenticatedSession session(String project, String permission) {
        var user = new PlatformUserEntity(); user.setId(7L); user.setUsername("catalog-editor");
        return new PlatformAuthenticatedSession(PlatformPrincipal.fromUser(user), "session-1", LocalDateTime.now().plusHours(1), List.of("CATALOG"), List.of(permission), List.of(new PlatformPermissionGrant(permission, "PROJECT", project)));
    }
    private static MockHttpServletRequest request(String method, PlatformAuthenticatedSession session) {
        var request = new MockHttpServletRequest(method, "/api/business-capabilities/projects/7/groups"); request.setAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE, session); return request;
    }
    private static RequestEntity<Object> input(String method) { return new RequestEntity<>(null, HttpMethod.valueOf(method), URI.create("http://localhost/api")); }
}
