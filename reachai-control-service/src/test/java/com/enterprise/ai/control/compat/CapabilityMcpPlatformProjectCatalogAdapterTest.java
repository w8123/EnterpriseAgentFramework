package com.enterprise.ai.control.compat;

import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformPermissionGrant;
import com.enterprise.ai.control.identity.PlatformPrincipal;
import com.enterprise.ai.control.identity.PlatformUserEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class CapabilityMcpPlatformProjectCatalogAdapterTest {
    @Test
    void filtersBeforePaginationAndNeverReturnsProjectCredentials() {
        RestTemplate http = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(http);
        String payload = """
                [{"id":1,"projectCode":"orders","name":"订单","authApiKeyValue":"secret"},
                 {"id":2,"projectCode":"billing","name":"账务","authApiKeyValue":"secret"},
                 {"id":3,"projectCode":"warehouse","name":"仓库","authApiKeyValue":"secret"}]
                """;
        for (int i = 0; i < 2; i++) {
            server.expect(requestTo("http://capability:18605/api/scan-projects"))
                    .andExpect(header("X-ReachAI-Control-Capability-Proxy", "true"))
                    .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body(payload));
        }
        var adapter = new CapabilityMcpPlatformProjectCatalogAdapter(
                new CapabilityCompatibilityProxyController(http, "http://capability:18605"), new ObjectMapper());
        var actor = actor(List.of(new PlatformPermissionGrant("workflow:read", "PROJECT", "orders"),
                new PlatformPermissionGrant("agent:read", "PROJECT", "warehouse")));

        Map<?, ?> first = (Map<?, ?>) adapter.listProjects(actor, 0, 1).getBody();
        Map<?, ?> second = (Map<?, ?>) adapter.listProjects(actor, 1, 1).getBody();
        assertEquals(List.of(Map.of("id", 1, "projectCode", "orders", "name", "订单")), first.get("projects"));
        assertEquals(true, first.get("hasMore"));
        assertEquals(List.of(Map.of("id", 3, "projectCode", "warehouse", "name", "仓库")), second.get("projects"));
        assertEquals(false, second.get("hasMore"));
        assertFalse(first.toString().contains("secret"));
        assertFalse(second.toString().contains("billing"));
        server.verify();
    }

    @Test
    void deniesBeforeContactingCapabilityAndDoesNotInferWorkspaceMembership() {
        RestTemplate http = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(http);
        var adapter = new CapabilityMcpPlatformProjectCatalogAdapter(
                new CapabilityCompatibilityProxyController(http, "http://capability:18605"), new ObjectMapper());
        var noRead = actor(List.of(new PlatformPermissionGrant("workflow:write", "PROJECT", "orders")));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> adapter.listProjects(noRead, 0, 50)).getStatusCode());

        server.expect(requestTo("http://capability:18605/api/scan-projects"))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                        .body("[{\"id\":1,\"projectCode\":\"orders\"}]"));
        var workspaceOnly = actor(List.of(new PlatformPermissionGrant("workflow:read", "WORKSPACE", "workspace-a")));
        Map<?, ?> result = (Map<?, ?>) adapter.listProjects(workspaceOnly, 0, 50).getBody();
        assertTrue(((List<?>) result.get("projects")).isEmpty());
        assertEquals(true, result.get("unresolvedWorkspaceScopes"));
        server.verify();
    }

    @Test
    void refusesMalformedUpstreamCatalog() {
        RestTemplate http = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(http);
        server.expect(requestTo("http://capability:18605/api/scan-projects"))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{}"));
        var adapter = new CapabilityMcpPlatformProjectCatalogAdapter(
                new CapabilityCompatibilityProxyController(http, "http://capability:18605"), new ObjectMapper());
        var global = actor(List.of(new PlatformPermissionGrant("workflow:read", "GLOBAL", "*")));
        assertEquals(HttpStatus.BAD_GATEWAY, adapter.listProjects(global, 0, 50).getStatusCode());
        server.verify();
    }

    private static PlatformAuthenticatedSession actor(List<PlatformPermissionGrant> grants) {
        var user = new PlatformUserEntity();
        user.setId(7L);
        return new PlatformAuthenticatedSession(PlatformPrincipal.fromUser(user), "pai_19",
                LocalDateTime.now().plusDays(1), List.of(),
                grants.stream().map(PlatformPermissionGrant::permissionCode).toList(), grants);
    }
}
